"""PLACE-001 endpoint와 검색 service 통합 흐름 테스트."""

from __future__ import annotations

import uuid
from copy import deepcopy
from typing import Any

import pytest
from fastapi.testclient import TestClient

from app.api.dependencies import get_current_principal
from app.api.v1.places import _place_service
from app.clients.google_places import GooglePlacesClientError
from app.core.security import AuthPrincipal
from app.main import app
from app.services.place import PlaceService


class TourStub:
    """페이지별 TourAPI 응답을 순서대로 반환한다."""

    def __init__(self, pages: list[list[dict[str, str]]]) -> None:
        self.pages = pages

    async def search_keyword(self, **params: Any) -> dict[str, Any]:
        page = int(params["pageNo"])
        items = deepcopy(self.pages[page - 1])
        return {
            "response": {
                "body": {
                    "items": {"item": items},
                    "pageNo": page,
                    "numOfRows": params["numOfRows"],
                    "totalCount": sum(len(entries) for entries in self.pages),
                }
            }
        }

    async def search_by_area(self, **params: Any) -> dict[str, Any]:
        return await self.search_keyword(**params)

    async def get_common_detail(self, content_id: str) -> dict[str, Any]:
        matches = [item for page in self.pages for item in page if item["contentid"] == content_id]
        item = (matches[0] | {"contenttypeid": "12", "overview": "<p>상세 설명</p>"}) if matches else None
        return {"response": {"body": {"items": {"item": [item]} if item else ""}}}

    async def get_intro_detail(self, content_id: str, content_type_id: str) -> dict[str, Any]:
        return {"response": {"body": {"items": ""}}}


class StringEmptyTourStub(TourStub):
    """실제 TourAPI의 문자열 empty items 응답을 반환한다."""

    async def search_keyword(self, **params: Any) -> dict[str, Any]:
        return {"response": {"body": {"items": "", "totalCount": 0}}}


class GoogleStub:
    """보완 결과가 없는 Google Places 대역이다."""

    def __init__(self, detail: dict[str, Any] | None = None) -> None:
        self.detail = detail or {}

    async def search_text(self, text_query: str, **params: Any) -> dict[str, Any]:
        return {"places": []}

    async def get_place(self, place_id: str) -> dict[str, Any]:
        return deepcopy(self.detail)


class FailingGoogleStub(GoogleStub):
    """Google 보완 호출 장애를 재현한다."""

    async def search_text(self, text_query: str, **params: Any) -> dict[str, Any]:
        raise GooglePlacesClientError("GOOGLE_PLACES_FAILED", retryable=True)


def tour_item(content_id: str) -> dict[str, str]:
    """최소 TourAPI 장소 fixture를 만든다."""
    return {
        "contentid": content_id,
        "title": f"테스트 장소 {content_id}",
        "addr1": "서울특별시 중구",
        "mapy": "37.5666",
        "mapx": "126.9784",
        "lclsSystm1": "HS",
        "lclsSystm2": "HS01",
        "lclsSystm3": "HS010100",
    }


@pytest.fixture
def principal_override() -> None:
    """검색 통합 테스트에 인증 principal을 주입한다."""
    app.dependency_overrides[get_current_principal] = lambda: AuthPrincipal(
        user_id=uuid.uuid4(), session_id=uuid.uuid4(), token_id=uuid.uuid4()
    )
    yield
    app.dependency_overrides.clear()


@pytest.mark.usefixtures("principal_override")
def test_search_returns_empty_result() -> None:
    """provider 결과가 없으면 빈 목록과 종료 pagination을 반환한다."""
    service = PlaceService(TourStub([[]]), GoogleStub(), cursor_secret="test-secret")
    app.dependency_overrides[_place_service] = lambda: service

    response = TestClient(app).get("/api/v1/places/search", params={"query": "테스트"})

    assert response.status_code == 200
    assert response.json()["data"]["items"] == []
    assert response.json()["meta"]["pagination"] == {
        "nextCursor": None,
        "hasNext": False,
    }


@pytest.mark.usefixtures("principal_override")
def test_search_returns_empty_for_tour_string_items() -> None:
    """TourAPI 문자열 empty 응답도 200 빈 목록으로 반환한다."""
    service = PlaceService(
        StringEmptyTourStub([[]]), GoogleStub(), cursor_secret="test-secret"
    )
    app.dependency_overrides[_place_service] = lambda: service

    response = TestClient(app).get("/api/v1/places/search", params={"query": "없음"})

    assert response.status_code == 200
    assert response.json()["data"]["items"] == []
    assert response.json()["meta"]["pagination"]["hasNext"] is False


@pytest.mark.usefixtures("principal_override")
def test_google_failure_returns_tour_search_result() -> None:
    """Google 보완 장애가 발생해도 TourAPI 검색 결과를 정상 응답한다."""
    commercial = tour_item("1") | {
        "lclsSystm1": "FD",
        "lclsSystm2": "FD05",
        "lclsSystm3": "FD050100",
    }
    service = PlaceService(
        TourStub([[commercial]]), FailingGoogleStub(), cursor_secret="test-secret"
    )
    app.dependency_overrides[_place_service] = lambda: service

    response = TestClient(app).get(
        "/api/v1/places/search",
        params={"query": "카페", "category": "CAFE", "limit": 2},
    )

    assert response.status_code == 200
    assert [item["placeId"] for item in response.json()["data"]["items"]] == [
        "tourapi:1"
    ]


@pytest.mark.usefixtures("principal_override")
def test_search_follows_next_cursor_without_duplicate() -> None:
    """다음 cursor 요청은 다음 Tour page만 반환한다."""
    service = PlaceService(
        TourStub([[tour_item("1")], [tour_item("2")]]),
        GoogleStub(),
        cursor_secret="test-secret",
    )
    app.dependency_overrides[_place_service] = lambda: service
    client = TestClient(app)

    first = client.get(
        "/api/v1/places/search", params={"query": "테스트", "limit": 1}
    )
    cursor = first.json()["meta"]["pagination"]["nextCursor"]
    second = client.get(
        "/api/v1/places/search",
        params={"query": "테스트", "limit": 1, "cursor": cursor},
    )

    assert first.status_code == second.status_code == 200
    assert [item["placeId"] for item in first.json()["data"]["items"]] == [
        "tourapi:1"
    ]
    assert [item["placeId"] for item in second.json()["data"]["items"]] == [
        "tourapi:2"
    ]


@pytest.mark.usefixtures("principal_override")
def test_tour_detail_endpoint_combines_provider_response() -> None:
    """TourAPI 상세을 PLACE-002 envelope로 반환한다."""
    service = PlaceService(
        TourStub([[tour_item("1")]]), GoogleStub(), cursor_secret="test-secret"
    )
    app.dependency_overrides[_place_service] = lambda: service

    response = TestClient(app).get("/api/v1/places/tourapi:1")

    assert response.status_code == 200
    assert response.json()["data"]["placeId"] == "tourapi:1"
    assert response.json()["data"]["description"] == "상세 설명"


@pytest.mark.usefixtures("principal_override")
def test_google_detail_endpoint_returns_allowed_fields() -> None:
    """Google 전용 장소의 허용된 상세 필드를 반환한다."""
    detail = {
        "id": "g1", "displayName": {"text": "테스트 카페"},
        "formattedAddress": "서울특별시 중구", "types": ["cafe"],
        "location": {"latitude": 37.5, "longitude": 127.0},
        "rating": 4.5, "userRatingCount": 10, "attributions": [],
    }
    service = PlaceService(TourStub([[]]), GoogleStub(detail), cursor_secret="test-secret")
    app.dependency_overrides[_place_service] = lambda: service

    response = TestClient(app).get("/api/v1/places/google:g1")

    assert response.status_code == 200
    assert response.json()["data"]["placeId"] == "google:g1"
    assert response.json()["data"]["description"] is None
