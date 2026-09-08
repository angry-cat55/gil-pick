"""위치 이벤트 검증과 도착 후보 처리 서비스."""

from __future__ import annotations

import logging
import uuid
from collections.abc import Sequence
from datetime import UTC, date, datetime, timedelta

from geoalchemy2 import Geometry, WKTElement
from sqlalchemy import cast, func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.api.errors import AppError
from app.models.itinerary import ItineraryItem, Place, TripDay
from app.models.progress import ProgressEvent, ProgressTransition
from app.models.trip import Trip
from app.schemas.progress import (
    CandidateEvidence,
    DetectionTarget,
    PendingCandidate,
    ProgressEventRequest,
    ProgressEventResult,
    TransitionCandidate,
    TransitionResult,
)
from app.services.eta import recalculate_day_eta
from app.services.progress import apply_manual_transition

MAX_ACCURACY_METERS = 100
MAX_EVENT_AGE_SECONDS = 120
MAX_ARRIVAL_PROMPTS = 2
REPROMPT_DELAY_MINUTES = 10
AUTO_FINALIZE_MINUTES = 5
logger = logging.getLogger("gilpick.detection")


class DetectionService:
    """위치 이벤트를 저장하고 자동 감지 후보를 처리한다."""

    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def register_event(
        self,
        *,
        user_id: uuid.UUID,
        trip_id: uuid.UUID,
        visit_date: date,
        payload: ProgressEventRequest,
        received_at: datetime | None = None,
    ) -> ProgressEventResult:
        """이벤트를 한 번만 저장하고 유효한 DWELL이면 도착 후보를 만든다.

        Args:
            user_id: 요청 사용자 ID.
            trip_id: 이벤트가 속한 여행 ID.
            visit_date: 이벤트가 속한 방문 날짜.
            payload: 기기가 전송한 위치 이벤트.
            received_at: 테스트에서 주입할 서버 수신 시각.

        Returns:
            이벤트 수락 여부와 생성된 후보.

        Raises:
            AppError: 여행 또는 일정 항목이 없거나 소유권이 없는 경우.
        """
        now = received_at or datetime.now(UTC)
        trip = await self.session.get(Trip, trip_id)
        if trip is None or trip.deleted_at is not None:
            raise AppError(404, "TRIP_NOT_FOUND", "여행을 찾을 수 없습니다.")
        if trip.user_id != user_id:
            raise AppError(403, "TRIP_FORBIDDEN", "다른 사용자의 여행에는 접근할 수 없습니다.")
        day = await self.session.scalar(
            select(TripDay)
            .options(selectinload(TripDay.items))
            .where(TripDay.trip_id == trip_id, TripDay.visit_date == visit_date)
            .with_for_update()
        )
        if day is None:
            raise AppError(404, "TRIP_NOT_FOUND", "해당 날짜의 여행을 찾을 수 없습니다.")
        existing = await self.session.scalar(
            select(ProgressEvent).where(
                ProgressEvent.client_event_id == payload.event_id
            )
        )
        if existing is not None:
            if existing.trip_day_id != day.trip_day_id:
                raise AppError(403, "TRIP_FORBIDDEN", "다른 여행의 이벤트에는 접근할 수 없습니다.")
            return await self._event_result(existing)
        item = next((x for x in day.items if x.item_id == payload.item_id), None)
        if item is None:
            exists = await self.session.get(ItineraryItem, payload.item_id)
            if exists is not None:
                raise AppError(403, "TRIP_FORBIDDEN", "다른 여행의 일정에는 접근할 수 없습니다.")
            raise AppError(404, "ITINERARY_ITEM_NOT_FOUND", "일정 항목을 찾을 수 없습니다.")
        reason = self._rejection_reason(day, item, payload, now)
        if reason is None and payload.event_type.value == "DWELL":
            history = list(
                (
                    await self.session.scalars(
                        select(ProgressTransition).where(
                            ProgressTransition.trip_day_id == day.trip_day_id,
                            ProgressTransition.primary_item_id == item.item_id,
                            ProgressTransition.transition_type == "ARRIVAL",
                        )
                    )
                ).all()
            )
            reason = self._arrival_block_reason(history, now)
        event = ProgressEvent(
            client_event_id=payload.event_id,
            trip_day_id=day.trip_day_id,
            item_id=item.item_id,
            event_type=payload.event_type.value,
            geofence_id=payload.geofence_id,
            location=WKTElement(
                f"POINT({payload.location.longitude} {payload.location.latitude})",
                srid=4326,
            ),
            accuracy_meters=payload.location.accuracy_meters,
            occurred_at=payload.occurred_at,
            received_at=now,
            accepted=reason is None,
            rejection_reason=reason,
        )
        self.session.add(event)
        await self.session.flush()
        candidate = None
        if reason is None and payload.event_type.value == "DWELL":
            transition = ProgressTransition(
                trip_day_id=day.trip_day_id,
                primary_item_id=item.item_id,
                trigger_event_id=event.progress_event_id,
                transition_type="ARRIVAL",
                status="PENDING_CONFIRMATION",
                source="GEOFENCE_DWELL",
                affected_items=[],
                detected_at=now,
                auto_finalize_at=now + timedelta(minutes=AUTO_FINALIZE_MINUTES),
                schedule_version_before=day.schedule_version,
                progress_version_after=day.progress_version,
                idempotency_key=payload.event_id,
            )
            self.session.add(transition)
            await self.session.flush()
            candidate = self._candidate(transition, event)
        logger.info(
            {
                "operation": "REGISTER_PROGRESS_EVENT",
                "client_event_id": str(payload.event_id),
                "accepted": event.accepted,
                "rejection_reason": reason,
            }
        )
        return self._result(event, candidate)

    async def decide_transition(
        self,
        *,
        user_id: uuid.UUID,
        transition_id: uuid.UUID,
        decision: str,
        idempotency_key: uuid.UUID,
        decided_at: datetime | None = None,
    ) -> TransitionResult:
        """확인 대기 중인 후보에 사용자의 결정을 적용한다.

        Args:
            user_id: 요청 사용자 ID.
            transition_id: 처리할 후보 전환 ID.
            decision: 사용자가 선택한 결정.
            idempotency_key: 결정 요청의 멱등 키.
            decided_at: 테스트에서 주입할 서버 결정 시각.

        Returns:
            확정 또는 취소된 전환 결과.

        Raises:
            AppError: 후보가 없거나 처리할 수 없거나 소유권이 없는 경우.
        """
        now = decided_at or datetime.now(UTC)
        transition = await self.session.scalar(
            select(ProgressTransition)
            .options(
                selectinload(ProgressTransition.trip_day).selectinload(TripDay.items)
            )
            .where(ProgressTransition.transition_id == transition_id)
            .with_for_update()
        )
        if transition is None:
            raise AppError(404, "TRANSITION_NOT_FOUND", "진행 전환을 찾을 수 없습니다.")
        day = transition.trip_day
        trip = await self.session.get(Trip, day.trip_id)
        if trip is None or trip.user_id != user_id:
            raise AppError(403, "TRIP_FORBIDDEN", "다른 여행의 진행 전환에는 접근할 수 없습니다.")
        if transition.status != "PENDING_CONFIRMATION":
            if (
                transition.idempotency_key == idempotency_key
                and transition.response_snapshot
            ):
                return TransitionResult.model_validate(transition.response_snapshot)
            raise AppError(409, "TRANSITION_NOT_PENDING", "이미 처리된 진행 전환입니다.")
        key_owner = await self.session.scalar(
            select(ProgressTransition).where(
                ProgressTransition.trip_day_id == day.trip_day_id,
                ProgressTransition.idempotency_key == idempotency_key,
                ProgressTransition.transition_id != transition.transition_id,
            )
        )
        if key_owner is not None:
            raise AppError(
                409,
                "IDEMPOTENCY_KEY_CONFLICT",
                "같은 Idempotency-Key를 다른 진행 전환 요청에 사용할 수 없습니다.",
            )
        allowed = {
            "ARRIVAL": {"CONFIRM", "NOT_ARRIVED"},
            "DEPARTURE": {"CONFIRM", "STILL_HERE"},
        }.get(transition.transition_type, set())
        if decision not in allowed:
            raise AppError(409, "INVALID_DECISION", "후보 종류에 맞지 않는 응답입니다.")
        transition.idempotency_key = idempotency_key
        transition.decision = decision
        if decision == "NOT_ARRIVED":
            transition.status, transition.cancelled_at = "CANCELLED", now
            count = await self.session.scalar(
                select(func.count())
                .select_from(ProgressTransition)
                .where(
                    ProgressTransition.trip_day_id == day.trip_day_id,
                    ProgressTransition.primary_item_id == transition.primary_item_id,
                    ProgressTransition.transition_type == "ARRIVAL",
                )
            )
            next_at = (
                now + timedelta(minutes=REPROMPT_DELAY_MINUTES)
                if (count or 0) < 2
                else None
            )
            result = TransitionResult(
                transition_id=transition.transition_id,
                status="CANCELLED",
                affected_items=[],
                day_status=None,
                undo_deadline=None,
                next_prompt_at=next_at,
                progress_version=day.progress_version,
            )
        else:
            item = next(
                (x for x in day.items if x.item_id == transition.primary_item_id),
                None,
            )
            eligible = item is not None and (
                (
                    transition.transition_type == "ARRIVAL"
                    and item.status == "EN_ROUTE"
                )
                or (
                    transition.transition_type == "DEPARTURE"
                    and item.status == "ARRIVED"
                )
            )
            if not eligible:
                transition.status, transition.cancelled_at = "CANCELLED", now
                raise AppError(
                    409,
                    "TRANSITION_NOT_PENDING",
                    "후보 대상의 상태가 이미 변경되었습니다.",
                )
            composite = transition.transition_type == "ARRIVAL" and any(
                x.status == "ARRIVED" and x.item_id != item.item_id for x in day.items
            )
            affected, _ = apply_manual_transition(
                day,
                item,
                "ARRIVED"
                if transition.transition_type == "ARRIVAL"
                else "COMPLETED",
                now,
            )
            day.progress_version += 1
            transition.transition_type = (
                "COMPOSITE" if composite else transition.transition_type
            )
            transition.status, transition.source = "CONFIRMED", "GEOFENCE_CONFIRMED"
            transition.affected_items, transition.confirmed_at = affected, now
            transition.schedule_version_after = day.schedule_version
            transition.progress_version_after = day.progress_version
            await recalculate_day_eta(self.session, day.trip_day_id)
            affected_items = [entry for entry in affected if "itemId" in entry]
            result = TransitionResult(
                transition_id=transition.transition_id,
                status="CONFIRMED",
                affected_items=affected_items,
                day_status=day.status,
                undo_deadline=None,
                next_prompt_at=None,
                progress_version=day.progress_version,
            )
        transition.response_snapshot = result.model_dump(mode="json", by_alias=True)
        await self.session.flush()
        logger.info(
            {
                "operation": "DECIDE_PROGRESS_TRANSITION",
                "transition_id": str(transition.transition_id),
                "decision": decision,
                "result": transition.status,
            }
        )
        return result

    async def build_state(
        self, day: TripDay
    ) -> tuple[list[DetectionTarget], PendingCandidate | None]:
        """날짜 상태와 전환 이력에서 감지 대상과 현재 후보를 산출한다.

        Args:
            day: 감지 상태를 계산할 여행 날짜 aggregate.

        Returns:
            현재 등록할 감지 대상 목록과 확인 대기 후보.
        """
        if day.status != "IN_PROGRESS" or not day.detection_active:
            return [], None
        transitions = list(
            (
                await self.session.scalars(
                    select(ProgressTransition)
                    .where(ProgressTransition.trip_day_id == day.trip_day_id)
                    .order_by(ProgressTransition.detected_at)
                )
            ).all()
        )
        pending = next(
            (
                x
                for x in reversed(transitions)
                if x.status == "PENDING_CONFIRMATION"
            ),
            None,
        )
        pending_data = None
        if pending is not None and pending.trigger_event_id:
            event = await self.session.get(ProgressEvent, pending.trigger_event_id)
            if event:
                pending_data = PendingCandidate.model_validate(
                    self._candidate(pending, event).model_dump()
                )
        items = [x for x in day.items if x.status in {"EN_ROUTE", "ARRIVED"}]
        if not items:
            return [], pending_data
        point = cast(Place.location, Geometry(geometry_type="POINT", srid=4326))
        rows = (
            await self.session.execute(
                select(ItineraryItem.item_id, func.ST_X(point), func.ST_Y(point))
                .join(Place, Place.place_id == ItineraryItem.place_id)
                .where(ItineraryItem.item_id.in_([x.item_id for x in items]))
            )
        ).all()
        coords = {row[0]: (float(row[2]), float(row[1])) for row in rows}
        targets = []
        for item in items:
            kind = "ARRIVAL" if item.status == "EN_ROUTE" else "DEPARTURE"
            history = [
                x for x in transitions if x.primary_item_id == item.item_id
            ]
            if kind == "ARRIVAL" and self._arrival_block_reason(
                history, datetime.now(UTC)
            ):
                continue
            lat, lon = coords[item.item_id]
            targets.append(
                DetectionTarget(
                    item_id=item.item_id,
                    kind=kind,
                    geofence_id=f"{item.item_id}:{kind}",
                    latitude=lat,
                    longitude=lon,
                    radius_meters=300 if kind == "ARRIVAL" else 400,
                    dwell_minutes=5 if kind == "ARRIVAL" else None,
                )
            )
        return targets, pending_data

    @staticmethod
    def _arrival_block_reason(
        transitions: Sequence[ProgressTransition], now: datetime
    ) -> str | None:
        """도착 후보 이력에서 현재 생성 차단 사유를 계산한다."""
        arrivals = [x for x in transitions if x.transition_type == "ARRIVAL"]
        if any(x.status == "PENDING_CONFIRMATION" for x in arrivals):
            return "DETECTION_PAUSED"
        if len(arrivals) >= MAX_ARRIVAL_PROMPTS:
            return "PROMPT_LIMIT_REACHED"
        last = max(
            (
                x.cancelled_at
                for x in arrivals
                if x.decision == "NOT_ARRIVED" and x.cancelled_at is not None
            ),
            default=None,
        )
        if last is not None and now < last + timedelta(minutes=REPROMPT_DELAY_MINUTES):
            return "DETECTION_PAUSED"
        return None

    @staticmethod
    def _rejection_reason(
        day: TripDay,
        item: ItineraryItem,
        payload: ProgressEventRequest,
        now: datetime,
    ) -> str | None:
        if payload.location.accuracy_meters > MAX_ACCURACY_METERS:
            return "LOW_ACCURACY"
        if abs((now - payload.occurred_at).total_seconds()) > MAX_EVENT_AGE_SECONDS:
            return "STALE"
        if day.status != "IN_PROGRESS" or not day.detection_active:
            return "DAY_NOT_IN_PROGRESS"
        kind = "ARRIVAL" if payload.event_type.value == "DWELL" else "DEPARTURE"
        if (
            item.trip_day_id != day.trip_day_id
            or item.status != ("EN_ROUTE" if kind == "ARRIVAL" else "ARRIVED")
            or payload.geofence_id != f"{item.item_id}:{kind}"
        ):
            return "ITEM_NOT_ELIGIBLE"
        return None

    @staticmethod
    def _result(
        event: ProgressEvent,
        candidate: TransitionCandidate | None = None,
    ) -> ProgressEventResult:
        return ProgressEventResult(
            event_id=event.client_event_id,
            accepted=event.accepted,
            rejection_reason=event.rejection_reason,
            candidate=candidate,
            cancelled_transition_id=None,
        )

    async def _event_result(self, event: ProgressEvent) -> ProgressEventResult:
        transition = await self.session.scalar(
            select(ProgressTransition).where(
                ProgressTransition.trigger_event_id == event.progress_event_id
            )
        )
        candidate = (
            self._candidate(transition, event)
            if transition is not None
            and transition.status == "PENDING_CONFIRMATION"
            else None
        )
        return self._result(event, candidate)

    @staticmethod
    def _candidate(
        transition: ProgressTransition, event: ProgressEvent
    ) -> TransitionCandidate:
        return TransitionCandidate(
            transition_id=transition.transition_id,
            item_id=transition.primary_item_id,
            type=transition.transition_type,
            status="PENDING_CONFIRMATION",
            detected_at=transition.detected_at,
            auto_finalize_at=transition.auto_finalize_at,
            allowed_decisions=["CONFIRM", "NOT_ARRIVED"],
            evidence=CandidateEvidence(
                occurred_at=event.occurred_at,
                accuracy_meters=float(event.accuracy_meters),
                dwell_minutes=5,
            ),
        )


__all__ = ["DetectionService"]
