"""수단별 예상값은 서로 독립적이며 정식 경로를 바꾸지 않는다."""

import asyncio
from dataclasses import replace

import pytest

from app.api.errors import AppError
from app.clients.route_provider import Provider, RouteProviderError, TransportMode
from tests.unit.test_route_service import FakeProvider, _service, _snapshot


@pytest.mark.asyncio
async def test_estimates_keep_successes_when_transit_fails():
    transit = FakeProvider(provider=Provider.KAKAO, failures=[
        RouteProviderError("ROUTE_NOT_FOUND", retryable=False),
    ])
    snapshot = _snapshot(2)
    calculator = _service(transit=transit)

    values = await calculator.calculate_estimates(snapshot, sequence=1)

    assert [value.transport_mode for value in values] == ["WALK", "TRANSIT", "CAR"]
    assert [value.status for value in values] == ["READY", "FAILED", "READY"]
    assert values[0].duration_seconds > 0
    assert values[0].distance_meters > 0
    assert values[0].provider == "TMAP"
    assert values[0].provider_attribution == "TMAP"
    assert values[1].duration_seconds is None
    assert values[1].distance_meters is None
    assert values[1].failure.code == "ROUTE_NOT_FOUND"
    assert snapshot.items[0].transport_mode_to_next == TransportMode.WALK


@pytest.mark.asyncio
async def test_estimates_share_bounded_concurrency_and_keep_ready_on_deadline():
    class SlowTransit(FakeProvider):
        async def calculate(self, *args, **kwargs):
            await asyncio.sleep(10)

    tmap = FakeProvider(provider=Provider.TMAP)
    calculator = _service(tmap=tmap, transit=SlowTransit(provider=Provider.KAKAO),
                          concurrency=2, deadline_seconds=0.05)

    values = await calculator.calculate_estimates(_snapshot(2), sequence=1)

    assert [value.status for value in values] == ["READY", "FAILED", "READY"]
    assert values[1].failure.code == "ROUTE_PROVIDER_TIMEOUT"
    assert tmap.max_active <= 2


@pytest.mark.asyncio
async def test_cancelling_estimates_cancels_provider_calls():
    cancelled = asyncio.Event()

    class BlockingProvider(FakeProvider):
        async def calculate(self, *args, **kwargs):
            try:
                await asyncio.Event().wait()
            finally:
                cancelled.set()

    calculator = _service(
        tmap=BlockingProvider(provider=Provider.TMAP),
        transit=BlockingProvider(provider=Provider.KAKAO),
    )
    task = asyncio.create_task(calculator.calculate_estimates(_snapshot(2), sequence=1))
    await asyncio.sleep(0)

    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task

    assert cancelled.is_set()


@pytest.mark.asyncio
async def test_only_missing_modes_are_calculated_and_retry_once():
    transit = FakeProvider(provider=Provider.KAKAO, failures=[
        RouteProviderError("ROUTE_PROVIDER_UNAVAILABLE", retryable=True),
    ])
    tmap = FakeProvider(provider=Provider.TMAP)
    values = await _service(tmap=tmap, transit=transit).calculate_estimates(
        _snapshot(2), sequence=1, modes=[TransportMode.TRANSIT],
    )
    assert len(values) == 1
    assert values[0].status == "READY"
    assert transit.calls == 2
    assert tmap.calls == 0


@pytest.mark.asyncio
@pytest.mark.parametrize("count,sequence", [(0, 1), (1, 1), (2, 2), (2, 0)])
async def test_nonexistent_segment_is_rejected_before_provider(count, sequence):
    provider = FakeProvider(provider=Provider.TMAP)
    with pytest.raises(AppError) as caught:
        await _service(tmap=provider).calculate_estimates(_snapshot(count), sequence=sequence)
    assert caught.value.code == "ROUTE_SEGMENT_NOT_FOUND"
    assert provider.calls == 0


@pytest.mark.asyncio
async def test_provider_mode_mismatch_fails_only_affected_estimate():
    class WrongMode(FakeProvider):
        async def calculate(self, *args, **kwargs):
            value = await super().calculate(*args, **kwargs)
            return value.model_copy(update={"transport_mode": TransportMode.CAR})

    values = await _service(transit=WrongMode(provider=Provider.KAKAO)).calculate_estimates(
        _snapshot(2), sequence=1,
    )
    assert [value.status for value in values] == ["READY", "FAILED", "READY"]
    assert values[1].failure.code == "ROUTE_INVALID_RESULT"
