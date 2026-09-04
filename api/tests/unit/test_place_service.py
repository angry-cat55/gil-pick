"""장소 검색 service의 provider 조합·매칭·cursor 단위 테스트."""

from __future__ import annotations

from copy import deepcopy
from typing import Any

import pytest

from app.api.errors import AppError
from app.schemas.place import PlaceCategory
from app.services.place import PlaceService


def tour_item(
    content_id: str,
    *,
    name: str = "테스트 장소",
    large: str = "HS",
    middle: str = "HS01",
    small: str = "HS010100",
    address: str = "서울특별시 중구 세종대로 110",
    latitude: float = 37.56661,
    longitude: float = 126.978388,
) -> dict[str, str]:
    """TourAPI 검색 item을 만든다."""
    return {
        "contentid": content_id,
        "title": name,
        "addr1": address,
        "mapy": str(latitude),
        "mapx": str(longitude),
        "firstimage": "https://example.com/place.jpg",
        "lclsSystm1": large,
        "lclsSystm2": middle,
        "lclsSystm3": small,
    }


def tour_response(
    items: list[dict[str, str]], *, page: int = 1, total: int | None = None
) -> dict[str, Any]:
    """TourAPI 정상 검색 응답을 만든다."""
    return {
        "response": {
            "header": {"resultCode": "0000", "resultMsg": "OK"},
            "body": {
                "items": {"item": items},
                "numOfRows": len(items),
                "pageNo": page,
                "totalCount": len(items) if total is None else total,
            },
        }
    }


def google_place(
    place_id: str,
    *,
    name: str,
    address: str,
    latitude: float,
    longitude: float,
) -> dict[str, Any]:
    """Google Text Search place를 만든다."""
    return {
        "id": place_id,
        "displayName": {"text": name, "languageCode": "ko"},
        "formattedAddress": address,
        "location": {"latitude": latitude, "longitude": longitude},
        "types": ["cafe", "food"],
        "rating": 4.6,
        "userRatingCount": 321,
        "businessStatus": "OPERATIONAL",
        "regularOpeningHours": {"weekdayDescriptions": ["월요일: 10:00~20:00"]},
        "currentOpeningHours": {"weekdayDescriptions": ["월요일: 10:00~20:00"]},
        "attributions": ["Google Maps"],
    }


class StubTourClient:
    """검색 응답과 호출 순서를 기록하는 TourAPI 대역."""

    def __init__(self, responses: list[dict[str, Any]]) -> None:
        self.responses = list(responses)
        self.calls: list[tuple[str, dict[str, Any]]] = []

    async def search_keyword(self, **params: Any) -> dict[str, Any]:
        self.calls.append(("keyword", params))
        return deepcopy(self.responses.pop(0))

    async def search_by_area(self, **params: Any) -> dict[str, Any]:
        self.calls.append(("area", params))
        return deepcopy(self.responses.pop(0))


class StubGoogleClient:
    """Text Search 응답과 호출 순서를 기록하는 Google 대역."""

    def __init__(self, response: dict[str, Any] | None = None) -> None:
        self.response = response or {"places": []}
        self.calls: list[tuple[str, dict[str, Any]]] = []

    async def search_text(self, text_query: str, **params: Any) -> dict[str, Any]:
        self.calls.append((text_query, params))
        return deepcopy(self.response)


def service(
    tour: StubTourClient, google: StubGoogleClient | None = None
) -> PlaceService:
    """고정 cursor secret을 사용하는 service를 만든다."""
    return PlaceService(tour, google or StubGoogleClient(), cursor_secret="test-secret")


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("large", "middle", "expected_category", "expected_minutes"),
    [
        ("NA", "NA01", PlaceCategory.NATURE, 120),
        ("HS", "HS01", PlaceCategory.HISTORY_CULTURE, 90),
        ("VE", "VE01", PlaceCategory.OTHER, 60),
        ("FD", "FD01", PlaceCategory.FOOD, 60),
        ("FD", "FD05", PlaceCategory.CAFE, 60),
        ("SH", "SH01", PlaceCategory.SHOPPING, 90),
        ("ZZ", "ZZ01", PlaceCategory.OTHER, 60),
    ],
)
async def test_search_maps_category_and_recommended_stay(
    large: str,
    middle: str,
    expected_category: PlaceCategory,
    expected_minutes: int,
) -> None:
    """TourAPI 신분류를 내부 category와 체류시간으로 변환한다."""
    tour = StubTourClient(
        [tour_response([tour_item("1", large=large, middle=middle)])]
    )

    items, _, _ = await service(tour).search_places(
        query="테스트", category=None, area_code=None, cursor=None, limit=20
    )

    assert items[0].category is expected_category
    assert items[0].recommended_stay_minutes == expected_minutes


@pytest.mark.asyncio
async def test_search_routes_keyword_and_category_only_to_tourapi() -> None:
    """키워드 유무에 따라 TourAPI 검색 endpoint를 선택한다."""
    tour = StubTourClient(
        [tour_response([tour_item("1")]), tour_response([tour_item("2")])]
    )
    place_service = service(tour)

    await place_service.search_places(
        query="궁궐", category=PlaceCategory.HISTORY_CULTURE,
        area_code="1", cursor=None, limit=10,
    )
    await place_service.search_places(
        query=None, category=PlaceCategory.NATURE,
        area_code="1", cursor=None, limit=10,
    )

    assert [call[0] for call in tour.calls] == ["keyword", "area"]
    assert tour.calls[0][1]["keyword"] == "궁궐"
    assert tour.calls[0][1]["areaCode"] == "1"
    assert tour.calls[1][1]["lclsSystm1"] == "NA"


@pytest.mark.asyncio
async def test_google_is_called_only_for_commercial_category_shortage() -> None:
    """상업 category의 TourAPI 결과가 limit 미만일 때만 부족분을 요청한다."""
    google = StubGoogleClient(
        {
            "places": [
                google_place(
                    "g1", name="다른 카페", address="서울특별시 종로구 새문안로 1",
                    latitude=37.57, longitude=126.97,
                )
            ]
        }
    )
    commercial_tour = StubTourClient(
        [tour_response([tour_item("1", large="FD", middle="FD05")])]
    )

    items, _, _ = await service(commercial_tour, google).search_places(
        query="카페", category=PlaceCategory.CAFE,
        area_code=None, cursor=None, limit=2,
    )

    assert len(items) == 2
    assert len(google.calls) == 1
    assert google.calls[0][1]["maxResultCount"] == 1

    google.calls.clear()
    nature_tour = StubTourClient([tour_response([tour_item("2")])])
    await service(nature_tour, google).search_places(
        query="숲", category=PlaceCategory.NATURE,
        area_code=None, cursor=None, limit=2,
    )

    assert google.calls == []


@pytest.mark.asyncio
async def test_confirmed_google_match_merges_only_allowed_fields() -> None:
    """50m·정규화 이름·주소가 모두 맞으면 TourAPI ID에 Google 필드만 병합한다."""
    tour = StubTourClient(
        [
            tour_response(
                [tour_item("1", name="테스트-카페", large="FD", middle="FD05")]
            )
        ]
    )
    google = StubGoogleClient(
        {
            "places": [
                google_place(
                    "g1", name="테스트 카페", address="서울특별시 중구 세종대로 110",
                    latitude=37.5667, longitude=126.9784,
                )
            ]
        }
    )

    items, _, _ = await service(tour, google).search_places(
        query="카페", category=PlaceCategory.CAFE,
        area_code=None, cursor=None, limit=2,
    )

    assert len(items) == 1
    assert items[0].place_id == "tourapi:1"
    assert items[0].name == "테스트-카페"
    assert items[0].rating == 4.6
    assert items[0].google_attributions == ["Google Maps"]


@pytest.mark.asyncio
async def test_ambiguous_google_candidate_is_excluded() -> None:
    """가깝지만 주소가 다른 모호한 후보는 별도 결과로도 노출하지 않는다."""
    tour = StubTourClient(
        [
            tour_response(
                [tour_item("1", name="테스트 카페", large="FD", middle="FD05")]
            )
        ]
    )
    google = StubGoogleClient(
        {
            "places": [
                google_place(
                    "g1", name="테스트 카페", address="서울특별시 강남구 테헤란로 1",
                    latitude=37.5667, longitude=126.9784,
                )
            ]
        }
    )

    items, _, _ = await service(tour, google).search_places(
        query="카페", category=PlaceCategory.CAFE,
        area_code=None, cursor=None, limit=2,
    )

    assert [item.place_id for item in items] == ["tourapi:1"]


@pytest.mark.asyncio
async def test_cursor_resumes_tour_page_and_deduplicates_seen_ids() -> None:
    """서명 cursor가 다음 TourAPI page와 이미 본 ID를 보존한다."""
    tour = StubTourClient(
        [
            tour_response([tour_item("1")], page=1, total=2),
            tour_response([tour_item("1"), tour_item("2")], page=2, total=2),
        ]
    )
    place_service = service(tour)

    first_items, cursor, has_next = await place_service.search_places(
        query="테스트", category=None, area_code=None, cursor=None, limit=1
    )
    second_items, _, _ = await place_service.search_places(
        query="테스트", category=None, area_code=None, cursor=cursor, limit=1
    )

    assert has_next is True
    assert cursor is not None
    assert [item.place_id for item in first_items] == ["tourapi:1"]
    assert [item.place_id for item in second_items] == ["tourapi:2"]
    assert tour.calls[1][1]["pageNo"] == 2


@pytest.mark.asyncio
@pytest.mark.parametrize("reuse_with_other_criteria", [False, True])
async def test_cursor_rejects_tampering_and_other_criteria(
    reuse_with_other_criteria: bool,
) -> None:
    """cursor 변조와 다른 검색 조건 재사용을 거부한다."""
    tour = StubTourClient([tour_response([tour_item("1")], total=2)])
    place_service = service(tour)
    _, cursor, _ = await place_service.search_places(
        query="테스트", category=None, area_code=None, cursor=None, limit=1
    )
    assert cursor is not None

    if reuse_with_other_criteria:
        next_cursor = cursor
        query = "다른 검색"
    else:
        next_cursor = f"{cursor[:-1]}{'A' if cursor[-1] != 'A' else 'B'}"
        query = "테스트"

    with pytest.raises(AppError) as error:
        await place_service.search_places(
            query=query, category=None, area_code=None,
            cursor=next_cursor, limit=1,
        )

    assert error.value.code == "INVALID_CURSOR"
