from datetime import datetime, timedelta, timezone

import httpx2
import pytest

from app.clients.kma import KmaClient, latest_base_slot, latitude_longitude_to_grid
from app.core.config import Settings


def _settings() -> Settings:
    return Settings(_env_file=None, database_url="postgresql+asyncpg://u:p@localhost/db",
        jwt_signing_secret="x" * 32, jwt_issuer="https://issuer", jwt_audience="app",
        kakao_rest_api_key="k", kakao_client_secret="k", kakao_redirect_uri="https://callback",
        android_app_link_base_url="https://app", android_app_link_host="app",
        tour_api_service_key="k", google_places_api_key="k", kma_service_key="service")


def test_seoul_city_hall_grid_known_value() -> None:
    assert latitude_longitude_to_grid(37.5665, 126.9780) == (60, 127)


def test_latest_base_slot_uses_previous_day_before_first_publication() -> None:
    kst = timezone(timedelta(hours=9))
    assert latest_base_slot(datetime(2026, 9, 8, 1, 0, tzinfo=kst)) == ("20260907", "2300")
    assert latest_base_slot(datetime(2026, 9, 8, 14, 11, tzinfo=kst)) == ("20260908", "1400")


@pytest.mark.asyncio
async def test_kma_groups_forecast_categories_by_slot() -> None:
    async def handler(request: httpx2.Request) -> httpx2.Response:
        assert request.url.params["serviceKey"] == "service"
        return httpx2.Response(200, json={"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[
            {"category":"POP","fcstDate":"20260908","fcstTime":"1500","fcstValue":"80"},
            {"category":"PCP","fcstDate":"20260908","fcstTime":"1500","fcstValue":"1.0mm"},
            {"category":"PTY","fcstDate":"20260908","fcstTime":"1500","fcstValue":"1"}]}}}})
    client = KmaClient(_settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler)))
    result = await client.get_forecast(37.5665, 126.9780)
    assert result and result[0].pop == 80 and result[0].pcp == "1.0mm" and result[0].pty == 1
    assert result[0].forecast_at.utcoffset() == timedelta(hours=9)


@pytest.mark.asyncio
async def test_kma_retries_timeout_once_then_returns_none() -> None:
    calls = 0
    async def handler(request: httpx2.Request) -> httpx2.Response:
        nonlocal calls; calls += 1; raise httpx2.ReadTimeout("timeout", request=request)
    client = KmaClient(_settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler)))
    assert await client.get_forecast(37.5, 127.0) is None
    assert calls == 2


@pytest.mark.asyncio
async def test_kma_empty_key_does_not_call_provider() -> None:
    calls = 0
    async def handler(_: httpx2.Request) -> httpx2.Response:
        nonlocal calls; calls += 1; return httpx2.Response(200, json={})
    settings = _settings().model_copy(update={"kma_service_key": type(_settings().kma_service_key)("")})
    client = KmaClient(settings, httpx2.AsyncClient(transport=httpx2.MockTransport(handler)))
    assert await client.get_forecast(37.5, 127.0) is None and calls == 0
