"""F002 여행 관리 HTTP 계약 테스트."""

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

    async def get_trip(self, *, user_id, trip_id) -> Trip:
        """요청한 식별자의 여행 상세 응답을 반환한다."""
        return Trip(
            trip_id=trip_id,
            name="서울 여행",
            start_date=date(2026, 9, 1),
            end_date=date(2026, 9, 3),
            status=TripStatus.UPCOMING,
            day_count=3,
            version=1,
            created_at=datetime.now(UTC),
        )

    async def update_trip(self, *, user_id, trip_id, payload) -> Trip:
        """요청한 필드를 반영한 여행 수정 응답을 반환한다."""
        return Trip(
            trip_id=trip_id,
            name=payload.name.strip() if payload.name else "서울 여행",
            start_date=payload.start_date or date(2026, 9, 1),
            end_date=payload.end_date or date(2026, 9, 3),
            status=TripStatus.UPCOMING,
            day_count=2,
            version=payload.version + 1,
            created_at=datetime.now(UTC),
        )

    async def delete_trip(self, *, user_id, trip_id) -> None:
        """요청한 여행의 삭제가 성공한 것으로 처리한다."""


class StubTripListService(StubTripService):
    """목록 query 전달과 응답 envelope를 검증하는 service 대역이다."""

    def __init__(self) -> None:
        self.list_call = None

    async def list_trips(self, *, user_id, query, status, cursor, limit):
        """요청 인자를 기록하고 정렬된 두 여행과 다음 cursor를 반환한다."""
        self.list_call = {
            "user_id": user_id,
            "query": query,
            "status": status,
            "cursor": cursor,
            "limit": limit,
        }
        return (
            [
                Trip(
                    trip_id=uuid.uuid4(),
                    name="진행 중 여행",
                    start_date=date(2026, 8, 27),
                    end_date=date(2026, 8, 29),
                    status=TripStatus.IN_PROGRESS,
                    day_count=3,
                    version=1,
                    created_at=datetime.now(UTC),
                ),
                Trip(
                    trip_id=uuid.uuid4(),
                    name="예정 여행",
                    start_date=date(2026, 9, 1),
                    end_date=date(2026, 9, 2),
                    status=TripStatus.UPCOMING,
                    day_count=2,
                    version=1,
                    created_at=datetime.now(UTC),
                ),
            ],
            "next-page",
            True,
        )


class RejectingTripService:
    """지정한 공개 오류를 반환하는 여행 service 대역이다."""

    def __init__(self, error: AppError) -> None:
        self.error = error

    async def create_trip(self, *, user_id, payload, idempotency_key) -> Trip:
        """설정된 검증 오류를 그대로 발생시킨다."""
        raise self.error

    async def get_trip(self, *, user_id, trip_id) -> Trip:
        """설정된 조회 오류를 그대로 발생시킨다."""
        raise self.error

    async def update_trip(self, *, user_id, trip_id, payload) -> Trip:
        """설정된 수정 오류를 그대로 발생시킨다."""
        raise self.error

    async def delete_trip(self, *, user_id, trip_id) -> None:
        """설정된 삭제 오류를 그대로 발생시킨다."""
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


def test_list_trips_contract_forwards_filters_and_returns_pagination(
    client: TestClient,
) -> None:
    """목록 검색·상태·cursor가 service에 전달되고 공통 envelope로 반환되는지 확인한다."""
    service = StubTripListService()
    app.dependency_overrides[_trip_service] = lambda: service

    response = client.get(
        "/api/v1/trips",
        params={
            "query": "여행",
            "status": "IN_PROGRESS",
            "cursor": "current-page",
            "limit": 2,
        },
    )

    assert response.status_code == 200
    assert service.list_call is not None
    assert service.list_call["query"] == "여행"
    assert service.list_call["status"] is TripStatus.IN_PROGRESS
    assert service.list_call["cursor"] == "current-page"
    assert service.list_call["limit"] == 2
    assert [item["status"] for item in response.json()["data"]["items"]] == [
        "IN_PROGRESS",
        "UPCOMING",
    ]
    assert response.json()["meta"]["pagination"] == {
        "nextCursor": "next-page",
        "hasNext": True,
    }
    assert response.json()["meta"]["requestId"] == response.headers["X-Request-ID"]


@pytest.mark.parametrize("params", ({"limit": 0}, {"limit": 101}, {"status": "UNKNOWN"}))
def test_list_trips_contract_rejects_invalid_query(
    client: TestClient,
    params: dict[str, int | str],
) -> None:
    """공개 계약의 limit 범위와 상태 enum 밖 요청을 400 오류 envelope로 거부한다."""
    response = client.get("/api/v1/trips", params=params)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_REQUEST"


def test_list_trips_openapi_declares_query_and_pagination_contract() -> None:
    """여행 목록 OpenAPI가 검색·상태·cursor·limit과 공개 응답을 선언하는지 확인한다."""
    operation = app.openapi()["paths"]["/api/v1/trips"]["get"]
    parameters = {item["name"]: item for item in operation["parameters"]}

    assert set(parameters) == {"query", "status", "cursor", "limit"}
    assert parameters["limit"]["schema"] == {
        "type": "integer",
        "maximum": 100,
        "minimum": 1,
        "default": 20,
        "title": "Limit",
    }
    assert set(operation["responses"]) == {"200", "400", "401"}


def test_get_trip_contract_returns_200_envelope(client: TestClient) -> None:
    """여행 상세 조회 성공 응답이 공개 계약과 일치하는지 확인한다."""
    trip_id = uuid.uuid4()

    response = client.get(f"/api/v1/trips/{trip_id}")

    assert response.status_code == 200
    assert response.json()["data"] == {
        "tripId": str(trip_id),
        "name": "서울 여행",
        "startDate": "2026-09-01",
        "endDate": "2026-09-03",
        "status": "UPCOMING",
        "dayCount": 3,
        "version": 1,
        "createdAt": response.json()["data"]["createdAt"],
    }
    assert response.json()["meta"]["requestId"] == response.headers["X-Request-ID"]


@pytest.mark.parametrize(
    ("status_code", "code"),
    [(403, "FORBIDDEN"), (404, "TRIP_NOT_FOUND")],
)
def test_get_trip_contract_maps_ownership_and_missing_errors(
    client: TestClient,
    status_code: int,
    code: str,
) -> None:
    """비소유 여행과 미존재·삭제 여행을 공통 오류 envelope로 반환한다."""
    app.dependency_overrides[_trip_service] = lambda: RejectingTripService(
        AppError(status_code, code, "여행을 조회할 수 없습니다.")
    )

    response = client.get(f"/api/v1/trips/{uuid.uuid4()}")

    assert response.status_code == status_code
    assert response.json()["error"]["code"] == code
    assert response.json()["meta"]["requestId"] == response.headers["X-Request-ID"]


def test_get_trip_contract_rejects_invalid_uuid(client: TestClient) -> None:
    """UUID가 아닌 여행 식별자를 400 공통 오류 envelope로 거부한다."""
    response = client.get("/api/v1/trips/not-a-uuid")

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_REQUEST"
    assert response.json()["meta"]["requestId"] == response.headers["X-Request-ID"]


def test_get_trip_openapi_declares_path_and_responses() -> None:
    """여행 상세 OpenAPI가 UUID 경로와 공개 응답 상태를 선언하는지 확인한다."""
    operation = app.openapi()["paths"]["/api/v1/trips/{tripId}"]["get"]
    trip_id = next(item for item in operation["parameters"] if item["name"] == "tripId")

    assert trip_id["required"] is True
    assert trip_id["schema"]["format"] == "uuid"
    assert set(operation["responses"]) == {"200", "400", "401", "403", "404"}


def test_update_trip_contract_returns_200_envelope(client: TestClient) -> None:
    """여행 수정 성공 응답이 version 증가를 포함한 공개 계약과 일치하는지 확인한다."""
    trip_id = uuid.uuid4()

    response = client.patch(
        f"/api/v1/trips/{trip_id}",
        json={
            "name": "  서울 여행 수정  ",
            "endDate": "2026-09-02",
            "version": 1,
            "confirmDeleteOutOfRangeItems": True,
        },
    )

    assert response.status_code == 200
    assert {
        "tripId": str(trip_id),
        "name": "서울 여행 수정",
        "endDate": "2026-09-02",
        "dayCount": 2,
        "version": 2,
    }.items() <= response.json()["data"].items()
    assert response.json()["meta"]["requestId"] == response.headers["X-Request-ID"]


@pytest.mark.parametrize(
    ("status_code", "code"),
    [
        (403, "FORBIDDEN"),
        (409, "VERSION_CONFLICT"),
        (409, "TRIP_LOCKED"),
        (422, "INVALID_TRIP_PERIOD"),
    ],
)
def test_update_trip_contract_maps_domain_errors(
    client: TestClient,
    status_code: int,
    code: str,
) -> None:
    """여행 수정의 소유권·충돌·잠금·검증 실패를 공통 오류 envelope로 반환한다."""
    app.dependency_overrides[_trip_service] = lambda: RejectingTripService(
        AppError(status_code, code, "여행을 수정할 수 없습니다.")
    )

    response = client.patch(
        f"/api/v1/trips/{uuid.uuid4()}",
        json={"name": "수정 여행", "version": 1},
    )

    assert response.status_code == status_code
    assert response.json()["error"]["code"] == code
    assert response.json()["meta"]["requestId"] == response.headers["X-Request-ID"]


def test_update_trip_contract_validates_trimmed_name_in_service(client: TestClient) -> None:
    """trim 후 짧은 여행명을 422 도메인 오류로 반환한다."""
    app.dependency_overrides[_trip_service] = lambda: RejectingTripService(
        AppError(422, "VALIDATION_ERROR", "여행명이 올바르지 않습니다.")
    )

    response = client.patch(
        f"/api/v1/trips/{uuid.uuid4()}",
        json={"name": " ", "version": 1},
    )

    assert response.status_code == 422
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"


@pytest.mark.parametrize(
    "payload",
    [
        {"name": None, "version": 1},
        {"startDate": None, "version": 1},
        {"endDate": None, "version": 1},
        {"name": "수정 여행", "version": True},
        {"name": "수정 여행", "version": "1"},
    ],
)
def test_update_trip_contract_rejects_null_fields_and_non_integer_version(
    client: TestClient,
    payload: dict[str, object],
) -> None:
    """명시적 null과 정수가 아닌 version을 400 계약 오류로 거부한다."""
    response = client.patch(f"/api/v1/trips/{uuid.uuid4()}", json=payload)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_REQUEST"


def test_update_trip_openapi_declares_request_and_responses() -> None:
    """여행 수정 OpenAPI가 요청 DTO와 공개 응답 상태를 선언하는지 확인한다."""
    operation = app.openapi()["paths"]["/api/v1/trips/{tripId}"]["patch"]

    assert operation["requestBody"]["required"] is True
    assert set(operation["responses"]) == {
        "200",
        "400",
        "401",
        "403",
        "404",
        "409",
        "422",
    }
    schema = app.openapi()["components"]["schemas"]["UpdateTripRequest"]
    assert schema["properties"]["name"]["type"] == "string"
    assert schema["properties"]["startDate"]["type"] == "string"
    assert schema["properties"]["endDate"]["type"] == "string"


def test_delete_trip_contract_returns_empty_204_and_is_repeatable(
    client: TestClient,
) -> None:
    """여행 삭제와 같은 요청의 반복이 본문 없는 204를 반환하는지 확인한다."""
    trip_id = uuid.uuid4()

    first = client.delete(f"/api/v1/trips/{trip_id}")
    second = client.delete(f"/api/v1/trips/{trip_id}")

    assert first.status_code == second.status_code == 204
    assert first.content == second.content == b""


@pytest.mark.parametrize(
    ("status_code", "code"),
    [(403, "FORBIDDEN"), (404, "TRIP_NOT_FOUND")],
)
def test_delete_trip_contract_maps_domain_errors(
    client: TestClient,
    status_code: int,
    code: str,
) -> None:
    """여행 삭제의 소유권·존재·상태 오류를 공통 오류 envelope로 반환한다."""
    app.dependency_overrides[_trip_service] = lambda: RejectingTripService(
        AppError(status_code, code, "여행을 삭제할 수 없습니다.")
    )

    response = client.delete(f"/api/v1/trips/{uuid.uuid4()}")

    assert response.status_code == status_code
    assert response.json()["error"]["code"] == code
    assert response.json()["meta"]["requestId"] == response.headers["X-Request-ID"]


def test_delete_trip_contract_rejects_invalid_uuid(client: TestClient) -> None:
    """UUID가 아닌 여행 식별자를 400 공통 오류 envelope로 거부한다."""
    response = client.delete("/api/v1/trips/not-a-uuid")

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_REQUEST"


def test_delete_trip_openapi_declares_path_and_responses() -> None:
    """여행 삭제 OpenAPI가 UUID 경로와 공개 응답 상태를 선언하는지 확인한다."""
    operation = app.openapi()["paths"]["/api/v1/trips/{tripId}"]["delete"]
    trip_id = next(item for item in operation["parameters"] if item["name"] == "tripId")

    assert trip_id["required"] is True
    assert trip_id["schema"]["format"] == "uuid"
    assert set(operation["responses"]) == {"204", "400", "401", "403", "404"}
