"""외부 provider 결손이 변수와 장소 단위로 격리되는지 검증한다."""

import uuid

import pytest
from geoalchemy2 import WKTElement
from sqlalchemy import delete, func, select

from app.db import transaction_session
from app.models.auth import User
from app.models.detection import Detection
from app.models.itinerary import ItineraryItem, Place
from app.schemas.detection import WeatherVerdict
from app.services.detection import evaluator
from tests.integration.variable_detection_support import factory, providers, seed


async def _raise():
    raise TimeoutError


@pytest.mark.asyncio
async def test_partial_failure_is_recorded_and_all_failure_creates_nothing(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, session_factory = await factory()
    try:
        _, item_id, user_id = await seed(session_factory, category="NATURE")
        providers(monkeypatch, evaluator)
        monkeypatch.setattr(evaluator, "evaluate_congestion", lambda *a, **k: _raise())
        monkeypatch.setattr(evaluator, "evaluate_operating_hours", lambda *a, **k: _raise())

        async def risky_weather(*args, **kwargs):
            return WeatherVerdict(
                available=True,
                precipitation_probability=80,
                precipitation_mm_per_hour=0,
                precipitation_type="RAIN",
                at_risk=True,
            )

        monkeypatch.setattr(evaluator, "evaluate_weather", risky_weather)
        async with transaction_session(session_factory) as session:
            assert (await evaluator.evaluate_all_active(session))[0] == 1
        async with session_factory() as session:
            detection = await session.scalar(
                select(Detection).where(Detection.item_id == item_id)
            )
        variables = detection.evaluation_snapshot["variables"]
        assert variables["congestion"]["unavailableReason"] == "TIMEOUT"
        assert variables["operatingHours"]["unavailableReason"] == "TIMEOUT"

        async with transaction_session(session_factory) as session:
            await session.execute(delete(Detection).where(Detection.item_id == item_id))
        monkeypatch.setattr(evaluator, "evaluate_weather", lambda *a, **k: _raise())
        async with transaction_session(session_factory) as session:
            assert (await evaluator.evaluate_all_active(session))[0] == 0
        async with session_factory() as session:
            assert await session.scalar(
                select(func.count()).select_from(Detection).where(Detection.item_id == item_id)
            ) == 0
    finally:
        if "user_id" in locals():
            async with transaction_session(session_factory) as session:
                await session.execute(delete(User).where(User.user_id == user_id))
        await engine.dispose()


@pytest.mark.asyncio
async def test_one_place_failure_does_not_stop_another_place(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, session_factory = await factory()
    try:
        _, first_id, user_id = await seed(session_factory, category="NATURE")
        async with transaction_session(session_factory) as session:
            first = await session.get(ItineraryItem, first_id)
            place = Place(
                google_place_id=f"places/{uuid.uuid4()}",
                name="두 번째 장소",
                category="NATURE",
                location=WKTElement("POINT(126.9770 37.5754)", srid=4326),
            )
            session.add(place)
            await session.flush()
            second = ItineraryItem(
                trip_day_id=first.trip_day_id,
                place_id=place.place_id,
                sequence=2,
                status="PLANNED",
                planned_stay_minutes=60,
                stay_source="RECOMMENDED",
                estimated_arrival_at=first.estimated_arrival_at,
            )
            session.add(second)
            await session.flush()
            second_id = second.item_id

        providers(monkeypatch, evaluator)
        monkeypatch.setattr(evaluator, "evaluate_congestion", lambda *a, **k: _raise())
        monkeypatch.setattr(evaluator, "evaluate_operating_hours", lambda *a, **k: _raise())
        async def weather(*args, **kwargs):
            if kwargs["longitude"] < 126.9769:
                raise TimeoutError
            return WeatherVerdict(
                available=True,
                precipitation_probability=80,
                precipitation_mm_per_hour=0,
                precipitation_type="RAIN",
                at_risk=True,
            )

        monkeypatch.setattr(evaluator, "evaluate_weather", weather)
        async with transaction_session(session_factory) as session:
            assert (await evaluator.evaluate_all_active(session))[0] == 1
        async with session_factory() as session:
            ids = set(
                (await session.execute(select(Detection.item_id))).scalars().all()
            )
        assert ids == {second_id}
    finally:
        if "user_id" in locals():
            async with transaction_session(session_factory) as session:
                await session.execute(delete(User).where(User.user_id == user_id))
        await engine.dispose()
