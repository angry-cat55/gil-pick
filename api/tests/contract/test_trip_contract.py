"""F002 여행 생성 HTTP 계약 테스트."""

import uuid
from datetime import date, datetime, UTC

import pytest
from fastapi.testclient import TestClient

from app.api.dependencies import get_current_principal
from app.api.errors import AppError
from app.api.v1.trips import _trip_service
from app.core.security import AuthPrincipal
from app.main import app
from app.schemas.trip import Trip, TripStatus


class StubTripService:
    """HTTP 계약 검증에 필요한 여행 생성 결과만 제공한다."""

    async def create_trip(self, *, user_id, payload, idempotency_key) -> Trip:
        """요청마다 새 여행 응답을 반환한다."""
        return Trip(
            trip_id=uuid.uuid4(),
            name=payload.name.strip(),
            start_date=payload.start_date,
            end_date=payload.end_date,
            status=TripStatus.UPCOMING,
            day_count=(payload.end_date - payload.start_date).days + 1,
            version=1,
            created_at=datetime.now(UTC),
        )


class RejectingTripService:
    """지정한 공개 오류를 반환하는 여행 service 대역이다."""

    def __init__(self, error: AppError) -> None:
        self.error = error

    async def create_trip(self, *, user_id, payload, idempotency_key) -> Trip:
        """설정된 검증 오류를 그대로 발생시킨다."""
        raise self.error


@pytest.fixture
def client() -> TestClient:
    """인증 principal과 여행 service를 격리한 HTTP client를 제공한다."""
    principal = AuthPrincipal(
        user_id=uuid.uuid4(),
        session_id=uuid.uuid4(),
        token_id=uuid.uuid4(),
    )
    app.dependency_overrides[get_current_principal] = lambda: principal
    app.dependency_overrides[_trip_service] = StubTripService
    try:
        yield TestClient(app)
    finally:
        app.dependency_overrides.clear()


def test_create_trip_contract_returns_201_envelope(client: TestClient) -> None:
    """여행 생성 성공 응답이 공개 계약과 일치하는지 확인한다."""
    response = client.post(
        "/api/v1/trips",
        headers={"Idempotency-Key": str(uuid.uuid4())},
        json={
            "name": "  서울 여행  ",
            "startDate": "2026-09-01",
            "endDate": "2026-09-03",
        },
    )

    assert response.status_code == 201
    assert {
        "name": "서울 여행",
        "dayCount": 3,
        "version": 1,
        "status": "UPCOMING",
    }.items() <= response.json()["data"].items()
    assert response.json()["meta"]["requestId"] == response.headers["X-Request-ID"]


def test_create_trip_contract_allows_duplicate_names(client: TestClient) -> None:
    """서로 다른 생성 요청은 여행명이 같아도 모두 성공하는지 확인한다."""
    payload = {
        "name": "서울 여행",
        "startDate": "2026-09-01",
        "endDate": "2026-09-03",
    }
    first = client.post(
        "/api/v1/trips",
        headers={"Idempotency-Key": str(uuid.uuid4())},
        json=payload,
    )
    second = client.post(
        "/api/v1/trips",
        headers={"Idempotency-Key": str(uuid.uuid4())},
        json=payload,
    )

    assert first.status_code == second.status_code == 201
    assert first.json()["data"]["tripId"] != second.json()["data"]["tripId"]


@pytest.mark.parametrize(
    ("payload", "code"),
    [
        ({"name": " ", "startDate": "2026-09-01", "endDate": "2026-09-03"}, "VALIDATION_ERROR"),
        ({"name": "서울 여행", "startDate": "2026-09-04", "endDate": "2026-09-03"}, "INVALID_TRIP_PERIOD"),
        ({"name": "서울 여행", "startDate": "2026-09-01", "endDate": "2026-09-08"}, "INVALID_TRIP_PERIOD"),
    ],
)
def test_create_trip_contract_maps_domain_validation(
    client: TestClient, payload: dict[str, str], code: str
) -> None:
    """여행명과 기간 검증 실패를 422 공통 오류 envelope로 반환한다."""
    app.dependency_overrides[_trip_service] = lambda: RejectingTripService(
        AppError(422, code, "여행 생성 입력이 올바르지 않습니다.")
    )

    response = client.post(
        "/api/v1/trips",
        headers={"Idempotency-Key": str(uuid.uuid4())},
        json=payload,
    )

    assert response.status_code == 422
    assert response.json()["error"]["code"] == code
    assert response.json()["meta"]["requestId"] == response.headers["X-Request-ID"]


def test_create_trip_openapi_requires_idempotency_key() -> None:
    """여행 생성 OpenAPI가 필수 멱등성 header와 응답 상태를 선언하는지 확인한다."""
    operation = app.openapi()["paths"]["/api/v1/trips"]["post"]
    header = next(item for item in operation["parameters"] if item["name"] == "Idempotency-Key")

    assert header["required"] is True
    assert set(operation["responses"]) == {"201", "400", "401", "422"}
