"""F008 감지 결과 공개 API 계약."""

from __future__ import annotations

import uuid
from datetime import datetime
from enum import StrEnum
from typing import Literal

from pydantic import Field

from app.schemas.auth import ApiModel, ErrorBody, ResponseMeta


class DetectionStatus(StrEnum):
    ACTIVE = "ACTIVE"
    RESOLVED = "RESOLVED"
    DISMISSED = "DISMISSED"
    INVALIDATED = "INVALIDATED"


class DetectionType(StrEnum):
    CONGESTION = "CONGESTION"
    WEATHER = "WEATHER"
    OPERATING_HOURS = "OPERATING_HOURS"


class UnavailableReason(StrEnum):
    NO_FORECAST = "NO_FORECAST"
    NOT_IN_SUPPORT_AREA = "NOT_IN_SUPPORT_AREA"
    HOURS_UNKNOWN = "HOURS_UNKNOWN"
    INDOOR = "INDOOR"
    TIMEOUT = "TIMEOUT"


class DetectionErrorCode(StrEnum):
    INVALID_REQUEST = "INVALID_REQUEST"
    INVALID_ACCESS_TOKEN = "INVALID_ACCESS_TOKEN"
    TRIP_FORBIDDEN = "TRIP_FORBIDDEN"
    TRIP_NOT_FOUND = "TRIP_NOT_FOUND"
    DETECTION_NOT_FOUND = "DETECTION_NOT_FOUND"
    DETECTION_FORBIDDEN = "DETECTION_FORBIDDEN"


class CongestionVerdict(ApiModel):
    available: bool
    unavailable_reason: UnavailableReason | None = None
    level: Literal["RELAXED", "NORMAL", "SLIGHTLY_CROWDED", "CROWDED"] | None = None
    sensitivity: Literal["HIGH", "MEDIUM"] | None = None
    crowded: bool | None = None


class WeatherVerdict(ApiModel):
    available: bool
    unavailable_reason: UnavailableReason | None = None
    precipitation_probability: int | None = Field(default=None, ge=0, le=100)
    precipitation_mm_per_hour: float | None = Field(default=None, ge=0)
    precipitation_type: Literal["NONE", "RAIN", "RAIN_SNOW", "SNOW", "SHOWER"] | None = None
    at_risk: bool | None = None


class OperatingHoursVerdict(ApiModel):
    available: bool
    unavailable_reason: UnavailableReason | None = None
    closes_at: datetime | None = None
    closing_soon: bool | None = None
    visit_blocked: bool | None = None
    temp_closed: bool | None = None


class VariableVerdicts(ApiModel):
    congestion: CongestionVerdict
    weather: WeatherVerdict
    operating_hours: OperatingHoursVerdict


class DetectionListItem(ApiModel):
    detection_id: uuid.UUID; item_id: uuid.UUID; place_name: str; primary_type: DetectionType
    status: DetectionStatus; total_risk_score: int = Field(ge=0, le=100); eta: datetime; reason: str
    created_at: datetime; read: bool


class DetectionListData(ApiModel):
    items: list[DetectionListItem]


class Pagination(ApiModel):
    next_cursor: str | None; has_next: bool


class PaginatedMeta(ResponseMeta):
    pagination: Pagination


class DetectionListEnvelope(ApiModel):
    success: Literal[True]; data: DetectionListData; meta: PaginatedMeta


class DetectionDetail(ApiModel):
    detection_id: uuid.UUID; trip_id: uuid.UUID; item_id: uuid.UUID; place_name: str
    primary_type: DetectionType; status: DetectionStatus; eta: datetime
    total_risk_score: int = Field(ge=0, le=100); reason: str; variables: VariableVerdicts
    read: bool; created_at: datetime; last_evaluated_at: datetime


class DetectionDetailEnvelope(ApiModel):
    success: Literal[True]; data: DetectionDetail; meta: ResponseMeta


class DetectionReadData(ApiModel):
    detection_id: uuid.UUID; read: Literal[True]


class DetectionReadEnvelope(ApiModel):
    success: Literal[True]; data: DetectionReadData; meta: ResponseMeta


class DetectionDismissData(ApiModel):
    detection_id: uuid.UUID
    status: DetectionStatus
    # 거절·처리 시각. INVALIDATED처럼 사용자 결정이 없으면 null.
    decided_at: datetime | None


class DetectionDismissEnvelope(ApiModel):
    success: Literal[True]; data: DetectionDismissData; meta: ResponseMeta


class DetectionErrorBody(ErrorBody):
    code: DetectionErrorCode


class ErrorEnvelope(ApiModel):
    success: Literal[False]; error: DetectionErrorBody; meta: ResponseMeta
