"""대체 장소 후보 탐색과 외부 변수 평가 파이프라인."""

from __future__ import annotations

import uuid
import logging
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.clients.google_places import GooglePlacesClientError
from app.schemas.alternatives import (
    AlternativeCandidate,
    AlternativeListData,
    OperatingStatus,
    ScoreBreakdown,
)
from app.schemas.detection import CongestionVerdict, WeatherVerdict
from app.schemas.place import PlaceCategory, PlaceSummary
from app.services.alternatives.candidate_token import issue_candidate_token
from app.services.alternatives.policy import (
    MAX_CANDIDATES,
    OPERATING_CHECK_LIMIT,
    SEARCH_RADII_METERS,
    TOUR_API_NUM_OF_ROWS,
)
from app.services.alternatives.scoring import (
    adjusted_ratings,
    candidate_score,
    ranking_key,
)
from app.services.detection.congestion import evaluate_congestion
from app.services.detection.operating_hours_source import (
    BusinessStatus,
    OperatingHours,
    OperatingHoursSource,
)
from app.services.detection.policy import CLOSING_SOON_MINUTES, weather_exposure
from app.services.detection.weather import evaluate_weather
from app.services.place import (
    distance_meters,
    find_match,
    google_place,
    merge_google,
    tour_place,
)


_TOUR_LARGE = {
    PlaceCategory.NATURE: "NA",
    PlaceCategory.HISTORY_CULTURE: "HS",
    PlaceCategory.FOOD: "FD",
    PlaceCategory.CAFE: "FD",
    PlaceCategory.SHOPPING: "SH",
}
logger = logging.getLogger("gilpick.alternatives")


@dataclass
class _EvaluatedCandidate:
    place: PlaceSummary
    distance: float
    congestion: CongestionVerdict
    weather: WeatherVerdict
    operating: OperatingHours = OperatingHours()


class _MemoizedPopulationClient:
    """한 요청 안에서 동일 서울시 구역 조회 결과를 재사용한다."""

    def __init__(self, client: Any) -> None:
        self.client = client
        self.cache: dict[str, Any] = {}

    async def get_population(self, area_code: str) -> Any:
        """구역별 첫 조회 결과 또는 예외를 cache한다."""
        if area_code not in self.cache:
            self.cache[area_code] = await self.client.get_population(area_code)
        return self.cache[area_code]


def _items(payload: dict[str, Any]) -> list[dict[str, Any]]:
    container = payload.get("response", {}).get("body", {}).get("items", {})
    raw = container.get("item", []) if isinstance(container, dict) else []
    if isinstance(raw, dict):
        return [raw]
    return raw if isinstance(raw, list) else []


def _category_stage(
    items: list[tuple[PlaceSummary, float]], origin: PlaceSummary
) -> tuple[list[tuple[PlaceSummary, float]], str | None]:
    category = origin.tour_api_category
    if category is not None and category.small:
        matched = [item for item in items if item[0].tour_api_category and item[0].tour_api_category.small == category.small]
        if matched:
            return matched, "SMALL"
    if category is not None and category.middle:
        matched = [item for item in items if item[0].tour_api_category and item[0].tour_api_category.middle == category.middle]
        if matched:
            return matched, "MIDDLE"
    matched = [item for item in items if item[0].category == origin.category]
    return (matched, "LARGE") if matched else ([], None)


def _operating_status(hours: OperatingHours, eta: datetime) -> OperatingStatus:
    if hours.status in {
        BusinessStatus.CLOSED_TEMPORARILY,
        BusinessStatus.CLOSED_PERMANENTLY,
    } or hours.is_open is False:
        return OperatingStatus.CLOSED
    if hours.is_open is None:
        return OperatingStatus.UNKNOWN
    if hours.closes_at is not None and hours.closes_at <= eta + timedelta(minutes=CLOSING_SOON_MINUTES):
        return OperatingStatus.CLOSING_SOON
    return OperatingStatus.OPEN


async def build_candidates(
    *,
    session: AsyncSession,
    detection_id: uuid.UUID,
    origin: PlaceSummary,
    eta: datetime,
    scheduled_place_ids: set[str],
    tour_client: Any,
    google_client: Any,
    operating_hours_source: OperatingHoursSource,
    kma_client: Any,
    seoul_client: Any,
    candidate_secret: str,
    evaluated_at: datetime,
) -> AlternativeListData:
    """외부 provider를 조합해 저장하지 않는 대체 후보를 만든다."""
    params: dict[str, Any] = {
        "mapX": origin.longitude,
        "mapY": origin.latitude,
        "radius": SEARCH_RADII_METERS[-1],
        "numOfRows": TOUR_API_NUM_OF_ROWS,
    }
    large = _TOUR_LARGE.get(origin.category)
    if large:
        params["lclsSystm1"] = large
        if origin.category is PlaceCategory.CAFE:
            params["lclsSystm2"] = "FD05"
    payload = await tour_client.search_by_location(**params)
    normalized: list[tuple[PlaceSummary, float]] = []
    for raw in _items(payload):
        place = tour_place(raw)
        if place is None or place.latitude is None or place.longitude is None:
            continue
        distance = float(raw.get("dist") or distance_meters(origin, place))
        if place.place_id not in scheduled_place_ids and place.place_id != origin.place_id:
            normalized.append((place, distance))

    selected: list[tuple[PlaceSummary, float]] = []
    radius = SEARCH_RADII_METERS[-1]
    match_level = "NONE"
    for current_radius in SEARCH_RADII_METERS:
        selected, stage = _category_stage(
            [item for item in normalized if item[1] <= current_radius], origin
        )
        if selected:
            radius, match_level = current_radius, stage or "NONE"
            break

    if not selected:
        logger.info(
            "대체 후보 평가 완료",
            extra={
                "detection_id": str(detection_id),
                "search_radius_meters": radius,
                "category_match_level": "NONE",
                "candidate_count": 0,
                "provider_availability": {"tour_api": True},
            },
        )
        return AlternativeListData(
            detection_id=detection_id,
            origin_place_id=origin.place_id,
            eta=eta,
            search_radius_meters=radius,
            category_match_level="NONE",
            evaluated_at=evaluated_at,
            items=[],
        )

    try:
        weather = await evaluate_weather(
            kma_client,
            category=origin.category.value,
            latitude=float(origin.latitude),
            longitude=float(origin.longitude),
            eta=eta,
        )
    except Exception:
        weather = WeatherVerdict(available=False, unavailable_reason="TIMEOUT")

    population_client = _MemoizedPopulationClient(seoul_client)
    evaluated: list[_EvaluatedCandidate] = []
    for place, distance in selected:
        try:
            congestion = await evaluate_congestion(
                session,
                population_client,
                category=place.category.value,
                latitude=float(place.latitude),
                longitude=float(place.longitude),
                eta=eta,
            )
        except Exception:
            congestion = CongestionVerdict(available=False, unavailable_reason="TIMEOUT")
        evaluated.append(_EvaluatedCandidate(place, distance, congestion, weather))

    def preliminary(item: _EvaluatedCandidate) -> tuple[float, float]:
        score = candidate_score(
            distance_meters=item.distance,
            search_radius_meters=radius,
            congestion_level=item.congestion.level if item.congestion.available else None,
            crowded=bool(item.congestion.crowded),
            weather_at_risk=item.weather.at_risk if item.weather.available else None,
        ).score
        return -score, item.distance

    open_candidates: list[_EvaluatedCandidate] = []
    google_available = False
    for item in sorted(evaluated, key=preliminary)[:OPERATING_CHECK_LIMIT]:
        try:
            response = await google_client.search_text(
                item.place.name,
                locationBias={
                    "circle": {
                        "center": {
                            "latitude": item.place.latitude,
                            "longitude": item.place.longitude,
                        },
                        "radius": 50.0,
                    }
                },
                maxResultCount=3,
            )
            google_available = True
            for raw in response.get("places", []):
                google = google_place(raw, item.place.category)
                if google is not None and find_match([item.place], google)[0]:
                    merge_google(item.place, google)
                    item.operating = operating_hours_source.parse(raw, eta)
                    break
        except GooglePlacesClientError:
            pass
        if _operating_status(item.operating, eta) is not OperatingStatus.CLOSED:
            open_candidates.append(item)
            if len(open_candidates) == MAX_CANDIDATES:
                break

    ratings = adjusted_ratings(
        [(item.place.rating, item.place.user_rating_count) for item in open_candidates]
    )
    scored: list[tuple[_EvaluatedCandidate, float | None, Any]] = []
    for item, rating in zip(open_candidates, ratings, strict=True):
        operating_status = _operating_status(item.operating, eta)
        score = candidate_score(
            distance_meters=item.distance,
            search_radius_meters=radius,
            adjusted_rating=rating,
            congestion_level=item.congestion.level if item.congestion.available else None,
            crowded=bool(item.congestion.crowded),
            weather_at_risk=item.weather.at_risk if item.weather.available else None,
            indoor=weather_exposure(item.place.category.value) == "INDOOR",
            closer=item.distance <= radius / 2,
            open_at_eta=operating_status in {OperatingStatus.OPEN, OperatingStatus.CLOSING_SOON},
        )
        scored.append((item, rating, score))
    scored.sort(
        key=lambda entry: ranking_key(
            entry[2].score,
            entry[0].distance,
            entry[1],
            entry[0].place.user_rating_count,
        )
    )

    result = AlternativeListData(
        detection_id=detection_id,
        origin_place_id=origin.place_id,
        eta=eta,
        search_radius_meters=radius,
        category_match_level=match_level,
        evaluated_at=evaluated_at,
        items=[
            AlternativeCandidate(
                rank=index,
                candidate_id=issue_candidate_token(
                    detection_id,
                    item.place.place_id,
                    evaluated_at,
                    candidate_secret,
                ),
                place=item.place,
                distance_meters=round(item.distance),
                adjusted_rating=rating,
                score=score.score,
                display_score=score.display_score,
                score_breakdown=ScoreBreakdown(**score.breakdown),
                operating_status=_operating_status(item.operating, eta),
                closes_at=item.operating.closes_at,
                reasons=list(score.reasons),
            )
            for index, (item, rating, score) in enumerate(scored[:MAX_CANDIDATES], 1)
        ],
    )
    logger.info(
        "대체 후보 평가 완료",
        extra={
            "detection_id": str(detection_id),
            "search_radius_meters": radius,
            "category_match_level": match_level,
            "candidate_count": len(result.items),
            "provider_availability": {
                "tour_api": True,
                "google_places": google_available,
                "kma": weather.available,
                "seoul_citydata": any(item.congestion.available for item in evaluated),
            },
        },
    )
    return result


__all__ = ["build_candidates"]
