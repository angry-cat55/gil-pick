"""F003 장소 검색 HTTP 계약 테스트."""

from __future__ import annotations

import uuid

import pytest
from fastapi.testclient import TestClient

from app.api.dependencies import get_current_principal
from app.api.errors import AppError
from app.api.v1 import places as places_api
from app.core.security import AuthPrincipal
from app.main import app
from app.schemas.place import (
    BusinessStatus,
    PlaceCategory,
    PlaceSource,
    PlaceSummary,
    TourApiCategory,
)


class StubPlaceSearchService:
    """PLACE-001 요청 전달과 응답 envelope를 검증하는 service 대역."""

    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []

    async def search_places(
        self,
        *,
        query: str | None,
        category: PlaceCategory | None,
        area_code: str | None,
        cursor: str | None,
        limit: int,
    ) -> tuple[list[PlaceSummary], str | None, bool]:
        """요청 인자를 기록하고 nullable 필드가 포함된 한 페이지를 반환한다."""
        self.calls.append(
            {
                "query": query,
                "category": category,
                "area_code": area_code,
                "cursor": cursor,
                "limit": limit,
            }
        )
        if cursor == "invalid-cursor":
            raise AppError(400, "INVALID_CURSOR", "커서가 올바르지 않습니다.")
        return (
            [
                PlaceSummary(
                    place_id="tourapi:126508",
                    source=PlaceSource.TOUR_API,
                    source_place_id="126508",
                    name="경복궁",
                    category=PlaceCategory.HISTORY_CULTURE,
                    tour_api_category=TourApiCategory(
                        large="A02", middle="A0201", small=None
                    ),
                    address="서울특별시 종로구",
                    latitude=37.5796,
                    longitude=126.977,
                    image_url=None,
                    recommended_stay_minutes=90,
                    rating=4.7,
                    user_rating_count=1200,
                    business_status=BusinessStatus.OPERATIONAL,
                    regular_opening_hours=None,
                    current_opening_hours=None,
                    google_attributions=None,
                )
            ],
            "next-page",
            True,
        )


@pytest.fixture
def place_client() -> tuple[TestClient, StubPlaceSearchService]:
    """인증 principal과 장소 service를 격리한 HTTP client를 제공한다."""
    principal = AuthPrincipal(
        user_id=uuid.uuid4(),
        session_id=uuid.uuid4(),
        token_id=uuid.uuid4(),
    )
    service = StubPlaceSearchService()
    app.dependency_overrides[get_current_principal] = lambda: principal
    dependency = getattr(places_api, "_place_service", None)
    if dependency is not None:
        app.dependency_overrides[dependency] = lambda: service
    try:
        yield TestClient(app), service
    finally:
        app.dependency_overrides.clear()


@pytest.mark.parametrize(
    "params",
    [
        {"query": "  경복궁  "},
        {"category": "HISTORY_CULTURE"},
        {"query": "  경복궁  ", "category": "HISTORY_CULTURE"},
    ],
)
def test_search_contract_accepts_query_and_category_combinations(
    place_client: tuple[TestClient, StubPlaceSearchService],
    params: dict[str, str],
) -> None:
    """키워드와 category의 단독·조합 요청을 허용하고 키워드를 trim한다."""
    client, service = place_client

    response = client.get("/api/v1/places/search", params=params)

    assert response.status_code == 200
    assert service.calls[-1]["query"] == ("경복궁" if "query" in params else None)
    assert service.calls[-1]["category"] == (
        PlaceCategory.HISTORY_CULTURE if "category" in params else None
    )


def test_search_contract_returns_nullable_fields_and_pagination(
    place_client: tuple[TestClient, StubPlaceSearchService],
) -> None:
    """검색 결과의 필수 nullable 필드와 cursor pagination을 반환한다."""
    client, _ = place_client

    response = client.get(
        "/api/v1/places/search",
        params={"query": "경복궁", "areaCode": "1", "cursor": "current", "limit": 1},
    )

    assert response.status_code == 200
    assert response.json()["data"]["items"][0]["imageUrl"] is None
    assert response.json()["meta"]["pagination"] == {
        "nextCursor": "next-page",
        "hasNext": True,
    }
    assert response.json()["meta"]["requestId"] == response.headers["X-Request-ID"]


@pytest.mark.parametrize(
    "params",
    [
        {},
        {"query": "가"},
        {"query": "   "},
        {"query": "가", "category": "FOOD"},
        {"category": "OTHER", "areaCode": "99"},
        {"category": "OTHER", "limit": 0},
        {"category": "OTHER", "limit": 21},
        {"category": "OTHER", "cursor": ""},
    ],
)
def test_search_contract_rejects_invalid_request_without_calling_service(
    place_client: tuple[TestClient, StubPlaceSearchService],
    params: dict[str, object],
) -> None:
    """유효하지 않은 검색 조건은 provider 경계에 도달하기 전에 거부한다."""
    client, service = place_client

    response = client.get("/api/v1/places/search", params=params)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_REQUEST"
    assert service.calls == []


def test_search_contract_maps_invalid_cursor(
    place_client: tuple[TestClient, StubPlaceSearchService],
) -> None:
    """변조되거나 조건과 맞지 않는 cursor를 공개 오류로 반환한다."""
    client, _ = place_client

    response = client.get(
        "/api/v1/places/search",
        params={"category": "FOOD", "cursor": "invalid-cursor"},
    )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_CURSOR"
    assert response.json()["error"]["retryable"] is False


def test_search_openapi_declares_parameters_and_responses() -> None:
    """PLACE-001 OpenAPI가 query와 공개 응답 상태를 선언한다."""
    operation = app.openapi()["paths"]["/api/v1/places/search"]["get"]
    parameters = {item["name"]: item for item in operation["parameters"]}

    assert set(parameters) == {"query", "category", "areaCode", "cursor", "limit"}
    assert parameters["limit"]["schema"] == {
        "type": "integer",
        "maximum": 20,
        "minimum": 1,
        "default": 20,
        "title": "Limit",
    }
    assert set(operation["responses"]) == {"200", "400", "401", "429", "502", "504"}
