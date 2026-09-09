"""F006/F007 진행 전환이 F011 도착·출발 확인·자동 처리 알림으로 이어지는지 검증한다."""

import os
import uuid
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta

import pytest
from geoalchemy2 import WKTElement
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import selectinload

from app.clients.fcm import FcmSendResult
from app.db import transaction_session
from app.models.auth import DeviceSession, User
from app.models.itinerary import ItineraryItem, Place, TripDay
from app.models.notification import Notification
from app.models.trip import Trip
from app.schemas.progress import ProgressEventRequest
from app.services.detection import DetectionService
from app.services.notification.dispatch import NotificationDispatchService


@pytest.fixture
async def session_factory() -> AsyncIterator[async_sessionmaker[AsyncSession]]:
    database_url = os.getenv("TEST_DATABASE_URL") or os.getenv("DATABASE_URL")
    if not database_url:
        pytest.fail("TEST_DATABASE_URL 또는 DATABASE_URL이 필요합니다.")
    engine = create_async_engine(database_url)
    yield async_sessionmaker(engine, expire_on_commit=False)
    await engine.dispose()


class _StubFcm:
    def __init__(self) -> None:
        self.calls = 0
        self.settings = type("S", (), {"fcm_enabled": True})()

    async def send(self, token: str, data: dict[str, str]) -> FcmSendResult:
        self.calls += 1
        return FcmSendResult.OK

    async def aclose(self) -> None:
        return None


async def _seed(
    factory: async_sessionmaker[AsyncSession],
    *,
    item_status: str = "EN_ROUTE",
    replacement_enabled: bool = True,
    with_device: bool = True,
    extra_item_status: str | None = None,
) -> dict[str, object]:
    now = datetime.now(UTC)
    async with transaction_session(factory) as session:
        user = User(
            social_provider="KAKAO",
            social_subject=f"notif-progress-{uuid.uuid4()}",
            replacement_suggestion_enabled=replacement_enabled,
        )
        session.add(user)
        await session.flush()
        if with_device:
            session.add(
                DeviceSession(
                    user_id=user.user_id,
                    client_device_id="dev-1",
                    platform="ANDROID",
                    refresh_token_hash="t" * 64,
                    refresh_expires_at=now + timedelta(days=30),
                    fcm_token=f"token-{uuid.uuid4()}",
                )
            )
        trip = Trip(user_id=user.user_id, name="진행 알림", start_date=now.date(), end_date=now.date())
        place = Place(
            tour_content_id=f"notif-progress-{uuid.uuid4()}",
            name="경복궁",
            category="OTHER",
            location=WKTElement("POINT(126.977 37.5796)", srid=4326),
        )
        session.add_all([trip, place])
        await session.flush()
        day = TripDay(
            trip_id=trip.trip_id,
            visit_date=now.date(),
            day_number=1,
            status="IN_PROGRESS",
            schedule_version=1,
            progress_version=1,
            detection_active=True,
        )
        session.add(day)
        await session.flush()
        item = ItineraryItem(
            trip_day_id=day.trip_day_id,
            place_id=place.place_id,
            sequence=1,
            status=item_status,
            planned_stay_minutes=30,
            stay_source="USER_ADJUSTED",
        )
        session.add(item)
        await session.flush()
        extra_id = None
        if extra_item_status is not None:
            extra_place = Place(
                tour_content_id=f"notif-progress-{uuid.uuid4()}",
                name="다음 장소",
                category="OTHER",
                location=WKTElement("POINT(127.01 37.51)", srid=4326),
            )
            session.add(extra_place)
            await session.flush()
            extra = ItineraryItem(
                trip_day_id=day.trip_day_id,
                place_id=extra_place.place_id,
                sequence=2,
                status=extra_item_status,
                planned_stay_minutes=30,
                stay_source="USER_ADJUSTED",
            )
            session.add(extra)
            await session.flush()
            extra_id = extra.item_id
        return {
            "trip_id": trip.trip_id,
            "item_id": item.item_id,
            "extra_item_id": extra_id,
            "user_id": user.user_id,
            "day_id": day.trip_day_id,
            "now": now,
        }


def _payload(item_id: uuid.UUID, now: datetime, *, event_type: str = "DWELL") -> ProgressEventRequest:
    kind = "ARRIVAL" if event_type == "DWELL" else "DEPARTURE"
    return ProgressEventRequest.model_validate(
        {
            "eventId": str(uuid.uuid4()),
            "eventType": event_type,
            "itemId": str(item_id),
            "geofenceId": f"{item_id}:{kind}",
            "occurredAt": now.isoformat(),
            "location": {"latitude": 37.5, "longitude": 127.0, "accuracyMeters": 20},
        }
    )


async def _register(factory, seed, *, event_type: str = "DWELL", item_id: uuid.UUID | None = None):
    async with transaction_session(factory) as session:
        return await DetectionService(session).register_event(
            user_id=seed["user_id"],
            trip_id=seed["trip_id"],
            visit_date=seed["now"].date(),
            payload=_payload(item_id or seed["item_id"], seed["now"], event_type=event_type),
            received_at=seed["now"],
        )


async def _notifications(factory, user_id, *, type_: str | None = None) -> list[Notification]:
    async with factory() as session:
        stmt = select(Notification).where(Notification.user_id == user_id)
        if type_ is not None:
            stmt = stmt.where(Notification.type == type_)
        return list((await session.scalars(stmt.order_by(Notification.created_at))).all())


async def _run_tick(factory, *, now: datetime) -> _StubFcm:
    stub = _StubFcm()
    async with factory() as session:
        async with session.begin():
            await NotificationDispatchService(session, client=stub, now=lambda: now).tick()
    return stub


async def _cleanup(factory, user_id) -> None:
    from sqlalchemy import delete

    async with transaction_session(factory) as session:
        await session.execute(delete(User).where(User.user_id == user_id))


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("item_status", "event_type", "expected_type", "kind"),
    [
        ("EN_ROUTE", "DWELL", "ARRIVAL_CHECK", "arrival_check:1"),
        ("ARRIVED", "EXIT", "DEPARTURE_CHECK", "departure_check"),
    ],
)
async def test_register_event_creates_transition_check_notification(
    session_factory, item_status, event_type, expected_type, kind
) -> None:
    seed = await _seed(session_factory, item_status=item_status)
    try:
        result = await _register(session_factory, seed, event_type=event_type)
        assert result.candidate is not None

        rows = await _notifications(session_factory, seed["user_id"])
        assert len(rows) == 1
        row = rows[0]
        assert row.type == expected_type
        assert row.transition_id == result.candidate.transition_id
        assert row.item_id == seed["item_id"]
        assert row.dedup_key == f"transition:{result.candidate.transition_id}:{kind}"
        assert row.sent_at is None
    finally:
        await _cleanup(session_factory, seed["user_id"])


@pytest.mark.asyncio
async def test_tick_auto_confirm_creates_and_sends_notification(session_factory) -> None:
    seed = await _seed(session_factory, extra_item_status="PLANNED")
    try:
        result = await _register(session_factory, seed)
        assert result.candidate is not None
        finalize_at = result.candidate.auto_finalize_at

        stub = await _run_tick(session_factory, now=finalize_at + timedelta(seconds=1))

        rows = await _notifications(session_factory, seed["user_id"])
        auto = [r for r in rows if r.type == "ARRIVAL_AUTO_CONFIRMED"]
        assert len(auto) == 1
        assert "되돌" in auto[0].body
        # 같은 tick이 finalize hook으로 만든 자동 확정 알림을 생성 후 발송까지 한다.
        assert auto[0].sent_at is not None
        # 미발송이던 확인 알림(1) + 새 자동 확정 알림(1) 모두 이 tick에서 발송된다.
        assert all(r.sent_at is not None for r in rows)
        assert stub.calls == 2
    finally:
        await _cleanup(session_factory, seed["user_id"])


@pytest.mark.asyncio
async def test_not_arrived_reprompt_creates_prompt_seq_2_once(session_factory) -> None:
    seed = await _seed(session_factory)
    try:
        first = await _register(session_factory, seed)
        assert first.candidate is not None
        async with transaction_session(session_factory) as session:
            decision = await DetectionService(session).decide_transition(
                user_id=seed["user_id"],
                transition_id=first.candidate.transition_id,
                decision="NOT_ARRIVED",
                idempotency_key=uuid.uuid4(),
                decided_at=seed["now"],
            )
        assert decision.next_prompt_at is not None

        # 재질문 시각 이후 tick 두 번 → prompt_seq=2 알림은 한 번만
        await _run_tick(session_factory, now=decision.next_prompt_at + timedelta(seconds=1))
        await _run_tick(session_factory, now=decision.next_prompt_at + timedelta(minutes=5))

        checks = await _notifications(session_factory, seed["user_id"], type_="ARRIVAL_CHECK")
        keys = sorted(row.dedup_key for row in checks)
        assert keys == [
            f"transition:{first.candidate.transition_id}:arrival_check:1",
            f"transition:{first.candidate.transition_id}:arrival_check:2",
        ]
    finally:
        await _cleanup(session_factory, seed["user_id"])


@pytest.mark.asyncio
async def test_progress_notifications_ignore_replacement_setting(session_factory) -> None:
    seed = await _seed(session_factory, replacement_enabled=False)
    try:
        result = await _register(session_factory, seed)
        assert result.candidate is not None

        rows = await _notifications(session_factory, seed["user_id"], type_="ARRIVAL_CHECK")
        assert len(rows) == 1  # 설정 off여도 진행 알림은 생성(SC-003)
    finally:
        await _cleanup(session_factory, seed["user_id"])


@pytest.mark.asyncio
async def test_composite_auto_confirm_creates_one_notification(session_factory) -> None:
    # 이전 장소 ARRIVED + 다음 장소 EN_ROUTE → 다음 장소 도착 자동 확정이 COMPOSITE
    seed = await _seed(session_factory, item_status="ARRIVED", extra_item_status="EN_ROUTE")
    try:
        result = await _register(session_factory, seed, item_id=seed["extra_item_id"])
        assert result.candidate is not None
        finalize_at = result.candidate.auto_finalize_at

        await _run_tick(session_factory, now=finalize_at + timedelta(seconds=1))

        auto = await _notifications(session_factory, seed["user_id"], type_="ARRIVAL_AUTO_CONFIRMED")
        assert len(auto) == 1
        assert auto[0].transition_id == result.candidate.transition_id
    finally:
        await _cleanup(session_factory, seed["user_id"])


@pytest.mark.asyncio
async def test_still_here_creates_no_further_departure_notification(session_factory) -> None:
    seed = await _seed(session_factory, item_status="ARRIVED")
    try:
        first = await _register(session_factory, seed, event_type="EXIT")
        assert first.candidate is not None
        async with transaction_session(session_factory) as session:
            await DetectionService(session).decide_transition(
                user_id=seed["user_id"],
                transition_id=first.candidate.transition_id,
                decision="STILL_HERE",
                idempotency_key=uuid.uuid4(),
                decided_at=seed["now"],
            )
        # 같은 장소 재-EXIT 시도는 차단되어 새 후보·알림이 없어야 한다
        again = await _register(session_factory, seed, event_type="EXIT")
        assert again.candidate is None

        rows = await _notifications(session_factory, seed["user_id"], type_="DEPARTURE_CHECK")
        assert len(rows) == 1  # 최초 1건뿐, 추가 없음
    finally:
        await _cleanup(session_factory, seed["user_id"])
