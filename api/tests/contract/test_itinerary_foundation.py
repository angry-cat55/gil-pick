"""F004 일정 라우터의 인증·소유권 경계 계약 테스트."""

import uuid
from datetime import date, datetime, UTC

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from app.api.dependencies import get_current_principal
from app.api.errors import AppError
from app.api.v1.itinerary import _trip_service
from app.core.security import AuthPrincipal
from app.main import app
from app.schemas.trip import Trip, TripStatus


class StubTripService:
    def __init__(self, error: AppError | None = None) -> None:
        self.error = error

    async def get_trip(self, *, user_id: uuid.UUID, trip_id: uuid.UUID) -> Trip:
        if self.error:
            raise self.error
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


@pytest.fixture
def principal() -> AuthPrincipal:
    return AuthPrincipal(user_id=uuid.uuid4(), session_id=uuid.uuid4(), token_id=uuid.uuid4())


def test_itinerary_requires_authentication() -> None:
    def reject_authentication() -> None:
        raise HTTPException(status_code=401, detail="INVALID_ACCESS_TOKEN")

    app.dependency_overrides[get_current_principal] = reject_authentication
    app.dependency_overrides[_trip_service] = lambda: StubTripService()
    try:
        response = TestClient(app).get(f"/api/v1/trips/{uuid.uuid4()}/itinerary")
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 401


@pytest.mark.parametrize(
    ("error", "expected_status"),
    [
        (AppError(403, "FORBIDDEN", "다른 사용자의 여행입니다."), 403),
        (AppError(404, "TRIP_NOT_FOUND", "삭제되었거나 없는 여행입니다."), 404),
    ],
)
def test_itinerary_reuses_trip_ownership_boundary(
    principal: AuthPrincipal, error: AppError, expected_status: int
) -> None:
    app.dependency_overrides[get_current_principal] = lambda: principal
    app.dependency_overrides[_trip_service] = lambda: StubTripService(error)
    try:
        response = TestClient(app).get(f"/api/v1/trips/{uuid.uuid4()}/itinerary")
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == expected_status


def test_itinerary_rejects_date_outside_trip_period(principal: AuthPrincipal) -> None:
    app.dependency_overrides[get_current_principal] = lambda: principal
    app.dependency_overrides[_trip_service] = lambda: StubTripService()
    try:
        response = TestClient(app).get(
            f"/api/v1/trips/{uuid.uuid4()}/days/2026-09-04/itinerary"
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "TRIP_NOT_FOUND"


def test_itinerary_routes_are_registered() -> None:
    paths = app.openapi()["paths"]

    assert "/api/v1/trips/{tripId}/itinerary" in paths
    assert "/api/v1/trips/{tripId}/days/{date}/itinerary" in paths
    assert set(paths["/api/v1/trips/{tripId}/days/{date}/itinerary"]) == {"get", "put"}
