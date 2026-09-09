"""ETA 변경 직후 날짜 단위 재평가 흐름을 검증한다."""

from decimal import Decimal

import pytest
from sqlalchemy import delete, select

from app.db import transaction_session
from app.models.auth import User
from app.models.detection import Detection
from app.models.itinerary import ItineraryItem
from app.schemas.detection import CongestionVerdict, OperatingHoursVerdict, WeatherVerdict
from app.services.detection import evaluator
from tests.integration.variable_detection_support import factory, providers, seed


async def _value(value):
    return value


@pytest.mark.asyncio
async def test_eta_reevaluation_updates_active_detection_without_resolving(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, session_factory = await factory()
    try:
        _, item_id, user_id = await seed(session_factory, category="CAFE")
        providers(monkeypatch, evaluator)
        crowded = True
        monkeypatch.setattr(
            evaluator,
            "evaluate_congestion",
            lambda *a, **k: _value(
                CongestionVerdict(
                    available=True,
                    level="CROWDED" if crowded else "NORMAL",
                    sensitivity="MEDIUM",
                    crowded=crowded,
                )
            ),
        )
        monkeypatch.setattr(
            evaluator,
            "evaluate_weather",
            lambda *a, **k: _value(WeatherVerdict(available=False, unavailable_reason="NO_FORECAST")),
        )
        monkeypatch.setattr(
            evaluator,
            "evaluate_operating_hours",
            lambda *a, **k: _value(OperatingHoursVerdict(available=False, unavailable_reason="HOURS_UNKNOWN")),
        )

        async with transaction_session(session_factory) as session:
            assert await evaluator.evaluate_all_active(session) == 1
            item = await session.get(ItineraryItem, item_id)
            day_id = item.trip_day_id

        crowded = False
        assert await evaluator.reevaluate_day(session_factory, day_id) == 1

        async with session_factory() as session:
            detection = await session.scalar(
                select(Detection).where(Detection.item_id == item_id)
            )
        assert detection is not None
        assert detection.status == "ACTIVE"
        assert detection.score == Decimal("0")
        assert detection.evaluation_snapshot["variables"]["congestion"]["crowded"] is False
    finally:
        if "user_id" in locals():
            async with transaction_session(session_factory) as session:
                await session.execute(delete(User).where(User.user_id == user_id))
        await engine.dispose()
