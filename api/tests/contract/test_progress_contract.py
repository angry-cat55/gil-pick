"""F006 진행 API의 경로·요청·응답 계약 검증."""

from __future__ import annotations

import uuid
from datetime import UTC, date, datetime
from pathlib import Path

import pytest
import yaml
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
        return ProgressData(
            tripId=kwargs["trip_id"],
            date=kwargs["visit_date"],
            dayStatus="NOT_STARTED",
            progressVersion=0,
            scheduleVersion=1,
            actualStartedAt=None,
            completedAt=None,
            startLocation=None,
            currentItemId=None,
            nextItemId=None,
            items=[],
            detectionTargets=[],
            pendingCandidate=None,
            undoable=None,
        )

    async def start_day(self, **kwargs):
        return ProgressData(
            tripId=kwargs["trip_id"],
            date=kwargs["visit_date"],
            dayStatus="IN_PROGRESS",
            progressVersion=1,
            scheduleVersion=1,
            actualStartedAt=datetime.now(UTC),
            completedAt=None,
            startLocation=None,
            currentItemId=None,
            nextItemId=None,
            items=[],
            detectionTargets=[],
            pendingCandidate=None,
            undoable=None,
        )

    async def update_item_status(self, **kwargs):
        return ProgressData(
            tripId=uuid.uuid4(),
            date=date(2026, 9, 1),
            dayStatus="IN_PROGRESS",
            progressVersion=2,
            scheduleVersion=1,
            actualStartedAt=datetime.now(UTC),
            completedAt=None,
            startLocation=None,
            currentItemId=kwargs["item_id"],
            nextItemId=None,
            items=[],
            detectionTargets=[],
            pendingCandidate=None,
            undoable=None,
        )


class _UnstoredDayProgress:
    async def get_day(self, **kwargs):
        return ProgressData(
            tripId=kwargs["trip_id"], date=kwargs["visit_date"],
            dayStatus="NOT_STARTED", progressVersion=0, scheduleVersion=0,
            actualStartedAt=None, completedAt=None, startLocation=None,
            currentItemId=None, nextItemId=None, items=[],
            detectionTargets=[], pendingCandidate=None, undoable=None,
        )

    async def start_day(self, **kwargs):
        raise AppError(422, "DAY_EMPTY", "장소가 없는 날짜는 시작할 수 없습니다.")


class _UnexpectedProgress:
    async def get_day(self, **kwargs):
        raise AssertionError("기간·소유권 검증 실패 시 진행 서비스를 호출하면 안 됩니다.")

    async def start_day(self, **kwargs):
        raise AssertionError("기간·소유권 검증 실패 시 진행 서비스를 호출하면 안 됩니다.")


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
        assert response.json()["data"]["detectionTargets"] == []
        assert response.json()["data"]["pendingCandidate"] is None
        assert response.json()["data"]["undoable"] is None
        missing = client.post(f"/api/v1/trips/{trip_id}/days/2026-09-01/progress/start", json={"progressVersion": 0})
        assert missing.status_code == 400
        started = client.post(
            f"/api/v1/trips/{trip_id}/days/2026-09-01/progress/start",
            headers={"Idempotency-Key": str(uuid.uuid4())},
            json={"progressVersion": 0, "currentLocation": None},
        )
        assert started.status_code == 200
        assert started.json()["data"]["dayStatus"] == "IN_PROGRESS"
        assert started.json()["data"]["detectionTargets"] == []
        assert started.json()["data"]["pendingCandidate"] is None
        assert started.json()["data"]["undoable"] is None
    finally:
        app.dependency_overrides.clear()


def test_progress_openapi_declares_expected_responses() -> None:
    schema = app.openapi()
    get_op = schema["paths"]["/api/v1/trips/{tripId}/days/{date}/progress"]["get"]
    start_op = schema["paths"]["/api/v1/trips/{tripId}/days/{date}/progress/start"]["post"]
    assert {"200", "400", "401", "403", "404"} <= set(get_op["responses"])
    assert {"200", "400", "401", "403", "404", "409", "422"} <= set(start_op["responses"])
    assert "422" not in get_op["responses"]

    progress_schema = schema["components"]["schemas"]["ProgressData"]
    assert {"detectionTargets", "pendingCandidate", "undoable"} <= set(
        progress_schema["required"]
    )
    assert progress_schema["properties"]["detectionTargets"]["items"]["$ref"].endswith(
        "/DetectionTarget"
    )
    assert "PendingCandidate" in str(
        progress_schema["properties"]["pendingCandidate"]
    )
    assert "UndoableTransition" in str(progress_schema["properties"]["undoable"])
    item_schema = schema["components"]["schemas"]["ProgressItem"]
    assert {"processingSource", "eventRejectionReason"} <= set(
        item_schema["properties"]
    )
    assert set(
        schema["components"]["schemas"]["ProgressProcessingSource"]["enum"]
    ) == {"MANUAL", "AUTO"}


def test_progress_source_contract_matches_common_validation_error_policy() -> None:
    contract_path = (
        Path(__file__).parents[3]
        / "specs"
        / "006-trip-progress"
        / "contracts"
        / "progress.openapi.yaml"
    )
    contract = yaml.safe_load(contract_path.read_text(encoding="utf-8"))
    paths = contract["paths"]
    get_responses = paths["/trips/{tripId}/days/{date}/progress"]["get"]["responses"]
    start_responses = paths["/trips/{tripId}/days/{date}/progress/start"]["post"][
        "responses"
    ]
    update_responses = paths["/itinerary-items/{itemId}/status"]["patch"][
        "responses"
    ]
    error_body = contract["components"]["schemas"]["ErrorEnvelope"]["properties"][
        "error"
    ]
    progress_item = contract["components"]["schemas"]["ProgressItem"]

    assert "400" in get_responses
    assert "400" in start_responses
    assert "400" in update_responses
    assert "INVALID_REQUEST" not in start_responses["422"]["description"]
    assert "details" in error_body["required"]
    assert "details" in error_body["properties"]
    assert {"processingSource", "eventRejectionReason"} <= set(
        progress_item["properties"]
    )
    assert set(
        contract["components"]["schemas"]["ProgressProcessingSource"]["enum"]
    ) == {"MANUAL", "AUTO"}


def test_unstored_day_progress_contract_returns_empty_and_rejects_start() -> None:
    principal = AuthPrincipal(user_id=uuid.uuid4(), session_id=uuid.uuid4(), token_id=uuid.uuid4())
    app.dependency_overrides[get_current_principal] = lambda: principal
    app.dependency_overrides[_trip_service] = lambda: _Trip()
    app.dependency_overrides[_service] = lambda: _UnstoredDayProgress()
    trip_id = uuid.uuid4()
    try:
        client = TestClient(app)
        progress = client.get(
            f"/api/v1/trips/{trip_id}/days/2026-09-08/progress"
        )
        start = client.post(
            f"/api/v1/trips/{trip_id}/days/2026-09-08/progress/start",
            headers={"Idempotency-Key": str(uuid.uuid4())},
            json={"progressVersion": 0},
        )
    finally:
        app.dependency_overrides.clear()

    assert progress.status_code == 200
    assert progress.json()["data"] == {
        "tripId": str(trip_id),
        "date": "2026-09-08",
        "dayStatus": "NOT_STARTED",
        "progressVersion": 0,
        "scheduleVersion": 0,
        "actualStartedAt": None,
        "completedAt": None,
        "startLocation": None,
        "currentItemId": None,
        "nextItemId": None,
        "items": [],
        "detectionTargets": [],
        "pendingCandidate": None,
        "undoable": None,
    }
    assert start.status_code == 422
    assert start.json()["error"]["code"] == "DAY_EMPTY"


def test_out_of_range_day_is_rejected_before_progress_service() -> None:
    principal = AuthPrincipal(user_id=uuid.uuid4(), session_id=uuid.uuid4(), token_id=uuid.uuid4())
    app.dependency_overrides[get_current_principal] = lambda: principal
    app.dependency_overrides[_trip_service] = lambda: _Trip()
    app.dependency_overrides[_service] = lambda: _UnexpectedProgress()
    trip_id = uuid.uuid4()
    try:
        client = TestClient(app)
        progress = client.get(
            f"/api/v1/trips/{trip_id}/days/2026-09-11/progress"
        )
        start = client.post(
            f"/api/v1/trips/{trip_id}/days/2026-09-11/progress/start",
            headers={"Idempotency-Key": str(uuid.uuid4())},
            json={"progressVersion": 0},
        )
    finally:
        app.dependency_overrides.clear()

    assert progress.status_code == 404
    assert progress.json()["error"]["code"] == "TRIP_NOT_FOUND"
    assert start.status_code == 404
    assert start.json()["error"]["code"] == "TRIP_NOT_FOUND"


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
    app.dependency_overrides[_service] = lambda: _UnexpectedProgress()
    try:
        client = TestClient(app)
        trip_id = uuid.uuid4()
        response = client.get(f"/api/v1/trips/{trip_id}/days/2026-09-01/progress")
        start = client.post(
            f"/api/v1/trips/{trip_id}/days/2026-09-01/progress/start",
            headers={"Idempotency-Key": str(uuid.uuid4())},
            json={"progressVersion": 0},
        )
    finally:
        app.dependency_overrides.clear()
    assert response.status_code == expected_status
    assert response.json()["error"]["code"] == expected_code
    assert start.status_code == expected_status
    assert start.json()["error"]["code"] == expected_code


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
    assert response.json()["data"]["detectionTargets"] == []
    assert response.json()["data"]["pendingCandidate"] is None
    assert response.json()["data"]["undoable"] is None
    operation = app.openapi()["paths"]["/api/v1/itinerary-items/{itemId}/status"]["patch"]
    assert {"200", "400", "401", "403", "404", "409", "422"} <= set(operation["responses"])
    assert ProgressErrorCode.IDEMPOTENCY_KEY_CONFLICT == "IDEMPOTENCY_KEY_CONFLICT"
