"""운영시간 위험의 ACTIVE 감지 생성·갱신 통합 테스트."""

import json
import uuid
from datetime import datetime, timedelta

import pytest
from sqlalchemy import delete, select
from starlette.requests import Request

from app.api.errors import AppError
from app.api.v1.detections import get_detection, list_detections, mark_detection_read
from app.core.security import AuthPrincipal
from app.db import transaction_session
from app.models.auth import User
from app.models.detection import Detection
from app.models.itinerary import ItineraryItem, Place, TripDay
from app.models.trip import Trip
from app.schemas.detection import (
    CongestionVerdict,
    OperatingHoursVerdict,
    WeatherVerdict,
)
from app.schemas.itinerary import SaveDayItineraryRequest
from app.services.detection import evaluator
from app.services.itinerary import ItineraryService
from tests.integration.variable_detection_support import KST, factory, providers, seed


@pytest.mark.asyncio
async def test_visit_blocked_creates_one_active_detection(monkeypatch: pytest.MonkeyPatch) -> None:
    engine, session_factory = await factory()
    try:
        trip_id, item_id, user_id = await seed(session_factory)
        providers(monkeypatch, evaluator)
        monkeypatch.setattr(evaluator, "evaluate_weather", lambda *a, **k: _value(WeatherVerdict(available=False, unavailable_reason="NO_FORECAST")))
        monkeypatch.setattr(evaluator, "evaluate_congestion", lambda *a, **k: _value(CongestionVerdict(available=False, unavailable_reason="NOT_IN_SUPPORT_AREA")))
        monkeypatch.setattr(evaluator, "evaluate_operating_hours", lambda *a, **k: _value(OperatingHoursVerdict(available=True, closing_soon=False, visit_blocked=True, temp_closed=False)))
        async with transaction_session(session_factory) as session:
            assert (await evaluator.evaluate_all_active(session))[0] == 1
        async with transaction_session(session_factory) as session:
            assert (await evaluator.evaluate_all_active(session))[0] == 1
        async with session_factory() as session:
            rows = list((await session.scalars(select(Detection).where(Detection.item_id == item_id))).all())
        assert len(rows) == 1
        assert rows[0].status == "ACTIVE"
        assert rows[0].primary_type == "OPERATING_HOURS"
        assert rows[0].evaluation_snapshot["variables"]["operatingHours"]["visitBlocked"] is True
        request = Request({"type": "http", "method": "GET", "path": "/", "headers": []})
        request.state.request_id = uuid.uuid4()
        principal = AuthPrincipal(user_id, uuid.uuid4(), uuid.uuid4())
        async with transaction_session(session_factory) as session:
            listed = await list_detections(request, trip_id, principal, session)
            detail = await get_detection(request, rows[0].detection_id, principal, session)
            read = await mark_detection_read(request, rows[0].detection_id, principal, session)
            stored = await session.get(Detection, rows[0].detection_id)
            first_read_at = stored.read_at
            repeated_read = await mark_detection_read(
                request, rows[0].detection_id, principal, session
            )
            await session.refresh(stored)
            listed_after_read = await list_detections(
                request, trip_id, principal, session
            )
            detail_after_read = await get_detection(
                request, rows[0].detection_id, principal, session
            )
            other = AuthPrincipal(uuid.uuid4(), uuid.uuid4(), uuid.uuid4())
            forbidden_calls = (
                lambda: list_detections(request, trip_id, other, session),
                lambda: get_detection(request, rows[0].detection_id, other, session),
                lambda: mark_detection_read(request, rows[0].detection_id, other, session),
            )
            for call in forbidden_calls:
                with pytest.raises(AppError) as forbidden:
                    await call()
                assert forbidden.value.status_code == 403
        assert json.loads(listed.body)["data"]["items"][0]["primaryType"] == "OPERATING_HOURS"
        assert json.loads(detail.body)["data"]["variables"]["operatingHours"]["visitBlocked"] is True
        assert json.loads(read.body)["data"]["read"] is True
        assert json.loads(repeated_read.body)["data"]["read"] is True
        assert json.loads(listed_after_read.body)["data"]["items"][0]["read"] is True
        assert json.loads(detail_after_read.body)["data"]["read"] is True
        assert first_read_at is not None
        assert stored.read_at == first_read_at
    finally:
        if "user_id" in locals():
            async with transaction_session(session_factory) as session:
                await session.execute(delete(User).where(User.user_id == user_id))
        await engine.dispose()


@pytest.mark.asyncio
async def test_saved_tourapi_match_reaches_operating_hours_evaluator(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """TourAPI 검색 매칭 ID가 일정 저장 뒤에도 Google 운영시간 조회에 사용된다."""
    engine, session_factory = await factory()
    user_id: uuid.UUID | None = None
    try:
        now = datetime.now(KST)
        async with transaction_session(session_factory) as session:
            user = User(social_provider="KAKAO", social_subject=f"tour-match-{uuid.uuid4()}")
            session.add(user)
            await session.flush()
            user_id = user.user_id
            trip = Trip(
                user_id=user.user_id,
                name="TourAPI Google ID 전달",
                start_date=now.date(),
                end_date=now.date(),
            )
            session.add(trip)
            await session.flush()
            trip_id = trip.trip_id

        payload = SaveDayItineraryRequest.model_validate({
            "version": 0,
            "items": [{
                "itemId": None,
                "placeId": "tourapi:126508",
                "place": {
                    "name": "매칭 장소",
                    "category": "CAFE",
                    "tourApiCategory": {"large": "FD", "middle": "FD05", "small": None},
                    "address": "서울특별시 중구",
                    "latitude": 37.5752,
                    "longitude": 126.9768,
                    "imageUrl": None,
                    "googlePlaceId": "ChIJ_tour_match",
                },
                "sequence": 1,
                "plannedStayMinutes": 60,
                "staySource": "RECOMMENDED",
                "transportModeToNext": None,
            }],
        })
        async with transaction_session(session_factory) as session:
            saved, _, _ = await ItineraryService(session).save_day(
                trip_id=trip_id,
                visit_date=now.date(),
                start_date=now.date(),
                payload=payload,
                idempotency_key=uuid.uuid4(),
            )
            day = await session.scalar(select(TripDay).where(TripDay.trip_id == trip_id))
            item = await session.get(ItineraryItem, saved.items[0].item_id)
            assert day is not None and item is not None
            day.status = "IN_PROGRESS"
            day.detection_active = True
            item.estimated_arrival_at = now + timedelta(hours=1)

        requested_ids: list[str] = []

        async def hours_verdict(*args, place_id: str, **kwargs):
            requested_ids.append(place_id)
            return OperatingHoursVerdict(
                available=True,
                closing_soon=False,
                visit_blocked=True,
                temp_closed=False,
            )

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
        monkeypatch.setattr(evaluator, "evaluate_operating_hours", hours_verdict)

        async with transaction_session(session_factory) as session:
            rows = await evaluator._load_eligible_rows(
                session, trip_day_id=day.trip_day_id
            )
            count, _ = await evaluator._evaluate_rows(session, rows)
        async with session_factory() as session:
            place = await session.scalar(select(Place).where(Place.tour_content_id == "126508"))
            detection = await session.scalar(
                select(Detection).where(Detection.item_id == saved.items[0].item_id)
            )

        assert count == 1
        assert requested_ids == ["ChIJ_tour_match"]
        assert place is not None and place.google_place_id == "ChIJ_tour_match"
        assert detection is not None and detection.primary_type == "OPERATING_HOURS"
    finally:
        if user_id is not None:
            async with transaction_session(session_factory) as session:
                await session.execute(delete(User).where(User.user_id == user_id))
        await engine.dispose()


@pytest.mark.asyncio
async def test_global_evaluation_includes_each_users_active_trip(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, session_factory = await factory()
    user_ids: list[uuid.UUID] = []
    try:
        for _ in range(2):
            _, _, user_id = await seed(session_factory)
            user_ids.append(user_id)
        providers(monkeypatch, evaluator)
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
            assert (await evaluator.evaluate_all_active(session))[0] == 2
        async with session_factory() as session:
            assert len((await session.scalars(select(Detection))).all()) == 2
    finally:
        if user_ids:
            async with transaction_session(session_factory) as session:
                await session.execute(delete(User).where(User.user_id.in_(user_ids)))
        await engine.dispose()


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "options",
    [
        {"day_status": "NOT_STARTED"},
        {"day_status": "COMPLETED"},
        {"visit_offset_days": -1},
        {"with_eta": False},
    ],
)
async def test_ineligible_items_are_not_evaluated(
    monkeypatch: pytest.MonkeyPatch, options: dict[str, object]
) -> None:
    engine, session_factory = await factory()
    try:
        _, item_id, user_id = await seed(session_factory, **options)
        providers(monkeypatch, evaluator)
        async with transaction_session(session_factory) as session:
            assert (await evaluator.evaluate_all_active(session))[0] == 0
        async with session_factory() as session:
            assert await session.scalar(
                select(Detection).where(Detection.item_id == item_id)
            ) is None
    finally:
        if "user_id" in locals():
            async with transaction_session(session_factory) as session:
                await session.execute(delete(User).where(User.user_id == user_id))
        await engine.dispose()


async def _value(value): return value
