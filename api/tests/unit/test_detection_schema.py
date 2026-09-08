"""F007 위치 감지 Pydantic schema의 공개 계약을 검증한다."""

import uuid
from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from app.schemas.progress import (
    DecisionRequest,
    DetectionTarget,
    ProgressErrorCode,
    ProgressEventRequest,
    ProgressEventResult,
    RejectionReason,
    TransitionCandidate,
    TransitionResult,
    UndoResult,
)


NOW = datetime(2026, 9, 8, tzinfo=UTC)


def test_progress_event_request_validates_location_and_uses_camel_case() -> None:
    payload = {
        "eventId": str(uuid.uuid4()),
        "eventType": "DWELL",
        "itemId": str(uuid.uuid4()),
        "geofenceId": "arrival-geofence",
        "occurredAt": NOW.isoformat(),
        "location": {"latitude": 37.5, "longitude": 127.0, "accuracyMeters": 10},
    }

    assert ProgressEventRequest.model_validate(payload).event_type == "DWELL"

    payload["location"]["latitude"] = 91
    with pytest.raises(ValidationError):
        ProgressEventRequest.model_validate(payload)


def test_progress_event_result_requires_nullable_contract_fields() -> None:
    payload = {
        "eventId": str(uuid.uuid4()),
        "accepted": False,
        "rejectionReason": "LOW_ACCURACY",
        "candidate": None,
        "cancelledTransitionId": None,
    }

    result = ProgressEventResult.model_validate(payload)

    assert result.rejection_reason is RejectionReason.LOW_ACCURACY
    assert result.model_dump(mode="json", by_alias=True) == payload

    del payload["candidate"]
    with pytest.raises(ValidationError):
        ProgressEventResult.model_validate(payload)


def test_transition_candidate_contains_allowed_decisions_and_evidence() -> None:
    candidate = TransitionCandidate.model_validate(
        {
            "transitionId": str(uuid.uuid4()),
            "itemId": str(uuid.uuid4()),
            "type": "ARRIVAL",
            "status": "PENDING_CONFIRMATION",
            "detectedAt": NOW.isoformat(),
            "autoFinalizeAt": NOW.isoformat(),
            "allowedDecisions": ["CONFIRM", "NOT_ARRIVED"],
            "evidence": {
                "occurredAt": NOW.isoformat(),
                "accuracyMeters": 12.5,
                "dwellMinutes": 5,
            },
        }
    )

    assert candidate.allowed_decisions == ["CONFIRM", "NOT_ARRIVED"]
    assert candidate.evidence.dwell_minutes == 5


def test_decision_transition_undo_and_detection_target_match_contract() -> None:
    assert DecisionRequest.model_validate({"decision": "STILL_HERE"}).decision == "STILL_HERE"

    transition = TransitionResult.model_validate(
        {
            "transitionId": str(uuid.uuid4()),
            "status": "CONFIRMED",
            "affectedItems": [],
            "dayStatus": "IN_PROGRESS",
            "undoDeadline": None,
            "nextPromptAt": None,
            "progressVersion": 2,
        }
    )
    undo = UndoResult.model_validate(
        {
            "transitionId": str(uuid.uuid4()),
            "status": "UNDONE",
            "restoredItems": [],
            "dayStatus": "IN_PROGRESS",
            "detectionResumeAt": NOW.isoformat(),
            "progressVersion": 3,
        }
    )
    target = DetectionTarget.model_validate(
        {
            "itemId": str(uuid.uuid4()),
            "kind": "DEPARTURE",
            "geofenceId": "departure-geofence",
            "latitude": 37.5,
            "longitude": 127.0,
            "radiusMeters": 100,
            "dwellMinutes": None,
        }
    )

    assert transition.undo_deadline is None
    assert undo.status == "UNDONE"
    assert target.radius_meters == 100


def test_rejection_reasons_and_new_error_codes_match_contract() -> None:
    assert {reason.value for reason in RejectionReason} == {
        "LOW_ACCURACY",
        "STALE",
        "DAY_NOT_IN_PROGRESS",
        "ITEM_NOT_ELIGIBLE",
        "DETECTION_PAUSED",
        "PROMPT_LIMIT_REACHED",
        "DEPARTURE_DETECTION_STOPPED",
    }
    assert {
        "TRANSITION_NOT_PENDING",
        "INVALID_DECISION",
        "UNDO_WINDOW_EXPIRED",
        "TRANSITION_NOT_UNDOABLE",
    } <= {code.value for code in ProgressErrorCode}
