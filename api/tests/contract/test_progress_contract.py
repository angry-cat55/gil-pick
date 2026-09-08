"""F006 진행 API의 경로·요청·응답 계약 검증."""

from __future__ import annotations

import uuid
from datetime import UTC, date, datetime

import pytest
from fastapi.testclient import TestClient

from app.api.dependencies import get_current_principal
from app.api.v1.itinerary import _trip_service
from app.api.v1.progress import _service
from app.api.errors import AppError
from app.core.security import AuthPrincipal
from app.main import app
from app.schemas.progress import ProgressData, ProgressErrorCode
from app.schemas.trip import Trip, TripStatus


class _Trip:
    def __init__(self, error: AppError | None = None) -> None:
        self.error = error

    async def get_trip(self, *, user_id, trip_id):
        if self.error is not None:
            raise self.error
        return Trip(tripId=trip_id, name="여행", startDate=date(2026, 9, 1), endDate=date(2026, 9, 10), status=TripStatus.UPCOMING, dayCount=10, version=1, createdAt=datetime.now(UTC))


class _Progress:
    async def get_day(self, **kwargs):
        return ProgressData(tripId=kwargs["trip_id"], date=kwargs["visit_date"], dayStatus="NOT_STARTED", progressVersion=0, scheduleVersion=1, actualStartedAt=None, completedAt=None, startLocation=None, currentItemId=None, nextItemId=None, items=[])

    async def start_day(self, **kwargs):
        return ProgressData(tripId=kwargs["trip_id"], date=kwargs["visit_date"], dayStatus="IN_PROGRESS", progressVersion=1, scheduleVersion=1, actualStartedAt=datetime.now(UTC), completedAt=None, startLocation=None, currentItemId=None, nextItemId=None, items=[])

    async def update_item_status(self, **kwargs):
        return ProgressData(tripId=uuid.uuid4(), date=date(2026, 9, 1), dayStatus="IN_PROGRESS", progressVersion=2, scheduleVersion=1, actualStartedAt=datetime.now(UTC), completedAt=None, startLocation=None, currentItemId=kwargs["item_id"], nextItemId=None, items=[])


def test_progress_paths_and_start_contract() -> None:
    principal = AuthPrincipal(user_id=uuid.uuid4(), session_id=uuid.uuid4(), token_id=uuid.uuid4())
    app.dependency_overrides[get_current_principal] = lambda: principal
    app.dependency_overrides[_trip_service] = lambda: _Trip()
    app.dependency_overrides[_service] = lambda: _Progress()
    trip_id = uuid.uuid4()
    try:
        client = TestClient(app)
        response = client.get(f"/api/v1/trips/{trip_id}/days/2026-09-01/progress")
        assert response.status_code == 200
        assert response.json()["data"]["dayStatus"] == "NOT_STARTED"
        missing = client.post(f"/api/v1/trips/{trip_id}/days/2026-09-01/progress/start", json={"progressVersion": 0})
        assert missing.status_code == 400
        started = client.post(
            f"/api/v1/trips/{trip_id}/days/2026-09-01/progress/start",
            headers={"Idempotency-Key": str(uuid.uuid4())},
            json={"progressVersion": 0, "currentLocation": None},
        )
        assert started.status_code == 200
        assert started.json()["data"]["dayStatus"] == "IN_PROGRESS"
    finally:
        app.dependency_overrides.clear()


def test_progress_openapi_declares_expected_responses() -> None:
    schema = app.openapi()
    get_op = schema["paths"]["/api/v1/trips/{tripId}/days/{date}/progress"]["get"]
    start_op = schema["paths"]["/api/v1/trips/{tripId}/days/{date}/progress/start"]["post"]
    assert {"200", "401", "403", "404"} <= set(get_op["responses"])
    assert {"200", "401", "403", "404", "409", "422"} <= set(start_op["responses"])
    assert "422" not in get_op["responses"]


@pytest.mark.parametrize(
    ("error", "expected_status", "expected_code"),
    [
        (AppError(403, "FORBIDDEN", "금지"), 403, "TRIP_FORBIDDEN"),
        (AppError(404, "TRIP_NOT_FOUND", "없음"), 404, "TRIP_NOT_FOUND"),
    ],
)
def test_progress_reuses_trip_ownership_errors(error, expected_status, expected_code) -> None:
    principal = AuthPrincipal(user_id=uuid.uuid4(), session_id=uuid.uuid4(), token_id=uuid.uuid4())
    app.dependency_overrides[get_current_principal] = lambda: principal
    app.dependency_overrides[_trip_service] = lambda: _Trip(error)
    app.dependency_overrides[_service] = lambda: _Progress()
    try:
        response = TestClient(app).get(f"/api/v1/trips/{uuid.uuid4()}/days/2026-09-01/progress")
    finally:
        app.dependency_overrides.clear()
    assert response.status_code == expected_status
    assert response.json()["error"]["code"] == expected_code


def test_update_status_contract_requires_idempotency_key_and_returns_progress() -> None:
    principal = AuthPrincipal(user_id=uuid.uuid4(), session_id=uuid.uuid4(), token_id=uuid.uuid4())
    app.dependency_overrides[get_current_principal] = lambda: principal
    app.dependency_overrides[_service] = lambda: _Progress()
    item_id = uuid.uuid4()
    try:
        client = TestClient(app)
        missing = client.patch(
            f"/api/v1/itinerary-items/{item_id}/status",
            json={"status": "ARRIVED", "progressVersion": 1},
        )
        response = client.patch(
            f"/api/v1/itinerary-items/{item_id}/status",
            headers={"Idempotency-Key": str(uuid.uuid4())},
            json={"status": "ARRIVED", "progressVersion": 1},
        )
    finally:
        app.dependency_overrides.clear()

    assert missing.status_code == 400
    assert response.status_code == 200
    assert response.json()["data"]["progressVersion"] == 2
    operation = app.openapi()["paths"]["/api/v1/itinerary-items/{itemId}/status"]["patch"]
    assert {"200", "401", "403", "404", "409", "422"} <= set(operation["responses"])
    assert ProgressErrorCode.IDEMPOTENCY_KEY_CONFLICT == "IDEMPOTENCY_KEY_CONFLICT"
