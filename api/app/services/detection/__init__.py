"""위치 감지 이벤트 처리와 여행 변수 감지 기반을 공개한다."""

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
    UndoableTransition,
    UndoResult,
)
from app.services.eta import recalculate_day_eta
from app.services.notification import NotificationService
from app.services.progress import apply_manual_transition

MAX_ACCURACY_METERS = 100
MAX_EVENT_AGE_SECONDS = 120
MAX_ARRIVAL_PROMPTS = 2
REPROMPT_DELAY_MINUTES = 10
AUTO_FINALIZE_MINUTES = 5
# 무응답 자동 확정 뒤 되돌릴 수 있는 시간(분). requirements.md PROG-07이 확정한 값.
UNDO_WINDOW_MINUTES = 5
# 도착 후보를 파생 판정할 때 함께 세는 전환 종류. COMPOSITE는 도착이 이전 완료를
# 함께 처리한 전환이므로 도착 질문 횟수·재개 시각 계산에 포함한다.
_ARRIVAL_TRANSITION_TYPES = ("ARRIVAL", "COMPOSITE")
# 같은 장소의 자동 출발 확정을 이 횟수만큼 되돌리면 그 날짜 동안 자동 출발을 멈춘다(FR-017a).
DEPARTURE_UNDO_STOP_COUNT = 2
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
        """이벤트를 한 번만 저장하고 종류에 따라 도착·출발 후보를 만들거나 취소한다.

        먼저 그 날짜의 만료된 무응답 후보를 자동 확정한다(지연 확정). 이어서
        유효한 `DWELL`은 도착 후보, `EXIT`는 출발 후보를 만들고, `REENTER`는
        살아 있는 출발 후보를 취소한다. 기준 미충족 이벤트도 저장하되
        `accepted=False`로 둔다.

        Args:
            user_id: 요청 사용자 ID.
            trip_id: 이벤트가 속한 여행 ID.
            visit_date: 이벤트가 속한 방문 날짜.
            payload: 기기가 전송한 위치 이벤트.
            received_at: 테스트에서 주입할 서버 수신 시각.

        Returns:
            이벤트 수락 여부, 생성된 후보, 재진입으로 취소된 후보 ID.

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
        # 지연 확정: 이 요청을 처리하기 전에 만료된 후보를 먼저 자동 확정한다.
        await self.finalize_due_candidates(day, now=now)
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
        event_type = payload.event_type.value
        if reason is None and event_type in ("DWELL", "EXIT"):
            history = list(
                (
                    await self.session.scalars(
                        select(ProgressTransition).where(
                            ProgressTransition.trip_day_id == day.trip_day_id,
                            ProgressTransition.primary_item_id == item.item_id,
                        )
                    )
                ).all()
            )
            reason = (
                self._arrival_block_reason(history, now)
                if event_type == "DWELL"
                else self._departure_block_reason(history, now)
            )
        event = ProgressEvent(
            client_event_id=payload.event_id,
            trip_day_id=day.trip_day_id,
            item_id=item.item_id,
            event_type=event_type,
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
        cancelled_id = None
        if reason is None and event_type in ("DWELL", "EXIT"):
            is_departure = event_type == "EXIT"
            transition = ProgressTransition(
                trip_day_id=day.trip_day_id,
                primary_item_id=item.item_id,
                trigger_event_id=event.progress_event_id,
                transition_type="DEPARTURE" if is_departure else "ARRIVAL",
                status="PENDING_CONFIRMATION",
                source="GEOFENCE_EXIT" if is_departure else "GEOFENCE_DWELL",
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
            # F011: 확인 후보 생성 직후 도착·출발 확인 알림을 같은 transaction에서 만든다.
            await NotificationService(self.session, now=lambda: now).create_transition_check(
                transition, prompt_seq=1
            )
        elif event_type == "REENTER":
            cancelled_id = await self._cancel_pending_departure(day, item, now)
        logger.info(
            {
                "operation": "REGISTER_PROGRESS_EVENT",
                "client_event_id": str(payload.event_id),
                "accepted": event.accepted,
                "rejection_reason": reason,
            }
        )
        return self._result(event, candidate, cancelled_id)

    async def _cancel_pending_departure(
        self, day: TripDay, item: ItineraryItem, now: datetime
    ) -> uuid.UUID | None:
        """재진입 시 살아 있는 출발 후보를 취소하고 그 ID를 돌려준다(FR-012)."""
        pending = await self.session.scalar(
            select(ProgressTransition)
            .where(
                ProgressTransition.trip_day_id == day.trip_day_id,
                ProgressTransition.primary_item_id == item.item_id,
                ProgressTransition.transition_type == "DEPARTURE",
                ProgressTransition.status == "PENDING_CONFIRMATION",
            )
            .with_for_update()
        )
        if pending is None:
            return None
        pending.status, pending.cancelled_at = "CANCELLED", now
        await self.session.flush()
        logger.info(
            {
                "operation": "CANCEL_DEPARTURE_CANDIDATE",
                "transition_id": str(pending.transition_id),
                "result": "REENTER",
            }
        )
        return pending.transition_id

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
        # 지연 확정: 만료된 다른 후보(또는 이 후보 자체)를 먼저 자동 확정한다.
        await self.finalize_due_candidates(day, now=now)
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
        if decision in ("NOT_ARRIVED", "STILL_HERE"):
            # NOT_ARRIVED(도착 거절)와 STILL_HERE(아직 머무는 중) 모두 후보만 취소하고
            # 상태를 바꾸지 않는다. NOT_ARRIVED만 재질문 시각을 돌려준다(FR-007).
            # STILL_HERE는 그 날짜의 자동 출발을 멈춘다(FR-013, _departure_block_reason).
            transition.status, transition.cancelled_at = "CANCELLED", now
            next_at = None
            if decision == "NOT_ARRIVED":
                count = await self.session.scalar(
                    select(func.count())
                    .select_from(ProgressTransition)
                    .where(
                        ProgressTransition.trip_day_id == day.trip_day_id,
                        ProgressTransition.primary_item_id
                        == transition.primary_item_id,
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

    async def finalize_due_candidates(
        self, day: TripDay | None, *, now: datetime | None = None
    ) -> None:
        """만료된 무응답 후보를 저장된 auto_finalize_at 기준으로 자동 확정한다.

        주기 작업자 없이(지연 확정, research 2절), 그 날짜에 대한 다음 요청을
        처리하기 전에 호출한다. 확정 시각과 되돌리기 마감은 실행 시각이 아니라
        후보에 저장된 `auto_finalize_at`을 기준으로 기록한다(FR-015a·FR-018).

        Args:
            day: 확정 대상을 찾을 여행 날짜 aggregate. items가 로드돼 있어야 한다.
            now: 만료 판정에 쓸 서버 시각. 테스트에서 주입한다.
        """
        if day is None:
            return
        now = now or datetime.now(UTC)
        due = list(
            (
                await self.session.scalars(
                    select(ProgressTransition)
                    .where(
                        ProgressTransition.trip_day_id == day.trip_day_id,
                        ProgressTransition.status == "PENDING_CONFIRMATION",
                        ProgressTransition.auto_finalize_at.is_not(None),
                        ProgressTransition.auto_finalize_at <= now,
                    )
                    .order_by(ProgressTransition.auto_finalize_at)
                    .with_for_update()
                )
            ).all()
        )
        for transition in due:
            await self._auto_confirm(day, transition, now=now)

    async def _auto_confirm(
        self, day: TripDay, transition: ProgressTransition, *, now: datetime | None = None
    ) -> None:
        """무응답 후보 하나를 F006 전환 규칙으로 확정하거나, 대상이 바뀌었으면 취소한다."""
        now = now or datetime.now(UTC)
        finalized_at = transition.auto_finalize_at
        item = next(
            (x for x in day.items if x.item_id == transition.primary_item_id), None
        )
        is_arrival = transition.transition_type in _ARRIVAL_TRANSITION_TYPES
        eligible = item is not None and (
            (is_arrival and item.status == "EN_ROUTE")
            or (not is_arrival and item.status == "ARRIVED")
        )
        if not eligible:
            transition.status, transition.cancelled_at = "CANCELLED", finalized_at
            await self.session.flush()
            logger.info(
                {
                    "operation": "AUTO_CONFIRM_PROGRESS_TRANSITION",
                    "transition_id": str(transition.transition_id),
                    "result": "CANCELLED",
                }
            )
            return
        composite = is_arrival and any(
            x.status == "ARRIVED" and x.item_id != item.item_id for x in day.items
        )
        affected, _ = apply_manual_transition(
            day, item, "ARRIVED" if is_arrival else "COMPLETED", finalized_at
        )
        day.progress_version += 1
        transition.transition_type = (
            "COMPOSITE" if composite else transition.transition_type
        )
        transition.status, transition.source = "AUTO_CONFIRMED", "GEOFENCE_AUTO"
        transition.affected_items, transition.confirmed_at = affected, finalized_at
        transition.undo_deadline = finalized_at + timedelta(
            minutes=UNDO_WINDOW_MINUTES
        )
        transition.schedule_version_after = day.schedule_version
        transition.progress_version_after = day.progress_version
        await recalculate_day_eta(self.session, day.trip_day_id)
        await self.session.flush()
        # F011: 자동 확정 직후 되돌리기 안내 알림을 같은 transaction에서 만든다.
        await NotificationService(
            self.session, now=lambda: now
        ).create_transition_auto_confirmed(transition)
        logger.info(
            {
                "operation": "AUTO_CONFIRM_PROGRESS_TRANSITION",
                "transition_id": str(transition.transition_id),
                "result": "AUTO_CONFIRMED",
            }
        )

    async def undo_transition(
        self,
        *,
        user_id: uuid.UUID,
        transition_id: uuid.UUID,
        idempotency_key: uuid.UUID,
        undone_at: datetime | None = None,
    ) -> UndoResult:
        """무응답으로 자동 확정된 전환을 되돌린다(PROG-005).

        `affected_items`에 기록된 모든 상태 변경과 날짜 상태 스냅샷을 확정 직전으로
        복원하고 ETA를 다시 계산한다(FR-017·FR-019). 만료는 서버 시각으로 판정한다.

        Args:
            user_id: 요청 사용자 ID.
            transition_id: 되돌릴 전환 ID.
            idempotency_key: 되돌리기 요청의 멱등 키.
            undone_at: 테스트에서 주입할 서버 되돌리기 시각.

        Returns:
            복원 결과와 감지 재개 시각.

        Raises:
            AppError: 전환이 없거나, 소유권이 없거나, 자동 확정이 아니거나,
                되돌릴 수 있는 시간이 지난 경우.
        """
        now = undone_at or datetime.now(UTC)
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
        if transition.status == "UNDONE":
            if (
                transition.idempotency_key == idempotency_key
                and transition.response_snapshot
            ):
                return UndoResult.model_validate(transition.response_snapshot)
            raise AppError(409, "TRANSITION_NOT_UNDOABLE", "이미 되돌린 진행 전환입니다.")
        if transition.status != "AUTO_CONFIRMED":
            raise AppError(
                409,
                "TRANSITION_NOT_UNDOABLE",
                "무응답으로 자동 확정된 전환만 되돌릴 수 있습니다.",
            )
        if transition.undo_deadline is None or now >= transition.undo_deadline:
            raise AppError(409, "UNDO_WINDOW_EXPIRED", "되돌릴 수 있는 시간이 지났습니다.")
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
        items_by_id = {x.item_id: x for x in day.items}
        restored: list[dict[str, str]] = []
        day_restored = False
        for entry in transition.affected_items or []:
            if "itemId" in entry:
                target = items_by_id.get(uuid.UUID(entry["itemId"]))
                if target is None or target.status != entry["afterStatus"]:
                    continue
                target.status = entry["beforeStatus"]
                self._reset_actuals(target, entry["beforeStatus"])
                restored.append(
                    {
                        "itemId": str(target.item_id),
                        "beforeStatus": entry["afterStatus"],
                        "afterStatus": entry["beforeStatus"],
                    }
                )
            elif "dayStatusBefore" in entry:
                day.status = entry["dayStatusBefore"]
                day_restored = True
                if day.status == "IN_PROGRESS":
                    day.completed_at = None
                    day.detection_active = True
        day.progress_version += 1
        transition.status, transition.undone_at = "UNDONE", now
        transition.idempotency_key = idempotency_key
        transition.schedule_version_after = day.schedule_version
        transition.progress_version_after = day.progress_version
        await recalculate_day_eta(self.session, day.trip_day_id)
        result = UndoResult(
            transition_id=transition.transition_id,
            status="UNDONE",
            restored_items=restored,
            day_status=day.status if day_restored else None,
            detection_resume_at=now + timedelta(minutes=REPROMPT_DELAY_MINUTES),
            progress_version=day.progress_version,
        )
        transition.response_snapshot = result.model_dump(mode="json", by_alias=True)
        await self.session.flush()
        logger.info(
            {
                "operation": "UNDO_PROGRESS_TRANSITION",
                "transition_id": str(transition.transition_id),
                "result": "UNDONE",
            }
        )
        return result

    async def current_undoable(
        self, day: TripDay | None, *, now: datetime | None = None
    ) -> UndoableTransition | None:
        """되돌리기 창이 아직 열린 자동 확정 전환을 PROG-001 응답용으로 반환한다."""
        if day is None:
            return None
        now = now or datetime.now(UTC)
        transition = await self.session.scalar(
            select(ProgressTransition)
            .where(
                ProgressTransition.trip_day_id == day.trip_day_id,
                ProgressTransition.status == "AUTO_CONFIRMED",
                ProgressTransition.undo_deadline.is_not(None),
                ProgressTransition.undo_deadline > now,
            )
            .order_by(ProgressTransition.confirmed_at.desc())
        )
        if transition is None:
            return None
        return UndoableTransition(
            transition_id=transition.transition_id,
            item_id=transition.primary_item_id,
            type=transition.transition_type,
            confirmed_at=transition.confirmed_at,
            undo_deadline=transition.undo_deadline,
        )

    @staticmethod
    def _reset_actuals(item: ItineraryItem, status: str) -> None:
        """되돌린 항목의 실제 시각 필드를 복원 상태에 맞춰 지운다."""
        if status in ("PLANNED", "EN_ROUTE"):
            item.actual_arrived_at = None
            item.actual_departed_at = None
            item.completed_at = None
        elif status == "ARRIVED":
            item.actual_departed_at = None
            item.completed_at = None

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
        now = datetime.now(UTC)
        targets = []
        for item in items:
            kind = "ARRIVAL" if item.status == "EN_ROUTE" else "DEPARTURE"
            history = [
                x for x in transitions if x.primary_item_id == item.item_id
            ]
            if kind == "ARRIVAL" and self._arrival_block_reason(history, now):
                continue
            if kind == "DEPARTURE" and self._departure_block_reason(history, now):
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
        """도착 후보 이력에서 현재 생성 차단 사유를 계산한다.

        되돌린 자동 도착은 그 시각부터 재질문 간격만큼 감지를 쉬고(FR-017a),
        그 질문도 장소별 상한(FR-008)에 포함해 무한 반복을 막는다.
        """
        arrivals = [
            x for x in transitions if x.transition_type in _ARRIVAL_TRANSITION_TYPES
        ]
        if any(x.status == "PENDING_CONFIRMATION" for x in arrivals):
            return "DETECTION_PAUSED"
        if len(arrivals) >= MAX_ARRIVAL_PROMPTS:
            return "PROMPT_LIMIT_REACHED"
        resume_bases = [
            x.cancelled_at
            for x in arrivals
            if x.decision == "NOT_ARRIVED" and x.cancelled_at is not None
        ] + [x.undone_at for x in arrivals if x.undone_at is not None]
        last = max(resume_bases, default=None)
        if last is not None and now < last + timedelta(minutes=REPROMPT_DELAY_MINUTES):
            return "DETECTION_PAUSED"
        return None

    @staticmethod
    def _departure_block_reason(
        transitions: Sequence[ProgressTransition], now: datetime
    ) -> str | None:
        """출발 후보 이력에서 현재 생성 차단 사유를 계산한다.

        `아직 머무는 중`(FR-013)이나 자동 출발 되돌리기 2회(FR-017a)면 그 날짜 동안
        멈추고, 되돌린 직후에는 재질문 간격만큼 쉬었다가 재개한다.
        """
        departures = [x for x in transitions if x.transition_type == "DEPARTURE"]
        if any(x.status == "PENDING_CONFIRMATION" for x in departures):
            return "DETECTION_PAUSED"
        if any(x.decision == "STILL_HERE" for x in departures):
            return "DEPARTURE_DETECTION_STOPPED"
        undo_count = sum(1 for x in departures if x.undone_at is not None)
        if undo_count >= DEPARTURE_UNDO_STOP_COUNT:
            return "DEPARTURE_DETECTION_STOPPED"
        last_undo = max(
            (x.undone_at for x in departures if x.undone_at is not None),
            default=None,
        )
        if last_undo is not None and now < last_undo + timedelta(
            minutes=REPROMPT_DELAY_MINUTES
        ):
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
        cancelled_transition_id: uuid.UUID | None = None,
    ) -> ProgressEventResult:
        return ProgressEventResult(
            event_id=event.client_event_id,
            accepted=event.accepted,
            rejection_reason=event.rejection_reason,
            candidate=candidate,
            cancelled_transition_id=cancelled_transition_id,
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
        is_departure = transition.transition_type == "DEPARTURE"
        return TransitionCandidate(
            transition_id=transition.transition_id,
            item_id=transition.primary_item_id,
            type=transition.transition_type,
            status="PENDING_CONFIRMATION",
            detected_at=transition.detected_at,
            auto_finalize_at=transition.auto_finalize_at,
            allowed_decisions=(
                ["CONFIRM", "STILL_HERE"]
                if is_departure
                else ["CONFIRM", "NOT_ARRIVED"]
            ),
            evidence=CandidateEvidence(
                occurred_at=event.occurred_at,
                accuracy_meters=float(event.accuracy_meters),
                dwell_minutes=None if is_departure else 5,
            ),
        )


__all__ = ["DetectionService"]
