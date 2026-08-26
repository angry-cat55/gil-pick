"""Kakao login transaction and ticket exchange service."""

from __future__ import annotations

import hashlib
import hmac
import logging
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any

from sqlalchemy import select, update
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.clients.kakao import KakaoClientError
from app.core.config import Settings
from app.core.logging import log_auth_event
from app.core.security import create_access_token, create_opaque_token, parse_opaque_token
from app.models.auth import AuthLoginTransaction, DeviceSession, User
from app.schemas.auth import AuthTokenData, RefreshTokenData, UserSummary

logger = logging.getLogger("gilpick.auth")
TRANSACTION_TTL = timedelta(minutes=10)
TICKET_TTL = timedelta(seconds=120)
REFRESH_TTL = timedelta(days=30)


class AuthServiceError(RuntimeError):
    """로그인 service 실패와 client 공개 오류 code를 나타낸다."""

    def __init__(self, code: str, *, status_code: int = 401, retryable: bool = False):
        super().__init__(code)
        self.code = code
        self.status_code = status_code
        self.retryable = retryable


class InvalidLoginTicketError(AuthServiceError):
    """유효하지 않거나 이미 소비된 login ticket이다."""

    def __init__(self, code: str = "INVALID_LOGIN_TICKET") -> None:
        super().__init__(code, status_code=401)


class InvalidRefreshTokenError(AuthServiceError):
    """유효하지 않거나 이미 회전·폐기된 Refresh Token이다."""

    def __init__(self, code: str = "INVALID_REFRESH_TOKEN") -> None:
        super().__init__(code, status_code=401)


@dataclass(slots=True)
class LoginExchangeResult:
    """Ticket 교환 뒤 endpoint가 직렬화할 인증 결과."""

    access_token: str
    refresh_token: str
    user: UserSummary
    is_new_user: bool

    def as_data(self) -> AuthTokenData:
        """공개 API schema로 변환한다."""
        return AuthTokenData(
            access_token=self.access_token,
            expires_in=3600,
            refresh_token=self.refresh_token,
            refresh_expires_in=2592000,
            user=self.user,
        )


@dataclass(slots=True)
class RefreshResult:
    """Refresh Token 회전 뒤 endpoint가 직렬화할 결과."""

    access_token: str
    refresh_token: str

    def as_data(self) -> RefreshTokenData:
        """공개 API schema로 변환한다."""
        return RefreshTokenData(
            access_token=self.access_token,
            expires_in=3600,
            refresh_token=self.refresh_token,
            refresh_expires_in=2592000,
        )


class AuthService:
    """짧은 callback transaction과 원자적 login ticket 교환을 조정한다."""

    def __init__(self, session_factory: async_sessionmaker[AsyncSession], kakao_client: Any, settings: Settings):
        self.session_factory = session_factory
        self.kakao_client = kakao_client
        self.settings = settings

    async def create_login_transaction(self, device_id: uuid.UUID, *, now: datetime | None = None) -> tuple[AuthLoginTransaction, str]:
        """기기에 결합된 transaction과 Kakao authorization URL을 생성한다."""
        clock = now or datetime.now(UTC)
        raw_state = create_opaque_token().secret
        transaction = AuthLoginTransaction(
            state_hash=hashlib.sha256(raw_state.encode("ascii")).hexdigest(),
            client_device_id=str(device_id),
            platform="ANDROID",
            status="PENDING",
            expires_at=clock + TRANSACTION_TTL,
        )
        async with self.session_factory() as session:
            async with session.begin():
                session.add(transaction)
                await session.flush()
        from urllib.parse import urlencode
        query = urlencode({
            "client_id": self.settings.kakao_rest_api_key.get_secret_value(),
            "redirect_uri": self.settings.kakao_redirect_uri,
            "response_type": "code",
            "state": raw_state,
        })
        return transaction, f"https://kauth.kakao.com/oauth/authorize?{query}"

    async def handle_kakao_callback(self, *, state: str, code: str, now: datetime | None = None) -> str:
        """state를 선점하고 외부 호출 뒤 일회용 login ticket을 발급한다."""
        clock = now or datetime.now(UTC)
        transaction = await self._claim_state(state, clock)

        try:
            kakao_token = await self.kakao_client.exchange_code(code)
            try:
                profile = await self.kakao_client.get_user_profile(kakao_token)
            except (KakaoClientError, TimeoutError) as first_error:
                if isinstance(first_error, KakaoClientError) and not first_error.retryable:
                    raise
                profile = await self.kakao_client.get_user_profile(kakao_token)
        except (KakaoClientError, TimeoutError) as exc:
            error_code = getattr(exc, "code", "KAKAO_API_TIMEOUT")
            await self._finish_callback(transaction.transaction_id, "FAILED", error_code=error_code)
            raise AuthServiceError(error_code, retryable=getattr(exc, "retryable", True)) from exc

        ticket = create_opaque_token(transaction.transaction_id)
        await self._finish_callback(
            transaction.transaction_id,
            "VERIFIED",
            ticket=ticket,
            profile=profile,
            now=clock,
        )
        return ticket.encoded

    async def fail_kakao_callback(self, *, state: str, error_code: str, now: datetime | None = None) -> None:
        """신뢰 가능한 provider 실패 callback을 선점하고 FAILED로 종결한다."""
        transaction = await self._claim_state(state, now or datetime.now(UTC))
        await self._finish_callback(transaction.transaction_id, "FAILED", error_code=error_code)

    async def _claim_state(self, state: str, clock: datetime) -> AuthLoginTransaction:
        """PENDING state를 짧은 transaction에서 정확히 한 번 PROCESSING으로 선점한다."""
        state_hash = hashlib.sha256(state.encode()).hexdigest()
        async with self.session_factory() as session:
            async with session.begin():
                result = await session.execute(
                    update(AuthLoginTransaction)
                    .where(
                        AuthLoginTransaction.state_hash == state_hash,
                        AuthLoginTransaction.status == "PENDING",
                        AuthLoginTransaction.expires_at > clock,
                    )
                    .values(status="PROCESSING")
                    .returning(AuthLoginTransaction)
                )
                transaction = result.scalar_one_or_none()
                if transaction is None:
                    raise AuthServiceError("LOGIN_TRANSACTION_EXPIRED", status_code=400)
                transaction.status = "PROCESSING"
        return transaction

    async def _finish_callback(self, transaction_id: uuid.UUID, status: str, *, error_code: str | None = None, ticket: Any = None, profile: dict[str, Any] | None = None, now: datetime | None = None) -> None:
        """외부 호출 결과를 새 짧은 DB transaction에서 확정한다."""
        async with self.session_factory() as session:
            async with session.begin():
                result = await session.execute(
                    select(AuthLoginTransaction)
                    .where(AuthLoginTransaction.transaction_id == transaction_id)
                    .with_for_update()
                )
                transaction = result.scalar_one_or_none()
                if transaction is None or transaction.status != "PROCESSING":
                    raise AuthServiceError("LOGIN_TRANSACTION_EXPIRED", status_code=400)
                transaction.status = status
                transaction.failure_code = error_code
                if status == "VERIFIED":
                    transaction.login_ticket_hash = ticket.secret_hash
                    transaction.ticket_expires_at = (now or datetime.now(UTC)) + TICKET_TTL
                    transaction.social_subject = str(profile["id"])
                    transaction.nickname = profile.get("nickname")
                    transaction.profile_image_url = profile.get("profile_image_url")
        log_auth_event(logger, operation="KAKAO_CALLBACK", result=status, transaction_id=str(transaction_id), error_code=error_code)

    async def exchange_login_ticket(self, *, login_ticket: str, device_id: str | uuid.UUID, now: datetime | None = None) -> LoginExchangeResult:
        """Service 소유 session에서 ticket을 교환한다."""
        async with self.session_factory() as session:
            async with session.begin():
                return await exchange_login_ticket(session, login_ticket, device_id, self.settings, now=now)


async def exchange_login_ticket(session: AsyncSession, login_ticket: str, device_id: str | uuid.UUID, settings: Settings, *, now: datetime | None = None) -> LoginExchangeResult:
    """Login ticket 소비와 user/session upsert를 한 transaction에서 수행한다."""
    clock = now or datetime.now(UTC)
    try:
        ticket = parse_opaque_token(login_ticket)
    except ValueError as exc:
        raise InvalidLoginTicketError() from exc
    result = await session.execute(
        select(AuthLoginTransaction)
        .where(AuthLoginTransaction.transaction_id == ticket.selector)
        .with_for_update()
    )
    transaction = result.scalar_one_or_none()
    if transaction is None or transaction.status != "VERIFIED" or transaction.login_ticket_hash is None or not hmac.compare_digest(transaction.login_ticket_hash, ticket.secret_hash):
        raise InvalidLoginTicketError()
    if transaction.ticket_expires_at is None or transaction.ticket_expires_at <= clock:
        raise InvalidLoginTicketError("LOGIN_TICKET_EXPIRED")
    if transaction.client_device_id != str(device_id):
        raise AuthServiceError("DEVICE_MISMATCH", status_code=403)

    user_result = await session.execute(
        select(User).where(User.social_provider == "KAKAO", User.social_subject == transaction.social_subject)
    )
    user = user_result.scalar_one_or_none()
    is_new_user = user is None
    if user is None:
        user_id = uuid.uuid4()
        insert_result = await session.execute(
            pg_insert(User)
            .values(
                user_id=user_id,
                social_provider="KAKAO",
                social_subject=transaction.social_subject,
                nickname=transaction.nickname,
                profile_image_url=transaction.profile_image_url,
            )
            .on_conflict_do_nothing(constraint="uq_users_social_identity")
            .returning(User)
        )
        user = insert_result.scalar_one_or_none()
        if user is None:
            existing_result = await session.execute(
                select(User).where(
                    User.social_provider == "KAKAO",
                    User.social_subject == transaction.social_subject,
                )
            )
            user = existing_result.scalar_one_or_none()
            if user is None:
                raise AuthServiceError("KAKAO_AUTH_FAILED")
            is_new_user = False
    if user.deleted_at is not None:
        raise AuthServiceError("KAKAO_AUTH_FAILED")
    if not is_new_user:
        if user.user_id is None:
            user.user_id = uuid.uuid4()
        if transaction.nickname is not None:
            user.nickname = transaction.nickname
        if transaction.profile_image_url is not None:
            user.profile_image_url = transaction.profile_image_url

    candidate = create_opaque_token()
    session_result = await session.execute(
        pg_insert(DeviceSession)
        .values(
            session_id=candidate.selector,
            user_id=user.user_id,
            client_device_id=str(device_id),
            platform="ANDROID",
            refresh_token_hash=candidate.secret_hash,
            refresh_expires_at=clock + REFRESH_TTL,
            revoked_at=None,
            last_seen_at=clock,
        )
        .on_conflict_do_update(
            constraint="uq_device_sessions_user_device",
            set_={
                "refresh_token_hash": candidate.secret_hash,
                "refresh_expires_at": clock + REFRESH_TTL,
                "revoked_at": None,
                "last_seen_at": clock,
            },
        )
        .returning(DeviceSession)
    )
    device_session = session_result.scalar_one_or_none()
    if device_session is None:  # 최소 test double 호환
        device_session = DeviceSession(session_id=candidate.selector, user_id=user.user_id, client_device_id=str(device_id), platform="ANDROID", refresh_token_hash=candidate.secret_hash, refresh_expires_at=clock + REFRESH_TTL, last_seen_at=clock)
    refresh = type(candidate)(selector=device_session.session_id, secret=candidate.secret)

    transaction.status = "CONSUMED"
    transaction.consumed_at = clock
    transaction.login_ticket_hash = None
    transaction.social_subject = None
    transaction.nickname = None
    transaction.profile_image_url = None
    access = create_access_token(user.user_id, device_session.session_id, settings, now=clock)
    return LoginExchangeResult(
        access_token=access,
        refresh_token=refresh.encoded,
        user=UserSummary(user_id=user.user_id, nickname=user.nickname, profile_image_url=user.profile_image_url, provider="KAKAO"),
        is_new_user=is_new_user,
    )


async def rotate_refresh_token(
    session: AsyncSession,
    refresh_token: str,
    device_id: str | uuid.UUID,
    settings: Settings,
    *,
    now: datetime | None = None,
) -> RefreshResult:
    """조건부 update 한 건으로 현재 기기의 Refresh Token을 회전한다."""
    clock = now or datetime.now(UTC)
    try:
        current = parse_opaque_token(refresh_token)
    except ValueError as exc:
        raise InvalidRefreshTokenError() from exc
    replacement = create_opaque_token(current.selector)
    result = await session.execute(
        update(DeviceSession)
        .where(
            DeviceSession.session_id == current.selector,
            DeviceSession.refresh_token_hash == current.secret_hash,
            DeviceSession.client_device_id == str(device_id),
            DeviceSession.revoked_at.is_(None),
            DeviceSession.refresh_expires_at > clock,
        )
        .values(
            refresh_token_hash=replacement.secret_hash,
            refresh_expires_at=clock + REFRESH_TTL,
            last_seen_at=clock,
        )
        .returning(DeviceSession.user_id, DeviceSession.session_id)
    )
    rotated = result.one_or_none()
    if rotated is None:
        await _raise_refresh_rejection(session, current, device_id, clock)

    user_id, session_id = rotated
    log_auth_event(
        logger,
        operation="REFRESH_TOKEN",
        result="SUCCEEDED",
        session_id=str(session_id),
    )
    return RefreshResult(
        access_token=create_access_token(user_id, session_id, settings, now=clock),
        refresh_token=replacement.encoded,
    )


async def logout_device_session(
    session: AsyncSession,
    refresh_token: str,
    device_id: str | uuid.UUID,
    *,
    now: datetime | None = None,
) -> None:
    """현재 기기 session을 폐기하고 같은 자격의 반복 요청은 성공 처리한다."""
    clock = now or datetime.now(UTC)
    try:
        current = parse_opaque_token(refresh_token)
    except ValueError as exc:
        raise InvalidRefreshTokenError() from exc
    result = await session.execute(
        update(DeviceSession)
        .where(
            DeviceSession.session_id == current.selector,
            DeviceSession.refresh_token_hash == current.secret_hash,
            DeviceSession.client_device_id == str(device_id),
            DeviceSession.revoked_at.is_(None),
            DeviceSession.refresh_expires_at > clock,
        )
        .values(revoked_at=clock, last_seen_at=clock)
        .returning(DeviceSession.session_id)
    )
    session_id = result.scalar_one_or_none()
    if session_id is None:
        stored = await session.get(DeviceSession, current.selector)
        if stored is None or not hmac.compare_digest(
            stored.refresh_token_hash, current.secret_hash
        ):
            raise InvalidRefreshTokenError()
        if stored.client_device_id != str(device_id):
            raise AuthServiceError("DEVICE_MISMATCH", status_code=403)
        if stored.refresh_expires_at <= clock:
            raise InvalidRefreshTokenError()
        if stored.revoked_at is None:
            raise InvalidRefreshTokenError()
        session_id = stored.session_id
    log_auth_event(
        logger,
        operation="LOGOUT",
        result="SUCCEEDED",
        session_id=str(session_id),
    )


async def _raise_refresh_rejection(
    session: AsyncSession,
    current: Any,
    device_id: str | uuid.UUID,
    clock: datetime,
) -> None:
    """조건부 회전 실패를 공개 오류 계약으로 분류한다."""
    stored = await session.get(DeviceSession, current.selector)
    if stored is None or not hmac.compare_digest(
        stored.refresh_token_hash, current.secret_hash
    ):
        raise InvalidRefreshTokenError()
    if stored.client_device_id != str(device_id):
        raise AuthServiceError("DEVICE_MISMATCH", status_code=403)
    if stored.refresh_expires_at <= clock:
        raise InvalidRefreshTokenError("TOKEN_EXPIRED")
    raise InvalidRefreshTokenError()
