"""승인된 로컬 key가 있을 때만 실행하는 경로 provider live smoke test."""

from __future__ import annotations

import os
from time import monotonic

import pytest

from app.clients.odsay import OdsayClient
from app.clients.route_provider import Coordinate, Provider, TransportMode
from app.clients.tmap import TmapClient
from app.core.config import Settings


pytestmark = pytest.mark.skipif(
    os.getenv("RUN_ROUTE_PROVIDER_SMOKE") != "1",
    reason="RUN_ROUTE_PROVIDER_SMOKE=1인 명시적 live 검증에서만 실행",
)

ORIGIN = Coordinate(longitude=126.9780, latitude=37.5665)
WALK_DESTINATION = Coordinate(longitude=126.9850, latitude=37.5700)
TRANSIT_DESTINATION = Coordinate(longitude=127.0276, latitude=37.4979)


def _settings() -> Settings:
    return Settings(jwt_signing_secret="route-smoke-only-not-a-real-secret")


@pytest.mark.asyncio
async def test_tmap_representative_walk_route() -> None:
    settings = _settings()
    assert settings.tmap_api_key.get_secret_value()
    client = TmapClient(settings)
    try:
        result = await client.calculate(
            ORIGIN,
            WALK_DESTINATION,
            TransportMode.WALK,
            deadline=monotonic() + settings.route_calculation_deadline_seconds,
        )
    finally:
        await client.close()

    assert result.provider is Provider.TMAP
    assert result.attribution == "TMAP"
    assert result.duration_seconds >= 0
    assert result.distance_meters >= 0
    assert len(result.coordinates) >= 2


@pytest.mark.asyncio
async def test_odsay_representative_transit_route() -> None:
    settings = _settings()
    assert settings.odsay_api_key.get_secret_value()
    client = OdsayClient(settings)
    try:
        result = await client.calculate(
            ORIGIN,
            TRANSIT_DESTINATION,
            TransportMode.TRANSIT,
            deadline=monotonic() + settings.route_calculation_deadline_seconds,
        )
    finally:
        await client.close()

    assert result.provider is Provider.ODSAY
    assert result.attribution == "ODsay"
    assert result.duration_seconds >= 0
    assert result.distance_meters >= 0
    assert len(result.coordinates) >= 2
