"""Kakao 로그인 service의 상태 전이와 transaction 경계 테스트."""

from __future__ import annotations

from contextlib import AbstractAsyncContextManager
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest

from app.core.config import Settings
from app.core.security import create_opaque_token
from app.models.auth import AuthLoginTransaction, User
from app.services.auth import AuthService, AuthServiceError


def _settings() -> Settings:
    """외부 환경에 의존하지 않는 유효 설정을 반환한다."""
    return Settings(
        _env_file=None,
        database_url="postgresql+asyncpg://user:password@localhost/gilpick",
        jwt_signing_secret="test-signing-secret-at-least-32-bytes",
        jwt_issuer="https://api.gilpick.example",
        jwt_audience="gilpick-android",
        kakao_rest_api_key="rest-key",
        kakao_client_secret="client-secret",
        kakao_redirect_uri="https://api.gilpick.example/api/v1/auth/kakao/callback",
        android_app_link_base_url="https://app.gilpick.example/auth/kakao/complete",
        android_app_link_host="app.gilpick.example",
    )


class _Result:
    """Service가 사용하는 SQLAlchemy result의 최소 test double."""

    def __init__(self, value: object | None = None, *, rowcount: int = 1) -> None:
        self.value = value
        self.rowcount = rowcount

    def scalar_one_or_none(self) -> object | None:
        return self.value

    def scalar_one(self) -> object:
        assert self.value is not None
        return self.value


class _Begin(AbstractAsyncContextManager[None]):
    def __init__(self, session: "_Session") -> None:
        self.session = session

    async def __aenter__(self) -> None:
        assert not self.session.in_transaction
        self.session.in_transaction = True

    async def __aexit__(self, *exc_info: object) -> None:
        self.session.in_transaction = False


class _Session(AbstractAsyncContextManager["_Session"]):
    def __init__(self, *results: _Result) -> None:
        self.results = list(results)
        self.in_transaction = False
        self.added: list[object] = []

    async def __aenter__(self) -> "_Session":
        return self

    async def __aexit__(self, *exc_info: object) -> None:
        assert not self.in_transaction

    def begin(self) -> _Begin:
        return _Begin(self)

    async def execute(self, _statement: object) -> _Result:
        assert self.in_transaction
        return self.results.pop(0) if self.results else _Result()

    async def flush(self) -> None:
        assert self.in_transaction

    def add(self, value: object) -> None:
        assert self.in_transaction
        self.added.append(value)


class _SessionFactory:
    def __init__(self, *sessions: _Session) -> None:
        self.sessions = list(sessions)
        self.created: list[_Session] = []

    def __call__(self) -> _Session:
        session = self.sessions.pop(0)
        self.created.append(session)
        return session


def _transaction(*, status: str = "PENDING") -> AuthLoginTransaction:
    now = datetime.now(UTC)
    return AuthLoginTransaction(
        transaction_id=uuid4(),
        state_hash="a" * 64,
        client_device_id=str(uuid4()),
        platform="ANDROID",
        status=status,
        expires_at=now + timedelta(minutes=10),
    )


@pytest.mark.asyncio
async def test_callback_atomically_claims_state_before_calling_kakao() -> None:
    """같은 state는 한 요청만 선점하며 선점 실패 요청은 Kakao를 호출하지 않는다."""
    transaction = _transaction()
    winner_factory = _SessionFactory(_Session(_Result(transaction)), _Session(_Result(transaction)))
    loser_factory = _SessionFactory(_Session(_Result(rowcount=0)))
    kakao = SimpleNamespace(
        exchange_code=AsyncMock(return_value="kakao-access-token"),
        get_user_profile=AsyncMock(return_value={"id": "42", "nickname": None, "profile_image_url": None}),
    )

    winner = AuthService(winner_factory, kakao, _settings())
    await winner.handle_kakao_callback(state="state", code="one-time-code")

    assert transaction.status == "VERIFIED"
    assert kakao.exchange_code.await_count == 1

    loser = AuthService(loser_factory, kakao, _settings())
    with pytest.raises(AuthServiceError):
        await loser.handle_kakao_callback(state="state", code="one-time-code")

    assert kakao.exchange_code.await_count == 1


@pytest.mark.asyncio
async def test_callback_releases_database_transaction_during_external_http() -> None:
    """Kakao HTTP 대기 중에는 DB transaction과 row lock을 유지하지 않는다."""
    transaction = _transaction()
    factory = _SessionFactory(_Session(_Result(transaction)), _Session(_Result(transaction)))

    async def exchange_code(_code: str) -> str:
        assert all(not session.in_transaction for session in factory.created)
        return "kakao-access-token"

    async def get_user_profile(_token: str) -> dict[str, object | None]:
        assert all(not session.in_transaction for session in factory.created)
        return {"id": "42", "nickname": None, "profile_image_url": None}

    kakao = SimpleNamespace(exchange_code=exchange_code, get_user_profile=get_user_profile)
    await AuthService(factory, kakao, _settings()).handle_kakao_callback(
        state="state", code="one-time-code"
    )

    assert transaction.status == "VERIFIED"


@pytest.mark.asyncio
async def test_callback_does_not_retry_code_exchange_and_retries_profile_once() -> None:
    """code 교환은 무재시도이고 사용자 조회의 일시 오류만 정확히 한 번 재시도한다."""
    failed = _transaction()
    code_client = SimpleNamespace(
        exchange_code=AsyncMock(side_effect=TimeoutError),
        get_user_profile=AsyncMock(),
    )
    code_factory = _SessionFactory(_Session(_Result(failed)), _Session(_Result(failed)))

    with pytest.raises(AuthServiceError):
        await AuthService(code_factory, code_client, _settings()).handle_kakao_callback(
            state="state", code="one-time-code"
        )

    assert code_client.exchange_code.await_count == 1
    assert code_client.get_user_profile.await_count == 0
    assert failed.status == "FAILED"

    recovered = _transaction()
    profile_client = SimpleNamespace(
        exchange_code=AsyncMock(return_value="kakao-access-token"),
        get_user_profile=AsyncMock(
            side_effect=[TimeoutError, {"id": "42", "nickname": None, "profile_image_url": None}]
        ),
    )
    profile_factory = _SessionFactory(_Session(_Result(recovered)), _Session(_Result(recovered)))

    await AuthService(profile_factory, profile_client, _settings()).handle_kakao_callback(
        state="state", code="one-time-code"
    )

    assert profile_client.exchange_code.await_count == 1
    assert profile_client.get_user_profile.await_count == 2
    assert recovered.status == "VERIFIED"


@pytest.mark.asyncio
async def test_exchange_upserts_existing_user_without_erasing_nullable_profile() -> None:
    """nullable Kakao profile로 로그인해도 기존 non-null profile은 지우지 않는다."""
    transaction = _transaction(status="VERIFIED")
    ticket = create_opaque_token(transaction.transaction_id)
    transaction.login_ticket_hash = ticket.secret_hash
    transaction.ticket_expires_at = datetime.now(UTC) + timedelta(seconds=120)
    transaction.social_subject = "42"
    transaction.nickname = None
    transaction.profile_image_url = None
    user = User(
        social_provider="KAKAO",
        social_subject="42",
        nickname="기존 별명",
        profile_image_url="https://example.com/profile.png",
    )
    session = _Session(_Result(transaction), _Result(user), _Result())
    service = AuthService(_SessionFactory(session), SimpleNamespace(), _settings())

    result = await service.exchange_login_ticket(
        login_ticket=ticket.encoded,
        device_id=transaction.client_device_id,
    )

    assert result.user.user_id == user.user_id
    assert user.nickname == "기존 별명"
    assert user.profile_image_url == "https://example.com/profile.png"
    assert transaction.status == "CONSUMED"


@pytest.mark.asyncio
@pytest.mark.parametrize("terminal_status", ["VERIFIED", "FAILED"])
async def test_callback_transitions_pending_through_processing_to_terminal(
    terminal_status: str,
) -> None:
    """callback은 PENDING을 선점한 뒤 성공 또는 실패 terminal 상태로 끝낸다."""
    transaction = _transaction()
    observed: list[str] = []

    async def exchange_code(_code: str) -> str:
        observed.append(transaction.status)
        if terminal_status == "FAILED":
            raise TimeoutError
        return "kakao-access-token"

    kakao = SimpleNamespace(
        exchange_code=exchange_code,
        get_user_profile=AsyncMock(return_value={"id": "42", "nickname": None, "profile_image_url": None}),
    )
    factory = _SessionFactory(_Session(_Result(transaction)), _Session(_Result(transaction)))
    service = AuthService(factory, kakao, _settings())

    if terminal_status == "FAILED":
        with pytest.raises(AuthServiceError):
            await service.handle_kakao_callback(state="state", code="one-time-code")
    else:
        await service.handle_kakao_callback(state="state", code="one-time-code")

    assert observed == ["PROCESSING"]
    assert transaction.status == terminal_status
