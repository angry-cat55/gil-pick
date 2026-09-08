import httpx2
import pytest
from datetime import timedelta
from pydantic import SecretStr

from app.clients.seoul_citydata import CongestionLevel, SeoulCityDataClient
from tests.unit.test_kma_client import _settings


def _city_settings():
    return _settings().model_copy(update={"seoul_citydata_api_key": SecretStr("service")})


@pytest.mark.asyncio
async def test_citydata_maps_current_and_forecast_levels() -> None:
    async def handler(request: httpx2.Request) -> httpx2.Response:
        assert "/service/json/citydata_ppltn/1/5/POI014" in request.url.path
        return httpx2.Response(200, json={"SeoulRtd.citydata_ppltn":[{"AREA_CONGEST_LVL":"약간 붐빔","FCST_PPLTN":[{"FCST_TIME":"2026-09-08 15:00","FCST_CONGEST_LVL":"붐빔"}]}]})
    client = SeoulCityDataClient(_city_settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler)))
    result = await client.get_population("POI014")
    assert result and result.current_level is CongestionLevel.SLIGHTLY_CROWDED
    assert result.forecasts[0].level is CongestionLevel.CROWDED
    assert result.forecasts[0].forecast_at.utcoffset() == timedelta(hours=9)


@pytest.mark.asyncio
async def test_citydata_retries_server_error_once_then_returns_none() -> None:
    calls = 0
    async def handler(_: httpx2.Request) -> httpx2.Response:
        nonlocal calls; calls += 1; return httpx2.Response(503)
    client = SeoulCityDataClient(_city_settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler)))
    assert await client.get_population("POI014") is None
    assert calls == 2
