"""미발송 알림을 활성 기기로 전달한다."""

from __future__ import annotations

import asyncio
import logging
import uuid
from datetime import UTC, datetime

from sqlalchemy import DateTime, cast, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.clients.fcm import FcmClient, FcmClientError, FcmSendResult
from app.core.logging import request_id_context
from app.models.auth import DeviceSession
from app.models.notification import Notification
from app.models.itinerary import TripDay
from app.models.progress import ProgressTransition
from app.services.detection import DetectionService
from app.services.notification import NotificationService

logger = logging.getLogger("gilpick.notification.dispatch")


class NotificationDispatchService:
    """기기별 실패를 격리하며 알림의 종단 발송 시도를 완료한다."""

    def __init__(self, session: AsyncSession, *, client: FcmClient, now=None, sleep=asyncio.sleep) -> None:
        self.session, self.client = session, client
        self.now = now or (lambda: datetime.now(UTC))
        self.sleep = sleep

    async def send_one(self, notification: Notification) -> None:
        """등록된 모든 활성 토큰에 보내고 완료 시각을 남긴다."""
        sessions = list((await self.session.scalars(select(DeviceSession).where(DeviceSession.user_id == notification.user_id, DeviceSession.revoked_at.is_(None), DeviceSession.fcm_token.is_not(None)))).all())
        summary = {result.value: 0 for result in FcmSendResult}
        data = {key: str(value) for key, value in {"type": notification.type, "notificationId": notification.notification_id, "tripId": notification.trip_id, "tripDayId": notification.trip_day_id, "itemId": notification.item_id, "detectionId": notification.detection_id, "transitionId": notification.transition_id, "title": notification.title, "body": notification.body}.items() if value is not None}
        for device in sessions:
            result = FcmSendResult.FATAL
            for attempt in range(3):
                try:
                    result = await self.client.send(device.fcm_token, data)  # type: ignore[arg-type]
                except FcmClientError as exc:
                    result = FcmSendResult.RETRYABLE if exc.retryable else FcmSendResult.FATAL
                if result is not FcmSendResult.RETRYABLE or attempt == 2:
                    break
                await self.sleep((0.5, 1.5)[attempt])
            summary[result.value] += 1
            if result is FcmSendResult.INVALID_TOKEN:
                device.fcm_token = None
        notification.sent_at = self.now()
        failures = summary[FcmSendResult.RETRYABLE.value] + summary[FcmSendResult.FATAL.value]
        event = "notification_delivery_failed" if failures else "notification_delivered"
        if getattr(getattr(self.client, "settings", None), "fcm_enabled", True) is False:
            event = "fcm_skipped"
        logger.log(logging.ERROR if failures else logging.INFO, {"event": event, "request_id": request_id_context.get() or str(uuid.uuid4()), "type": notification.type, "target_id": str(notification.detection_id or notification.transition_id), "device_count": len(sessions), "results": summary})

    async def tick(self) -> int:
        """기한 전환·재질문을 반영한 뒤 미발송 큐를 한 번 처리한다."""
        now = self.now()
        days = list((await self.session.scalars(
            select(TripDay)
            .where(TripDay.status == "IN_PROGRESS")
            .options(selectinload(TripDay.items))
        )).all())
        detection_service = DetectionService(self.session)
        notification_service = NotificationService(self.session, now=self.now)
        for day in days:
            await detection_service.finalize_due_candidates(day, now=now)
        due_reprompts = list((await self.session.scalars(
            select(ProgressTransition).where(
                ProgressTransition.status == "CANCELLED",
                ProgressTransition.decision == "NOT_ARRIVED",
                cast(ProgressTransition.response_snapshot["nextPromptAt"].as_string(), DateTime(timezone=True)) <= now,
            )
        )).all())
        for transition in due_reprompts:
            await notification_service.create_transition_check(transition, prompt_seq=2)
        pending = list((await self.session.scalars(select(Notification).where(Notification.sent_at.is_(None)).order_by(Notification.created_at).limit(200).with_for_update(skip_locked=True))).all())
        for notification in pending:
            await self.send_one(notification)
        return len(pending)
