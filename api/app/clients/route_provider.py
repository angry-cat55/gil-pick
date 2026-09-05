"""TMAP과 ODsay가 공유하는 경로 계산 경계."""

from __future__ import annotations

from enum import StrEnum
from typing import Protocol

from pydantic import BaseModel, ConfigDict, Field


class Provider(StrEnum):
    TMAP = "TMAP"
    ODSAY = "ODSAY"


class TransportMode(StrEnum):
    WALK = "WALK"
    TRANSIT = "TRANSIT"
    CAR = "CAR"


class Coordinate(BaseModel):
    """WGS84 경도·위도 좌표."""

    model_config = ConfigDict(frozen=True)
    longitude: float = Field(ge=-180, le=180)
    latitude: float = Field(ge=-90, le=90)


class NormalizedRoute(BaseModel):
    """Provider 원문 형식을 제거한 단일 추천 경로."""

    model_config = ConfigDict(frozen=True)
    provider: Provider
    transport_mode: TransportMode
    duration_seconds: int = Field(ge=0)
    distance_meters: int = Field(ge=0)
    coordinates: list[Coordinate] = Field(min_length=2)
    attribution: str = Field(min_length=1)


class RouteProvider(Protocol):
    """구간별 기본 추천 경로 하나를 반환하는 비동기 Provider."""

    async def calculate(self, origin: Coordinate, destination: Coordinate, transport_mode: TransportMode, *, deadline: float) -> NormalizedRoute: ...


class RouteProviderError(RuntimeError):
    """서비스가 처리 가능한 안정적인 Provider 오류."""

    def __init__(self, code: str, *, retryable: bool) -> None:
        super().__init__(code)
        self.code = code
        self.retryable = retryable


__all__ = ["Coordinate", "NormalizedRoute", "Provider", "RouteProvider", "RouteProviderError", "TransportMode"]
