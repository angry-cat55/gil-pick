"""ODsay 검색·형상 adapter 계약 테스트."""

import json
import time
from pathlib import Path

import httpx2
import pytest

from app.clients.odsay import OdsayClient
from app.clients.route_provider import Coordinate, RouteProviderError, TransportMode
from app.core.config import Settings

FIXTURES = Path(__file__).parents[1] / "fixtures" / "odsay"


def settings() -> Settings:
    from tests.unit.test_config import valid_settings
    return Settings(_env_file=None, **valid_settings())


@pytest.mark.asyncio
async def test_odsay_uses_first_recommended_path_and_loads_its_geometry() -> None:
    search = json.loads((FIXTURES / "search_success.json").read_text(encoding="utf-8"))
    lane = json.loads((FIXTURES / "lane_success.json").read_text(encoding="utf-8"))
    calls: list[str] = []

    async def handler(request: httpx2.Request) -> httpx2.Response:
        calls.append(request.url.path)
        assert request.url.params["apiKey"] == "odsay-secret"
        if request.url.path.endswith("searchPubTransPathT"):
            assert request.url.params["OPT"] == "0"
            return httpx2.Response(200, json=search)
        assert request.url.params["mapObject"] == "0:0@2:2:201:205@100:1:20:23"
        return httpx2.Response(200, json=lane)

    client = OdsayClient(settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler)))
    route = await client.calculate(Coordinate(longitude=126.97, latitude=37.57), Coordinate(longitude=126.976, latitude=37.575), TransportMode.TRANSIT, deadline=time.monotonic() + 10)

    assert calls == ["/v1/api/searchPubTransPathT", "/v1/api/loadLane"]
    assert route.duration_seconds == 1500
    assert route.distance_meters == 8400
    assert len(route.coordinates) == 3


@pytest.mark.asyncio
async def test_odsay_shares_deadline_between_search_and_geometry(monkeypatch: pytest.MonkeyPatch) -> None:
    search = json.loads((FIXTURES / "search_success.json").read_text(encoding="utf-8"))
    lane = json.loads((FIXTURES / "lane_success.json").read_text(encoding="utf-8"))
    timeouts: list[float] = []
    async def handler(request: httpx2.Request) -> httpx2.Response:
        timeouts.append(request.extensions["timeout"]["read"])
        return httpx2.Response(200, json=search if request.url.path.endswith("searchPubTransPathT") else lane)
    ticks = iter([100.0, 104.0])
    monkeypatch.setattr("app.clients.odsay.monotonic", lambda: next(ticks))
    client = OdsayClient(settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler)))
    await client.calculate(Coordinate(longitude=126.97, latitude=37.57), Coordinate(longitude=126.976, latitude=37.575), TransportMode.TRANSIT, deadline=108.0)
    assert timeouts == [5.0, 4.0]


@pytest.mark.asyncio
@pytest.mark.parametrize(("payload", "code"), [({"result": {"path": []}}, "ROUTE_NOT_FOUND"), ({"error": {"code": "-98", "msg": "no path"}}, "ROUTE_NOT_FOUND")])
async def test_odsay_classifies_no_route(payload: dict[str, object], code: str) -> None:
    async def handler(_: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(200, json=payload)
    client = OdsayClient(settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler)))
    with pytest.raises(RouteProviderError, match=code):
        await client.calculate(Coordinate(longitude=126.97, latitude=37.57), Coordinate(longitude=126.976, latitude=37.575), TransportMode.TRANSIT, deadline=time.monotonic() + 10)


@pytest.mark.asyncio
@pytest.mark.parametrize(("status", "code", "retryable"), [(400, "ROUTE_PROVIDER_UNAVAILABLE", False), (429, "ROUTE_PROVIDER_RATE_LIMITED", True), (503, "ROUTE_PROVIDER_UNAVAILABLE", True)])
async def test_odsay_classifies_http_errors(status: int, code: str, retryable: bool) -> None:
    async def handler(_: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(status, json={})
    client = OdsayClient(settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler)))
    with pytest.raises(RouteProviderError, match=code) as caught:
        await client.calculate(Coordinate(longitude=126.97, latitude=37.57), Coordinate(longitude=126.976, latitude=37.575), TransportMode.TRANSIT, deadline=time.monotonic() + 10)
    assert caught.value.retryable is retryable


@pytest.mark.asyncio
async def test_odsay_rejects_invalid_geometry() -> None:
    search = json.loads((FIXTURES / "search_success.json").read_text(encoding="utf-8"))
    async def handler(request: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(200, json=search if request.url.path.endswith("searchPubTransPathT") else {"result": {"lane": []}})
    client = OdsayClient(settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler)))
    with pytest.raises(RouteProviderError, match="ROUTE_INVALID_RESULT"):
        await client.calculate(Coordinate(longitude=126.97, latitude=37.57), Coordinate(longitude=126.976, latitude=37.575), TransportMode.TRANSIT, deadline=time.monotonic() + 10)


@pytest.mark.asyncio
async def test_odsay_timeout_is_retryable() -> None:
    async def handler(request: httpx2.Request) -> httpx2.Response:
        raise httpx2.ReadTimeout("timeout", request=request)
    client = OdsayClient(settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler)))
    with pytest.raises(RouteProviderError, match="ROUTE_PROVIDER_TIMEOUT") as caught:
        await client.calculate(Coordinate(longitude=126.97, latitude=37.57), Coordinate(longitude=126.976, latitude=37.575), TransportMode.TRANSIT, deadline=time.monotonic() + 10)
    assert caught.value.retryable
