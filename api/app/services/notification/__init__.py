"""알림 생성·조회·읽음 서비스."""

from __future__ import annotations

import logging
import uuid
from collections.abc import Callable
from datetime import UTC, datetime, timedelta

from sqlalchemy import select, update
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.errors import AppError
from app.core.config import get_settings
from app.core.logging import request_id_context
from app.models.auth import DeviceSession, User
from app.models.detection import Detection
from app.models.itinerary import ItineraryItem, Place, TripDay
from app.models.notification import Notification
from app.models.progress import ProgressTransition
from app.models.trip import Trip
from app.services.notification.dedup import detection_key, transition_auto_key, transition_check_key
from app.services.notification.messages import place_change_message, transition_auto_message, transition_check_message

logger = logging.getLogger("gilpick.notification")


class NotificationService:
    """도메인 이벤트를 멱등 알림 행으로 변환한다."""

    def __init__(self, session: AsyncSession, *, now: Callable[[], datetime] | None = None) -> None:
        self.session = session
        self.now = now or (lambda: datetime.now(UTC))

    async def create_place_change_suggestion(self, detection: Detection) -> Notification | None:
        """활성 감지와 설정을 확인해 장소 변경 제안 한 건을 만든다."""
        row = (await self.session.execute(
            select(Trip.user_id, Trip.trip_id, Place.name, User.replacement_suggestion_enabled)
            .join(TripDay, TripDay.trip_id == Trip.trip_id)
            .join(ItineraryItem, ItineraryItem.trip_day_id == TripDay.trip_day_id)
            .join(Place, Place.place_id == ItineraryItem.place_id)
            .join(User, User.user_id == Trip.user_id)
            .where(ItineraryItem.item_id == detection.item_id)
        )).one_or_none()
        if row is None or not row.replacement_suggestion_enabled or detection.status != "ACTIVE":
            return None
        title, body = place_change_message(row.name, detection.reason)
        return await self._insert(user_id=row.user_id, trip_id=row.trip_id, trip_day_id=detection.trip_day_id, item_id=detection.item_id, detection_id=detection.detection_id, type="PLACE_CHANGE_SUGGESTION", title=title, body=body, dedup_key=detection_key(detection.detection_id))

    async def create_transition_check(self, transition: ProgressTransition, prompt_seq: int = 1) -> Notification | None:
        """대기 중인 도착·출발 전환의 확인 알림을 만든다."""
        allowed_status = transition.status == "PENDING_CONFIRMATION" or (
            prompt_seq == 2
            and transition.status == "CANCELLED"
            and transition.decision == "NOT_ARRIVED"
        )
        if not allowed_status or transition.primary_item_id is None:
            return None
        context = await self._transition_context(transition)
        if context is None or context.day_status != "IN_PROGRESS":
            return None
        kind = "DEPARTURE" if transition.transition_type == "DEPARTURE" else "ARRIVAL"
        title, body = transition_check_message(kind, context.name, prompt_seq)
        return await self._insert(user_id=context.user_id, trip_id=context.trip_id, trip_day_id=transition.trip_day_id, item_id=transition.primary_item_id, transition_id=transition.transition_id, type=f"{kind}_CHECK", title=title, body=body, dedup_key=transition_check_key(transition.transition_id, kind, prompt_seq))

    async def create_transition_auto_confirmed(self, transition: ProgressTransition) -> Notification | None:
        """자동 확정된 전환의 되돌리기 안내 알림을 만든다."""
        if transition.status != "AUTO_CONFIRMED" or transition.primary_item_id is None:
            return None
        context = await self._transition_context(transition)
        if context is None:
            return None
        kind = "DEPARTURE" if transition.transition_type == "DEPARTURE" else "ARRIVAL"
        title, body = transition_auto_message(kind, transition.undo_deadline, now=self.now())
        return await self._insert(user_id=context.user_id, trip_id=context.trip_id, trip_day_id=transition.trip_day_id, item_id=transition.primary_item_id, transition_id=transition.transition_id, type=f"{kind}_AUTO_CONFIRMED", title=title, body=body, dedup_key=transition_auto_key(transition.transition_id))

    async def list_notifications(self, user_id: uuid.UUID, *, cursor: tuple[datetime, uuid.UUID] | None = None, limit: int = 20, read: bool | None = None) -> list[Notification]:
        """90일 안의 사용자 알림을 안정적인 최신순으로 조회한다."""
        statement = select(Notification).where(Notification.user_id == user_id, Notification.created_at >= self.now() - timedelta(days=get_settings().notification_retention_days))
        if read is not None:
            statement = statement.where(Notification.read_at.is_not(None) if read else Notification.read_at.is_(None))
        if cursor:
            created_at, notification_id = cursor
            statement = statement.where((Notification.created_at < created_at) | ((Notification.created_at == created_at) & (Notification.notification_id < notification_id)))
        return list((await self.session.scalars(statement.order_by(Notification.created_at.desc(), Notification.notification_id.desc()).limit(limit))).all())

    async def mark_read(self, user_id: uuid.UUID, notification_id: uuid.UUID) -> Notification:
        """소유한 알림 한 건을 멱등하게 읽음 처리한다."""
        notification = await self.session.get(Notification, notification_id)
        if notification is None:
            raise AppError(404, "NOTIFICATION_NOT_FOUND", "알림을 찾을 수 없습니다.")
        if notification.user_id != user_id:
            raise AppError(403, "NOTIFICATION_FORBIDDEN", "다른 사용자의 알림입니다.")
        if notification.read_at is None:
            notification.read_at = self.now()
        return notification

    async def mark_all_read(self, user_id: uuid.UUID) -> int:
        """사용자의 안 읽은 알림을 모두 읽음 처리한다."""
        result = await self.session.execute(update(Notification).where(Notification.user_id == user_id, Notification.read_at.is_(None)).values(read_at=self.now()))
        return result.rowcount or 0

    async def register_fcm_token(
        self,
        user_id: uuid.UUID,
        device_id: str,
        fcm_token: str,
        platform: str,
    ) -> DeviceSession:
        """소유한 활성 기기 session에 FCM token을 멱등 등록한다."""
        device_session = await self._owned_active_device(user_id, device_id)
        await self.session.execute(
            update(DeviceSession)
            .where(
                DeviceSession.fcm_token == fcm_token,
                DeviceSession.session_id != device_session.session_id,
                DeviceSession.revoked_at.is_(None),
            )
            .values(fcm_token=None)
        )
        device_session.fcm_token = fcm_token
        device_session.platform = platform
        return device_session

    async def unregister_fcm_token(
        self, user_id: uuid.UUID, device_id: str
    ) -> DeviceSession:
        """소유한 활성 기기 session의 FCM token을 멱등 해제한다."""
        device_session = await self._owned_active_device(user_id, device_id)
        device_session.fcm_token = None
        return device_session

    async def _owned_active_device(
        self, user_id: uuid.UUID, device_id: str
    ) -> DeviceSession:
        """기기 식별자의 활성 session과 소유권을 확인한다."""
        device_session = await self.session.scalar(
            select(DeviceSession).where(
                DeviceSession.user_id == user_id,
                DeviceSession.client_device_id == device_id,
                DeviceSession.revoked_at.is_(None),
            )
        )
        if device_session is not None:
            return device_session
        exists_for_another_user = await self.session.scalar(
            select(DeviceSession.session_id).where(
                DeviceSession.client_device_id == device_id,
                DeviceSession.user_id != user_id,
                DeviceSession.revoked_at.is_(None),
            ).limit(1)
        )
        if exists_for_another_user is not None:
            raise AppError(403, "DEVICE_FORBIDDEN", "다른 사용자의 기기입니다.")
        raise AppError(404, "DEVICE_SESSION_NOT_FOUND", "활성 기기 세션을 찾을 수 없습니다.")

    async def _transition_context(self, transition: ProgressTransition):
        return (await self.session.execute(select(Trip.user_id.label("user_id"), Trip.trip_id.label("trip_id"), TripDay.status.label("day_status"), Place.name.label("name")).join(TripDay, TripDay.trip_id == Trip.trip_id).join(ItineraryItem, ItineraryItem.trip_day_id == TripDay.trip_day_id).join(Place, Place.place_id == ItineraryItem.place_id).where(TripDay.trip_day_id == transition.trip_day_id, ItineraryItem.item_id == transition.primary_item_id))).one_or_none()

    async def _insert(self, **values: object) -> Notification | None:
        notification_id = uuid.uuid4()
        result = await self.session.execute(pg_insert(Notification).values(notification_id=notification_id, created_at=self.now(), **values).on_conflict_do_nothing(index_elements=[Notification.user_id, Notification.dedup_key], index_where=Notification.dedup_key.is_not(None)).returning(Notification))
        notification = result.scalar_one_or_none()
        logger.info({"event": "notification_created", "request_id": request_id_context.get() or str(notification_id), "type": values.get("type"), "target_id": str(values.get("detection_id") or values.get("transition_id")), "result": "CREATED" if notification else "DEDUPLICATED"})
        return notification


__all__ = ["NotificationService"]
