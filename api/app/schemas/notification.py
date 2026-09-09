"""알림 목록과 읽음 API schema."""

from __future__ import annotations

import uuid
from datetime import datetime
from enum import StrEnum
from typing import Literal

from pydantic import Field

from app.schemas.auth import ApiModel, ResponseMeta, SuccessEnvelope
from app.schemas.detection import PaginatedMeta

class NotificationType(StrEnum):
    """클라이언트가 분기할 수 있는 알림 유형."""

    PLACE_CHANGE_SUGGESTION = "PLACE_CHANGE_SUGGESTION"
    ARRIVAL_CHECK = "ARRIVAL_CHECK"
    DEPARTURE_CHECK = "DEPARTURE_CHECK"
    ARRIVAL_AUTO_CONFIRMED = "ARRIVAL_AUTO_CONFIRMED"
    DEPARTURE_AUTO_CONFIRMED = "DEPARTURE_AUTO_CONFIRMED"


class NotificationItem(ApiModel):
    """알림 목록에 노출하는 최소 저장 정보."""

    notification_id: uuid.UUID
    type: NotificationType
    trip_id: uuid.UUID
    trip_day_id: uuid.UUID | None = None
    item_id: uuid.UUID | None = None
    detection_id: uuid.UUID | None = None
    transition_id: uuid.UUID | None = None
    title: str = Field(max_length=200)
    body: str
    read: bool
    created_at: datetime


class NotificationListData(ApiModel):
    """cursor 페이지의 알림 목록."""

    items: list[NotificationItem]


class MarkReadResult(ApiModel):
    """단건 읽음 처리 결과."""

    notification_id: uuid.UUID
    read: Literal[True]


class MarkAllResult(ApiModel):
    """모두 읽음 처리 건수."""

    updated: int = Field(ge=0)


class NotificationListEnvelope(ApiModel):
    """알림 목록 pagination 응답."""

    success: Literal[True]
    data: NotificationListData
    meta: PaginatedMeta


MarkReadEnvelope = SuccessEnvelope[MarkReadResult]
MarkAllEnvelope = SuccessEnvelope[MarkAllResult]


__all__ = [
    "MarkAllEnvelope",
    "MarkAllResult",
    "MarkReadEnvelope",
    "MarkReadResult",
    "NotificationItem",
    "NotificationListData",
    "NotificationListEnvelope",
    "NotificationType",
    "ResponseMeta",
]
