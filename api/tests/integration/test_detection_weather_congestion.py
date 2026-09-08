"""날씨·혼잡 위험의 독립 판정과 감지 생성을 검증한다."""

import pytest
from sqlalchemy import delete, select

from app.db import transaction_session
from app.models.auth import User
from app.models.detection import Detection
from app.schemas.detection import CongestionVerdict, OperatingHoursVerdict, WeatherVerdict
from app.services.detection import evaluator
from tests.integration.variable_detection_support import factory, providers, seed


@pytest.mark.asyncio
@pytest.mark.parametrize(("weather_risk", "congestion_risk", "primary"), [(True, False, "WEATHER"), (False, True, "CONGESTION")])
async def test_weather_and_congestion_can_independently_create_detection(monkeypatch: pytest.MonkeyPatch, weather_risk: bool, congestion_risk: bool, primary: str) -> None:
    engine, session_factory = await factory()
    try:
        _, item_id, user_id = await seed(session_factory, category="NATURE")
        providers(monkeypatch, evaluator)
        monkeypatch.setattr(evaluator, "evaluate_weather", lambda *a, **k: _value(WeatherVerdict(available=True, precipitation_probability=80 if weather_risk else 0, precipitation_mm_per_hour=0, precipitation_type="RAIN" if weather_risk else "NONE", at_risk=weather_risk)))
        monkeypatch.setattr(evaluator, "evaluate_congestion", lambda *a, **k: _value(CongestionVerdict(available=True, level="CROWDED" if congestion_risk else "NORMAL", sensitivity="MEDIUM", crowded=congestion_risk)))
        monkeypatch.setattr(evaluator, "evaluate_operating_hours", lambda *a, **k: _value(OperatingHoursVerdict(available=False, unavailable_reason="HOURS_UNKNOWN")))
        async with transaction_session(session_factory) as session:
            await evaluator.evaluate_all_active(session)
        async with session_factory() as session:
            detection = await session.scalar(select(Detection).where(Detection.item_id == item_id))
        assert detection is not None and detection.primary_type == primary
    finally:
        if "user_id" in locals():
            async with transaction_session(session_factory) as session:
                await session.execute(delete(User).where(User.user_id == user_id))
        await engine.dispose()


async def _value(value): return value
