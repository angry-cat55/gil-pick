"""ODsay 대중교통 검색·형상 adapter."""

from __future__ import annotations

from time import monotonic
from typing import Any

import httpx2
from pydantic import ValidationError

from app.clients.route_provider import Coordinate, NormalizedRoute, Provider, RouteProviderError, TransportMode
from app.core.config import Settings


class OdsayClient:
    """추천 대중교통 경로와 mapObj 형상을 공통 형식으로 변환한다."""

    def __init__(self, settings: Settings, client: httpx2.AsyncClient | None = None) -> None:
        self.settings = settings
        self.client = client or httpx2.AsyncClient()

    async def calculate(self, origin: Coordinate, destination: Coordinate, transport_mode: TransportMode, *, deadline: float) -> NormalizedRoute:
        if transport_mode is not TransportMode.TRANSIT:
            raise ValueError("ODsay supports TRANSIT only")
        search = await self._get("searchPubTransPathT", {
            "SX": origin.longitude, "SY": origin.latitude, "EX": destination.longitude,
            "EY": destination.latitude, "OPT": 0, "SearchType": 0, "output": "json",
        }, deadline)
        path = self._recommended_path(search)
        map_obj = path["info"]["mapObj"]
        lane = await self._get("loadLane", {"mapObject": f"0:0@{map_obj}"}, deadline)
        try:
            return NormalizedRoute(
                provider=Provider.ODSAY, transport_mode=TransportMode.TRANSIT,
                duration_seconds=path["info"]["totalTime"] * 60,
                distance_meters=path["info"]["totalDistance"],
                coordinates=self._coordinates(lane), attribution="ODsay",
            )
        except (KeyError, TypeError, ValidationError, ValueError) as exc:
            raise RouteProviderError("ROUTE_INVALID_RESULT", retryable=False) from exc

    async def _get(self, endpoint: str, params: dict[str, object], deadline: float) -> dict[str, Any]:
        remaining = deadline - monotonic()
        if remaining <= 0:
            raise RouteProviderError("ROUTE_PROVIDER_TIMEOUT", retryable=True)
        try:
            response = await self.client.get(
                f"{self.settings.odsay_base_url}/{endpoint}",
                params={**params, "apiKey": self.settings.odsay_api_key.get_secret_value()},
                timeout=min(self.settings.route_provider_timeout_seconds, remaining),
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
        error = payload.get("error")
        if error:
            code = error.get("code") if isinstance(error, dict) else None
            if str(code) == "-98":
                raise RouteProviderError("ROUTE_NOT_FOUND", retryable=False)
            raise RouteProviderError("ROUTE_PROVIDER_UNAVAILABLE", retryable=False)
        return payload

    @staticmethod
    def _recommended_path(payload: dict[str, Any]) -> dict[str, Any]:
        try:
            paths = payload["result"]["path"]
            if not isinstance(paths, list) or not paths:
                raise RouteProviderError("ROUTE_NOT_FOUND", retryable=False)
            path = paths[0]
            if not isinstance(path, dict) or not isinstance(path.get("info"), dict) or not path["info"].get("mapObj"):
                raise TypeError
            return path
        except RouteProviderError:
            raise
        except (KeyError, TypeError) as exc:
            raise RouteProviderError("ROUTE_INVALID_RESULT", retryable=False) from exc

    @staticmethod
    def _coordinates(payload: dict[str, Any]) -> list[Coordinate]:
        try:
            lanes = payload["result"]["lane"]
            pieces = [
                [Coordinate(longitude=point["x"], latitude=point["y"]) for point in section["graphPos"]]
                for lane in lanes for section in lane["section"]
            ]
            joined: list[Coordinate] = []
            for piece in pieces:
                if len(piece) < 2 or (joined and joined[-1] != piece[0]):
                    raise ValueError("invalid geometry order")
                joined.extend(piece if not joined else piece[1:])
            if len(joined) < 2:
                raise ValueError("missing geometry")
            return joined
        except (KeyError, TypeError, ValidationError, ValueError) as exc:
            raise RouteProviderError("ROUTE_INVALID_RESULT", retryable=False) from exc
