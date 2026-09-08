"""F006 진행 Pydantic schema가 OpenAPI 계약을 지키는지 검증한다."""

import uuid
from datetime import UTC, date, datetime

import pytest
from pydantic import ValidationError

from app.schemas.progress import (
    InboundTravel,
    InboundTravelSource,
    ProgressData,
    ProgressErrorCode,
    ProgressItem,
    ProgressTargetStatus,
    StartDayProgressRequest,
    UpdateItemProgressStatusRequest,
)


def test_progress_data_serializes_nullable_inbound_travel_with_camel_case() -> None:
    item_id = uuid.uuid4()
    data = ProgressData(
        trip_id=uuid.uuid4(),
        date=date(2026, 9, 7),
        day_status="IN_PROGRESS",
        progress_version=1,
        schedule_version=2,
        actual_started_at=datetime(2026, 9, 7, tzinfo=UTC),
        completed_at=None,
        start_location=None,
        current_item_id=None,
        next_item_id=item_id,
        items=[
            ProgressItem(
                item_id=item_id,
                sequence=1,
                status="EN_ROUTE",
                estimated_arrival_at=None,
                estimated_departure_at=None,
                actual_arrived_at=None,
                completed_at=None,
                inbound_travel=None,
            )
        ],
    )

    payload = data.model_dump(mode="json", by_alias=True)

    assert payload["progressVersion"] == 1
    assert payload["items"][0]["inboundTravel"] is None


def test_inbound_travel_requires_source_and_accepts_null_from_item() -> None:
    travel = InboundTravel(
        from_item_id=None,
        transport_mode="WALK",
        duration_seconds=120,
        distance_meters=400,
        source=InboundTravelSource.COMPUTED,
    )

    assert travel.from_item_id is None
    assert travel.source is InboundTravelSource.COMPUTED


@pytest.mark.parametrize(
    ("field", "value"),
    [("latitude", 91), ("longitude", -181), ("accuracyMeters", -0.1)],
)
def test_start_request_rejects_out_of_range_location(field: str, value: float) -> None:
    location = {
        "latitude": 37.5,
        "longitude": 127.0,
        "accuracyMeters": 10,
        "occurredAt": "2026-09-07T00:00:00Z",
    }
    location[field] = value

    with pytest.raises(ValidationError):
        StartDayProgressRequest.model_validate(
            {"progressVersion": 0, "currentLocation": location}
        )


def test_start_request_accepts_omitted_or_null_location_and_rejects_snake_case() -> None:
    assert StartDayProgressRequest.model_validate(
        {"progressVersion": 0}
    ).current_location is None
    assert StartDayProgressRequest.model_validate(
        {"progressVersion": 0, "currentLocation": None}
    ).current_location is None

    with pytest.raises(ValidationError):
        StartDayProgressRequest.model_validate({"progress_version": 0})


def test_progress_response_nullable_fields_are_still_required() -> None:
    with pytest.raises(ValidationError):
        ProgressItem.model_validate(
            {
                "itemId": str(uuid.uuid4()),
                "sequence": 1,
                "status": "PLANNED",
            }
        )


def test_progress_status_request_allows_only_four_target_statuses() -> None:
    assert {status.value for status in ProgressTargetStatus} == {
        "ARRIVED", "COMPLETED", "SKIPPED", "PLANNED"
    }
    with pytest.raises(ValidationError):
        UpdateItemProgressStatusRequest.model_validate(
            {"status": "EN_ROUTE", "progressVersion": 1}
        )


def test_progress_error_codes_match_contract() -> None:
    assert {code.value for code in ProgressErrorCode} == {
        "INVALID_REQUEST", "INVALID_ACCESS_TOKEN", "TRIP_FORBIDDEN",
        "TRIP_NOT_FOUND", "ITINERARY_ITEM_NOT_FOUND", "VERSION_CONFLICT",
        "IDEMPOTENCY_KEY_CONFLICT",
        "DAY_NOT_TODAY", "DAY_EMPTY", "DAY_NOT_STARTED",
        "INVALID_STATUS_TRANSITION",
    }
