"""ROUTE 조회와 일정 저장 자동 계산의 HTTP 계약을 검증한다."""

from __future__ import annotations

import uuid
from datetime import UTC, date, datetime

import pytest
from fastapi.testclient import TestClient

from app.api.dependencies import get_current_principal
from app.api.errors import AppError
from app.api.v1.itinerary import _trip_service
from app.api.v1.route import _route_service
from app.core.security import AuthPrincipal
from app.main import app
from app.schemas.route import (
    FailedRouteData,
    NotCalculatedRouteData,
    ReadyRouteData,
    Route,
    RouteFailure,
)
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


class StubRouteService:
    def __init__(self, result: NotCalculatedRouteData | ReadyRouteData | FailedRouteData) -> None:
        self.result = result
        self.calls: list[dict[str, object]] = []

    async def get_current(self, **kwargs):  # type: ignore[no-untyped-def]
        self.calls.append(kwargs)
        return self.result


def _route_data(status: str):  # type: ignore[no-untyped-def]
    trip_id = uuid.uuid4()
    common = {
        "tripId": trip_id,
        "date": date(2026, 9, 1),
        "scheduleVersion": 2,
        "routeStatus": status,
    }
    if status == "NOT_CALCULATED":
        return NotCalculatedRouteData(**common, route=None, failure=None)
    if status == "FAILED":
        return FailedRouteData(
            **common,
            route=None,
            failure=RouteFailure(
                code="ROUTE_PROVIDER_TIMEOUT",
                message="경로 계산 시간이 초과되었습니다.",
                retryable=True,
            ),
        )
    item_id = uuid.uuid4()
    route = Route(
        routeId=uuid.uuid4(),
        scheduleVersion=2,
        totalDurationSeconds=0,
        totalDistanceMeters=0,
        markers=[
            {
                "itemId": item_id,
                "sequence": 1,
                "name": "경복궁",
                "latitude": 37.5796,
                "longitude": 126.977,
            }
        ],
        segments=[],
        providerAttributions=[],
        calculatedAt=datetime.now(UTC),
    )
    return ReadyRouteData(**common, route=route, failure=None)


@pytest.fixture
def principal() -> AuthPrincipal:
    return AuthPrincipal(
        user_id=uuid.uuid4(),
        session_id=uuid.uuid4(),
        token_id=uuid.uuid4(),
    )


@pytest.mark.parametrize("status", ["NOT_CALCULATED", "READY", "FAILED"])
def test_get_route_returns_status_specific_success_envelope(
    principal: AuthPrincipal,
    status: str,
) -> None:
    service = StubRouteService(_route_data(status))
    app.dependency_overrides[get_current_principal] = lambda: principal
    app.dependency_overrides[_trip_service] = lambda: StubTripService()
    app.dependency_overrides[_route_service] = lambda: service
    try:
        response = TestClient(app).get(
            f"/api/v1/trips/{uuid.uuid4()}/days/2026-09-01/route"
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert response.json()["success"] is True
    assert response.json()["data"]["routeStatus"] == status
    assert "requestId" in response.json()["meta"]
    assert service.calls[0]["visit_date"] == date(2026, 9, 1)


@pytest.mark.parametrize(
    ("error", "status", "code"),
    [
        (AppError(403, "FORBIDDEN", "금지"), 403, "TRIP_FORBIDDEN"),
        (AppError(404, "TRIP_NOT_FOUND", "없음"), 404, "TRIP_NOT_FOUND"),
    ],
)
def test_get_route_reuses_trip_access_policy(
    principal: AuthPrincipal,
    error: AppError,
    status: int,
    code: str,
) -> None:
    app.dependency_overrides[get_current_principal] = lambda: principal
    app.dependency_overrides[_trip_service] = lambda: StubTripService(error)
    app.dependency_overrides[_route_service] = lambda: StubRouteService(
        _route_data("NOT_CALCULATED")
    )
    try:
        response = TestClient(app).get(
            f"/api/v1/trips/{uuid.uuid4()}/days/2026-09-01/route"
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == status
    assert response.json()["error"]["code"] == code


def test_get_route_rejects_date_outside_trip(principal: AuthPrincipal) -> None:
    app.dependency_overrides[get_current_principal] = lambda: principal
    app.dependency_overrides[_trip_service] = lambda: StubTripService()
    app.dependency_overrides[_route_service] = lambda: StubRouteService(
        _route_data("NOT_CALCULATED")
    )
    try:
        response = TestClient(app).get(
            f"/api/v1/trips/{uuid.uuid4()}/days/2026-09-04/route"
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "TRIP_NOT_FOUND"


def test_route_openapi_declares_contract_responses() -> None:
    operation = app.openapi()["paths"]["/api/v1/trips/{tripId}/days/{date}/route"]["get"]

    assert {"200", "401", "403", "404"} <= set(operation["responses"])
