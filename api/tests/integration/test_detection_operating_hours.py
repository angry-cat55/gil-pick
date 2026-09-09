"""운영시간 위험의 ACTIVE 감지 생성·갱신 통합 테스트."""

import json
import uuid

import pytest
from sqlalchemy import delete, select
from starlette.requests import Request

from app.api.errors import AppError
from app.api.v1.detections import get_detection, list_detections, mark_detection_read
from app.core.security import AuthPrincipal
from app.db import transaction_session
from app.models.auth import User
from app.models.detection import Detection
from app.schemas.detection import CongestionVerdict, OperatingHoursVerdict, WeatherVerdict
from app.services.detection import evaluator
from tests.integration.variable_detection_support import factory, providers, seed


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
            assert await evaluator.evaluate_all_active(session) == 1
        async with transaction_session(session_factory) as session:
            assert await evaluator.evaluate_all_active(session) == 1
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
            with pytest.raises(AppError) as forbidden:
                await get_detection(
                    request,
                    rows[0].detection_id,
                    AuthPrincipal(uuid.uuid4(), uuid.uuid4(), uuid.uuid4()),
                    session,
                )
        assert json.loads(listed.body)["data"]["items"][0]["primaryType"] == "OPERATING_HOURS"
        assert json.loads(detail.body)["data"]["variables"]["operatingHours"]["visitBlocked"] is True
        assert json.loads(read.body)["data"]["read"] is True
        assert forbidden.value.status_code == 403
    finally:
        if "user_id" in locals():
            async with transaction_session(session_factory) as session:
                await session.execute(delete(User).where(User.user_id == user_id))
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
            assert await evaluator.evaluate_all_active(session) == 0
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
