"""F005 공개 경로 계약."""

from __future__ import annotations

import uuid
from datetime import date, datetime
from enum import StrEnum
from typing import Annotated, Literal

from pydantic import Field, model_validator

from app.schemas.auth import ApiModel, ResponseMeta


class RouteStatus(StrEnum):
    NOT_CALCULATED = "NOT_CALCULATED"
    READY = "READY"
    FAILED = "FAILED"


class TransportMode(StrEnum):
    WALK = "WALK"
    TRANSIT = "TRANSIT"
    CAR = "CAR"


class Provider(StrEnum):
    TMAP = "TMAP"
    ODSAY = "ODSAY"


class RouteFailureCode(StrEnum):
    ROUTE_PROVIDER_TIMEOUT = "ROUTE_PROVIDER_TIMEOUT"
    ROUTE_PROVIDER_RATE_LIMITED = "ROUTE_PROVIDER_RATE_LIMITED"
    ROUTE_PROVIDER_UNAVAILABLE = "ROUTE_PROVIDER_UNAVAILABLE"
    ROUTE_NOT_FOUND = "ROUTE_NOT_FOUND"
    ROUTE_INVALID_RESULT = "ROUTE_INVALID_RESULT"


Position = tuple[Annotated[float, Field(ge=-180, le=180)], Annotated[float, Field(ge=-90, le=90)]]


class RouteGeometry(ApiModel):
    type: Literal["LineString"]
    coordinates: list[Position] = Field(min_length=2)


class RouteMarker(ApiModel):
    item_id: uuid.UUID
    sequence: int = Field(ge=1, le=10)
    name: str = Field(min_length=1)
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)


class RouteSegment(ApiModel):
    sequence: int = Field(ge=1, le=9)
    from_item_id: uuid.UUID
    to_item_id: uuid.UUID
    transport_mode: TransportMode
    provider: Provider
    duration_seconds: int = Field(ge=0)
    distance_meters: int = Field(ge=0)
    geometry: RouteGeometry
    provider_attribution: str = Field(min_length=1)


class Route(ApiModel):
    route_id: uuid.UUID
    schedule_version: int = Field(ge=1)
    total_duration_seconds: int = Field(ge=0)
    total_distance_meters: int = Field(ge=0)
    markers: list[RouteMarker] = Field(min_length=1, max_length=10)
    segments: list[RouteSegment] = Field(max_length=9)
    provider_attributions: list[str]
    calculated_at: datetime

    @model_validator(mode="after")
    def validate_order_and_totals(self) -> "Route":
        if [marker.sequence for marker in self.markers] != list(range(1, len(self.markers) + 1)):
            raise ValueError("markers must follow itinerary order")
        if [segment.sequence for segment in self.segments] != list(range(1, len(self.segments) + 1)):
            raise ValueError("segments must be ordered")
        if len(self.segments) != len(self.markers) - 1:
            raise ValueError("segments must connect adjacent markers")
        for segment, start, end in zip(self.segments, self.markers, self.markers[1:]):
            if segment.from_item_id != start.item_id or segment.to_item_id != end.item_id:
                raise ValueError("segment endpoints must match adjacent markers")
        if self.total_duration_seconds != sum(segment.duration_seconds for segment in self.segments):
            raise ValueError("totalDurationSeconds must equal segment sum")
        if self.total_distance_meters != sum(segment.distance_meters for segment in self.segments):
            raise ValueError("totalDistanceMeters must equal segment sum")
        expected = list(dict.fromkeys(segment.provider_attribution for segment in self.segments))
        if self.provider_attributions != expected:
            raise ValueError("providerAttributions must be ordered and unique")
        return self


class RouteFailure(ApiModel):
    code: RouteFailureCode
    message: str
    retryable: bool


class RouteDataBase(ApiModel):
    trip_id: uuid.UUID
    date: date
    schedule_version: int = Field(ge=0)


class NotCalculatedRouteData(RouteDataBase):
    route_status: Literal[RouteStatus.NOT_CALCULATED]
    route: None
    failure: None


class ReadyRouteData(RouteDataBase):
    route_status: Literal[RouteStatus.READY]
    route: Route
    failure: None


class FailedRouteData(RouteDataBase):
    route_status: Literal[RouteStatus.FAILED]
    route: None
    failure: RouteFailure


RouteData = Annotated[NotCalculatedRouteData | ReadyRouteData | FailedRouteData, Field(discriminator="route_status")]


class RouteEnvelope(ApiModel):
    success: Literal[True]
    data: RouteData
    meta: ResponseMeta


__all__ = ["FailedRouteData", "NotCalculatedRouteData", "Provider", "ReadyRouteData", "Route", "RouteData", "RouteEnvelope", "RouteFailure", "RouteFailureCode", "RouteGeometry", "RouteMarker", "RouteSegment", "RouteStatus", "TransportMode"]
