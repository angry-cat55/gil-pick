"""대체 장소 후보 탐색·선별 파이프라인 테스트."""

from __future__ import annotations

import json
import logging
import uuid
from datetime import UTC, datetime
from typing import Any

import httpx2
import pytest

from app.clients.google_places import GooglePlacesClient
from app.clients.tour_api import TourApiClient, TourApiClientError
from app.core.config import Settings
from app.core.logging import request_id_context
from app.schemas.detection import CongestionVerdict, WeatherVerdict
from app.schemas.place import PlaceCategory, PlaceSource, PlaceSummary, TourApiCategory
from app.services.alternatives.candidate_token import verify_candidate_token
from app.services.alternatives.candidates import build_candidates
from app.services.detection.operating_hours_source import OperatingHoursSource

ETA = datetime(2026, 9, 9, 12, tzinfo=UTC)
DETECTION_ID = uuid.UUID("00000000-0000-0000-0000-000000000009")
SECRET = "candidate-test-secret-at-least-32-bytes"


def settings() -> Settings:
    return Settings(
        _env_file=None,
        database_url="postgresql+asyncpg://user:password@localhost/gilpick",
        jwt_signing_secret=SECRET,
        jwt_issuer="https://api.gilpick.example",
        jwt_audience="gilpick-android",
        kakao_rest_api_key="rest-key",
        kakao_client_secret="client-secret",
        kakao_redirect_uri="https://api.gilpick.example/api/v1/auth/kakao/callback",
        android_app_link_base_url="https://app.gilpick.example/auth/kakao/complete",
        android_app_link_host="app.gilpick.example",
        tour_api_service_key="tour-key",
        google_places_api_key="google-key",
    )


def origin(*, google_only: bool = False) -> PlaceSummary:
    return PlaceSummary(
        place_id="google:origin" if google_only else "tourapi:origin",
        source=PlaceSource.GOOGLE_PLACES if google_only else PlaceSource.TOUR_API,
        source_place_id="origin",
        name="기존 카페",
        category=PlaceCategory.CAFE,
        tour_api_category=None if google_only else TourApiCategory(
            large="FD", middle="FD05", small="FD050100"
        ),
        address="서울 중구 기준로 1",
        latitude=37.5665,
        longitude=126.9780,
        image_url=None,
        recommended_stay_minutes=60,
        rating=None,
        user_rating_count=None,
        business_status=None,
        regular_opening_hours=None,
        current_opening_hours=None,
        google_attributions=None,
    )


def tour_item(
    number: int,
    distance: int,
    *,
    large: str = "FD",
    middle: str = "FD05",
    small: str = "FD050100",
) -> dict[str, str]:
    return {
        "contentid": str(number),
        "title": f"후보 {number}",
        "addr1": f"서울 중구 후보로 {number}",
        "mapy": "37.5665",
        "mapx": f"{126.9780 + number * 0.000001:.6f}",
        "lclsSystm1": large,
        "lclsSystm2": middle,
        "lclsSystm3": small,
        "dist": str(distance),
    }


def tour_payload(items: list[dict[str, str]]) -> dict[str, Any]:
    return {
        "response": {
            "header": {"resultCode": "0000"},
            "body": {"items": {"item": items}, "totalCount": len(items)},
        }
    }


def google_payload(item: dict[str, str], *, state: str = "OPEN") -> dict[str, Any]:
    if state == "UNKNOWN":
        periods: list[dict[str, Any]] = []
        business_status = "OPERATIONAL"
    else:
        close_hour = 10 if state == "CLOSED" else 23
        periods = [
            {
                "open": {"day": 3, "hour": 0, "minute": 0},
                "close": {"day": 3, "hour": close_hour, "minute": 0},
            }
        ]
        business_status = (
            "CLOSED_TEMPORARILY" if state == "TEMP_CLOSED" else "OPERATIONAL"
        )
    return {
        "places": [
            {
                "id": f"g-{item['contentid']}",
                "displayName": {"text": item["title"]},
                "formattedAddress": item["addr1"],
                "location": {
                    "latitude": float(item["mapy"]),
                    "longitude": float(item["mapx"]),
                },
                "types": ["cafe"],
                "rating": 4.5,
                "userRatingCount": 100,
                "businessStatus": business_status,
                "regularOpeningHours": {"periods": periods},
            }
        ]
    }


def clients(
    items: list[dict[str, str]], states: dict[str, str] | None = None
) -> tuple[TourApiClient, GooglePlacesClient, list[str]]:
    google_calls: list[str] = []

    async def tour_handler(request: httpx2.Request) -> httpx2.Response:
        assert request.url.path.endswith("/locationBasedList2")
        assert request.url.params["radius"] == "2000"
        assert request.url.params["numOfRows"] == "100"
        return httpx2.Response(200, json=tour_payload(items))

    async def google_handler(request: httpx2.Request) -> httpx2.Response:
        body = __import__("json").loads(request.content)
        name = body["textQuery"]
        google_calls.append(name)
        item = next(value for value in items if value["title"] == name)
        return httpx2.Response(
            200, json=google_payload(item, state=(states or {}).get(name, "OPEN"))
        )

    config = settings()
    tour = TourApiClient(
        config, httpx2.AsyncClient(transport=httpx2.MockTransport(tour_handler))
    )
    google = GooglePlacesClient(
        config, httpx2.AsyncClient(transport=httpx2.MockTransport(google_handler))
    )
    return tour, google, google_calls


async def run_pipeline(
    items: list[dict[str, str]],
    *,
    origin_place: PlaceSummary | None = None,
    scheduled_place_ids: set[str] | None = None,
    states: dict[str, str] | None = None,
) -> tuple[Any, list[str]]:
    tour, google, calls = clients(items, states)
    result = await build_candidates(
        session=object(),
        detection_id=DETECTION_ID,
        origin=origin_place or origin(),
        eta=ETA,
        scheduled_place_ids=scheduled_place_ids or set(),
        tour_client=tour,
        google_client=google,
        operating_hours_source=OperatingHoursSource(settings(), google),
        kma_client=object(),
        seoul_client=object(),
        candidate_secret=SECRET,
        evaluated_at=ETA,
    )
    return result, calls


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("items", "origin_place", "radius", "level"),
    [
        ([tour_item(1, 400)], origin(), 500, "SMALL"),
        (
            [tour_item(1, 800, small="FD050200")],
            origin(),
            1000,
            "MIDDLE",
        ),
        ([tour_item(1, 400)], origin(google_only=True), 500, "LARGE"),
    ],
)
async def test_radius_and_category_ladder(
    monkeypatch: pytest.MonkeyPatch,
    items: list[dict[str, str]],
    origin_place: PlaceSummary,
    radius: int,
    level: str,
) -> None:
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_weather",
        _clear_weather,
    )
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_congestion",
        _clear_congestion,
    )

    result, _ = await run_pipeline(items, origin_place=origin_place)

    assert result.search_radius_meters == radius
    assert result.category_match_level == level
    assert [item.rank for item in result.items] == [1]
    claims = verify_candidate_token(result.items[0].candidate_id, SECRET, ETA)
    assert claims is not None
    assert claims.detection_id == DETECTION_ID
    assert claims.place_id == result.items[0].place.place_id
    assert claims.evaluated_at == result.evaluated_at


@pytest.mark.asyncio
async def test_no_match_returns_successful_empty_2km_result(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_weather", _clear_weather
    )
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_congestion", _clear_congestion
    )

    result, calls = await run_pipeline(
        [tour_item(1, 1900, large="NA", middle="NA01", small="NA010100")]
    )

    assert result.search_radius_meters == 2000
    assert result.category_match_level == "NONE"
    assert result.items == []
    assert calls == []


@pytest.mark.asyncio
async def test_origin_and_same_day_places_are_excluded(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_weather", _clear_weather
    )
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_congestion", _clear_congestion
    )
    items = [tour_item(1, 100), tour_item(2, 200), tour_item(3, 300)]
    current = origin().model_copy(
        update={"place_id": "tourapi:1", "source_place_id": "1"}
    )

    result, calls = await run_pipeline(
        items,
        origin_place=current,
        scheduled_place_ids={"tourapi:2"},
    )

    assert [item.place.place_id for item in result.items] == ["tourapi:3"]
    assert calls == ["후보 3"]


@pytest.mark.asyncio
async def test_closed_places_are_skipped_unknown_is_kept_and_ten_are_filled(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_weather", _clear_weather
    )
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_congestion", _clear_congestion
    )
    items = [tour_item(number, 100 + number) for number in range(1, 14)]
    states = {"후보 1": "CLOSED", "후보 2": "TEMP_CLOSED", "후보 3": "UNKNOWN"}

    result, calls = await run_pipeline(items, states=states)

    assert len(result.items) == 10
    assert "tourapi:1" not in {item.place.place_id for item in result.items}
    assert "tourapi:2" not in {item.place.place_id for item in result.items}
    unknown = next(item for item in result.items if item.place.place_id == "tourapi:3")
    assert unknown.operating_status == "UNKNOWN"
    assert unknown.closes_at is None
    assert len(calls) == 12


@pytest.mark.asyncio
async def test_operating_check_stops_at_twenty_without_expanding_radius(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_weather", _clear_weather
    )
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_congestion", _clear_congestion
    )
    items = [tour_item(number, 100 + number) for number in range(1, 31)]

    result, calls = await run_pipeline(
        items, states={item["title"]: "CLOSED" for item in items}
    )

    assert result.items == []
    assert result.search_radius_meters == 500
    assert result.category_match_level == "SMALL"
    assert len(calls) == 20


@pytest.mark.asyncio
async def test_weather_runs_once_and_seoul_population_is_memoized_by_area(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    weather_calls = 0

    async def weather(*args: Any, **kwargs: Any) -> WeatherVerdict:
        nonlocal weather_calls
        weather_calls += 1
        return WeatherVerdict(available=True, at_risk=False)

    class SeoulClient:
        def __init__(self) -> None:
            self.calls: list[str] = []

        async def get_population(self, area_code: str) -> object:
            self.calls.append(area_code)
            return object()

    async def congestion(
        session: object, client: Any, *, longitude: float, **kwargs: Any
    ) -> CongestionVerdict:
        area_code = "A" if longitude < 126.978003 else "B"
        await client.get_population(area_code)
        return CongestionVerdict(
            available=True, level="NORMAL", sensitivity="MEDIUM", crowded=False
        )

    monkeypatch.setattr("app.services.alternatives.candidates.evaluate_weather", weather)
    monkeypatch.setattr("app.services.alternatives.candidates.evaluate_congestion", congestion)
    items = [tour_item(number, 100 + number) for number in range(1, 5)]
    tour, google, _ = clients(items)
    seoul = SeoulClient()

    await build_candidates(
        session=object(), detection_id=DETECTION_ID, origin=origin(), eta=ETA,
        scheduled_place_ids=set(), tour_client=tour, google_client=google,
        operating_hours_source=OperatingHoursSource(settings(), google),
        kma_client=object(), seoul_client=seoul, candidate_secret=SECRET,
        evaluated_at=ETA,
    )

    assert weather_calls == 1
    assert seoul.calls == ["A", "B"]


async def _clear_weather(*args: Any, **kwargs: Any) -> WeatherVerdict:
    return WeatherVerdict(available=True, at_risk=False)


async def _clear_congestion(*args: Any, **kwargs: Any) -> CongestionVerdict:
    return CongestionVerdict(
        available=True, level="RELAXED", sensitivity="MEDIUM", crowded=False
    )


# --- US4: 외부 데이터 결손 격리와 추적 log (T033) ---


def _nature_origin() -> PlaceSummary:
    """야외(NATURE) 기준 장소. 실내 격리와 무관하게 날씨 변수를 유지하는 대조군."""
    return origin(google_only=True).model_copy(update={"category": PlaceCategory.NATURE})


def _nature_items(count: int) -> list[dict[str, str]]:
    return [
        tour_item(number, 200 + number, large="NA", middle="NA01", small="NA0101")
        for number in range(1, count + 1)
    ]


def _google_always_times_out() -> GooglePlacesClient:
    async def handler(request: httpx2.Request) -> httpx2.Response:
        raise httpx2.TimeoutException("timeout", request=request)

    return GooglePlacesClient(
        settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler))
    )


async def _run(
    *,
    items: list[dict[str, str]],
    origin_place: PlaceSummary | None = None,
    tour_client: Any | None = None,
    google_client: Any | None = None,
) -> Any:
    tour, google, _ = clients(items)
    return await build_candidates(
        session=object(),
        detection_id=DETECTION_ID,
        origin=origin_place or origin(),
        eta=ETA,
        scheduled_place_ids=set(),
        tour_client=tour_client or tour,
        google_client=google_client or google,
        operating_hours_source=OperatingHoursSource(settings(), google_client or google),
        kma_client=object(),
        seoul_client=object(),
        candidate_secret=SECRET,
        evaluated_at=ETA,
    )


@pytest.mark.asyncio
async def test_google_all_timeout_keeps_candidates_without_rating_as_unknown(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_weather", _clear_weather
    )
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_congestion", _clear_congestion
    )

    result = await _run(
        items=[tour_item(number, 100 + number) for number in range(1, 4)],
        google_client=_google_always_times_out(),
    )

    assert len(result.items) == 3
    for candidate in result.items:
        assert candidate.adjusted_rating is None
        assert candidate.score_breakdown.rating is None
        assert candidate.operating_status == "UNKNOWN"
        assert candidate.closes_at is None


@pytest.mark.asyncio
async def test_congestion_unavailable_is_isolated_to_that_variable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_weather", _clear_weather
    )

    async def outside_support_area(*args: Any, **kwargs: Any) -> CongestionVerdict:
        return CongestionVerdict(
            available=False, unavailable_reason="NOT_IN_SUPPORT_AREA"
        )

    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_congestion", outside_support_area
    )

    result = await _run(items=[tour_item(1, 300), tour_item(2, 400)])

    assert len(result.items) == 2
    for candidate in result.items:
        assert candidate.score_breakdown.congestion is None


@pytest.mark.asyncio
async def test_indoor_candidate_excludes_weather_even_when_forecast_available(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def sunny(*args: Any, **kwargs: Any) -> WeatherVerdict:
        return WeatherVerdict(available=True, at_risk=False)

    monkeypatch.setattr("app.services.alternatives.candidates.evaluate_weather", sunny)
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_congestion", _clear_congestion
    )

    # 기준·후보 모두 CAFE(실내) → 예보가 있어도 후보 점수에서 weather 제외
    result = await _run(items=[tour_item(1, 300), tour_item(2, 400)])

    assert len(result.items) == 2
    for candidate in result.items:
        assert candidate.score_breakdown.weather is None


@pytest.mark.asyncio
async def test_outdoor_candidate_keeps_weather_when_forecast_available(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def sunny(*args: Any, **kwargs: Any) -> WeatherVerdict:
        return WeatherVerdict(available=True, at_risk=False)

    monkeypatch.setattr("app.services.alternatives.candidates.evaluate_weather", sunny)
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_congestion", _clear_congestion
    )

    result = await _run(items=_nature_items(2), origin_place=_nature_origin())

    assert len(result.items) == 2
    for candidate in result.items:
        assert candidate.score_breakdown.weather == 1.0


@pytest.mark.asyncio
async def test_kma_failure_removes_weather_from_all_candidates_with_success(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def kma_down(*args: Any, **kwargs: Any) -> WeatherVerdict:
        raise RuntimeError("기상청 timeout")

    monkeypatch.setattr("app.services.alternatives.candidates.evaluate_weather", kma_down)
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_congestion", _clear_congestion
    )

    # 야외 후보라 실내 격리가 아닌 기상청 실패로 weather가 빠지는 것을 확인한다
    result = await _run(items=_nature_items(2), origin_place=_nature_origin())

    assert len(result.items) == 2
    for candidate in result.items:
        assert candidate.score_breakdown.weather is None


@pytest.mark.asyncio
async def test_weight_redistribution_when_rating_and_weather_excluded(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_weather", _clear_weather
    )
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_congestion", _clear_congestion
    )

    # Google 실패 → rating 제외, CAFE(실내) → weather 제외. 남는 변수: distance(0.35)+congestion(0.20)
    result = await _run(
        items=[tour_item(1, 250)], google_client=_google_always_times_out()
    )

    candidate = result.items[0]
    distance = candidate.score_breakdown.distance
    congestion = candidate.score_breakdown.congestion
    assert candidate.score_breakdown.rating is None
    assert candidate.score_breakdown.weather is None
    assert candidate.score == pytest.approx(
        100 * (0.35 * distance + 0.20 * congestion) / 0.55
    )


@pytest.mark.asyncio
async def test_single_candidate_google_failure_does_not_affect_others(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_weather", _clear_weather
    )
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_congestion", _clear_congestion
    )
    items = [tour_item(1, 200), tour_item(2, 300), tour_item(3, 400)]

    async def tour_handler(request: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(200, json=tour_payload(items))

    async def google_handler(request: httpx2.Request) -> httpx2.Response:
        name = json.loads(request.content)["textQuery"]
        if name == "후보 2":
            raise httpx2.TimeoutException("timeout", request=request)
        item = next(value for value in items if value["title"] == name)
        return httpx2.Response(200, json=google_payload(item, state="OPEN"))

    config = settings()
    tour = TourApiClient(
        config, httpx2.AsyncClient(transport=httpx2.MockTransport(tour_handler))
    )
    google = GooglePlacesClient(
        config, httpx2.AsyncClient(transport=httpx2.MockTransport(google_handler))
    )
    result = await build_candidates(
        session=object(), detection_id=DETECTION_ID, origin=origin(), eta=ETA,
        scheduled_place_ids=set(), tour_client=tour, google_client=google,
        operating_hours_source=OperatingHoursSource(config, google),
        kma_client=object(), seoul_client=object(), candidate_secret=SECRET,
        evaluated_at=ETA,
    )

    by_id = {candidate.place.place_id: candidate for candidate in result.items}
    assert set(by_id) == {"tourapi:1", "tourapi:2", "tourapi:3"}
    assert by_id["tourapi:2"].operating_status == "UNKNOWN"
    assert by_id["tourapi:2"].score_breakdown.rating is None
    assert by_id["tourapi:1"].score_breakdown.rating is not None
    assert by_id["tourapi:3"].score_breakdown.rating is not None


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("tour_behavior", "expected_code"),
    [("timeout", "TOUR_API_TIMEOUT"), ("server_error", "TOUR_API_FAILED")],
)
async def test_tour_api_failure_raises_instead_of_returning_empty_list(
    monkeypatch: pytest.MonkeyPatch, tour_behavior: str, expected_code: str
) -> None:
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_weather", _clear_weather
    )
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_congestion", _clear_congestion
    )

    async def tour_handler(request: httpx2.Request) -> httpx2.Response:
        if tour_behavior == "timeout":
            raise httpx2.TimeoutException("timeout", request=request)
        return httpx2.Response(503, json={})

    config = settings()
    tour = TourApiClient(
        config, httpx2.AsyncClient(transport=httpx2.MockTransport(tour_handler))
    )
    _, google, _ = clients([tour_item(1, 300)])

    with pytest.raises(TourApiClientError) as raised:
        await build_candidates(
            session=object(), detection_id=DETECTION_ID, origin=origin(), eta=ETA,
            scheduled_place_ids=set(), tour_client=tour, google_client=google,
            operating_hours_source=OperatingHoursSource(config, google),
            kma_client=object(), seoul_client=object(), candidate_secret=SECRET,
            evaluated_at=ETA,
        )

    assert raised.value.code == expected_code


@pytest.mark.asyncio
async def test_pipeline_logs_request_id_radius_stage_count_and_provider_availability(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_weather", _clear_weather
    )
    monkeypatch.setattr(
        "app.services.alternatives.candidates.evaluate_congestion", _clear_congestion
    )

    token = request_id_context.set("11111111-1111-1111-1111-111111111111")
    try:
        with caplog.at_level(logging.INFO, logger="gilpick.alternatives"):
            await _run(items=[tour_item(1, 300)])
    finally:
        request_id_context.reset(token)

    record = next(
        r for r in caplog.records if r.getMessage() == "대체 후보 평가 완료"
    )
    assert record.request_id == "11111111-1111-1111-1111-111111111111"
    assert record.search_radius_meters == 500
    assert record.category_match_level == "SMALL"
    assert record.candidate_count == 1
    assert set(record.provider_availability) == {
        "tour_api", "google_places", "kma", "seoul_citydata"
    }
