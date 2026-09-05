"""TMAP 도보·자동차 adapter 계약 테스트."""

import json
import time
from pathlib import Path

import httpx2
import pytest

from app.clients.route_provider import Coordinate, RouteProviderError, TransportMode
from app.clients.tmap import TmapClient
from app.core.config import Settings

FIXTURES = Path(__file__).parents[1] / "fixtures" / "tmap"


def settings() -> Settings:
    from tests.unit.test_config import valid_settings
    return Settings(_env_file=None, **valid_settings())


@pytest.mark.asyncio
@pytest.mark.parametrize(("mode", "fixture", "path"), [(TransportMode.WALK, "pedestrian_success.json", "/tmap/routes/pedestrian"), (TransportMode.CAR, "car_success.json", "/tmap/routes")])
async def test_tmap_maps_mode_request_and_normalizes_default_route(mode: TransportMode, fixture: str, path: str) -> None:
    payload = json.loads((FIXTURES / fixture).read_text(encoding="utf-8"))

    async def handler(request: httpx2.Request) -> httpx2.Response:
        assert request.url.path == path
        assert request.url.params["version"] == "1"
        assert request.headers["appkey"] == "tmap-secret"
        body = json.loads(request.content)
        assert body["reqCoordType"] == "WGS84GEO"
        assert body["resCoordType"] == "WGS84GEO"
        assert body["searchOption"] == "0"
        return httpx2.Response(200, json=payload)

    client = TmapClient(settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler)))
    route = await client.calculate(Coordinate(longitude=126.97, latitude=37.57), Coordinate(longitude=126.976, latitude=37.575), mode, deadline=time.monotonic() + 10)

    assert route.transport_mode is mode
    assert route.coordinates[0].longitude == 126.97
    assert route.coordinates[-1].longitude == 126.976


@pytest.mark.asyncio
@pytest.mark.parametrize(("status", "code", "retryable"), [(400, "ROUTE_PROVIDER_UNAVAILABLE", False), (429, "ROUTE_PROVIDER_RATE_LIMITED", True), (500, "ROUTE_PROVIDER_UNAVAILABLE", True)])
async def test_tmap_classifies_http_errors(status: int, code: str, retryable: bool) -> None:
    async def handler(_: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(status, json={})
    client = TmapClient(settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler)))
    with pytest.raises(RouteProviderError, match=code) as caught:
        await client.calculate(Coordinate(longitude=126.97, latitude=37.57), Coordinate(longitude=126.976, latitude=37.575), TransportMode.WALK, deadline=time.monotonic() + 10)
    assert caught.value.retryable is retryable


@pytest.mark.asyncio
async def test_tmap_rejects_non_contiguous_or_negative_result() -> None:
    payload = json.loads((FIXTURES / "pedestrian_success.json").read_text(encoding="utf-8"))
    payload["features"][0]["properties"]["totalTime"] = -1
    async def handler(_: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(200, json=payload)
    client = TmapClient(settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler)))
    with pytest.raises(RouteProviderError, match="ROUTE_INVALID_RESULT"):
        await client.calculate(Coordinate(longitude=126.97, latitude=37.57), Coordinate(longitude=126.976, latitude=37.575), TransportMode.WALK, deadline=time.monotonic() + 10)


@pytest.mark.asyncio
async def test_tmap_rejects_out_of_order_geometry() -> None:
    payload = json.loads((FIXTURES / "pedestrian_success.json").read_text(encoding="utf-8"))
    payload["features"][2]["geometry"]["coordinates"][0] = [126.974, 37.574]
    async def handler(_: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(200, json=payload)
    client = TmapClient(settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler)))
    with pytest.raises(RouteProviderError, match="ROUTE_INVALID_RESULT"):
        await client.calculate(Coordinate(longitude=126.97, latitude=37.57), Coordinate(longitude=126.976, latitude=37.575), TransportMode.WALK, deadline=time.monotonic() + 10)


@pytest.mark.asyncio
async def test_tmap_timeout_is_retryable() -> None:
    async def handler(request: httpx2.Request) -> httpx2.Response:
        raise httpx2.ReadTimeout("timeout", request=request)
    client = TmapClient(settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler)))
    with pytest.raises(RouteProviderError, match="ROUTE_PROVIDER_TIMEOUT") as caught:
        await client.calculate(Coordinate(longitude=126.97, latitude=37.57), Coordinate(longitude=126.976, latitude=37.575), TransportMode.CAR, deadline=time.monotonic() + 10)
    assert caught.value.retryable
