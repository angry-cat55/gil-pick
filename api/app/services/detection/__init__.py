"""위치 감지 이벤트 처리와 여행 변수 감지 기반을 공개한다."""

from __future__ import annotations

import logging
import uuid
from datetime import UTC, date, datetime

from geoalchemy2 import WKTElement
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.errors import AppError
from app.models.itinerary import ItineraryItem, TripDay
from app.models.progress import ProgressEvent
from app.models.trip import Trip
from app.schemas.progress import ProgressEventRequest, ProgressEventResult

logger = logging.getLogger("gilpick.detection")

MAX_ACCURACY_METERS = 100
MAX_EVENT_AGE_SECONDS = 120


class DetectionService:
    """위치 이벤트를 검증해 판정 사용 여부와 함께 저장한다."""

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
        """이벤트를 한 번만 저장하고 같은 ID 재전송에는 최초 결과를 반환한다."""
        now = received_at or datetime.now(UTC)
        existing = await self.session.scalar(
            select(ProgressEvent).where(
                ProgressEvent.client_event_id == payload.event_id
            )
        )
        if existing is not None:
            return self._result(existing)

        trip = await self.session.get(Trip, trip_id)
        if trip is None or trip.deleted_at is not None:
            raise AppError(404, "TRIP_NOT_FOUND", "여행을 찾을 수 없습니다.")
        if trip.user_id != user_id:
            raise AppError(403, "TRIP_FORBIDDEN", "다른 사용자의 여행에는 접근할 수 없습니다.")

        day = await self.session.scalar(
            select(TripDay).where(
                TripDay.trip_id == trip_id,
                TripDay.visit_date == visit_date,
            )
        )
        if day is None:
            raise AppError(404, "TRIP_NOT_FOUND", "해당 날짜의 여행을 찾을 수 없습니다.")

        item = await self.session.get(ItineraryItem, payload.item_id)
        if item is None:
            raise AppError(404, "ITINERARY_ITEM_NOT_FOUND", "일정 항목을 찾을 수 없습니다.")

        rejection_reason = self._rejection_reason(day, item, payload, now)
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
            accepted=rejection_reason is None,
            rejection_reason=rejection_reason,
        )
        self.session.add(event)
        await self.session.flush()
        logger.info(
            "위치 이벤트 처리 완료",
            extra={
                "client_event_id": str(payload.event_id),
                "accepted": event.accepted,
                "rejection_reason": rejection_reason,
            },
        )
        return self._result(event)

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

        expected_kind = "ARRIVAL" if payload.event_type.value == "DWELL" else "DEPARTURE"
        expected_status = "EN_ROUTE" if expected_kind == "ARRIVAL" else "ARRIVED"
        if (
            item.trip_day_id != day.trip_day_id
            or item.status != expected_status
            or payload.geofence_id != f"{item.item_id}:{expected_kind}"
        ):
            return "ITEM_NOT_ELIGIBLE"
        return None

    @staticmethod
    def _result(event: ProgressEvent) -> ProgressEventResult:
        return ProgressEventResult(
            event_id=event.client_event_id,
            accepted=event.accepted,
            rejection_reason=event.rejection_reason,
            candidate=None,
            cancelled_transition_id=None,
        )
