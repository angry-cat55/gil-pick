"""F009 대체 장소 추천 API 스키마."""

from __future__ import annotations

import uuid
from datetime import datetime
from enum import StrEnum
from typing import Literal

from pydantic import Field

from app.schemas.auth import ApiModel, ErrorBody, ResponseMeta
from app.schemas.detection import DetectionStatus, PaginatedMeta
from app.schemas.place import PlaceSummary


class OperatingStatus(StrEnum):
    """대체 장소의 예상 도착 시점 영업 상태."""

    OPEN = "OPEN"
    CLOSING_SOON = "CLOSING_SOON"
    CLOSED = "CLOSED"
    UNKNOWN = "UNKNOWN"


class AlternativeErrorCode(StrEnum):
    """대체 장소 API에서 노출하는 오류 code."""

    INVALID_REQUEST = "INVALID_REQUEST"
    INVALID_CURSOR = "INVALID_CURSOR"
    INVALID_ACCESS_TOKEN = "INVALID_ACCESS_TOKEN"
    TRIP_FORBIDDEN = "TRIP_FORBIDDEN"
    DETECTION_NOT_FOUND = "DETECTION_NOT_FOUND"
    DETECTION_NOT_ACTIVE = "DETECTION_NOT_ACTIVE"
    INVALID_CANDIDATE = "INVALID_CANDIDATE"
    TOUR_API_RATE_LIMITED = "TOUR_API_RATE_LIMITED"
    TOUR_API_FAILED = "TOUR_API_FAILED"
    TOUR_API_TIMEOUT = "TOUR_API_TIMEOUT"


class ScoreBreakdown(ApiModel):
    """추천 점수에 실제로 쓰인 0~1 정규화 변수."""

    distance: float | None = Field(default=None, ge=0, le=1)
    rating: float | None = Field(default=None, ge=0, le=1)
    congestion: float | None = Field(default=None, ge=0, le=1)
    weather: float | None = Field(default=None, ge=0, le=1)


class AlternativeCandidate(ApiModel):
    """점수와 운영 상태를 포함한 추천 후보."""

    rank: int = Field(ge=1, le=10)
    candidate_id: str
    place: PlaceSummary
    distance_meters: int = Field(ge=0)
    adjusted_rating: float | None
    score: float = Field(ge=0, le=100)
    display_score: int = Field(ge=0, le=100)
    score_breakdown: ScoreBreakdown
    operating_status: OperatingStatus
    closes_at: datetime | None
    reasons: list[str]


class AlternativeListData(ApiModel):
    """탐색 기준과 최종 추천 후보 목록."""

    detection_id: uuid.UUID
    origin_place_id: str
    eta: datetime
    search_radius_meters: Literal[500, 1000, 2000]
    category_match_level: Literal["SMALL", "MIDDLE", "LARGE", "NONE"]
    evaluated_at: datetime
    items: list[AlternativeCandidate] = Field(max_length=10)


class AlternativeSearchItem(ApiModel):
    """직접 검색 장소에 거리와 방문 가능 상태를 덧붙인 항목."""

    place: PlaceSummary
    distance_meters: int | None = Field(ge=0)
    operating_status: OperatingStatus
    visitable: bool
    in_schedule: bool


class DetectionDismissData(ApiModel):
    """감지 거절 후의 현재 상태."""

    detection_id: uuid.UUID
    status: DetectionStatus
    decided_at: datetime | None


class AlternativeListEnvelope(ApiModel):
    success: Literal[True]
    data: AlternativeListData
    meta: ResponseMeta


class AlternativeSearchData(ApiModel):
    items: list[AlternativeSearchItem]


class AlternativeSearchEnvelope(ApiModel):
    success: Literal[True]
    data: AlternativeSearchData
    meta: PaginatedMeta


class DetectionDismissEnvelope(ApiModel):
    success: Literal[True]
    data: DetectionDismissData
    meta: ResponseMeta


class AlternativeErrorBody(ErrorBody):
    code: AlternativeErrorCode


class ErrorEnvelope(ApiModel):
    success: Literal[False]
    error: AlternativeErrorBody
    meta: ResponseMeta


__all__ = [
    "AlternativeCandidate",
    "AlternativeErrorCode",
    "AlternativeListData",
    "AlternativeListEnvelope",
    "AlternativeSearchData",
    "AlternativeSearchEnvelope",
    "AlternativeSearchItem",
    "DetectionDismissData",
    "DetectionDismissEnvelope",
    "ErrorEnvelope",
    "OperatingStatus",
    "ScoreBreakdown",
]

