"""F010 일정 변경 공개 API 계약."""

from __future__ import annotations

import uuid
from datetime import date, datetime
from typing import Literal

from pydantic import Field

from app.schemas.auth import ApiModel, ResponseMeta


ComparisonScalar = int | float | str | None


class CreatePreviewRequest(ApiModel):
    place_id: str = Field(min_length=1)
    candidate_id: str | None = None
    schedule_version: int = Field(ge=1)


class ComparisonValue(ApiModel):
    before: ComparisonScalar
    after: ComparisonScalar


class PreviewComparison(ApiModel):
    total_duration_seconds: ComparisonValue
    total_distance_meters: ComparisonValue
    estimated_arrival_at: ComparisonValue
    closes_at: ComparisonValue


class ReplacedPlace(ApiModel):
    place_id: str
    name: str
    category: str
    latitude: float | None = None
    longitude: float | None = None


class RoutePreview(ApiModel):
    preview_id: uuid.UUID
    detection_id: uuid.UUID
    trip_id: uuid.UUID
    date: date
    item_id: uuid.UUID
    original_place: ReplacedPlace
    alternative_place: ReplacedPlace
    detection_reason: str
    comparison: PreviewComparison
    route: dict[str, object]
    schedule_version: int
    expires_at: datetime


class Replacement(ApiModel):
    replacement_id: uuid.UUID
    trip_id: uuid.UUID
    date: date
    item_id: uuid.UUID
    original_place_id: str
    new_place_id: str
    new_place_name: str
    original_place_name: str
    schedule_version: int
    route_status: Literal["READY"]
    undo_expires_at: datetime


class ReplacementUndoResult(ApiModel):
    replacement_id: uuid.UUID
    restored: bool
    schedule_version: int
    route_status: Literal["READY", "FAILED"]
    detection_restored: bool


class UndoableReplacement(ApiModel):
    replacement_id: uuid.UUID
    item_id: uuid.UUID
    original_place_name: str
    new_place_name: str
    undo_expires_at: datetime


class RoutePreviewEnvelope(ApiModel):
    success: Literal[True]
    data: RoutePreview
    meta: ResponseMeta


class ReplacementEnvelope(ApiModel):
    success: Literal[True]
    data: Replacement
    meta: ResponseMeta


class UndoEnvelope(ApiModel):
    success: Literal[True]
    data: ReplacementUndoResult
    meta: ResponseMeta


__all__ = [
    "ComparisonValue",
    "CreatePreviewRequest",
    "PreviewComparison",
    "ReplacedPlace",
    "Replacement",
    "ReplacementEnvelope",
    "RoutePreview",
    "RoutePreviewEnvelope",
    "UndoableReplacement",
    "UndoEnvelope",
    "ReplacementUndoResult",
]
