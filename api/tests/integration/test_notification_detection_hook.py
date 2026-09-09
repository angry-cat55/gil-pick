"""F008 감지 최초 생성이 F011 장소 변경 제안 알림으로 이어지는지 검증한다."""

from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import delete, func, select, update

from app.db import transaction_session
from app.models.auth import DeviceSession, User
from app.models.detection import Detection
from app.models.notification import Notification
from app.schemas.detection import (
    CongestionVerdict,
    OperatingHoursVerdict,
    WeatherVerdict,
)
from app.jobs import variable_detection
from app.services.detection import evaluator
from tests.integration.variable_detection_support import factory, providers, seed


async def _value(value):
    return value


def _blocking_visit(monkeypatch: pytest.MonkeyPatch) -> None:
    """영업 불가 하나로만 ACTIVE 감지가 생기도록 provider 판정을 고정한다."""
    providers(monkeypatch, evaluator)
    monkeypatch.setattr(
        evaluator,
        "evaluate_weather",
        lambda *a, **k: _value(WeatherVerdict(available=False, unavailable_reason="NO_FORECAST")),
    )
    monkeypatch.setattr(
        evaluator,
        "evaluate_congestion",
        lambda *a, **k: _value(CongestionVerdict(available=False, unavailable_reason="NOT_IN_SUPPORT_AREA")),
    )
    monkeypatch.setattr(
        evaluator,
        "evaluate_operating_hours",
        lambda *a, **k: _value(
            OperatingHoursVerdict(available=True, closing_soon=False, visit_blocked=True, temp_closed=False)
        ),
    )


class _StubFcm:
    """발송 시도를 세고 결과를 흉내 내는 FcmClient 대역."""

    def __init__(self, *, error: Exception | None = None) -> None:
        self.error = error
        self.calls = 0
        self.settings = type("S", (), {"fcm_enabled": True})()

    async def send(self, token: str, data: dict[str, str]):
        self.calls += 1
        if self.error is not None:
            raise self.error
        from app.clients.fcm import FcmSendResult

        return FcmSendResult.OK

    async def aclose(self) -> None:
        return None


async def _attach_device(session_factory, user_id, token: str) -> None:
    async with transaction_session(session_factory) as session:
        session.add(
            DeviceSession(
                user_id=user_id,
                client_device_id="dev-1",
                platform="ANDROID",
                refresh_token_hash=token.ljust(64, "0")[:64],
                refresh_expires_at=datetime.now(UTC) + timedelta(days=30),
                fcm_token=token,
            )
        )


@pytest.mark.asyncio
async def test_first_insert_creates_one_place_change_notification(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, session_factory = await factory()
    try:
        trip_id, item_id, user_id = await seed(session_factory)
        _blocking_visit(monkeypatch)

        async with transaction_session(session_factory) as session:
            count, created = await evaluator.evaluate_all_active(session)
        assert count == 1
        assert len(created) == 1

        async with session_factory() as session:
            detection = await session.scalar(
                select(Detection).where(Detection.item_id == item_id)
            )
            notifications = list(
                (
                    await session.scalars(
                        select(Notification).where(Notification.user_id == user_id)
                    )
                ).all()
            )
        assert created == [detection.detection_id]
        assert len(notifications) == 1
        row = notifications[0]
        assert row.type == "PLACE_CHANGE_SUGGESTION"
        assert row.dedup_key == f"detection:{detection.detection_id}"
        assert row.detection_id == detection.detection_id
        assert row.trip_id == trip_id
        assert row.sent_at is None
    finally:
        if "user_id" in locals():
            async with transaction_session(session_factory) as session:
                await session.execute(delete(User).where(User.user_id == user_id))
        await engine.dispose()


@pytest.mark.asyncio
async def test_reevaluation_update_creates_no_new_notification(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, session_factory = await factory()
    try:
        _, item_id, user_id = await seed(session_factory)
        _blocking_visit(monkeypatch)

        async with transaction_session(session_factory) as session:
            _, created_first = await evaluator.evaluate_all_active(session)
        async with transaction_session(session_factory) as session:
            count_second, created_second = await evaluator.evaluate_all_active(session)

        assert len(created_first) == 1
        assert count_second == 1
        assert created_second == []
        async with session_factory() as session:
            total = await session.scalar(
                select(func.count()).select_from(Notification).where(Notification.user_id == user_id)
            )
        assert total == 1
    finally:
        if "user_id" in locals():
            async with transaction_session(session_factory) as session:
                await session.execute(delete(User).where(User.user_id == user_id))
        await engine.dispose()


@pytest.mark.asyncio
async def test_setting_off_creates_detection_without_notification(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, session_factory = await factory()
    try:
        _, item_id, user_id = await seed(session_factory)
        async with transaction_session(session_factory) as session:
            await session.execute(
                update(User)
                .where(User.user_id == user_id)
                .values(replacement_suggestion_enabled=False)
            )
        _blocking_visit(monkeypatch)

        async with transaction_session(session_factory) as session:
            count, created = await evaluator.evaluate_all_active(session)

        assert count == 1
        assert len(created) == 1  # 감지 자체는 정상 생성
        async with session_factory() as session:
            detections = await session.scalar(
                select(func.count()).select_from(Detection).where(Detection.item_id == item_id)
            )
            notifications = await session.scalar(
                select(func.count()).select_from(Notification).where(Notification.user_id == user_id)
            )
        assert detections == 1
        assert notifications == 0
    finally:
        if "user_id" in locals():
            async with transaction_session(session_factory) as session:
                await session.execute(delete(User).where(User.user_id == user_id))
        await engine.dispose()


@pytest.mark.asyncio
async def test_cycle_commit_dispatches_place_change_notification(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, session_factory = await factory()
    try:
        _, item_id, user_id = await seed(session_factory)
        _blocking_visit(monkeypatch)
        await _attach_device(session_factory, user_id, "token-1")

        stub = _StubFcm()

        async with session_factory() as session:
            async with session.begin():
                _, created = await evaluator.evaluate_all_active(session)
            await variable_detection._dispatch_place_change(session, created, stub)

        assert stub.calls == 1
        async with session_factory() as session:
            row = await session.scalar(
                select(Notification).where(Notification.user_id == user_id)
            )
        assert row.sent_at is not None
    finally:
        if "user_id" in locals():
            async with transaction_session(session_factory) as session:
                await session.execute(delete(User).where(User.user_id == user_id))
        await engine.dispose()


@pytest.mark.asyncio
async def test_dispatch_failure_does_not_roll_back_detection(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, session_factory = await factory()
    try:
        _, item_id, user_id = await seed(session_factory)
        _blocking_visit(monkeypatch)
        await _attach_device(session_factory, user_id, "token-2")

        stub = _StubFcm(error=RuntimeError("fcm down"))

        async with session_factory() as session:
            async with session.begin():
                _, created = await evaluator.evaluate_all_active(session)
            await variable_detection._dispatch_place_change(session, created, stub)

        async with session_factory() as session:
            detection = await session.scalar(
                select(func.count()).select_from(Detection).where(Detection.item_id == item_id)
            )
            notification = await session.scalar(
                select(func.count()).select_from(Notification).where(Notification.user_id == user_id)
            )
        assert detection == 1
        assert notification == 1
    finally:
        if "user_id" in locals():
            async with transaction_session(session_factory) as session:
                await session.execute(delete(User).where(User.user_id == user_id))
        await engine.dispose()
