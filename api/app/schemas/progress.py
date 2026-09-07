"""F006 여행 진행 공개 계약."""

from __future__ import annotations

import uuid
from datetime import date, datetime
from enum import StrEnum
from typing import Literal

from pydantic import ConfigDict, Field
from pydantic.alias_generators import to_camel

from app.schemas.auth import ApiModel, ResponseMeta
from app.schemas.itinerary import ItemStatus
from app.schemas.route import TransportMode


class ProgressRequestModel(ApiModel):
    """요청에서 camelCase 필드만 허용하는 진행 모델."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=False,
        validate_by_alias=True,
        validate_by_name=False,
        extra="forbid",
    )


class DayStatus(StrEnum):
    NOT_STARTED = "NOT_STARTED"
    IN_PROGRESS = "IN_PROGRESS"
    COMPLETED = "COMPLETED"


class ProgressTargetStatus(StrEnum):
    ARRIVED = "ARRIVED"
    COMPLETED = "COMPLETED"
    SKIPPED = "SKIPPED"
    PLANNED = "PLANNED"


class InboundTravelSource(StrEnum):
    PLANNED_ROUTE = "PLANNED_ROUTE"
    COMPUTED = "COMPUTED"


class ProgressErrorCode(StrEnum):
    INVALID_REQUEST = "INVALID_REQUEST"
    INVALID_ACCESS_TOKEN = "INVALID_ACCESS_TOKEN"
    TRIP_FORBIDDEN = "TRIP_FORBIDDEN"
    TRIP_NOT_FOUND = "TRIP_NOT_FOUND"
    ITINERARY_ITEM_NOT_FOUND = "ITINERARY_ITEM_NOT_FOUND"
    VERSION_CONFLICT = "VERSION_CONFLICT"
    DAY_NOT_TODAY = "DAY_NOT_TODAY"
    DAY_EMPTY = "DAY_EMPTY"
    DAY_NOT_STARTED = "DAY_NOT_STARTED"
    INVALID_STATUS_TRANSITION = "INVALID_STATUS_TRANSITION"


class CurrentLocation(ProgressRequestModel):
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    accuracy_meters: float = Field(ge=0)
    occurred_at: datetime


class StartDayProgressRequest(ProgressRequestModel):
    progress_version: int = Field(ge=0)
    current_location: CurrentLocation | None = None


class UpdateItemProgressStatusRequest(ProgressRequestModel):
    status: ProgressTargetStatus
    progress_version: int = Field(ge=1)


class StartLocation(ApiModel):
    latitude: float
    longitude: float


class InboundTravel(ApiModel):
    from_item_id: uuid.UUID | None
    transport_mode: TransportMode
    duration_seconds: int = Field(ge=0)
    distance_meters: int = Field(ge=0)
    source: InboundTravelSource


class ProgressItem(ApiModel):
    item_id: uuid.UUID
    sequence: int = Field(ge=1, le=10)
    status: ItemStatus
    estimated_arrival_at: datetime | None
    estimated_departure_at: datetime | None
    actual_arrived_at: datetime | None
    completed_at: datetime | None
    inbound_travel: InboundTravel | None


class ProgressData(ApiModel):
    trip_id: uuid.UUID
    date: date
    day_status: DayStatus
    progress_version: int = Field(ge=0)
    schedule_version: int = Field(ge=0)
    actual_started_at: datetime | None
    completed_at: datetime | None
    start_location: StartLocation | None
    current_item_id: uuid.UUID | None
    next_item_id: uuid.UUID | None
    items: list[ProgressItem]


class ProgressEnvelope(ApiModel):
    success: Literal[True]
    data: ProgressData
    meta: ResponseMeta


__all__ = [
    "CurrentLocation", "DayStatus", "InboundTravel", "InboundTravelSource",
    "ProgressData", "ProgressEnvelope", "ProgressErrorCode", "ProgressItem",
    "ProgressTargetStatus", "StartDayProgressRequest", "StartLocation",
    "UpdateItemProgressStatusRequest",
]
