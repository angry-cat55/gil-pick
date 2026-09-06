"""ITIN-001·002 HTTP 계약 테스트."""

import uuid
from datetime import UTC, date, datetime

import pytest
from fastapi.testclient import TestClient

from app.api.dependencies import get_current_principal
from app.api.errors import AppError
from app.api.v1.itinerary import _itinerary_service, _trip_service
from app.core.security import AuthPrincipal
from app.main import app
from app.schemas.itinerary import DayItinerary
from app.schemas.trip import Trip, TripStatus


class StubTripService:
    async def get_trip(self, *, user_id: uuid.UUID, trip_id: uuid.UUID) -> Trip:
        return Trip(
            tripId=trip_id,
            name="서울 여행",
            startDate=date(2026, 9, 1),
            endDate=date(2026, 9, 3),
            status=TripStatus.UPCOMING,
            dayCount=3,
            version=1,
            createdAt=datetime.now(UTC),
        )


class StubItineraryService:
    def __init__(self, *, error: AppError | None = None, created: bool = False) -> None:
        self.error = error
        self.created = created
        self.calls: list[dict] = []

    async def get_day(self, **kwargs) -> DayItinerary:
        self.calls.append(kwargs)
        return _day(version=0)

    async def save_day(self, **kwargs) -> tuple[DayItinerary, bool]:
        self.calls.append(kwargs)
        if self.error:
            raise self.error
        return _day(version=1), self.created


def _day(*, version: int) -> DayItinerary:
    return DayItinerary(
        date=date(2026, 9, 1),
        dayNumber=1,
        version=version,
        routeStatus="NOT_CALCULATED",
        items=[],
        route=None,
    )


@pytest.fixture
def principal() -> AuthPrincipal:
    return AuthPrincipal(user_id=uuid.uuid4(), session_id=uuid.uuid4(), token_id=uuid.uuid4())


def _override(principal: AuthPrincipal, service: StubItineraryService) -> None:
    app.dependency_overrides[get_current_principal] = lambda: principal
    app.dependency_overrides[_trip_service] = lambda: StubTripService()
    app.dependency_overrides[_itinerary_service] = lambda: service


def test_get_unsaved_day_returns_version_zero(principal: AuthPrincipal) -> None:
    service = StubItineraryService()
    _override(principal, service)
    try:
        response = TestClient(app).get(f"/api/v1/trips/{uuid.uuid4()}/days/2026-09-01/itinerary")
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert response.json()["data"] == {
        "date": "2026-09-01", "dayNumber": 1, "version": 0,
        "routeStatus": "NOT_CALCULATED", "items": [], "route": None,
    }


@pytest.mark.parametrize(("created", "status"), [(True, 201), (False, 200)])
def test_put_distinguishes_create_and_update(
    principal: AuthPrincipal, created: bool, status: int
) -> None:
    service = StubItineraryService(created=created)
    _override(principal, service)
    key = uuid.uuid4()
    try:
        response = TestClient(app).put(
            f"/api/v1/trips/{uuid.uuid4()}/days/2026-09-01/itinerary",
            headers={"Idempotency-Key": str(key)},
            json={"version": 0, "items": []},
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == status
    assert service.calls[0]["idempotency_key"] == key


@pytest.mark.parametrize(
    ("error", "status", "code"),
    [
        (AppError(409, "VERSION_CONFLICT", "충돌"), 409, "VERSION_CONFLICT"),
        (AppError(422, "INVALID_ITINERARY", "오류", details={"violations": []}), 422, "INVALID_ITINERARY"),
    ],
)
def test_put_returns_typed_errors(
    principal: AuthPrincipal, error: AppError, status: int, code: str
) -> None:
    service = StubItineraryService(error=error)
    _override(principal, service)
    try:
        response = TestClient(app).put(
            f"/api/v1/trips/{uuid.uuid4()}/days/2026-09-01/itinerary",
            headers={"Idempotency-Key": str(uuid.uuid4())},
            json={"version": 0, "items": []},
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == status
    assert response.json()["error"]["code"] == code


def test_put_requires_uuid_idempotency_key(principal: AuthPrincipal) -> None:
    _override(principal, StubItineraryService())
    try:
        response = TestClient(app).put(
            f"/api/v1/trips/{uuid.uuid4()}/days/2026-09-01/itinerary",
            headers={"Idempotency-Key": "not-a-uuid"},
            json={"version": 0, "items": []},
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 400
    operation = app.openapi()["paths"]["/api/v1/trips/{tripId}/days/{date}/itinerary"]["put"]
    header = next(item for item in operation["parameters"] if item["name"] == "Idempotency-Key")
    assert header["required"] is True
    assert header["schema"]["format"] == "uuid"
    assert {"200", "201", "409", "422"} <= set(operation["responses"])
