"""여행 진행 조회·시작 API."""

from __future__ import annotations

import logging
import uuid
from collections.abc import AsyncIterator
from datetime import date
from typing import Annotated

from fastapi import APIRouter, BackgroundTasks, Depends, Header, Path, Request
from fastapi.responses import JSONResponse
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.errors import success_response
from app.api.dependencies import get_current_principal
from app.api.v1.itinerary import _owned_trip_date
from app.db import create_session_factory, get_session
from app.models.itinerary import TripDay
from app.core.config import Settings, get_settings
from app.schemas.auth import ErrorEnvelope
from app.schemas.progress import (
    DecisionRequest,
    ProgressEventEnvelope,
    ProgressEventRequest,
    ProgressEnvelope,
    StartDayProgressRequest,
    TransitionResultEnvelope,
    UndoResultEnvelope,
    UpdateItemProgressStatusRequest,
)
from app.schemas.trip import Trip
from app.core.security import AuthPrincipal
from app.services.progress import ProgressService
from app.services.detection import DetectionService
from app.services.detection.evaluator import reevaluate_day
from app.services.route import build_route_service

router = APIRouter(prefix="/trips/{tripId}", tags=["progress"])
item_router = APIRouter(prefix="/itinerary-items", tags=["progress"])
transition_router = APIRouter(prefix="/progress/transitions", tags=["progress"])
logger = logging.getLogger(__name__)


async def _reevaluate_progress_day(
    session_factory, *, trip_id: uuid.UUID, visit_date: date
) -> None:
    """진행 변경 commit 이후 해당 날짜의 ETA 기반 변수를 재평가한다."""
    try:
        async with session_factory() as session:
            trip_day_id = await session.scalar(
                select(TripDay.trip_day_id).where(
                    TripDay.trip_id == trip_id,
                    TripDay.visit_date == visit_date,
                )
            )
        if trip_day_id is not None:
            await reevaluate_day(session_factory, trip_day_id)
    except Exception:
        logger.exception(
            "진행 변경 후 변수 재평가 연결에 실패했습니다.",
            extra={"trip_id": str(trip_id), "visit_date": visit_date.isoformat()},
        )


async def _service(
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> AsyncIterator[ProgressService]:
    route_service = build_route_service(settings)
    try:
        yield ProgressService(
            session,
            calculator=route_service.calculator,
            session_factory=create_session_factory(),
        )
    finally:
        await route_service.close()


@router.get(
    "/days/{date}/progress",
    response_model=ProgressEnvelope,
    responses={
        400: {"model": ErrorEnvelope},
        401: {"model": ErrorEnvelope},
        403: {"model": ErrorEnvelope},
        404: {"model": ErrorEnvelope},
    },
)
async def get_day_progress(
    visit_date: Annotated[date, Path(alias="date")],
    request: Request,
    trip: Annotated[Trip, Depends(_owned_trip_date)],
    service: Annotated[ProgressService, Depends(_service)],
) -> JSONResponse:
    data = await service.get_day(trip_id=trip.trip_id, visit_date=visit_date)
    return success_response(request, data)


@router.post(
    "/days/{date}/progress/start",
    response_model=ProgressEnvelope,
    responses={
        400: {"model": ErrorEnvelope},
        401: {"model": ErrorEnvelope},
        403: {"model": ErrorEnvelope},
        404: {"model": ErrorEnvelope},
        409: {"model": ErrorEnvelope},
        422: {"model": ErrorEnvelope},
    },
)
async def start_day_progress(
    payload: StartDayProgressRequest,
    visit_date: Annotated[date, Path(alias="date")],
    request: Request,
    background_tasks: BackgroundTasks,
    trip: Annotated[Trip, Depends(_owned_trip_date)],
    idempotency_key: Annotated[uuid.UUID, Header(alias="Idempotency-Key")],
    service: Annotated[ProgressService, Depends(_service)],
) -> JSONResponse:
    data = await service.start_day(
        trip_id=trip.trip_id, visit_date=visit_date,
        payload=payload, idempotency_key=idempotency_key,
    )
    if (session_factory := getattr(service, "session_factory", None)) is not None:
        background_tasks.add_task(
            _reevaluate_progress_day,
            session_factory,
            trip_id=trip.trip_id,
            visit_date=visit_date,
        )
    return success_response(request, data)


@router.post(
    "/days/{date}/progress/events",
    response_model=ProgressEventEnvelope,
    responses={
        400: {"model": ErrorEnvelope},
        401: {"model": ErrorEnvelope},
        403: {"model": ErrorEnvelope},
        404: {"model": ErrorEnvelope},
    },
)
async def register_progress_event(
    payload: ProgressEventRequest,
    visit_date: Annotated[date, Path(alias="date")],
    request: Request,
    trip: Annotated[Trip, Depends(_owned_trip_date)],
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> JSONResponse:
    """검증된 위치 이벤트를 저장하고 생성된 도착 후보를 반환한다."""
    data = await DetectionService(session).register_event(
        user_id=principal.user_id,
        trip_id=trip.trip_id,
        visit_date=visit_date,
        payload=payload,
    )
    return success_response(request, data)


@transition_router.post(
    "/{transitionId}/decisions",
    response_model=TransitionResultEnvelope,
    responses={
        400: {"model": ErrorEnvelope},
        401: {"model": ErrorEnvelope},
        403: {"model": ErrorEnvelope},
        404: {"model": ErrorEnvelope},
        409: {"model": ErrorEnvelope},
    },
)
async def decide_progress_transition(
    payload: DecisionRequest,
    transition_id: Annotated[uuid.UUID, Path(alias="transitionId")],
    request: Request,
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    idempotency_key: Annotated[uuid.UUID, Header(alias="Idempotency-Key")],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> JSONResponse:
    """도착 후보에 사용자의 확인 또는 거절 결정을 적용한다."""
    data = await DetectionService(session).decide_transition(
        user_id=principal.user_id,
        transition_id=transition_id,
        decision=payload.decision.value,
        idempotency_key=idempotency_key,
    )
    return success_response(request, data)


@transition_router.post(
    "/{transitionId}/undo",
    response_model=UndoResultEnvelope,
    responses={
        400: {"model": ErrorEnvelope},
        401: {"model": ErrorEnvelope},
        403: {"model": ErrorEnvelope},
        404: {"model": ErrorEnvelope},
        409: {"model": ErrorEnvelope},
    },
)
async def undo_progress_transition(
    transition_id: Annotated[uuid.UUID, Path(alias="transitionId")],
    request: Request,
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    idempotency_key: Annotated[uuid.UUID, Header(alias="Idempotency-Key")],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> JSONResponse:
    """무응답으로 자동 확정된 진행 전환을 되돌린다(PROG-005)."""
    data = await DetectionService(session).undo_transition(
        user_id=principal.user_id,
        transition_id=transition_id,
        idempotency_key=idempotency_key,
    )
    return success_response(request, data)


@item_router.patch(
    "/{itemId}/status",
    response_model=ProgressEnvelope,
    responses={
        400: {"model": ErrorEnvelope},
        401: {"model": ErrorEnvelope},
        403: {"model": ErrorEnvelope},
        404: {"model": ErrorEnvelope},
        409: {"model": ErrorEnvelope},
        422: {"model": ErrorEnvelope},
    },
)
async def update_item_progress_status(
    payload: UpdateItemProgressStatusRequest,
    item_id: Annotated[uuid.UUID, Path(alias="itemId")],
    request: Request,
    background_tasks: BackgroundTasks,
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    idempotency_key: Annotated[uuid.UUID, Header(alias="Idempotency-Key")],
    service: Annotated[ProgressService, Depends(_service)],
) -> JSONResponse:
    data = await service.update_item_status(
        user_id=principal.user_id,
        item_id=item_id,
        target_status=payload.status.value,
        progress_version=payload.progress_version,
        idempotency_key=idempotency_key,
    )
    if (session_factory := getattr(service, "session_factory", None)) is not None:
        background_tasks.add_task(
            _reevaluate_progress_day,
            session_factory,
            trip_id=data.trip_id,
            visit_date=data.date,
        )
    return success_response(request, data)


__all__ = ["item_router", "router", "transition_router"]
