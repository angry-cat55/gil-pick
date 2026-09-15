"""감지 결과의 중복 억제·종료·재개 생명주기를 검증한다."""

import asyncio
from datetime import datetime

import pytest
from sqlalchemy import delete, func, select, update

from app.db import transaction_session
from app.models.auth import User
from app.models.detection import Detection
from app.models.itinerary import ItineraryItem, TripDay
from app.models.notification import Notification
from app.schemas.detection import CongestionVerdict, OperatingHoursVerdict, WeatherVerdict
from app.services.detection import evaluator
from tests.integration.variable_detection_support import factory, providers, seed


async def _value(value):
    return value


def _risk_providers(monkeypatch: pytest.MonkeyPatch) -> None:
    providers(monkeypatch, evaluator)
    monkeypatch.setattr(
        evaluator,
        "evaluate_congestion",
        lambda *a, **k: _value(
            CongestionVerdict(
                available=True, level="CROWDED", sensitivity="MEDIUM", crowded=True
            )
        ),
    )
    monkeypatch.setattr(
        evaluator,
        "evaluate_weather",
        lambda *a, **k: _value(
            WeatherVerdict(available=False, unavailable_reason="NO_FORECAST")
        ),
    )
    monkeypatch.setattr(
        evaluator,
        "evaluate_operating_hours",
        lambda *a, **k: _value(
            OperatingHoursVerdict(available=False, unavailable_reason="HOURS_UNKNOWN")
        ),
    )


@pytest.mark.asyncio
@pytest.mark.parametrize("terminal_status", ["ARRIVED", "SKIPPED"])
async def test_detection_is_updated_then_invalidated_and_recreated(
    monkeypatch: pytest.MonkeyPatch, terminal_status: str
) -> None:
    engine, session_factory = await factory()
    try:
        _, item_id, user_id = await seed(session_factory, category="CAFE")
        _risk_providers(monkeypatch)
        async with transaction_session(session_factory) as session:
            item = await session.get(ItineraryItem, item_id)
            day_id = item.trip_day_id

        concurrent = await asyncio.gather(
            evaluator.reevaluate_day(session_factory, day_id),
            evaluator.reevaluate_day(session_factory, day_id),
        )
        assert [count for count, _ in concurrent] == [1, 1]
        async with session_factory() as session:
            first_evaluated_at = await session.scalar(
                select(Detection.last_evaluated_at).where(
                    Detection.item_id == item_id, Detection.status == "ACTIVE"
                )
            )
        assert (await evaluator.reevaluate_day(session_factory, day_id))[0] == 1
        async with session_factory() as session:
            active_count = await session.scalar(
                select(func.count()).select_from(Detection).where(
                    Detection.item_id == item_id, Detection.status == "ACTIVE"
                )
            )
            last_evaluated_at = await session.scalar(
                select(Detection.last_evaluated_at).where(
                    Detection.item_id == item_id, Detection.status == "ACTIVE"
                )
            )
        assert active_count == 1
        assert last_evaluated_at > first_evaluated_at

        async with transaction_session(session_factory) as session:
            await session.execute(
                update(ItineraryItem)
                .where(ItineraryItem.item_id == item_id)
                .values(status=terminal_status)
            )
        assert (await evaluator.reevaluate_day(session_factory, day_id))[0] == 0
        async with session_factory() as session:
            invalidated = await session.scalar(
                select(Detection).where(
                    Detection.item_id == item_id, Detection.status == "INVALIDATED"
                )
            )
        assert invalidated is not None and isinstance(invalidated.resolved_at, datetime)

        async with transaction_session(session_factory) as session:
            await session.execute(
                update(ItineraryItem).where(ItineraryItem.item_id == item_id).values(status="PLANNED")
            )
            await session.execute(
                update(TripDay).where(TripDay.trip_day_id == day_id).values(status="IN_PROGRESS", detection_active=True)
            )
        assert (await evaluator.reevaluate_day(session_factory, day_id))[0] == 1
        async with session_factory() as session:
            assert await session.scalar(
                select(func.count()).select_from(Detection).where(
                    Detection.item_id == item_id, Detection.status == "ACTIVE"
                )
            ) == 1

        async with transaction_session(session_factory) as session:
            await session.execute(
                update(TripDay)
                .where(TripDay.trip_day_id == day_id)
                .values(status="COMPLETED", detection_active=False)
            )
        assert (await evaluator.reevaluate_day(session_factory, day_id))[0] == 0
        async with session_factory() as session:
            assert await session.scalar(
                select(func.count()).select_from(Detection).where(
                    Detection.item_id == item_id, Detection.status == "ACTIVE"
                )
            ) == 0

        async with transaction_session(session_factory) as session:
            await session.execute(
                update(TripDay)
                .where(TripDay.trip_day_id == day_id)
                .values(status="IN_PROGRESS", detection_active=True)
            )
        assert (await evaluator.reevaluate_day(session_factory, day_id))[0] == 1

        async with transaction_session(session_factory) as session:
            await session.execute(
                update(Detection)
                .where(Detection.item_id == item_id, Detection.status == "ACTIVE")
                .values(status="RESOLVED", resolved_at=datetime.now(evaluator.KST))
            )
        assert (await evaluator.reevaluate_day(session_factory, day_id))[0] == 1
        async with session_factory() as session:
            assert await session.scalar(
                select(func.count()).select_from(Detection).where(
                    Detection.item_id == item_id, Detection.status == "ACTIVE"
                )
            ) == 1

        async with transaction_session(session_factory) as session:
            await session.execute(
                delete(ItineraryItem).where(ItineraryItem.item_id == item_id)
            )
        async with session_factory() as session:
            assert await session.scalar(
                select(func.count()).select_from(Detection).where(
                    Detection.item_id == item_id
                )
            ) == 0
    finally:
        if "user_id" in locals():
            async with transaction_session(session_factory) as session:
                await session.execute(delete(User).where(User.user_id == user_id))
        await engine.dispose()


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("dismissed_primary", "dismissed_visit_blocked", "current_visit_blocked", "expected_active"),
    [
        ("CONGESTION", False, False, 0),
        ("WEATHER", False, False, 1),
        ("OPERATING_HOURS", False, True, 1),
        ("OPERATING_HOURS", True, True, 0),
    ],
)
async def test_dismissed_detection_is_recreated_only_when_risk_worsens(
    monkeypatch: pytest.MonkeyPatch,
    dismissed_primary: str,
    dismissed_visit_blocked: bool,
    current_visit_blocked: bool,
    expected_active: int,
) -> None:
    engine, session_factory = await factory()
    try:
        _, item_id, user_id = await seed(session_factory, category="CAFE")
        _risk_providers(monkeypatch)
        if current_visit_blocked:
            monkeypatch.setattr(
                evaluator,
                "evaluate_congestion",
                lambda *a, **k: _value(
                    CongestionVerdict(
                        available=False,
                        unavailable_reason="NOT_IN_SUPPORT_AREA",
                    )
                ),
            )
            monkeypatch.setattr(
                evaluator,
                "evaluate_operating_hours",
                lambda *a, **k: _value(
                    OperatingHoursVerdict(
                        available=True,
                        closing_soon=False,
                        visit_blocked=True,
                        temp_closed=False,
                    )
                ),
            )
        async with transaction_session(session_factory) as session:
            await evaluator.evaluate_all_active(session)
            await session.execute(
                update(Detection)
                .where(Detection.item_id == item_id, Detection.status == "ACTIVE")
                .values(
                    status="DISMISSED",
                    primary_type=dismissed_primary,
                    evaluation_snapshot={
                        "variables": {
                            "operatingHours": {
                                "visitBlocked": dismissed_visit_blocked
                            }
                        }
                    },
                    resolved_at=datetime.now(evaluator.KST),
                )
            )

        async with transaction_session(session_factory) as session:
            item = await session.get(ItineraryItem, item_id)
            day_id = item.trip_day_id
        outcomes = await asyncio.gather(
            evaluator.reevaluate_day(session_factory, day_id),
            evaluator.reevaluate_day(session_factory, day_id),
        )

        async with session_factory() as session:
            active_count = await session.scalar(
                select(func.count()).select_from(Detection).where(
                    Detection.item_id == item_id, Detection.status == "ACTIVE"
                )
            )
            notification_count = await session.scalar(
                select(func.count()).select_from(Notification).where(
                    Notification.user_id == user_id
                )
            )
        assert active_count == expected_active
        assert sum(len(created) for _, created in outcomes) == expected_active
        assert notification_count == 1 + expected_active
    finally:
        if "user_id" in locals():
            async with transaction_session(session_factory) as session:
                await session.execute(delete(User).where(User.user_id == user_id))
        await engine.dispose()
