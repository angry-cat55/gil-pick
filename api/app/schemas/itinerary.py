"""F004 OpenAPI 계약과 일치하는 일정 구성 스키마."""

from __future__ import annotations

import uuid
from datetime import date
from enum import StrEnum
from typing import Literal

from pydantic import ConfigDict, Field, HttpUrl, field_validator
from pydantic.alias_generators import to_camel

from app.schemas.auth import ApiModel, ResponseMeta
from app.schemas.place import PLACE_ID_PATTERN, PlaceCategory, TourApiCategory
from app.schemas.route import Route, RouteStatus, TransportMode


class ItineraryRequestModel(ApiModel):
    """요청에서 camelCase 필드만 허용하는 일정 모델."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=False,
        validate_by_alias=True,
        validate_by_name=False,
        extra="forbid",
    )


class ItemStatus(StrEnum):
    PLANNED = "PLANNED"
    EN_ROUTE = "EN_ROUTE"
    ARRIVED = "ARRIVED"
    COMPLETED = "COMPLETED"
    SKIPPED = "SKIPPED"


class StaySource(StrEnum):
    RECOMMENDED = "RECOMMENDED"
    USER_ADJUSTED = "USER_ADJUSTED"


class ItineraryErrorCode(StrEnum):
    INVALID_REQUEST = "INVALID_REQUEST"
    INVALID_ITINERARY = "INVALID_ITINERARY"
    INVALID_ACCESS_TOKEN = "INVALID_ACCESS_TOKEN"
    TRIP_FORBIDDEN = "TRIP_FORBIDDEN"
    TRIP_NOT_FOUND = "TRIP_NOT_FOUND"
    VERSION_CONFLICT = "VERSION_CONFLICT"
    ITINERARY_ITEM_LOCKED = "ITINERARY_ITEM_LOCKED"
    CONFIRMATION_REQUIRED = "CONFIRMATION_REQUIRED"


class PlaceSnapshot(ItineraryRequestModel):
    name: str = Field(min_length=1, max_length=255)
    category: PlaceCategory
    tour_api_category: TourApiCategory | None
    address: str | None
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    image_url: HttpUrl | None


class ItineraryPlaceSummary(ApiModel):
    place_id: str = Field(pattern=PLACE_ID_PATTERN)
    name: str
    category: PlaceCategory
    address: str | None
    image_url: str | None


class ItineraryItem(ApiModel):
    item_id: uuid.UUID
    place: ItineraryPlaceSummary
    sequence: int = Field(ge=1, le=10)
    planned_stay_minutes: int = Field(ge=30, le=360, multiple_of=30)
    stay_source: StaySource
    transport_mode_to_next: TransportMode | None
    status: ItemStatus


class SaveItem(ItineraryRequestModel):
    item_id: uuid.UUID | None
    place_id: str = Field(pattern=PLACE_ID_PATTERN)
    place: PlaceSnapshot | None = None
    sequence: int = Field(ge=1, le=10)
    planned_stay_minutes: int = Field(ge=30, le=360, multiple_of=30)
    stay_source: StaySource
    transport_mode_to_next: TransportMode | None


class SaveDayItineraryRequest(ItineraryRequestModel):
    version: int = Field(ge=0)
    items: list[SaveItem] = Field(max_length=10)


class DayItinerary(ApiModel):
    date: date
    day_number: int = Field(ge=1, le=7)
    version: int = Field(ge=0)
    route_status: RouteStatus
    items: list[ItineraryItem]
    route: Route | None = None


class DayItineraryEnvelope(ApiModel):
    success: Literal[True]
    data: DayItinerary
    meta: ResponseMeta


class ItineraryOverview(ApiModel):
    trip_id: uuid.UUID
    days: list[DayItinerary]


class ItineraryOverviewEnvelope(ApiModel):
    success: Literal[True]
    data: ItineraryOverview
    meta: ResponseMeta


class Violation(ApiModel):
    field: str
    item_index: int | None = Field(ge=0)
    reason: str


class ErrorDetails(ApiModel):
    violations: list[Violation] | None = None
    item_id: uuid.UUID | None = None
    deleted_item_count: int | None = Field(default=None, ge=0)


__all__ = [
    "DayItinerary",
    "DayItineraryEnvelope",
    "ErrorDetails",
    "ItemStatus",
    "ItineraryErrorCode",
    "ItineraryItem",
    "ItineraryOverview",
    "ItineraryOverviewEnvelope",
    "ItineraryPlaceSummary",
    "PlaceSnapshot",
    "RouteStatus",
    "SaveDayItineraryRequest",
    "SaveItem",
    "StaySource",
    "TransportMode",
    "Violation",
]
