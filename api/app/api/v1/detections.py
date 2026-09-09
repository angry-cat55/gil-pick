"""F008 감지 결과 목록·상세·읽음 API."""

from __future__ import annotations

import base64
import binascii
import json
import uuid
from datetime import UTC, datetime
from decimal import Decimal
from typing import Annotated

from fastapi import APIRouter, Depends, Path, Query, Request
from fastapi.responses import JSONResponse
from sqlalchemy import and_, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import get_current_principal
from app.api.errors import AppError, get_request_id, success_response
from app.core.security import AuthPrincipal
from app.db import get_session
from app.models.detection import Detection
from app.models.itinerary import ItineraryItem, Place, TripDay
from app.models.trip import Trip
from app.schemas.auth import ResponseMeta
from app.schemas.alternatives import DetectionDismissEnvelope
from app.schemas.detection import (
    DetectionDetail,
    DetectionDetailEnvelope,
    DetectionListData,
    DetectionListEnvelope,
    DetectionListItem,
    DetectionReadData,
    DetectionReadEnvelope,
    DetectionStatus,
    ErrorEnvelope,
    PaginatedMeta,
    Pagination,
    VariableVerdicts,
)

router = APIRouter(tags=["detections"])


def _cursor(detected_at: datetime, detection_id: uuid.UUID) -> str:
    payload = json.dumps([detected_at.isoformat(), str(detection_id)]).encode()
    return base64.urlsafe_b64encode(payload).decode()


def _decode_cursor(value: str) -> tuple[datetime, uuid.UUID]:
    try:
        payload = json.loads(base64.urlsafe_b64decode(value))
        if not isinstance(payload, list) or len(payload) != 2 or not all(isinstance(item, str) for item in payload):
            raise ValueError
        timestamp = datetime.fromisoformat(payload[0])
        if timestamp.tzinfo is None or timestamp.utcoffset() is None:
            raise ValueError
        return timestamp, uuid.UUID(payload[1])
    except (ValueError, TypeError, json.JSONDecodeError, binascii.Error) as exc:
        raise AppError(400, "INVALID_REQUEST", "cursor 형식이 올바르지 않습니다.") from exc


@router.get(
    "/trips/{tripId}/detections",
    response_model=DetectionListEnvelope,
    responses={400: {"model": ErrorEnvelope}, 401: {"model": ErrorEnvelope}, 403: {"model": ErrorEnvelope}, 404: {"model": ErrorEnvelope}},
)
async def list_detections(
    request: Request,
    trip_id: Annotated[uuid.UUID, Path(alias="tripId")],
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    session: Annotated[AsyncSession, Depends(get_session)],
    cursor: Annotated[str | None, Query()] = None,
    limit: Annotated[int, Query(ge=1, le=50)] = 20,
    status: Annotated[DetectionStatus | None, Query()] = None,
) -> JSONResponse:
    """사용자 소유 여행의 감지 결과를 최신순 cursor로 조회한다."""
    trip = await session.get(Trip, trip_id)
    if trip is None or trip.deleted_at is not None:
        raise AppError(404, "TRIP_NOT_FOUND", "여행을 찾을 수 없습니다.")
    if trip.user_id != principal.user_id:
        raise AppError(403, "TRIP_FORBIDDEN", "다른 사용자의 여행에는 접근할 수 없습니다.")
    query = (
        select(Detection, Place.name)
        .join(TripDay, TripDay.trip_day_id == Detection.trip_day_id)
        .join(ItineraryItem, ItineraryItem.item_id == Detection.item_id)
        .join(Place, Place.place_id == ItineraryItem.place_id)
        .where(TripDay.trip_id == trip_id)
        .order_by(Detection.detected_at.desc(), Detection.detection_id.desc())
        .limit(limit + 1)
    )
    if status is not None:
        query = query.where(Detection.status == status.value)
    if cursor:
        timestamp, detection_id = _decode_cursor(cursor)
        query = query.where(or_(Detection.detected_at < timestamp, and_(Detection.detected_at == timestamp, Detection.detection_id < detection_id)))
    rows = (await session.execute(query)).all()
    has_next = len(rows) > limit
    rows = rows[:limit]
    items = [
        DetectionListItem(
            detection_id=detection.detection_id,
            item_id=detection.item_id,
            place_name=place_name,
            primary_type=detection.primary_type,
            status=detection.status,
            total_risk_score=round(float(detection.score or Decimal(0)) * 100),
            eta=detection.eta,
            reason=detection.reason,
            created_at=detection.detected_at,
            read=detection.read_at is not None,
        )
        for detection, place_name in rows
    ]
    next_cursor = _cursor(rows[-1][0].detected_at, rows[-1][0].detection_id) if has_next else None
    envelope = DetectionListEnvelope(
        success=True,
        data=DetectionListData(items=items),
        meta=PaginatedMeta(
            request_id=get_request_id(request),
            pagination=Pagination(next_cursor=next_cursor, has_next=has_next),
        ),
    )
    return JSONResponse(content=envelope.model_dump(mode="json", by_alias=True))


async def owned_detection(
    detection_id: uuid.UUID, user_id: uuid.UUID, session: AsyncSession
) -> tuple[Detection, uuid.UUID, str]:
    """감지 결과와 연결 여행이 요청 사용자 소유인지 검증한다.

    Args:
        detection_id: 조회할 감지 ID.
        user_id: 인증된 사용자 ID.
        session: 현재 DB session.

    Returns:
        감지 entity, 여행 ID, 장소명.

    Raises:
        AppError: 감지가 없거나 사용자가 소유자가 아닌 경우.
    """
    row = (
        await session.execute(
            select(Detection, Trip.user_id, Trip.trip_id, Place.name)
            .join(TripDay, TripDay.trip_day_id == Detection.trip_day_id)
            .join(Trip, Trip.trip_id == TripDay.trip_id)
            .join(ItineraryItem, ItineraryItem.item_id == Detection.item_id)
            .join(Place, Place.place_id == ItineraryItem.place_id)
            .where(Detection.detection_id == detection_id, Trip.deleted_at.is_(None))
        )
    ).one_or_none()
    if row is None:
        raise AppError(404, "DETECTION_NOT_FOUND", "감지 결과를 찾을 수 없습니다.")
    detection, owner_id, trip_id, place_name = row
    if owner_id != user_id:
        raise AppError(403, "DETECTION_FORBIDDEN", "다른 사용자의 감지 결과에는 접근할 수 없습니다.")
    return detection, trip_id, place_name


@router.get(
    "/detections/{detectionId}",
    response_model=DetectionDetailEnvelope,
    responses={401: {"model": ErrorEnvelope}, 403: {"model": ErrorEnvelope}, 404: {"model": ErrorEnvelope}},
)
async def get_detection(
    request: Request,
    detection_id: Annotated[uuid.UUID, Path(alias="detectionId")],
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> JSONResponse:
    """사용자 소유 감지 결과의 마지막 평가 snapshot을 반환한다."""
    detection, trip_id, place_name = await owned_detection(detection_id, principal.user_id, session)
    data = DetectionDetail(
        detection_id=detection.detection_id,
        trip_id=trip_id,
        item_id=detection.item_id,
        place_name=place_name,
        primary_type=detection.primary_type,
        status=detection.status,
        eta=detection.eta,
        total_risk_score=round(float(detection.score or Decimal(0)) * 100),
        reason=detection.reason,
        variables=VariableVerdicts.model_validate(detection.evaluation_snapshot["variables"]),
        read=detection.read_at is not None,
        created_at=detection.detected_at,
        last_evaluated_at=detection.last_evaluated_at,
    )
    return success_response(request, data)


@router.patch(
    "/detections/{detectionId}/read",
    response_model=DetectionReadEnvelope,
    responses={401: {"model": ErrorEnvelope}, 403: {"model": ErrorEnvelope}, 404: {"model": ErrorEnvelope}},
)
async def mark_detection_read(
    request: Request,
    detection_id: Annotated[uuid.UUID, Path(alias="detectionId")],
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> JSONResponse:
    """감지 결과를 최초 한 번만 읽음 처리한다."""
    detection, _, _ = await owned_detection(detection_id, principal.user_id, session)
    if detection.read_at is None:
        detection.read_at = datetime.now(UTC)
        await session.flush()
    return success_response(request, DetectionReadData(detection_id=detection_id, read=True))


@router.post(
    "/detections/{detectionId}/dismiss",
    response_model=DetectionDismissEnvelope,
    responses={401: {"model": ErrorEnvelope}, 403: {"model": ErrorEnvelope}, 404: {"model": ErrorEnvelope}},
)
async def dismiss_detection(
    request: Request,
    detection_id: Annotated[uuid.UUID, Path(alias="detectionId")],
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> JSONResponse:
    """`기존 일정 그대로 진행`(DETECT-004): ACTIVE 감지를 거절한다.

    비-`ACTIVE` 감지는 상태를 바꾸지 않고 현재 상태를 그대로 200으로 돌려준다(상태 기반 멱등).
    """
    # 순환 import(alternatives 서비스 → 이 모듈의 owned_detection)를 피하려고 함수 안에서 import한다.
    from app.services.alternatives import dismiss_detection as dismiss_active_detection

    data = await dismiss_active_detection(detection_id, principal.user_id, session)
    return success_response(request, data)
