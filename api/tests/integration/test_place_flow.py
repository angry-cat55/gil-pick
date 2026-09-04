"""PLACE-001 endpoint와 검색 service 통합 흐름 테스트."""

from __future__ import annotations

import uuid
from copy import deepcopy
from typing import Any

import pytest
from fastapi.testclient import TestClient

from app.api.dependencies import get_current_principal
from app.api.v1.places import _place_service
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


class GoogleStub:
    """보완 결과가 없는 Google Places 대역이다."""

    async def search_text(self, text_query: str, **params: Any) -> dict[str, Any]:
        return {"places": []}


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
