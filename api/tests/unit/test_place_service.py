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
