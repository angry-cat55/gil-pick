"""장소 API 스키마의 OpenAPI 계약 검증."""

from __future__ import annotations

import uuid

import pytest
from pydantic import ValidationError

from app.schemas.auth import ErrorBody
from app.schemas.place import PlaceEnvelope, PlaceListEnvelope, PlaceSummary


def place_payload(**overrides: object) -> dict[str, object]:
    """모든 필수·nullable 필드를 포함한 장소 payload를 만든다."""
    payload: dict[str, object] = {
        "placeId": "tourapi:12345",
        "source": "TOUR_API",
        "sourcePlaceId": "12345",
        "name": "경복궁",
        "category": "HISTORY_CULTURE",
        "tourApiCategory": {"large": "A02", "middle": None, "small": None},
        "address": "서울특별시 종로구",
        "latitude": 37.5796,
        "longitude": 126.977,
        "imageUrl": "https://example.com/place.jpg",
        "recommendedStayMinutes": 90,
        "rating": 4.7,
        "userRatingCount": 1200,
        "businessStatus": "OPERATIONAL",
        "regularOpeningHours": ["월요일: 09:00~18:00"],
        "currentOpeningHours": None,
        "googleAttributions": ["Google Maps"],
    }
    payload.update(overrides)
    return payload


def test_place_list_envelope_accepts_required_nullable_fields() -> None:
    """검색 응답은 nullable 필드와 pagination을 생략 없이 받는다."""
    payload = place_payload(tourApiCategory=None, imageUrl=None, rating=None)

    response = PlaceListEnvelope.model_validate(
        {
            "success": True,
            "data": {"items": [payload]},
            "meta": {
                "requestId": str(uuid.uuid4()),
                "pagination": {"nextCursor": None, "hasNext": False},
            },
        }
    )

    assert response.data.items[0].tour_api_category is None
    assert response.meta.pagination.has_next is False


def test_place_detail_uses_common_success_envelope() -> None:
    """상세 응답은 공통 requestId envelope와 상세 nullable 필드를 사용한다."""
    response = PlaceEnvelope.model_validate(
        {
            "success": True,
            "data": place_payload(
                description=None,
                phone=None,
                operatingGuide="매주 화요일 휴무",
            ),
            "meta": {"requestId": str(uuid.uuid4())},
        }
    )

    assert response.data.operating_guide == "매주 화요일 휴무"


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("placeId", "unknown:12345"),
        ("category", "LODGING"),
        ("businessStatus", "UNKNOWN"),
        ("recommendedStayMinutes", 30),
        ("latitude", 91),
        ("longitude", -181),
        ("rating", 5.1),
        ("userRatingCount", -1),
        ("imageUrl", "http://example.com/place.jpg"),
    ],
)
def test_place_summary_rejects_values_outside_contract(
    field: str, value: object
) -> None:
    """ID·enum·범위·HTTPS 제약을 벗어난 값을 거부한다."""
    with pytest.raises(ValidationError):
        PlaceSummary.model_validate(place_payload(**{field: value}))


@pytest.mark.parametrize("field", ["tourApiCategory", "address", "rating"])
def test_required_nullable_field_cannot_be_omitted(field: str) -> None:
    """nullable 응답 필드도 OpenAPI required 목록에 있으면 생략할 수 없다."""
    payload = place_payload()
    del payload[field]

    with pytest.raises(ValidationError):
        PlaceSummary.model_validate(payload)


def test_error_retryable_is_required() -> None:
    """공통 오류 응답은 재시도 가능 여부를 항상 포함한다."""
    with pytest.raises(ValidationError):
        ErrorBody.model_validate({"code": "TOUR_API_FAILED", "message": "오류"})
