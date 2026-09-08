"""F006 여행 진행 공개 계약."""

from __future__ import annotations

import uuid
from datetime import date, datetime
from enum import StrEnum
from typing import Literal

from pydantic import ConfigDict, Field
from pydantic.alias_generators import to_camel

from app.schemas.auth import ApiModel, ResponseMeta
from app.schemas.itinerary import ItemStatus
from app.schemas.route import TransportMode


class ProgressRequestModel(ApiModel):
    """요청에서 camelCase 필드만 허용하는 진행 모델."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=False,
        validate_by_alias=True,
        validate_by_name=False,
        extra="forbid",
    )


class DayStatus(StrEnum):
    NOT_STARTED = "NOT_STARTED"
    IN_PROGRESS = "IN_PROGRESS"
    COMPLETED = "COMPLETED"


class ProgressTargetStatus(StrEnum):
    ARRIVED = "ARRIVED"
    COMPLETED = "COMPLETED"
    SKIPPED = "SKIPPED"
    PLANNED = "PLANNED"


class InboundTravelSource(StrEnum):
    PLANNED_ROUTE = "PLANNED_ROUTE"
    COMPUTED = "COMPUTED"


class ProgressErrorCode(StrEnum):
    INVALID_REQUEST = "INVALID_REQUEST"
    INVALID_ACCESS_TOKEN = "INVALID_ACCESS_TOKEN"
    TRIP_FORBIDDEN = "TRIP_FORBIDDEN"
    TRIP_NOT_FOUND = "TRIP_NOT_FOUND"
    ITINERARY_ITEM_NOT_FOUND = "ITINERARY_ITEM_NOT_FOUND"
    VERSION_CONFLICT = "VERSION_CONFLICT"
    IDEMPOTENCY_KEY_CONFLICT = "IDEMPOTENCY_KEY_CONFLICT"
    DAY_NOT_TODAY = "DAY_NOT_TODAY"
    DAY_EMPTY = "DAY_EMPTY"
    DAY_NOT_STARTED = "DAY_NOT_STARTED"
    INVALID_STATUS_TRANSITION = "INVALID_STATUS_TRANSITION"
    TRANSITION_NOT_PENDING = "TRANSITION_NOT_PENDING"
    INVALID_DECISION = "INVALID_DECISION"
    UNDO_WINDOW_EXPIRED = "UNDO_WINDOW_EXPIRED"
    TRANSITION_NOT_UNDOABLE = "TRANSITION_NOT_UNDOABLE"


class ProgressEventType(StrEnum):
    DWELL = "DWELL"
    EXIT = "EXIT"
    REENTER = "REENTER"


class TransitionType(StrEnum):
    ARRIVAL = "ARRIVAL"
    DEPARTURE = "DEPARTURE"


class TransitionDecision(StrEnum):
    CONFIRM = "CONFIRM"
    NOT_ARRIVED = "NOT_ARRIVED"
    STILL_HERE = "STILL_HERE"


class RejectionReason(StrEnum):
    LOW_ACCURACY = "LOW_ACCURACY"
    STALE = "STALE"
    DAY_NOT_IN_PROGRESS = "DAY_NOT_IN_PROGRESS"
    ITEM_NOT_ELIGIBLE = "ITEM_NOT_ELIGIBLE"
    DETECTION_PAUSED = "DETECTION_PAUSED"
    PROMPT_LIMIT_REACHED = "PROMPT_LIMIT_REACHED"
    DEPARTURE_DETECTION_STOPPED = "DEPARTURE_DETECTION_STOPPED"


class EventLocation(ProgressRequestModel):
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    accuracy_meters: float = Field(ge=0)


class ProgressEventRequest(ProgressRequestModel):
    event_id: uuid.UUID
    event_type: ProgressEventType
    item_id: uuid.UUID
    geofence_id: str
    occurred_at: datetime
    location: EventLocation


class TransitionCandidate(ApiModel):
    transition_id: uuid.UUID
    item_id: uuid.UUID
    type: TransitionType
    status: Literal["PENDING_CONFIRMATION"]
    detected_at: datetime
    auto_finalize_at: datetime
    allowed_decisions: list[TransitionDecision]
    evidence: CandidateEvidence


class ProgressEventResult(ApiModel):
    event_id: uuid.UUID
    accepted: bool
    rejection_reason: RejectionReason | None
    candidate: TransitionCandidate | None
    cancelled_transition_id: uuid.UUID | None


class DecisionRequest(ProgressRequestModel):
    decision: TransitionDecision


class AffectedItem(ApiModel):
    item_id: uuid.UUID
    before_status: ItemStatus
    after_status: ItemStatus


class TransitionResult(ApiModel):
    transition_id: uuid.UUID
    status: Literal["CONFIRMED", "AUTO_CONFIRMED", "CANCELLED"]
    affected_items: list[AffectedItem]
    day_status: Literal["IN_PROGRESS", "COMPLETED"] | None
    undo_deadline: datetime | None
    next_prompt_at: datetime | None
    progress_version: int


class UndoResult(ApiModel):
    transition_id: uuid.UUID
    status: Literal["UNDONE"]
    restored_items: list[AffectedItem]
    day_status: Literal["IN_PROGRESS", "COMPLETED"] | None
    detection_resume_at: datetime
    progress_version: int


class CurrentLocation(ProgressRequestModel):
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    accuracy_meters: float = Field(ge=0)
    occurred_at: datetime


class StartDayProgressRequest(ProgressRequestModel):
    progress_version: int = Field(ge=0)
    current_location: CurrentLocation | None = None


class UpdateItemProgressStatusRequest(ProgressRequestModel):
    status: ProgressTargetStatus
    progress_version: int = Field(ge=1)


class StartLocation(ApiModel):
    latitude: float
    longitude: float


class InboundTravel(ApiModel):
    from_item_id: uuid.UUID | None
    transport_mode: TransportMode
    duration_seconds: int = Field(ge=0)
    distance_meters: int = Field(ge=0)
    source: InboundTravelSource


class ProgressItem(ApiModel):
    item_id: uuid.UUID
    sequence: int = Field(ge=1, le=10)
    status: ItemStatus
    estimated_arrival_at: datetime | None
    estimated_departure_at: datetime | None
    actual_arrived_at: datetime | None
    completed_at: datetime | None
    inbound_travel: InboundTravel | None


class DetectionTarget(ApiModel):
    """F007이 실제 값을 채우는 위치 감지 대상 계약."""

    item_id: uuid.UUID
    kind: Literal["ARRIVAL", "DEPARTURE"]
    geofence_id: str
    latitude: float
    longitude: float
    radius_meters: int = Field(ge=1)
    dwell_minutes: int | None


class CandidateEvidence(ApiModel):
    """자동 감지 후보를 사용자에게 설명하는 최소 근거."""

    occurred_at: datetime
    accuracy_meters: float
    dwell_minutes: int | None = None


class PendingCandidate(ApiModel):
    """F007 확인 응답을 기다리는 진행 전환 후보."""

    transition_id: uuid.UUID
    item_id: uuid.UUID
    type: Literal["ARRIVAL", "DEPARTURE"]
    status: Literal["PENDING_CONFIRMATION"]
    detected_at: datetime
    auto_finalize_at: datetime
    allowed_decisions: list[Literal["CONFIRM", "NOT_ARRIVED", "STILL_HERE"]]
    evidence: CandidateEvidence


class UndoableTransition(ApiModel):
    """F007 자동 확정 후 되돌릴 수 있는 전환 계약."""

    transition_id: uuid.UUID
    item_id: uuid.UUID
    type: Literal["ARRIVAL", "DEPARTURE", "COMPOSITE"]
    confirmed_at: datetime
    undo_deadline: datetime


class ProgressData(ApiModel):
    trip_id: uuid.UUID
    date: date
    day_status: DayStatus
    progress_version: int = Field(ge=0)
    schedule_version: int = Field(ge=0)
    actual_started_at: datetime | None
    completed_at: datetime | None
    start_location: StartLocation | None
    current_item_id: uuid.UUID | None
    next_item_id: uuid.UUID | None
    items: list[ProgressItem]
    # F007 구현 전에는 호출자가 []·null·null을 명시해 비활성 상태를 반환한다.
    detection_targets: list[DetectionTarget]
    pending_candidate: PendingCandidate | None
    undoable: UndoableTransition | None


class ProgressEnvelope(ApiModel):
    success: Literal[True]
    data: ProgressData
    meta: ResponseMeta


class ProgressEventEnvelope(ApiModel):
    success: Literal[True]
    data: ProgressEventResult
    meta: ResponseMeta


class TransitionResultEnvelope(ApiModel):
    success: Literal[True]
    data: TransitionResult
    meta: ResponseMeta


class UndoResultEnvelope(ApiModel):
    success: Literal[True]
    data: UndoResult
    meta: ResponseMeta


__all__ = [
    "AffectedItem", "CandidateEvidence", "CurrentLocation", "DayStatus",
    "DecisionRequest", "DetectionTarget", "EventLocation", "InboundTravel",
    "InboundTravelSource", "ProgressData", "ProgressEnvelope",
    "ProgressErrorCode", "ProgressEventEnvelope", "ProgressEventRequest",
    "ProgressEventResult",
    "ProgressEventType", "ProgressItem", "ProgressTargetStatus", "PendingCandidate",
    "RejectionReason", "StartDayProgressRequest", "StartLocation",
    "TransitionCandidate", "TransitionDecision", "TransitionResult",
    "TransitionResultEnvelope",
    "TransitionType", "UndoResult", "UndoResultEnvelope", "UndoableTransition",
    "UpdateItemProgressStatusRequest",
]
