"""Kakao Maps 대중교통 경로 adapter 계약 테스트."""

import time

import httpx2
import pytest

from app.clients.kakao_transit import KakaoTransitClient
from app.clients.route_provider import (
    Coordinate,
    Provider,
    RouteProviderError,
    TransportMode,
)
from app.core.config import Settings


def settings() -> Settings:
    from tests.unit.test_config import valid_settings

    return Settings(_env_file=None, **valid_settings())


def success_payload() -> dict[str, object]:
    """공식 응답 필드 중 길픽 변환에 필요한 최소 구조를 반환한다."""
    return {
        "status": "OK",
        "properties": {"total": 15},
        "routes": [
            {
                "properties": {
                    "type": "BUS_AND_SUBWAY",
                    "totalDistance": 12_300,
                    "totalTime": 2_400,
                    "transfers": 1,
                },
                "steps": [
                    {
                        "properties": {"type": "WALKING"},
                        "path": {
                            "points": [
                                [126.9780, 37.5665],
                                [126.9800, 37.5650],
                            ]
                        },
                    },
                    {
                        "properties": {"type": "SUBWAY"},
                        "path": {
                            "points": [
                                [126.9800, 37.5650],
                                [127.0276, 37.4979],
                            ]
                        },
                    },
                ],
            }
        ],
    }


@pytest.mark.asyncio
async def test_kakao_uses_first_route_and_normalizes_geometry() -> None:
    async def handler(request: httpx2.Request) -> httpx2.Response:
        assert request.url.path == "/v2/routing/publictraffic"
        assert request.headers["Authorization"] == "KakaoAK test-rest-key"
        assert request.url.params["start_x"] == "126.978"
        assert request.url.params["end_y"] == "37.4979"
        return httpx2.Response(200, json=success_payload())

    client = KakaoTransitClient(
        settings(),
        httpx2.AsyncClient(transport=httpx2.MockTransport(handler)),
    )
    route = await client.calculate(
        Coordinate(longitude=126.9780, latitude=37.5665),
        Coordinate(longitude=127.0276, latitude=37.4979),
        TransportMode.TRANSIT,
        deadline=time.monotonic() + 10,
    )

    assert route.provider is Provider.KAKAO
    assert route.duration_seconds == 2_400
    assert route.distance_meters == 12_300
    assert route.attribution == "Kakao Maps"
    assert [(point.longitude, point.latitude) for point in route.coordinates] == [
        (126.9780, 37.5665),
        (126.9800, 37.5650),
        (127.0276, 37.4979),
    ]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "status",
    ["STARTNODES_NULL", "ENDNODES_NULL", "EQUAL_POINTS", "NO_RESULTS"],
)
async def test_kakao_classifies_missing_route(status: str) -> None:
    async def handler(_: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(200, json={"status": status})

    client = KakaoTransitClient(
        settings(),
        httpx2.AsyncClient(transport=httpx2.MockTransport(handler)),
    )
    with pytest.raises(RouteProviderError, match="ROUTE_NOT_FOUND") as caught:
        await client.calculate(
            Coordinate(longitude=126.9780, latitude=37.5665),
            Coordinate(longitude=127.0276, latitude=37.4979),
            TransportMode.TRANSIT,
            deadline=time.monotonic() + 10,
        )
    assert not caught.value.retryable


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("status", "code", "retryable"),
    [
        (400, "ROUTE_PROVIDER_UNAVAILABLE", False),
        (429, "ROUTE_PROVIDER_RATE_LIMITED", True),
        (503, "ROUTE_PROVIDER_UNAVAILABLE", True),
    ],
)
async def test_kakao_classifies_http_errors(
    status: int,
    code: str,
    retryable: bool,
) -> None:
    async def handler(_: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(status, json={})

    client = KakaoTransitClient(
        settings(),
        httpx2.AsyncClient(transport=httpx2.MockTransport(handler)),
    )
    with pytest.raises(RouteProviderError, match=code) as caught:
        await client.calculate(
            Coordinate(longitude=126.9780, latitude=37.5665),
            Coordinate(longitude=127.0276, latitude=37.4979),
            TransportMode.TRANSIT,
            deadline=time.monotonic() + 10,
        )
    assert caught.value.retryable is retryable


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "payload",
    [
        {"status": "OK", "routes": []},
        {"status": "INVALID_REQUEST"},
        {
            "status": "OK",
            "routes": [
                {
                    "properties": {
                        "totalDistance": 1,
                        "totalTime": 1,
                    },
                    "steps": [{"path": {"points": []}}],
                }
            ],
        },
    ],
)
async def test_kakao_rejects_invalid_result(payload: dict[str, object]) -> None:
    async def handler(_: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(200, json=payload)

    client = KakaoTransitClient(
        settings(),
        httpx2.AsyncClient(transport=httpx2.MockTransport(handler)),
    )
    with pytest.raises(RouteProviderError, match="ROUTE_INVALID_RESULT"):
        await client.calculate(
            Coordinate(longitude=126.9780, latitude=37.5665),
            Coordinate(longitude=127.0276, latitude=37.4979),
            TransportMode.TRANSIT,
            deadline=time.monotonic() + 10,
        )


@pytest.mark.asyncio
async def test_kakao_timeout_is_retryable() -> None:
    async def handler(request: httpx2.Request) -> httpx2.Response:
        raise httpx2.ReadTimeout("timeout", request=request)

    client = KakaoTransitClient(
        settings(),
        httpx2.AsyncClient(transport=httpx2.MockTransport(handler)),
    )
    with pytest.raises(RouteProviderError, match="ROUTE_PROVIDER_TIMEOUT") as caught:
        await client.calculate(
            Coordinate(longitude=126.9780, latitude=37.5665),
            Coordinate(longitude=127.0276, latitude=37.4979),
            TransportMode.TRANSIT,
            deadline=time.monotonic() + 10,
        )
    assert caught.value.retryable
