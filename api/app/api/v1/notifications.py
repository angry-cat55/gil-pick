"""알림 목록과 읽음 API."""

from __future__ import annotations

import base64
import binascii
import json
import uuid
from datetime import datetime
from typing import Annotated

from fastapi import APIRouter, Depends, Path, Query, Request
from fastapi.responses import JSONResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import AuthPrincipal, get_current_principal
from app.api.errors import AppError, get_request_id, success_response
from app.db import get_session
from app.schemas.auth import ErrorEnvelope
from app.schemas.detection import PaginatedMeta, Pagination
from app.schemas.notification import (
    MarkAllEnvelope,
    MarkAllResult,
    MarkReadEnvelope,
    MarkReadResult,
    NotificationItem,
    NotificationListData,
    NotificationListEnvelope,
)
from app.services.notification import NotificationService

router = APIRouter(prefix="/notifications", tags=["notifications"])


def get_notification_service(
    session: Annotated[AsyncSession, Depends(get_session)],
) -> NotificationService:
    """요청 transaction을 사용하는 알림 service를 반환한다."""
    return NotificationService(session)


def _encode_cursor(created_at: datetime, notification_id: uuid.UUID) -> str:
    payload = json.dumps([created_at.isoformat(), str(notification_id)], separators=(",", ":"))
    return base64.urlsafe_b64encode(payload.encode()).decode().rstrip("=")


def _decode_cursor(value: str) -> tuple[datetime, uuid.UUID]:
    """불투명 cursor를 알림 정렬 키로 복원한다."""
    try:
        padding = "=" * (-len(value) % 4)
        payload = json.loads(
            base64.urlsafe_b64decode(value + padding).decode()
        )
        if not isinstance(payload, list) or len(payload) != 2 or not all(
            isinstance(item, str) for item in payload
        ):
            raise ValueError
        created_at, notification_id = payload
        timestamp = datetime.fromisoformat(created_at)
        if timestamp.tzinfo is None or timestamp.utcoffset() is None:
            raise ValueError
        return timestamp, uuid.UUID(notification_id)
    except (ValueError, TypeError, UnicodeDecodeError, json.JSONDecodeError, binascii.Error) as exc:
        raise AppError(400, "INVALID_REQUEST", "cursor 형식이 올바르지 않습니다.") from exc


@router.get(
    "",
    response_model=NotificationListEnvelope,
    responses={401: {"model": ErrorEnvelope}},
)
async def list_notifications(
    request: Request,
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    service: Annotated[NotificationService, Depends(get_notification_service)],
    cursor: Annotated[str | None, Query(min_length=1)] = None,
    limit: Annotated[int, Query(ge=1, le=50)] = 20,
    read: Annotated[bool | None, Query()] = None,
) -> JSONResponse:
    """인증 사용자의 최근 90일 알림을 최신순으로 조회한다."""
    rows = await service.list_notifications(
        principal.user_id,
        cursor=_decode_cursor(cursor) if cursor else None,
        limit=limit + 1,
        read=read,
    )
    has_next = len(rows) > limit
    page = rows[:limit]
    next_cursor = (
        _encode_cursor(page[-1].created_at, page[-1].notification_id)
        if has_next
        else None
    )
    envelope = NotificationListEnvelope(
        success=True,
        data=NotificationListData(
            items=[
                NotificationItem(
                    notification_id=row.notification_id,
                    type=row.type,
                    trip_id=row.trip_id,
                    trip_day_id=row.trip_day_id,
                    item_id=row.item_id,
                    detection_id=row.detection_id,
                    transition_id=row.transition_id,
                    title=row.title,
                    body=row.body,
                    read=row.read_at is not None,
                    created_at=row.created_at,
                )
                for row in page
            ]
        ),
        meta=PaginatedMeta(
            request_id=get_request_id(request),
            pagination=Pagination(next_cursor=next_cursor, has_next=has_next),
        ),
    )
    return JSONResponse(content=envelope.model_dump(mode="json", by_alias=True))


@router.patch(
    "/read-all",
    response_model=MarkAllEnvelope,
    responses={401: {"model": ErrorEnvelope}},
)
async def mark_all_notifications_read(
    request: Request,
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    service: Annotated[NotificationService, Depends(get_notification_service)],
) -> JSONResponse:
    """인증 사용자의 모든 알림을 멱등하게 읽음 처리한다."""
    updated = await service.mark_all_read(principal.user_id)
    return success_response(request, MarkAllResult(updated=updated))


@router.patch(
    "/{notificationId}/read",
    response_model=MarkReadEnvelope,
    responses={
        401: {"model": ErrorEnvelope},
        403: {"model": ErrorEnvelope},
        404: {"model": ErrorEnvelope},
    },
)
async def mark_notification_read(
    request: Request,
    notification_id: Annotated[uuid.UUID, Path(alias="notificationId")],
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    service: Annotated[NotificationService, Depends(get_notification_service)],
) -> JSONResponse:
    """소유한 알림 한 건을 멱등하게 읽음 처리한다."""
    notification = await service.mark_read(principal.user_id, notification_id)
    return success_response(
        request,
        MarkReadResult(notification_id=notification.notification_id, read=True),
    )


__all__ = ["get_notification_service", "router"]
