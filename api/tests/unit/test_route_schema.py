"""F005 경로 응답 schema의 상태별 불변 조건을 검증한다."""

import uuid
from datetime import UTC, date, datetime

import pytest
from pydantic import ValidationError

from app.schemas.itinerary import DayItinerary
from app.schemas.route import (
    FailedRouteData,
    Provider,
    ReadyRouteData,
    RouteFailureCode,
    RouteGeometry,
    RouteModeEstimate,
)


def _ready_payload() -> dict[str, object]:
    first, second = uuid.uuid4(), uuid.uuid4()
    return {
        "tripId": str(uuid.uuid4()), "date": "2026-09-06", "scheduleVersion": 2,
        "routeStatus": "READY", "failure": None,
        "route": {
            "routeId": str(uuid.uuid4()), "scheduleVersion": 2,
            "totalDurationSeconds": 600, "totalDistanceMeters": 800,
            "markers": [
                {"itemId": str(first), "sequence": 1, "name": "경복궁", "latitude": 37.57, "longitude": 126.97},
                {"itemId": str(second), "sequence": 2, "name": "광화문", "latitude": 37.575, "longitude": 126.976},
            ],
            "segments": [{
                "sequence": 1, "fromItemId": str(first), "toItemId": str(second),
                "transportMode": "WALK", "provider": "TMAP", "durationSeconds": 600,
                "distanceMeters": 800,
                "geometry": {"type": "LineString", "coordinates": [[126.97, 37.57], [126.976, 37.575]]},
                "providerAttribution": "TMAP",
            }],
            "providerAttributions": ["TMAP"], "calculatedAt": datetime.now(UTC).isoformat(),
        },
    }


def test_ready_route_accepts_ordered_segments_and_geojson() -> None:
    data = ReadyRouteData.model_validate(_ready_payload())
    assert data.route.total_duration_seconds == 600
    assert data.route.segments[0].geometry.type == "LineString"


def test_segment_walking_fallback_defaults_false_for_stored_routes() -> None:
    """#685: 필드가 없던 예전 저장 경로도 읽히고, 응답에는 기본값 false가 실린다."""
    data = ReadyRouteData.model_validate(_ready_payload())

    assert data.route.segments[0].is_walking_fallback is False
    assert data.model_dump(by_alias=True)["route"]["segments"][0]["isWalkingFallback"] is False


@pytest.mark.parametrize("provider", ["KAKAO", "ODSAY"])
def test_ready_route_accepts_current_and_legacy_transit_providers(provider: str) -> None:
    payload = _ready_payload()
    route = payload["route"]
    assert isinstance(route, dict)
    segments = route["segments"]
    assert isinstance(segments, list)
    segment = segments[0]
    assert isinstance(segment, dict)
    segment["transportMode"] = "TRANSIT"
    segment["provider"] = provider
    segment["providerAttribution"] = provider
    route["providerAttributions"] = [provider]

    data = ReadyRouteData.model_validate(payload)

    assert data.route.segments[0].provider is Provider(provider)


def test_route_geometry_rejects_invalid_or_short_linestring() -> None:
    with pytest.raises(ValidationError):
        RouteGeometry.model_validate({"type": "LineString", "coordinates": [[181, 37]]})


def test_failed_route_requires_failure_and_forbids_route() -> None:
    data = FailedRouteData.model_validate({
        "tripId": str(uuid.uuid4()), "date": "2026-09-06", "scheduleVersion": 2,
        "routeStatus": "FAILED", "route": None,
        "failure": {"code": "ROUTE_PROVIDER_TIMEOUT", "message": "시간을 초과했습니다.", "retryable": True},
    })
    assert data.failure.code is RouteFailureCode.ROUTE_PROVIDER_TIMEOUT
    invalid = _ready_payload()
    invalid["routeStatus"] = "FAILED"
    with pytest.raises(ValidationError):
        FailedRouteData.model_validate(invalid)


def test_day_itinerary_can_embed_ready_route() -> None:
    route_data = ReadyRouteData.model_validate(_ready_payload())
    day = DayItinerary(date=date(2026, 9, 6), day_number=1, version=2, route_status="READY", items=[], route=route_data.route)
    assert day.route is not None


def test_route_mode_estimate_requires_status_specific_fields() -> None:
    ready = RouteModeEstimate.model_validate({
        "transportMode": "WALK", "status": "READY", "durationSeconds": 60,
        "distanceMeters": 100, "provider": "TMAP", "providerAttribution": "TMAP",
        "failure": None,
    })
    assert ready.duration_seconds == 60
    with pytest.raises(ValidationError):
        RouteModeEstimate.model_validate({"transportMode": "CAR", "status": "READY"})
