"""Trip API schemas matching the F002 OpenAPI contract."""

from __future__ import annotations

import uuid
from datetime import date, datetime
from enum import StrEnum
from typing import Literal

from pydantic import ConfigDict, Field
from pydantic.alias_generators import to_camel

from app.schemas.auth import ApiModel, ErrorEnvelope, ResponseMeta, SuccessEnvelope


class TripRequestModel(ApiModel):
    """Strict camelCase base for trip request payloads."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=False,
        validate_by_alias=True,
        validate_by_name=False,
        extra="forbid",
    )


class TripStatus(StrEnum):
    """Trip status derived from KST dates rather than persisted."""

    UPCOMING = "UPCOMING"
    IN_PROGRESS = "IN_PROGRESS"
    COMPLETED = "COMPLETED"


class CreateTripRequest(TripRequestModel):
    """Trip creation payload; cross-field rules are enforced by the service."""

    name: str = Field(min_length=2, max_length=30)
    start_date: date
    end_date: date


class UpdateTripRequest(TripRequestModel):
    """Partial trip update payload with optimistic concurrency version."""

    name: str | None = Field(default=None, min_length=2, max_length=30)
    start_date: date | None = None
    end_date: date | None = None
    version: int
    confirm_delete_out_of_range_items: bool = False


class Trip(ApiModel):
    """Public trip representation."""

    trip_id: uuid.UUID
    name: str
    start_date: date
    end_date: date
    status: TripStatus
    day_count: int
    version: int
    created_at: datetime | None = None


class TripListData(ApiModel):
    """Items contained in a trip list response."""

    items: list[Trip]


class Pagination(ApiModel):
    """Cursor pagination state for a trip list."""

    next_cursor: str | None
    has_next: bool


class TripListMeta(ResponseMeta):
    """Request correlation and pagination metadata."""

    pagination: Pagination


class TripListResponse(ApiModel):
    """Trip list response envelope."""

    success: Literal[True]
    data: TripListData
    meta: TripListMeta


TripEnvelope = SuccessEnvelope[Trip]

__all__ = [
    "CreateTripRequest",
    "ErrorEnvelope",
    "Trip",
    "TripEnvelope",
    "TripListResponse",
    "TripStatus",
    "UpdateTripRequest",
]
