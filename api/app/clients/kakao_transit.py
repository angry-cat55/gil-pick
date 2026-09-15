"""Kakao Maps 대중교통 경로 adapter."""

from __future__ import annotations

from time import monotonic
from typing import Any

import httpx2
from pydantic import ValidationError

from app.clients.route_provider import (
    Coordinate,
    NormalizedRoute,
    NormalizedTransitStep,
    Provider,
    RouteProviderError,
    TransportMode,
    TransitStepType,
)
from app.core.config import Settings


class KakaoTransitClient:
    """Kakao 기본 추천 대중교통 경로를 공통 형식으로 변환한다."""

    def __init__(
        self,
        settings: Settings,
        client: httpx2.AsyncClient | None = None,
    ) -> None:
        self.settings = settings
        self.client = client or httpx2.AsyncClient()

    async def close(self) -> None:
        """내부 HTTP 연결 풀을 닫는다."""
        await self.client.aclose()

    async def calculate(
        self,
        origin: Coordinate,
        destination: Coordinate,
        transport_mode: TransportMode,
        *,
        deadline: float,
    ) -> NormalizedRoute:
        if transport_mode is not TransportMode.TRANSIT:
            raise ValueError("Kakao Maps supports TRANSIT only")

        remaining = deadline - monotonic()
        if remaining <= 0:
            raise RouteProviderError("ROUTE_PROVIDER_TIMEOUT", retryable=True)
        try:
            response = await self.client.get(
                f"{self.settings.kakao_maps_base_url}/v2/routing/publictraffic",
                headers={
                    "Authorization": (
                        "KakaoAK "
                        f"{self.settings.kakao_rest_api_key.get_secret_value()}"
                    )
                },
                params={
                    "start_x": origin.longitude,
                    "start_y": origin.latitude,
                    "end_x": destination.longitude,
                    "end_y": destination.latitude,
                },
                timeout=min(
                    self.settings.route_provider_timeout_seconds,
                    remaining,
                ),
            )
        except httpx2.TimeoutException as exc:
            raise RouteProviderError(
                "ROUTE_PROVIDER_TIMEOUT", retryable=True
            ) from exc
        except httpx2.RequestError as exc:
            raise RouteProviderError(
                "ROUTE_PROVIDER_UNAVAILABLE", retryable=True
            ) from exc

        if response.status_code == 429:
            raise RouteProviderError(
                "ROUTE_PROVIDER_RATE_LIMITED", retryable=True
            )
        if response.status_code >= 400:
            raise RouteProviderError(
                "ROUTE_PROVIDER_UNAVAILABLE",
                retryable=response.status_code >= 500,
            )

        try:
            payload = response.json()
        except (TypeError, ValueError) as exc:
            raise RouteProviderError(
                "ROUTE_INVALID_RESULT", retryable=False
            ) from exc
        if not isinstance(payload, dict):
            raise RouteProviderError("ROUTE_INVALID_RESULT", retryable=False)

        status = payload.get("status")
        if status in {"STARTNODES_NULL", "ENDNODES_NULL", "EQUAL_POINTS", "NO_RESULTS"}:
            raise RouteProviderError("ROUTE_NOT_FOUND", retryable=False)
        if status != "OK":
            raise RouteProviderError("ROUTE_INVALID_RESULT", retryable=False)

        route = self._recommended_route(payload)
        try:
            properties = route["properties"]
            coordinates = self._coordinates(route)
            return NormalizedRoute(
                provider=Provider.KAKAO,
                transport_mode=TransportMode.TRANSIT,
                duration_seconds=properties["totalTime"],
                distance_meters=properties["totalDistance"],
                coordinates=coordinates,
                attribution="Kakao Maps",
                steps=self._steps(route),
            )
        except (KeyError, TypeError, ValidationError, ValueError) as exc:
            raise RouteProviderError(
                "ROUTE_INVALID_RESULT", retryable=False
            ) from exc

    @staticmethod
    def _recommended_route(payload: dict[str, Any]) -> dict[str, Any]:
        try:
            routes = payload["routes"]
            if not isinstance(routes, list) or not routes:
                raise TypeError
            route = routes[0]
            if not isinstance(route, dict):
                raise TypeError
            return route
        except (KeyError, TypeError) as exc:
            raise RouteProviderError(
                "ROUTE_INVALID_RESULT", retryable=False
            ) from exc

    @staticmethod
    def _coordinates(route: dict[str, Any]) -> list[Coordinate]:
        try:
            pieces = [
                [
                    Coordinate(longitude=point[0], latitude=point[1])
                    for point in step["path"]["points"]
                ]
                for step in route["steps"]
            ]
            joined: list[Coordinate] = []
            for piece in pieces:
                if len(piece) < 2:
                    raise ValueError("invalid geometry")
                joined.extend(
                    piece[1:] if joined and joined[-1] == piece[0] else piece
                )
            if len(joined) < 2:
                raise ValueError("missing geometry")
            return joined
        except (IndexError, KeyError, TypeError, ValidationError, ValueError) as exc:
            raise RouteProviderError(
                "ROUTE_INVALID_RESULT", retryable=False
            ) from exc

    @staticmethod
    def _steps(route: dict[str, Any]) -> list[NormalizedTransitStep]:
        """상세 메타데이터가 모두 유효할 때만 단계 전체를 반환한다."""
        try:
            raw_steps = route["steps"]
            if not isinstance(raw_steps, list) or not raw_steps:
                return []
            parsed: list[NormalizedTransitStep] = []
            for raw_step in raw_steps:
                properties = raw_step["properties"]
                if not isinstance(properties, dict):
                    return []
                raw_type = properties["type"]
                step_type = {
                    "WALKING": TransitStepType.WALK,
                    "BUS": TransitStepType.BUS,
                    "SUBWAY": TransitStepType.SUBWAY,
                }[raw_type]
                stops = KakaoTransitClient._names(properties.get("stops"), "name")
                vehicles = KakaoTransitClient._names(properties.get("vehicles"), "name")
                parsed.append(
                    NormalizedTransitStep(
                        type=step_type,
                        duration_seconds=properties["time"],
                        distance_meters=properties["distance"],
                        boarding_name=stops[0] if stops else None,
                        alighting_name=stops[-1] if stops else None,
                        line_name=" / ".join(dict.fromkeys(vehicles)) or None,
                    )
                )
            return parsed
        except (KeyError, TypeError, ValidationError, ValueError):
            return []

    @staticmethod
    def _names(value: Any, key: str) -> list[str]:
        if value is None:
            return []
        if not isinstance(value, list):
            raise TypeError
        names: list[str] = []
        for item in value:
            if not isinstance(item, dict) or not isinstance(item.get(key), str):
                raise TypeError
            names.append(item[key])
        return names


__all__ = ["KakaoTransitClient"]
