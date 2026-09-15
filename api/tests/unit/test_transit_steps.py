"""Kakao 상세 단계의 정규화·오류 격리·저장 호환성을 검증한다."""

import time
from types import SimpleNamespace

import httpx2
import pytest

from app.clients.kakao_transit import KakaoTransitClient
from app.clients.route_provider import Coordinate, RouteProviderError, TransportMode
from app.services.route import _route_values, route_data_from_model
from tests.unit.test_kakao_transit_client import settings, success_payload
from tests.unit.test_route_service import _service, _snapshot


def detailed_payload():
    """공식 StepProperties 구조에 가상의 정류장·노선 정보를 채운다."""
    payload = success_payload()
    steps = payload["routes"][0]["steps"]
    steps[0]["properties"] = {"type": "WALKING", "time": 60, "distance": 80}
    steps[1]["properties"] = {
        "type": "BUS", "time": 600, "distance": 2000,
        "stops": [{"name": "출발 정류장"}, {"name": "환승 정류장"}],
        "vehicles": [{"name": "701", "type": "간선"}, {"name": "702", "type": "간선"}],
    }
    steps.append({
        "properties": {
            "type": "SUBWAY", "time": 1740, "distance": 10220,
            "stops": [{"name": "환승역"}, {"name": "중간역"}, {"name": "도착역"}],
            "vehicles": [{"name": "2호선", "type": "일반"}],
        },
        "path": {"points": [[127.0276, 37.4979], [127.03, 37.5]]},
    })
    return payload


def client_for(payload):
    return KakaoTransitClient(settings(), httpx2.AsyncClient(
        transport=httpx2.MockTransport(lambda _: httpx2.Response(200, json=payload)),
    ))


async def calculate(payload):
    client = client_for(payload)
    try:
        return await client.calculate(
            Coordinate(longitude=126.978, latitude=37.5665),
            Coordinate(longitude=127.03, latitude=37.5),
            TransportMode.TRANSIT,
            deadline=time.monotonic() + 10,
        )
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_official_step_properties_preserve_order_units_and_names():
    route = await calculate(detailed_payload())
    assert route.model_dump().get("steps") == [
        {"type": "WALK", "duration_seconds": 60, "distance_meters": 80,
         "boarding_name": None, "alighting_name": None, "line_name": None, "stop_count": None},
        {"type": "BUS", "duration_seconds": 600, "distance_meters": 2000,
         "boarding_name": "출발 정류장", "alighting_name": "환승 정류장",
         "line_name": "701 / 702", "stop_count": None},
        {"type": "SUBWAY", "duration_seconds": 1740, "distance_meters": 10220,
         "boarding_name": "환승역", "alighting_name": "도착역",
         "line_name": "2호선", "stop_count": None},
    ]
    assert (route.duration_seconds, route.distance_meters) == (2400, 12300)


@pytest.mark.asyncio
@pytest.mark.parametrize("properties", [None, {}, {"type": "BUS"},
    {"type": "FERRY", "time": 60, "distance": 80},
    {"type": "BUS", "time": -1, "distance": 80},
    {"type": "BUS", "time": True, "distance": 80},
    {"type": "BUS", "time": "60", "distance": 80},
    {"type": "BUS", "time": 60, "distance": 1.5},
    {"type": "BUS", "time": 60, "distance": 80, "stops": "bad"},
    {"type": "BUS", "time": 60, "distance": 80, "vehicles": [{"name": 123}]},
])
async def test_bad_metadata_discards_all_steps_but_preserves_geometry(properties):
    payload = detailed_payload()
    payload["routes"][0]["steps"][1]["properties"] = properties
    route = await calculate(payload)
    assert route.model_dump().get("steps") == []
    assert len(route.coordinates) == 4
    assert route.duration_seconds == 2400


@pytest.mark.asyncio
@pytest.mark.parametrize("vehicle", [{"name": "701"}, {"name": "701", "type": 1}])
async def test_unused_vehicle_type_does_not_discard_valid_name(vehicle):
    payload = detailed_payload()
    payload["routes"][0]["steps"][1]["properties"]["vehicles"] = [vehicle]

    route = await calculate(payload)

    assert route.steps[1].line_name == "701"


@pytest.mark.asyncio
async def test_legacy_provider_payload_and_absent_optional_metadata_are_supported():
    assert (await calculate(success_payload())).model_dump().get("steps") == []
    payload = detailed_payload()
    for step in payload["routes"][0]["steps"]:
        step["properties"].pop("stops", None)
        step["properties"].pop("vehicles", None)
    steps = (await calculate(payload)).model_dump().get("steps")
    assert steps is not None and len(steps) == 3
    assert all(step["boarding_name"] is None and step["line_name"] is None for step in steps)


@pytest.mark.asyncio
@pytest.mark.parametrize("path", [None, {}, {"points": []}, {"points": [[127, 37]]},
    {"points": [["bad", 37], [127, 38]]}])
async def test_valid_metadata_never_replaces_missing_or_invalid_geometry(path):
    payload = detailed_payload()
    payload["routes"][0]["steps"][1]["path"] = path
    with pytest.raises(RouteProviderError, match="ROUTE_INVALID_RESULT"):
        await calculate(payload)


@pytest.mark.asyncio
@pytest.mark.parametrize("metadata_valid", [True, False])
async def test_steps_survive_mixed_route_jsonb_write_and_read(metadata_valid):
    payload = detailed_payload()
    if not metadata_valid:
        payload["routes"][0]["steps"][1]["properties"] = {}
    client = client_for(payload)
    snapshot = _snapshot(4, [TransportMode.WALK, TransportMode.TRANSIT, TransportMode.CAR])
    try:
        result = await _service(transit=client).calculate(snapshot)
    finally:
        await client.close()
    assert result.status == "READY"
    values = _route_values(snapshot, result)
    restored = route_data_from_model(
        SimpleNamespace(trip_id=snapshot.trip_id, visit_date=snapshot.visit_date,
                        schedule_version=snapshot.schedule_version),
        SimpleNamespace(**values),
    ).model_dump(mode="json", by_alias=True)["route"]
    segments = restored["segments"]
    assert segments[0].get("steps") == segments[2].get("steps") == []
    transit = segments[1].get("steps")
    if metadata_valid:
        assert transit is not None and len(transit) == 3
        assert transit[1] == {"type": "BUS", "boardingName": "출발 정류장",
            "alightingName": "환승 정류장", "lineName": "701 / 702",
            "durationSeconds": 600, "distanceMeters": 2000, "stopCount": None}
    else:
        assert transit == []

    for segment in values["route_payload"]["segments"]:
        segment.pop("steps", None)
    legacy = route_data_from_model(
        SimpleNamespace(trip_id=snapshot.trip_id, visit_date=snapshot.visit_date,
                        schedule_version=snapshot.schedule_version),
        SimpleNamespace(**values),
    ).model_dump(mode="json", by_alias=True)["route"]
    assert all(segment.get("steps") == [] for segment in legacy["segments"])
