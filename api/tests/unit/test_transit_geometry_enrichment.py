"""대중교통 단계 사이의 실제 보행 geometry 보완 정책을 검증한다."""

from __future__ import annotations

import uuid
from datetime import date

import pytest

from app.clients.route_provider import (
    Coordinate,
    NormalizedRoute,
    NormalizedTransitStep,
    Provider,
    RouteProviderError,
    TransitStepType,
    TransportMode,
)
from app.services.route import (
    RouteCalculationService,
    RouteItemSnapshot,
    RouteSnapshot,
)


def point(longitude: float) -> Coordinate:
    return Coordinate(longitude=longitude, latitude=37.5)


def step(
    step_type: TransitStepType,
    start: float,
    end: float,
) -> NormalizedTransitStep:
    return NormalizedTransitStep(
        type=step_type,
        duration_seconds=60,
        distance_meters=100,
        geometry=[point(start), point(end)],
    )


def transit_route(steps: list[NormalizedTransitStep]) -> NormalizedRoute:
    coordinates = [coordinate for item in steps for coordinate in (item.geometry or [])]
    return NormalizedRoute(
        provider=Provider.KAKAO,
        transport_mode=TransportMode.TRANSIT,
        duration_seconds=600,
        distance_meters=5_000,
        coordinates=coordinates,
        attribution="Kakao Maps",
        steps=steps,
    )


class FixedTransitProvider:
    def __init__(self, route: NormalizedRoute) -> None:
        self.route = route
        self.calls = 0

    async def calculate(self, *args, **kwargs) -> NormalizedRoute:  # type: ignore[no-untyped-def]
        self.calls += 1
        return self.route


class WalkingProvider:
    def __init__(
        self,
        failure: RouteProviderError | None = None,
        *,
        endpoint_offset: float = 0.0,
    ) -> None:
        self.failure = failure
        self.endpoint_offset = endpoint_offset
        self.calls: list[tuple[Coordinate, Coordinate]] = []

    async def calculate(
        self,
        origin: Coordinate,
        destination: Coordinate,
        transport_mode: TransportMode,
        *,
        deadline: float,
    ) -> NormalizedRoute:
        assert transport_mode is TransportMode.WALK
        self.calls.append((origin, destination))
        if self.failure is not None:
            raise self.failure
        midpoint = Coordinate(
            longitude=(origin.longitude + destination.longitude) / 2,
            latitude=(origin.latitude + destination.latitude) / 2,
        )
        snapped_origin = Coordinate(
            longitude=origin.longitude + self.endpoint_offset,
            latitude=origin.latitude,
        )
        snapped_destination = Coordinate(
            longitude=destination.longitude - self.endpoint_offset,
            latitude=destination.latitude,
        )
        return NormalizedRoute(
            provider=Provider.TMAP,
            transport_mode=TransportMode.WALK,
            duration_seconds=999,
            distance_meters=999,
            coordinates=[snapped_origin, midpoint, snapped_destination],
            attribution="TMAP",
        )


def snapshot(origin: float = 127.0, destination: float = 127.013) -> RouteSnapshot:
    return RouteSnapshot(
        trip_day_id=uuid.uuid4(),
        trip_id=uuid.uuid4(),
        visit_date=date(2026, 9, 17),
        schedule_version=1,
        items=(
            RouteItemSnapshot(
                item_id=uuid.uuid4(), sequence=1, name="출발 장소",
                coordinate=point(origin), transport_mode_to_next=TransportMode.TRANSIT,
            ),
            RouteItemSnapshot(
                item_id=uuid.uuid4(), sequence=2, name="도착 장소",
                coordinate=point(destination), transport_mode_to_next=None,
            ),
        ),
    )


def service(route: NormalizedRoute, walking: WalkingProvider) -> RouteCalculationService:
    return RouteCalculationService(
        tmap=walking,
        transit=FixedTransitProvider(route),
        concurrency=3,
        deadline_seconds=10,
    )


@pytest.mark.asyncio
async def test_transit_gaps_are_filled_with_tmap_geometry_without_changing_kakao_totals() -> None:
    route = transit_route([
        step(TransitStepType.WALK, 127.001, 127.002),
        step(TransitStepType.BUS, 127.002, 127.010),
        step(TransitStepType.WALK, 127.011, 127.012),
    ])
    walking = WalkingProvider()

    result = await service(route, walking).calculate(snapshot())

    assert result.status == "READY"
    assert result.route is not None
    segment = result.route.segments[0]
    assert segment.duration_seconds == 600
    assert segment.distance_meters == 5_000
    assert segment.provider_attribution == "Kakao Maps · TMAP"
    assert len(walking.calls) == 3
    assert segment.geometry.coordinates[0] == (127.0, 37.5)
    assert segment.geometry.coordinates[-1] == (127.013, 37.5)
    assert segment.steps[0].geometry is not None
    assert segment.steps[0].geometry.coordinates[0] == (127.0, 37.5)
    assert segment.steps[2].geometry is not None
    assert segment.steps[2].geometry.coordinates[0] == (127.010, 37.5)
    assert segment.steps[2].geometry.coordinates[-1] == (127.013, 37.5)


@pytest.mark.asyncio
async def test_sub_three_meter_coordinate_error_is_snapped_without_tmap_call() -> None:
    route = transit_route([
        step(TransitStepType.WALK, 127.0, 127.002),
        step(TransitStepType.BUS, 127.002001, 127.010),
        step(TransitStepType.WALK, 127.010001, 127.013),
    ])
    walking = WalkingProvider()

    result = await service(route, walking).calculate(snapshot())

    assert result.status == "READY"
    assert result.route is not None
    assert walking.calls == []
    coordinates = result.route.segments[0].geometry.coordinates
    assert coordinates.count((127.002, 37.5)) == 1
    assert coordinates.count((127.010, 37.5)) == 1
    assert (127.002001, 37.5) not in coordinates
    assert (127.010001, 37.5) not in coordinates


@pytest.mark.asyncio
async def test_gap_without_adjacent_walk_step_fails_instead_of_drawing_straight_line() -> None:
    route = transit_route([
        step(TransitStepType.BUS, 127.0, 127.006),
        step(TransitStepType.SUBWAY, 127.007, 127.013),
    ])
    walking = WalkingProvider()

    result = await service(route, walking).calculate(snapshot())

    assert result.status == "FAILED"
    assert result.failure is not None
    assert result.failure.code == "ROUTE_INVALID_RESULT"
    assert walking.calls == []


@pytest.mark.asyncio
async def test_tmap_enrichment_failure_fails_entire_route() -> None:
    route = transit_route([
        step(TransitStepType.WALK, 127.001, 127.013),
    ])
    walking = WalkingProvider(
        RouteProviderError("ROUTE_PROVIDER_UNAVAILABLE", retryable=False)
    )

    result = await service(route, walking).calculate(snapshot())

    assert result.status == "FAILED"
    assert result.failure is not None
    assert result.failure.code == "ROUTE_PROVIDER_UNAVAILABLE"
    assert len(walking.calls) == 1


@pytest.mark.asyncio
async def test_tmap_road_snap_within_access_tolerance_is_normalized_to_requested_endpoints() -> None:
    route = transit_route([
        step(TransitStepType.WALK, 127.001, 127.013),
    ])
    walking = WalkingProvider(endpoint_offset=0.0001)  # 서울 위도 기준 약 9m

    result = await service(route, walking).calculate(snapshot())

    assert result.status == "READY"
    assert result.route is not None
    geometry = result.route.segments[0].geometry.coordinates
    assert geometry[0] == (127.0, 37.5)
    assert geometry[-1] == (127.013, 37.5)


@pytest.mark.asyncio
async def test_tmap_road_snap_outside_access_tolerance_is_rejected(caplog) -> None:  # type: ignore[no-untyped-def]
    route = transit_route([
        step(TransitStepType.WALK, 127.001, 127.013),
    ])
    walking = WalkingProvider(endpoint_offset=0.001)  # 서울 위도 기준 약 88m

    result = await service(route, walking).calculate(snapshot())

    assert result.status == "FAILED"
    assert result.failure is not None
    assert result.failure.code == "ROUTE_INVALID_RESULT"
    rejection = next(
        record
        for record in caplog.records
        if getattr(record, "stage", None) == "tmap_endpoint_validation"
    )
    assert rejection.result_code == "ROUTE_INVALID_RESULT"
    assert rejection.start_error_meters > 30
    assert rejection.end_error_meters > 30
    assert not hasattr(rejection, "start_coordinate")
    assert not hasattr(rejection, "end_coordinate")
