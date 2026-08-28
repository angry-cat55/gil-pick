"""F002 OpenAPI 계약과 일치하는 여행 API 스키마."""

from __future__ import annotations

import uuid
from datetime import date, datetime
from enum import StrEnum
from typing import Literal

from pydantic import ConfigDict, Field
from pydantic.alias_generators import to_camel

from app.schemas.auth import ApiModel, ErrorEnvelope, ResponseMeta, SuccessEnvelope


class TripRequestModel(ApiModel):
    """여행 요청 payload에 적용하는 엄격한 camelCase 기반 모델."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=False,
        validate_by_alias=True,
        validate_by_name=False,
        extra="forbid",
    )


class TripStatus(StrEnum):
    """저장하지 않고 KST 날짜를 기준으로 계산하는 여행 상태."""

    UPCOMING = "UPCOMING"
    IN_PROGRESS = "IN_PROGRESS"
    COMPLETED = "COMPLETED"


class CreateTripRequest(TripRequestModel):
    """여행 생성 payload이며 필드 간 규칙은 서비스에서 검증한다."""

    name: str = Field(json_schema_extra={"minLength": 2, "maxLength": 30})
    start_date: date
    end_date: date


class UpdateTripRequest(TripRequestModel):
    """낙관적 동시성 버전을 포함하는 여행 부분 수정 payload."""

    name: str = Field(  # type: ignore[assignment]
        default=None,
        json_schema_extra={"minLength": 2, "maxLength": 30},
    )
    start_date: date = None  # type: ignore[assignment]
    end_date: date = None  # type: ignore[assignment]
    version: int = Field(strict=True)
    confirm_delete_out_of_range_items: bool = False


class Trip(ApiModel):
    """외부에 제공하는 여행 표현."""

    trip_id: uuid.UUID
    name: str
    start_date: date
    end_date: date
    status: TripStatus
    day_count: int
    version: int
    created_at: datetime | None = None


class TripListData(ApiModel):
    """여행 목록 응답에 포함되는 항목."""

    items: list[Trip]


class Pagination(ApiModel):
    """여행 목록의 cursor pagination 상태."""

    next_cursor: str | None
    has_next: bool


class TripListMeta(ResponseMeta):
    """요청 추적 및 pagination 메타데이터."""

    pagination: Pagination


class TripListResponse(ApiModel):
    """여행 목록 응답 envelope."""

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
