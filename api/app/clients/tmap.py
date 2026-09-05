"""TMAP 보행자·자동차 경로 adapter."""

from __future__ import annotations

from time import monotonic
from typing import Any

import httpx2
from pydantic import ValidationError

from app.clients.route_provider import Coordinate, NormalizedRoute, Provider, RouteProviderError, TransportMode
from app.core.config import Settings


class TmapClient:
    """TMAP의 기본 추천 경로 하나를 공통 형식으로 변환한다."""

    def __init__(self, settings: Settings, client: httpx2.AsyncClient | None = None) -> None:
        self.settings = settings
        self.client = client or httpx2.AsyncClient()

    async def calculate(self, origin: Coordinate, destination: Coordinate, transport_mode: TransportMode, *, deadline: float) -> NormalizedRoute:
        if transport_mode not in (TransportMode.WALK, TransportMode.CAR):
            raise ValueError("TMAP supports WALK and CAR only")
        path = "/tmap/routes/pedestrian" if transport_mode is TransportMode.WALK else "/tmap/routes"
        body = {
            "startX": str(origin.longitude), "startY": str(origin.latitude),
            "endX": str(destination.longitude), "endY": str(destination.latitude),
            "startName": "출발지", "endName": "도착지",
            "reqCoordType": "WGS84GEO", "resCoordType": "WGS84GEO", "searchOption": "0",
        }
        payload = await self._request(path, body, deadline)
        return self._normalize(payload, transport_mode)

    async def _request(self, path: str, body: dict[str, str], deadline: float) -> dict[str, Any]:
        remaining = deadline - monotonic()
        if remaining <= 0:
            raise RouteProviderError("ROUTE_PROVIDER_TIMEOUT", retryable=True)
        try:
            response = await self.client.post(
                f"{self.settings.tmap_base_url}{path}", params={"version": "1", "format": "json"},
                headers={"appKey": self.settings.tmap_api_key.get_secret_value(), "Accept": "application/json"},
                json=body, timeout=min(self.settings.route_provider_timeout_seconds, remaining),
            )
        except httpx2.TimeoutException as exc:
            raise RouteProviderError("ROUTE_PROVIDER_TIMEOUT", retryable=True) from exc
        except httpx2.RequestError as exc:
            raise RouteProviderError("ROUTE_PROVIDER_UNAVAILABLE", retryable=True) from exc
        if response.status_code == 429:
            raise RouteProviderError("ROUTE_PROVIDER_RATE_LIMITED", retryable=True)
        if response.status_code >= 400:
            raise RouteProviderError("ROUTE_PROVIDER_UNAVAILABLE", retryable=response.status_code >= 500)
        try:
            payload = response.json()
        except (TypeError, ValueError) as exc:
            raise RouteProviderError("ROUTE_INVALID_RESULT", retryable=False) from exc
        if not isinstance(payload, dict):
            raise RouteProviderError("ROUTE_INVALID_RESULT", retryable=False)
        return payload

    @staticmethod
    def _normalize(payload: dict[str, Any], mode: TransportMode) -> NormalizedRoute:
        try:
            features = payload["features"]
            if not isinstance(features, list):
                raise TypeError
            summary = next(
                feature["properties"] for feature in features
                if isinstance(feature, dict) and isinstance(feature.get("properties"), dict)
                and "totalDistance" in feature["properties"] and "totalTime" in feature["properties"]
            )
            pieces = [
                feature["geometry"]["coordinates"] for feature in features
                if isinstance(feature, dict) and isinstance(feature.get("geometry"), dict)
                and feature["geometry"].get("type") == "LineString"
            ]
            coordinates = _join_coordinate_pieces(pieces)
            return NormalizedRoute(
                provider=Provider.TMAP, transport_mode=mode,
                duration_seconds=summary["totalTime"], distance_meters=summary["totalDistance"],
                coordinates=coordinates, attribution="TMAP",
            )
        except (KeyError, StopIteration, TypeError, ValidationError, ValueError) as exc:
            raise RouteProviderError("ROUTE_INVALID_RESULT", retryable=False) from exc


def _join_coordinate_pieces(pieces: list[Any]) -> list[Coordinate]:
    joined: list[Coordinate] = []
    for raw_piece in pieces:
        if not isinstance(raw_piece, list) or len(raw_piece) < 2:
            raise ValueError("invalid line")
        piece = [Coordinate(longitude=point[0], latitude=point[1]) for point in raw_piece]
        if joined and joined[-1] != piece[0]:
            raise ValueError("non-contiguous line")
        joined.extend(piece if not joined else piece[1:])
    if len(joined) < 2:
        raise ValueError("missing line")
    return joined
