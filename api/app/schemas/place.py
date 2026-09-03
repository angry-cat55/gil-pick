"""F003 OpenAPI 계약과 일치하는 장소 검색·상세 스키마."""

from __future__ import annotations

from enum import StrEnum
from typing import Literal

from pydantic import Field, HttpUrl, field_validator

from app.schemas.auth import ApiModel, ErrorEnvelope, ResponseMeta, SuccessEnvelope

PLACE_ID_PATTERN = r"^(tourapi|google):[A-Za-z0-9_-]+$"


class PlaceCategory(StrEnum):
    """길픽에서 사용하는 장소 카테고리."""

    NATURE = "NATURE"
    HISTORY_CULTURE = "HISTORY_CULTURE"
    FOOD = "FOOD"
    CAFE = "CAFE"
    SHOPPING = "SHOPPING"
    OTHER = "OTHER"


class PlaceSource(StrEnum):
    """장소 식별자의 기준 외부 제공자."""

    TOUR_API = "TOUR_API"
    GOOGLE_PLACES = "GOOGLE_PLACES"


class BusinessStatus(StrEnum):
    """Google Places가 제공하는 영업 상태 원문 enum."""

    OPERATIONAL = "OPERATIONAL"
    CLOSED_TEMPORARILY = "CLOSED_TEMPORARILY"
    CLOSED_PERMANENTLY = "CLOSED_PERMANENTLY"


class TourApiCategory(ApiModel):
    """TourAPI 신분류 원본 code."""

    large: str | None
    middle: str | None
    small: str | None


class PlaceSummary(ApiModel):
    """검색 목록과 상세가 공유하는 장소 표현."""

    place_id: str = Field(pattern=PLACE_ID_PATTERN)
    source: PlaceSource
    source_place_id: str
    name: str = Field(min_length=1)
    category: PlaceCategory
    tour_api_category: TourApiCategory | None
    address: str | None
    latitude: float | None = Field(ge=-90, le=90)
    longitude: float | None = Field(ge=-180, le=180)
    image_url: HttpUrl | None
    recommended_stay_minutes: Literal[60, 90, 120]
    rating: float | None = Field(ge=0, le=5)
    user_rating_count: int | None = Field(ge=0)
    business_status: BusinessStatus | None
    regular_opening_hours: list[str] | None
    current_opening_hours: list[str] | None
    google_attributions: list[str] | None

    @field_validator("image_url")
    @classmethod
    def validate_image_https(cls, value: HttpUrl | None) -> HttpUrl | None:
        """대표 이미지는 HTTPS URL만 허용한다."""
        if value is not None and value.scheme != "https":
            raise ValueError("imageUrl must use HTTPS")
        return value


class PlaceDetail(PlaceSummary):
    """검색 결과에 상세 설명·연락처·운영 안내를 추가한 표현."""

    description: str | None
    phone: str | None
    operating_guide: str | None


class PaginationMeta(ApiModel):
    """장소 검색의 cursor pagination 상태."""

    next_cursor: str | None
    has_next: bool


class PlaceListMeta(ResponseMeta):
    """요청 추적값과 필수 검색 pagination."""

    pagination: PaginationMeta


class PlaceListData(ApiModel):
    """장소 검색 결과 목록."""

    items: list[PlaceSummary]


class PlaceListEnvelope(ApiModel):
    """장소 검색 성공 응답 envelope."""

    success: Literal[True]
    data: PlaceListData
    meta: PlaceListMeta


PlaceEnvelope = SuccessEnvelope[PlaceDetail]

__all__ = [
    "BusinessStatus",
    "ErrorEnvelope",
    "PaginationMeta",
    "PlaceCategory",
    "PlaceDetail",
    "PlaceEnvelope",
    "PlaceListData",
    "PlaceListEnvelope",
    "PlaceListMeta",
    "PlaceSource",
    "PlaceSummary",
    "TourApiCategory",
]
