"""장소 검색 service의 provider 조합·매칭·cursor 단위 테스트."""

from __future__ import annotations

from copy import deepcopy
from typing import Any

import pytest

from app.api.errors import AppError
from app.clients.google_places import GooglePlacesClientError
from app.schemas.place import PlaceCategory
from app.services.place import (
    PlaceService,
    category_of,
    distance_meters,
    find_match,
    merge_google,
)
from app.services.place import (
    google_place as map_google_place,
)
from app.services.place import (
    tour_place as map_tour_place,
)


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
    types: list[str] | None = None,
) -> dict[str, Any]:
    """Google Text Search place를 만든다."""
    return {
        "id": place_id,
        "displayName": {"text": name, "languageCode": "ko"},
        "formattedAddress": address,
        "location": {"latitude": latitude, "longitude": longitude},
        "types": types or ["cafe", "food"],
        "rating": 4.6,
        "userRatingCount": 321,
        "businessStatus": "OPERATIONAL",
        "regularOpeningHours": {"weekdayDescriptions": ["월요일: 10:00~20:00"]},
        "currentOpeningHours": {
            "weekdayDescriptions": ["월요일: 10:00~20:00"],
            "openNow": True,
        },
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

    async def search_by_location(self, **params: Any) -> dict[str, Any]:
        self.calls.append(("location", params))
        return deepcopy(self.responses.pop(0))


class StubGoogleClient:
    """Text Search 응답과 호출 순서를 기록하는 Google 대역."""

    def __init__(self, response: dict[str, Any] | None = None) -> None:
        self.response = response or {"places": []}
        self.calls: list[tuple[str, dict[str, Any]]] = []

    async def search_text(self, text_query: str, **params: Any) -> dict[str, Any]:
        self.calls.append((text_query, params))
        return deepcopy(self.response)


class FailingGoogleClient(StubGoogleClient):
    """Google 보완 호출 실패를 재현하는 대역."""

    async def search_text(self, text_query: str, **params: Any) -> dict[str, Any]:
        self.calls.append((text_query, params))
        raise GooglePlacesClientError("GOOGLE_PLACES_TIMEOUT", retryable=True)


def service(
    tour: StubTourClient, google: StubGoogleClient | None = None
) -> PlaceService:
    """고정 cursor secret을 사용하는 service를 만든다."""
    return PlaceService(tour, google or StubGoogleClient(), cursor_secret="test-secret")


def test_public_place_helpers_preserve_mapping_and_matching() -> None:
    """공개 helper가 기존 장소 변환·매칭·병합 동작을 유지한다."""
    tour = map_tour_place(tour_item("1", name="테스트 카페", large="FD", middle="FD05"))
    google = map_google_place(
        google_place(
            "g1", name="테스트 카페", address="서울특별시 중구 세종대로 110",
            latitude=37.5667, longitude=126.9784,
        ),
        PlaceCategory.CAFE,
    )

    assert tour is not None
    assert google is not None
    assert category_of("FD", "FD05") is PlaceCategory.CAFE
    assert distance_meters(tour, google) <= 50
    assert find_match([tour], google) == (tour, False)
    assert tour.google_place_id is None

    merge_google(tour, google)

    assert tour.rating == google.rating
    assert tour.open_now is True
    assert tour.google_attributions == google.google_attributions
    assert tour.google_place_id == "g1"


@pytest.mark.parametrize(
    ("current_opening_hours", "expected"),
    [({"openNow": True}, True), ({"openNow": False}, False), ({}, None)],
)
def test_google_place_maps_nullable_open_now(
    current_opening_hours: dict[str, bool], expected: bool | None
) -> None:
    """Google이 제공한 현재 영업 여부만 그대로 매핑한다."""
    raw = google_place(
        "g1",
        name="테스트 카페",
        address="서울특별시 중구 세종대로 110",
        latitude=37.5667,
        longitude=126.9784,
    )
    raw["currentOpeningHours"] = current_opening_hours

    place = map_google_place(raw, PlaceCategory.CAFE)

    assert place is not None
    assert place.open_now is expected


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
async def test_search_accepts_tour_string_empty_items() -> None:
    """TourAPI의 문자열 empty items를 정상적인 빈 결과로 처리한다."""
    tour = StubTourClient(
        [{"response": {"body": {"items": "", "totalCount": 0}}}]
    )

    items, next_cursor, has_next = await service(tour).search_places(
        query="없는 장소", category=None, area_code=None, cursor=None, limit=20
    )

    assert items == []
    assert next_cursor is None
    assert has_next is False


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
async def test_search_always_restricts_tourapi_to_seoul() -> None:
    """클라이언트가 지역 코드를 생략해도 TourAPI 검색은 서울로 제한한다."""
    tour = StubTourClient(
        [tour_response([tour_item("1")]), tour_response([tour_item("2")])]
    )
    place_service = service(tour)

    await place_service.search_places(
        query="궁궐", category=None, area_code=None, cursor=None, limit=10,
    )
    await place_service.search_places(
        query=None, category=PlaceCategory.NATURE,
        area_code=None, cursor=None, limit=10,
    )

    assert [call[1]["areaCode"] for call in tour.calls] == ["1", "1"]


@pytest.mark.asyncio
async def test_queryless_search_uses_nearest_tourapi_order() -> None:
    """검색어가 없으면 사용자 위치 기준 5km 거리순 목록을 요청한다."""
    tour = StubTourClient([tour_response([tour_item("1")])])

    await service(tour).search_places(
        query=None,
        category=None,
        area_code="1",
        latitude=37.5884,
        longitude=127.0060,
        radius_meters=5000,
        cursor=None,
        limit=20,
    )

    assert tour.calls == [
        (
            "location",
            {
                "pageNo": 1,
                "numOfRows": 20,
                "mapX": 127.0060,
                "mapY": 37.5884,
                "radius": 5000,
                "arrange": "S",
            },
        )
    ]


@pytest.mark.asyncio
async def test_queryless_category_search_keeps_location_and_category_filter() -> None:
    """검색어 없이 category를 바꿔도 위치 기반 검색과 분류 조건을 함께 쓴다."""
    tour = StubTourClient([tour_response([tour_item("1", large="NA")])])

    await service(tour).search_places(
        query=None,
        category=PlaceCategory.NATURE,
        area_code="1",
        latitude=37.5884,
        longitude=127.0060,
        radius_meters=5000,
        cursor=None,
        limit=20,
    )

    assert tour.calls[0][0] == "location"
    assert tour.calls[0][1]["lclsSystm1"] == "NA"


@pytest.mark.asyncio
async def test_nearby_cursor_binds_location_radius_category_and_limit() -> None:
    """거리순 다음 페이지 cursor는 최초 위치 검색 조건에서만 재사용할 수 있다."""
    tour = StubTourClient(
        [
            tour_response([tour_item("1")], total=2),
            tour_response([tour_item("2")], page=2, total=2),
        ]
    )
    place_service = service(tour)
    _, cursor, _ = await place_service.search_places(
        query=None,
        category=None,
        area_code="1",
        latitude=37.5884,
        longitude=127.0060,
        radius_meters=5000,
        cursor=None,
        limit=1,
    )
    assert cursor is not None

    items, _, _ = await place_service.search_places(
        query=None,
        category=None,
        area_code="1",
        latitude=37.5884,
        longitude=127.0060,
        radius_meters=5000,
        cursor=cursor,
        limit=1,
    )

    assert [item.place_id for item in items] == ["tourapi:2"]
    assert tour.calls[1][1]["pageNo"] == 2

    with pytest.raises(AppError) as error:
        await place_service.search_places(
            query=None,
            category=None,
            area_code="1",
            latitude=37.5885,
            longitude=127.0060,
            radius_meters=5000,
            cursor=cursor,
            limit=1,
        )

    assert error.value.code == "INVALID_CURSOR"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "changed",
    [
        {"longitude": 127.0061},
        {"radius_meters": 3000},
        {"category": PlaceCategory.NATURE},
        {"limit": 2},
    ],
)
async def test_nearby_cursor_rejects_every_changed_search_condition(
    changed: dict[str, Any],
) -> None:
    """경도·반경·category·limit 중 하나라도 바뀌면 cursor를 거부한다."""
    place_service = service(
        StubTourClient([tour_response([tour_item("1")], total=2)])
    )
    criteria: dict[str, Any] = {
        "query": None,
        "category": None,
        "area_code": "1",
        "latitude": 37.5884,
        "longitude": 127.0060,
        "radius_meters": 5000,
        "cursor": None,
        "limit": 1,
    }
    _, cursor, _ = await place_service.search_places(**criteria)
    assert cursor is not None

    with pytest.raises(AppError) as error:
        await place_service.search_places(**(criteria | changed | {"cursor": cursor}))

    assert error.value.code == "INVALID_CURSOR"


@pytest.mark.asyncio
async def test_search_excludes_non_seoul_tourapi_results() -> None:
    """TourAPI가 잘못 분류한 결과를 반환해도 서울 주소만 포함한다."""
    tour = StubTourClient(
        [
            tour_response(
                [
                    tour_item("seoul", address="서울특별시 성북구 삼선교로 1"),
                    tour_item("busan", address="부산광역시 해운대구 해운대로 1"),
                ]
            )
        ]
    )

    items, _, _ = await service(tour).search_places(
        query="명소", category=None, area_code=None, cursor=None, limit=10,
    )

    assert [item.place_id for item in items] == ["tourapi:seoul"]


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
    assert google.calls[0][1]["pageSize"] == 1
    assert google.calls[0][1]["locationRestriction"] == {
        "rectangle": {
            "low": {"latitude": 37.413294, "longitude": 126.734086},
            "high": {"latitude": 37.715133, "longitude": 127.269311},
        }
    }

    google.calls.clear()
    nature_tour = StubTourClient([tour_response([tour_item("2")])])
    await service(nature_tour, google).search_places(
        query="숲", category=PlaceCategory.NATURE,
        area_code=None, cursor=None, limit=2,
    )

    assert google.calls == []


@pytest.mark.asyncio
async def test_keyword_search_with_location_prefers_results_inside_radius() -> None:
    """위치가 포함된 키워드 검색은 먼 TourAPI 결과를 빼고 Google도 같은 지역으로 제한한다."""
    google = StubGoogleClient(
        {
            "places": [
                google_place(
                    "nearby", name="스타벅스 종로점", address="서울특별시 종로구",
                    latitude=37.5700, longitude=126.9800,
                ),
                google_place(
                    "far-google", name="스타벅스 강남점", address="서울특별시 강남구",
                    latitude=37.4979, longitude=127.0276,
                ),
            ]
        }
    )
    tour = StubTourClient(
        [
            tour_response(
                [
                    tour_item(
                        "far", name="스타벅스 강남점", large="FD", middle="FD05",
                        latitude=37.4979, longitude=127.0276,
                    )
                ]
            )
        ]
    )

    items, _, _ = await service(tour, google).search_places(
        query="스타벅스", category=None, area_code=None,
        latitude=37.5665, longitude=126.9780, radius_meters=2000,
        cursor=None, limit=20,
    )

    assert [item.place_id for item in items] == ["google:nearby"]
    restriction = google.calls[0][1]["locationRestriction"]["rectangle"]
    assert restriction["low"]["latitude"] < 37.5665 < restriction["high"]["latitude"]
    assert restriction["low"]["longitude"] < 126.9780 < restriction["high"]["longitude"]


@pytest.mark.asyncio
async def test_google_supplement_excludes_non_seoul_addresses() -> None:
    """Google이 경계 밖 결과를 섞어도 서울 주소만 검색 결과에 포함한다."""
    google = StubGoogleClient(
        {
            "places": [
                google_place(
                    "seoul", name="서울 카페", address="서울특별시 성북구 삼선교로 1",
                    latitude=37.588, longitude=127.006,
                ),
                google_place(
                    "busan", name="부산 카페", address="부산광역시 해운대구 해운대로 1",
                    latitude=35.163, longitude=129.163,
                ),
            ]
        }
    )

    items, _, _ = await service(
        StubTourClient([tour_response([])]), google
    ).search_places(
        query="카페", category=PlaceCategory.CAFE,
        area_code=None, cursor=None, limit=2,
    )

    assert [item.place_id for item in items] == ["google:seoul"]


@pytest.mark.asyncio
async def test_all_keyword_search_uses_google_once_and_maps_its_category() -> None:
    """전체 키워드 검색은 Google 부족분을 한 번만 보완하고 type으로 분류한다."""
    tour = StubTourClient(
        [
            tour_response([tour_item("1")], total=3),
            tour_response([tour_item("2")], page=2, total=3),
        ]
    )
    google = StubGoogleClient(
        {
            "places": [
                google_place(
                    "g1", name="한성대학교", address="서울특별시 성북구 삼선교로 16길 116",
                    latitude=37.582, longitude=127.010, types=["university"],
                )
            ],
            "nextPageToken": "ignored-to-limit-calls",
        }
    )
    place_service = service(tour, google)

    first, cursor, has_next = await place_service.search_places(
        query="한성대학교", category=None, area_code=None, cursor=None, limit=2,
    )
    assert cursor is not None
    await place_service.search_places(
        query="한성대학교", category=None, area_code=None, cursor=cursor, limit=2,
    )

    assert has_next is True
    assert [item.category for item in first] == [
        PlaceCategory.HISTORY_CULTURE,
        PlaceCategory.OTHER,
    ]
    assert len(google.calls) == 1


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("category", "types", "label"),
    [
        (PlaceCategory.FOOD, ["restaurant", "food"], "음식점"),
        (PlaceCategory.CAFE, ["cafe", "food"], "카페"),
        (PlaceCategory.SHOPPING, ["shopping_mall", "store"], "쇼핑"),
    ],
)
async def test_google_supplement_matches_selected_category(
    category: PlaceCategory, types: list[str], label: str,
) -> None:
    """Google 보완 결과는 선택한 상업 카테고리와 일치해야 한다."""
    google = StubGoogleClient(
        {
            "places": [
                google_place(
                    "other", name="다른 유형", address="서울특별시 중구",
                    latitude=37.5, longitude=127.0, types=["university"],
                ),
                google_place(
                    "g1", name="검색 결과", address="서울특별시 중구",
                    latitude=37.5, longitude=127.0, types=types,
                )
            ]
        }
    )

    items, _, _ = await service(StubTourClient([tour_response([])]), google).search_places(
        query="테스트", category=category, area_code=None, cursor=None, limit=2,
    )

    assert len(items) == 1
    assert items[0].category is category
    assert google.calls[0][0] == f"테스트 {label}"


@pytest.mark.asyncio
async def test_google_search_failure_keeps_tour_results() -> None:
    """Google 보완 실패는 정상 TourAPI 검색 결과를 제거하지 않는다."""
    tour = StubTourClient(
        [tour_response([tour_item("1", large="FD", middle="FD05")])]
    )
    google = FailingGoogleClient()

    items, next_cursor, has_next = await service(tour, google).search_places(
        query="카페", category=PlaceCategory.CAFE,
        area_code=None, cursor=None, limit=2,
    )

    assert [item.place_id for item in items] == ["tourapi:1"]
    assert next_cursor is None
    assert has_next is False
    assert len(google.calls) == 1


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
    assert items[0].google_place_id == "g1"


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


class DetailTourClient:
    """TourAPI 공통·소개 상세 응답 대역."""

    def __init__(self, common: dict[str, Any], intro: dict[str, Any]) -> None:
        self.common = common
        self.intro = intro
        self.intro_calls: list[tuple[str, str]] = []

    async def get_common_detail(self, content_id: str) -> dict[str, Any]:
        return deepcopy(self.common)

    async def get_intro_detail(
        self, content_id: str, content_type_id: str
    ) -> dict[str, Any]:
        self.intro_calls.append((content_id, content_type_id))
        return deepcopy(self.intro)


class DetailGoogleClient(StubGoogleClient):
    """Google 검색·상세 응답 대역."""

    def __init__(self, detail: dict[str, Any] | None = None) -> None:
        super().__init__()
        self.detail = detail or {}

    async def get_place(self, place_id: str) -> dict[str, Any]:
        return deepcopy(self.detail)


def detail_response(item: dict[str, Any] | None) -> dict[str, Any]:
    """TourAPI 상세 envelope를 만든다."""
    return {
        "response": {
            "body": {
                "items": {"item": [item]} if item else "",
                "totalCount": 1 if item else 0,
            }
        }
    }


@pytest.mark.asyncio
async def test_tour_detail_combines_common_and_intro_without_inventing_open_now() -> None:
    """TourAPI 공통·소개 상세를 조합하고 HTML을 plain text로 바꾼다."""
    common = tour_item("1", name="테스트 상점", large="SH", middle="SH01") | {
        "contenttypeid": "38",
        "overview": "<p>안전한 <strong>설명</strong>입니다.</p>",
        "tel": "02-0000-0000",
    }
    intro = {
        "contentid": "1",
        "contenttypeid": "38",
        "opentime": "10:00~20:00",
        "restdateshopping": "매주 월요일",
    }
    place_service = PlaceService(
        DetailTourClient(detail_response(common), detail_response(intro)),
        DetailGoogleClient(), cursor_secret="test-secret",
    )

    detail = await place_service.get_place("tourapi:1")

    assert detail.place_id == "tourapi:1"
    assert detail.description == "안전한 설명입니다."
    assert detail.phone == "02-0000-0000"
    assert detail.operating_guide == "10:00~20:00, 매주 월요일"
    assert detail.business_status is None


@pytest.mark.asyncio
async def test_google_detail_returns_only_allowed_fields() -> None:
    """Google 전용 상세는 사진·리뷰 없이 허용된 필드만 반환한다."""
    raw = google_place(
        "g1", name="테스트 카페", address="서울특별시 중구 테스트로 1",
        latitude=37.5666, longitude=126.9784,
    ) | {
        "nationalPhoneNumber": "02-1111-2222",
        "photos": [{"name": "should-not-leak"}],
        "reviews": [{"text": "should-not-leak"}],
    }
    place_service = PlaceService(
        DetailTourClient(detail_response(None), detail_response(None)),
        DetailGoogleClient(raw), cursor_secret="test-secret",
    )

    detail = await place_service.get_place("google:g1")
    payload = detail.model_dump(mode="json", by_alias=True)

    assert detail.place_id == "google:g1"
    assert detail.category is PlaceCategory.CAFE
    assert detail.phone == "02-1111-2222"
    assert detail.description is None
    assert detail.operating_guide is None
    assert "photos" not in payload
    assert "reviews" not in payload


@pytest.mark.asyncio
async def test_google_detail_normalizes_structured_attribution() -> None:
    """Google attribution 객체를 Android가 표시할 문자열로 정규화한다."""
    raw = google_place(
        "g1", name="테스트 카페", address="서울특별시 중구",
        latitude=37.5, longitude=127.0,
    )
    raw["attributions"] = [
        {"provider": "Example Provider", "providerUri": "https://example.com"}
    ]
    place_service = PlaceService(
        DetailTourClient(detail_response(None), detail_response(None)),
        DetailGoogleClient(raw), cursor_secret="test-secret",
    )

    detail = await place_service.get_place("google:g1")

    assert detail.google_attributions == ["Example Provider (https://example.com)"]


@pytest.mark.asyncio
async def test_tour_commercial_detail_merges_confirmed_google_fields() -> None:
    """상업 Tour 상세은 확정 매칭된 Google 평점·영업정보만 보강한다."""
    common = tour_item(
        "1", name="테스트 카페", large="FD", middle="FD05"
    ) | {"contenttypeid": "39"}
    google = DetailGoogleClient()
    google.response = {
        "places": [
            google_place(
                "g1", name="테스트 카페", address="서울특별시 중구 세종대로 110",
                latitude=37.56661, longitude=126.978388,
            )
        ]
    }
    place_service = PlaceService(
        DetailTourClient(detail_response(common), detail_response({})),
        google, cursor_secret="test-secret",
    )

    detail = await place_service.get_place("tourapi:1")

    assert detail.place_id == "tourapi:1"
    assert detail.rating == 4.6
    assert detail.google_attributions == ["Google Maps"]
    assert detail.google_place_id == "g1"


@pytest.mark.asyncio
async def test_google_enrichment_failure_keeps_tour_detail() -> None:
    """Google 상세 보강 실패 시 TourAPI 상세만 반환한다."""
    common = tour_item("1", large="FD", middle="FD05") | {"contenttypeid": "39"}
    place_service = PlaceService(
        DetailTourClient(detail_response(common), detail_response({})),
        FailingGoogleClient(), cursor_secret="test-secret",
    )

    detail = await place_service.get_place("tourapi:1")

    assert detail.place_id == "tourapi:1"
    assert detail.rating is None
    assert detail.google_attributions is None


def test_google_degradation_log_keeps_status_without_provider_body(caplog) -> None:
    """Google 부분 실패 로그에는 분류 코드와 HTTP 상태만 남긴다."""
    error = GooglePlacesClientError(
        "GOOGLE_PLACES_FAILED",
        status_code=403,
    )

    with caplog.at_level("WARNING", logger="gilpick.place"):
        PlaceService._log_google_degradation("SEARCH_SUPPLEMENT", error)

    record = caplog.records[-1]
    assert record.operation == "SEARCH_SUPPLEMENT"
    assert record.result == "DEGRADED"
    assert record.error_code == "GOOGLE_PLACES_FAILED"
    assert record.provider_status == 403


@pytest.mark.asyncio
@pytest.mark.parametrize("place_id", ["tourapi:missing", "google:missing"])
async def test_detail_maps_empty_provider_result_to_not_found(place_id: str) -> None:
    """기준 provider에 장소가 없으면 PLACE_NOT_FOUND를 반환한다."""
    place_service = PlaceService(
        DetailTourClient(detail_response(None), detail_response(None)),
        DetailGoogleClient({}), cursor_secret="test-secret",
    )

    with pytest.raises(AppError) as error:
        await place_service.get_place(place_id)

    assert error.value.code == "PLACE_NOT_FOUND"
    assert error.value.status_code == 404
