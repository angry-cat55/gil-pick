"""날짜별 경로 orchestration의 동시성·deadline·상태 전이를 검증한다."""

from __future__ import annotations

import asyncio
import time
import uuid
from datetime import date

import pytest

from app.clients.route_provider import (
    Coordinate,
    NormalizedRoute,
    Provider,
    RouteProviderError,
    TransportMode,
)
from app.services.route import (
    RouteCalculationService,
    RouteItemSnapshot,
    RouteService,
    RouteSnapshot,
    SingleSegmentResult,
)


class FakeProvider:
    """외부 호출 없이 시도 횟수와 동시 실행을 관찰하는 Provider."""

    def __init__(self, *, provider: Provider, failures: list[RouteProviderError] | None = None) -> None:
        self.provider = provider
        self.failures = list(failures or [])
        self.calls = 0
        self.active = 0
        self.max_active = 0

    async def calculate(
        self,
        origin: Coordinate,
        destination: Coordinate,
        transport_mode: TransportMode,
        *,
        deadline: float,
    ) -> NormalizedRoute:
        self.calls += 1
        self.active += 1
        self.max_active = max(self.max_active, self.active)
        try:
            await asyncio.sleep(0)
            if self.failures:
                raise self.failures.pop(0)
            return NormalizedRoute(
                provider=self.provider,
                transport_mode=transport_mode,
                duration_seconds=100 * self.calls,
                distance_meters=1_000 * self.calls,
                coordinates=[origin, destination],
                attribution=self.provider.value,
            )
        finally:
            self.active -= 1


def _snapshot(count: int, modes: list[TransportMode] | None = None) -> RouteSnapshot:
    item_ids = [uuid.uuid4() for _ in range(count)]
    selected_modes = modes or [TransportMode.WALK] * max(0, count - 1)
    return RouteSnapshot(
        trip_day_id=uuid.uuid4(),
        trip_id=uuid.uuid4(),
        visit_date=date(2026, 9, 6),
        schedule_version=3,
        items=tuple(
            RouteItemSnapshot(
                item_id=item_id,
                sequence=index + 1,
                name=f"장소 {index + 1}",
                coordinate=Coordinate(longitude=127.0 + index / 100, latitude=37.5),
                transport_mode_to_next=(selected_modes[index] if index < count - 1 else None),
            )
            for index, item_id in enumerate(item_ids)
        ),
    )


def _service(
    *,
    tmap: FakeProvider | None = None,
    odsay: FakeProvider | None = None,
    concurrency: int = 3,
    deadline_seconds: float = 10.0,
) -> RouteCalculationService:
    return RouteCalculationService(
        tmap=tmap or FakeProvider(provider=Provider.TMAP),
        odsay=odsay or FakeProvider(provider=Provider.ODSAY),
        concurrency=concurrency,
        deadline_seconds=deadline_seconds,
    )


@pytest.mark.asyncio
async def test_zero_items_is_not_calculated_without_provider_call() -> None:
    tmap = FakeProvider(provider=Provider.TMAP)
    odsay = FakeProvider(provider=Provider.ODSAY)

    result = await _service(tmap=tmap, odsay=odsay).calculate(_snapshot(0))

    assert result.status == "NOT_CALCULATED"
    assert result.route is None and result.failure is None
    assert tmap.calls == odsay.calls == 0


@pytest.mark.asyncio
async def test_one_item_is_ready_with_zero_totals_without_provider_call() -> None:
    tmap = FakeProvider(provider=Provider.TMAP)

    result = await _service(tmap=tmap).calculate(_snapshot(1))

    assert result.status == "READY"
    assert result.route is not None
    assert result.route.total_duration_seconds == 0
    assert result.route.total_distance_meters == 0
    assert result.route.segments == []
    assert tmap.calls == 0


@pytest.mark.asyncio
async def test_segments_keep_itinerary_order_modes_and_totals() -> None:
    snapshot = _snapshot(4, [TransportMode.WALK, TransportMode.TRANSIT, TransportMode.CAR])

    result = await _service().calculate(snapshot)

    assert result.route is not None
    assert [segment.sequence for segment in result.route.segments] == [1, 2, 3]
    assert [segment.transport_mode.value for segment in result.route.segments] == ["WALK", "TRANSIT", "CAR"]
    assert result.route.total_duration_seconds == sum(segment.duration_seconds for segment in result.route.segments)
    assert result.route.total_distance_meters == sum(segment.distance_meters for segment in result.route.segments)


@pytest.mark.asyncio
async def test_at_most_configured_number_of_segments_run_concurrently() -> None:
    tmap = FakeProvider(provider=Provider.TMAP)

    result = await _service(tmap=tmap, concurrency=2).calculate(_snapshot(10))

    assert result.status == "READY"
    assert tmap.calls == 9
    assert tmap.max_active == 2


@pytest.mark.asyncio
async def test_retryable_failure_is_retried_once() -> None:
    tmap = FakeProvider(
        provider=Provider.TMAP,
        failures=[RouteProviderError("ROUTE_PROVIDER_TIMEOUT", retryable=True)],
    )

    result = await _service(tmap=tmap).calculate(_snapshot(2))

    assert result.status == "READY"
    assert tmap.calls == 2


@pytest.mark.asyncio
async def test_final_segment_failure_fails_whole_route_without_partial_result() -> None:
    class LastSegmentFailureProvider(FakeProvider):
        async def calculate(
            self,
            origin: Coordinate,
            destination: Coordinate,
            transport_mode: TransportMode,
            *,
            deadline: float,
        ) -> NormalizedRoute:
            if origin.longitude >= 127.01:
                self.calls += 1
                raise RouteProviderError(
                    "ROUTE_PROVIDER_UNAVAILABLE", retryable=True
                )
            return await super().calculate(
                origin,
                destination,
                transport_mode,
                deadline=deadline,
            )

    tmap = LastSegmentFailureProvider(provider=Provider.TMAP)

    result = await _service(tmap=tmap).calculate(_snapshot(3))

    assert result.status == "FAILED"
    assert result.route is None
    assert result.failure is not None
    assert result.failure.code == "ROUTE_PROVIDER_UNAVAILABLE"


@pytest.mark.asyncio
async def test_multiple_failures_use_earliest_itinerary_segment_code() -> None:
    class ModeFailureProvider(FakeProvider):
        async def calculate(
            self,
            origin: Coordinate,
            destination: Coordinate,
            transport_mode: TransportMode,
            *,
            deadline: float,
        ) -> NormalizedRoute:
            await asyncio.sleep(0)
            code = (
                "ROUTE_NOT_FOUND"
                if origin.longitude < 127.01
                else "ROUTE_INVALID_RESULT"
            )
            raise RouteProviderError(code, retryable=False)

    result = await _service(
        tmap=ModeFailureProvider(provider=Provider.TMAP)
    ).calculate(_snapshot(3))

    assert result.failure is not None
    assert result.failure.code == "ROUTE_NOT_FOUND"


@pytest.mark.asyncio
async def test_global_deadline_returns_typed_timeout_failure() -> None:
    class HangingProvider(FakeProvider):
        async def calculate(self, *args, **kwargs) -> NormalizedRoute:  # type: ignore[no-untyped-def]
            await asyncio.sleep(1)
            return await super().calculate(*args, **kwargs)

    result = await _service(
        tmap=HangingProvider(provider=Provider.TMAP),
        deadline_seconds=0.01,
    ).calculate(_snapshot(2))

    assert result.status == "FAILED"
    assert result.failure is not None
    assert result.failure.code == "ROUTE_PROVIDER_TIMEOUT"


@pytest.mark.asyncio
async def test_all_segments_share_ten_second_deadline_from_mock_clock(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class DeadlineRecordingProvider(FakeProvider):
        def __init__(self) -> None:
            super().__init__(provider=Provider.TMAP)
            self.deadlines: list[float] = []

        async def calculate(self, *args, deadline: float, **kwargs) -> NormalizedRoute:  # type: ignore[no-untyped-def]
            self.deadlines.append(deadline)
            return await super().calculate(*args, deadline=deadline, **kwargs)

    monkeypatch.setattr("app.services.route.monotonic", lambda: 100.0)
    provider = DeadlineRecordingProvider()

    result = await _service(tmap=provider, deadline_seconds=10).calculate(_snapshot(3))

    assert result.status == "READY"
    assert provider.deadlines == [110.0, 110.0]


@pytest.mark.asyncio
async def test_retry_does_not_extend_deadline_from_mock_clock(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class RetryDeadlineProvider(FakeProvider):
        def __init__(self) -> None:
            super().__init__(
                provider=Provider.TMAP,
                failures=[
                    RouteProviderError("ROUTE_PROVIDER_UNAVAILABLE", retryable=True)
                ],
            )
            self.deadlines: list[float] = []

        async def calculate(self, *args, deadline: float, **kwargs) -> NormalizedRoute:  # type: ignore[no-untyped-def]
            self.deadlines.append(deadline)
            return await super().calculate(*args, deadline=deadline, **kwargs)

    clock = iter([100.0, 101.0, 102.0, 103.0, 104.0, 105.0])
    monkeypatch.setattr("app.services.route.monotonic", lambda: next(clock))
    provider = RetryDeadlineProvider()

    result = await _service(tmap=provider, deadline_seconds=10).calculate(_snapshot(2))

    assert result.status == "READY"
    assert provider.deadlines == [110.0, 110.0]


@pytest.mark.asyncio
async def test_unexpected_provider_error_is_normalized() -> None:
    class InvalidProvider(FakeProvider):
        async def calculate(self, *args, **kwargs) -> NormalizedRoute:  # type: ignore[no-untyped-def]
            raise KeyError("malformed provider payload")

    result = await _service(
        tmap=InvalidProvider(provider=Provider.TMAP),
    ).calculate(_snapshot(2))

    assert result.status == "FAILED"
    assert result.failure is not None
    assert result.failure.code == "ROUTE_INVALID_RESULT"


@pytest.mark.asyncio
async def test_route_service_closes_each_shared_provider_once() -> None:
    class CloseableProvider(FakeProvider):
        def __init__(self, *, provider: Provider) -> None:
            super().__init__(provider=provider)
            self.close_calls = 0

        async def close(self) -> None:
            self.close_calls += 1

    tmap = CloseableProvider(provider=Provider.TMAP)
    odsay = CloseableProvider(provider=Provider.ODSAY)
    calculator = _service(tmap=tmap, odsay=odsay)
    service = RouteService(None, calculator)  # type: ignore[arg-type]

    await service.close()

    assert tmap.close_calls == 1
    assert odsay.close_calls == 1


@pytest.mark.asyncio
async def test_route_service_deadline_includes_persistence_and_reload() -> None:
    snapshot = _snapshot(1)

    class SlowPersistenceService(RouteService):
        async def _load_snapshot(self, **kwargs):  # type: ignore[no-untyped-def]
            return snapshot

        async def _persist(self, *args, **kwargs):  # type: ignore[no-untyped-def]
            await asyncio.sleep(1)
            return True

    service = SlowPersistenceService(
        None,  # type: ignore[arg-type]
        _service(deadline_seconds=0.01),
    )

    started_at = time.monotonic()
    result = await service.calculate_current(
        trip_id=snapshot.trip_id,
        visit_date=snapshot.visit_date,
    )

    assert time.monotonic() - started_at < 0.2
    assert result.route_status == "FAILED"
    assert result.failure is not None
    assert result.failure.code == "ROUTE_PROVIDER_TIMEOUT"


def test_snapshot_rejects_changed_version_before_result_activation() -> None:
    snapshot = _snapshot(2)

    assert snapshot.matches_version(snapshot.schedule_version)
    assert not snapshot.matches_version(snapshot.schedule_version + 1)


@pytest.mark.asyncio
async def test_single_segment_returns_only_progress_travel_fields() -> None:
    provider = FakeProvider(provider=Provider.TMAP)
    service = _service(tmap=provider)

    result = await service.calculate_single_segment(
        origin=Coordinate(longitude=127.0, latitude=37.5),
        destination=Coordinate(longitude=127.1, latitude=37.6),
        transport_mode=TransportMode.WALK,
    )

    assert result == SingleSegmentResult(
        duration_seconds=100,
        distance_meters=1_000,
        provider=Provider.TMAP,
    )


@pytest.mark.asyncio
async def test_single_segment_retries_transient_failure_once_with_five_second_attempts(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class DeadlineProvider(FakeProvider):
        def __init__(self) -> None:
            super().__init__(
                provider=Provider.ODSAY,
                failures=[RouteProviderError("ROUTE_PROVIDER_UNAVAILABLE", retryable=True)],
            )
            self.deadlines: list[float] = []

        async def calculate(self, *args, deadline: float, **kwargs) -> NormalizedRoute:  # type: ignore[no-untyped-def]
            self.deadlines.append(deadline)
            return await super().calculate(*args, deadline=deadline, **kwargs)

    clock = iter([100.0, 100.0, 100.0, 101.0, 101.0, 101.0])
    monkeypatch.setattr("app.services.route.monotonic", lambda: next(clock))
    provider = DeadlineProvider()

    result = await _service(odsay=provider).calculate_single_segment(
        origin=Coordinate(longitude=127.0, latitude=37.5),
        destination=Coordinate(longitude=127.1, latitude=37.6),
        transport_mode=TransportMode.TRANSIT,
    )

    assert result.provider is Provider.ODSAY
    assert provider.calls == 2
    assert provider.deadlines == [105.0, 106.0]


@pytest.mark.asyncio
async def test_single_segment_clamps_second_attempt_to_remaining_overall_deadline(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class DeadlineProvider(FakeProvider):
        def __init__(self) -> None:
            super().__init__(
                provider=Provider.TMAP,
                failures=[RouteProviderError("ROUTE_PROVIDER_TIMEOUT", retryable=True)],
            )
            self.deadlines: list[float] = []

        async def calculate(self, *args, deadline: float, **kwargs) -> NormalizedRoute:  # type: ignore[no-untyped-def]
            self.deadlines.append(deadline)
            return await super().calculate(*args, deadline=deadline, **kwargs)

    clock = iter([100.0, 100.0, 105.0, 105.0])
    monkeypatch.setattr("app.services.route.monotonic", lambda: next(clock))
    provider = DeadlineProvider()

    result = await _service(tmap=provider).calculate_single_segment(
        origin=Coordinate(longitude=127.0, latitude=37.5),
        destination=Coordinate(longitude=127.1, latitude=37.6),
        transport_mode=TransportMode.WALK,
    )

    assert result.provider is Provider.TMAP
    assert provider.deadlines == [105.0, 108.0]


@pytest.mark.asyncio
async def test_single_segment_does_not_retry_permanent_failure() -> None:
    provider = FakeProvider(
        provider=Provider.TMAP,
        failures=[RouteProviderError("ROUTE_NOT_FOUND", retryable=False)],
    )

    with pytest.raises(RouteProviderError, match="ROUTE_NOT_FOUND"):
        await _service(tmap=provider).calculate_single_segment(
            origin=Coordinate(longitude=127.0, latitude=37.5),
            destination=Coordinate(longitude=127.1, latitude=37.6),
            transport_mode=TransportMode.CAR,
        )

    assert provider.calls == 1


@pytest.mark.asyncio
async def test_single_segment_enforces_eight_second_overall_deadline() -> None:
    class HangingProvider(FakeProvider):
        async def calculate(self, *args, **kwargs) -> NormalizedRoute:  # type: ignore[no-untyped-def]
            await asyncio.sleep(1)
            return await super().calculate(*args, **kwargs)

    started_at = time.monotonic()
    with pytest.raises(RouteProviderError, match="ROUTE_PROVIDER_TIMEOUT"):
        await _service(tmap=HangingProvider(provider=Provider.TMAP)).calculate_single_segment(
            origin=Coordinate(longitude=127.0, latitude=37.5),
            destination=Coordinate(longitude=127.1, latitude=37.6),
            transport_mode=TransportMode.WALK,
            overall_deadline_seconds=0.01,
        )

    assert time.monotonic() - started_at < 0.2
