"""F010 일정 변경 Pydantic schema의 공개 계약을 검증한다."""

import uuid
from datetime import UTC, date, datetime

import pytest
from pydantic import ValidationError

from app.schemas.replacement import (
    ComparisonValue,
    CreatePreviewRequest,
    PreviewComparison,
    ReplacedPlace,
    Replacement,
    RoutePreview,
    UndoableReplacement,
    ReplacementUndoResult,
)


NOW = datetime(2026, 9, 10, tzinfo=UTC)


def test_create_preview_request_uses_schedule_version_alias() -> None:
    payload = {"placeId": "tourapi:123", "candidateId": None, "scheduleVersion": 8}

    request = CreatePreviewRequest.model_validate(payload)

    assert request.schedule_version == 8
    assert request.model_dump(mode="json", by_alias=True) == payload
    with pytest.raises(ValidationError):
        CreatePreviewRequest.model_validate({"placeId": "tourapi:123", "itineraryVersion": 8})


def test_comparison_accepts_nullable_before_and_after_values() -> None:
    comparison = PreviewComparison(
        total_duration_seconds=ComparisonValue(before=60, after=50),
        total_distance_meters=ComparisonValue(before=1000, after=900),
        estimated_arrival_at=ComparisonValue(before=NOW.isoformat(), after=None),
        closes_at=ComparisonValue(before=None, after="18:00"),
    )

    assert comparison.estimated_arrival_at.after is None
    assert comparison.model_dump(mode="json", by_alias=True)["closesAt"]["before"] is None


def test_replacement_response_models_match_contract_fields() -> None:
    replacement_id = uuid.uuid4()
    item_id = uuid.uuid4()
    place = ReplacedPlace(place_id="tourapi:123", name="장소", category="관광지")
    comparison = PreviewComparison(
        total_duration_seconds=ComparisonValue(before=60, after=50),
        total_distance_meters=ComparisonValue(before=1000, after=900),
        estimated_arrival_at=ComparisonValue(before=NOW.isoformat(), after=NOW.isoformat()),
        closes_at=ComparisonValue(before=None, after=None),
    )

    preview = RoutePreview(
        preview_id=uuid.uuid4(),
        detection_id=uuid.uuid4(),
        trip_id=uuid.uuid4(),
        date=date(2026, 9, 10),
        item_id=item_id,
        original_place=place,
        alternative_place=place,
        detection_reason="혼잡",
        comparison=comparison,
        route={},
        schedule_version=8,
        expires_at=NOW,
    )
    approved = Replacement(
        replacement_id=replacement_id,
        trip_id=uuid.uuid4(),
        date=date(2026, 9, 10),
        item_id=item_id,
        original_place_id="tourapi:1",
        new_place_id="tourapi:2",
        original_place_name="기존 장소",
        new_place_name="대체 장소",
        schedule_version=9,
        route_status="READY",
        undo_expires_at=NOW,
    )
    undo = ReplacementUndoResult(
        replacement_id=replacement_id,
        restored=True,
        schedule_version=10,
        route_status="FAILED",
        detection_restored=False,
    )
    undoable = UndoableReplacement(
        replacement_id=replacement_id,
        item_id=item_id,
        original_place_name="기존",
        new_place_name="변경",
        undo_expires_at=NOW,
    )

    assert preview.model_dump(mode="json", by_alias=True)["scheduleVersion"] == 8
    assert approved.route_status == "READY"
    assert undo.detection_restored is False
    assert undoable.model_dump(mode="json", by_alias=True)["undoExpiresAt"] == NOW.isoformat().replace("+00:00", "Z")
