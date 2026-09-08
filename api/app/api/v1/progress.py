"""여행 진행 조회·시작 API."""

from __future__ import annotations

import uuid
from collections.abc import AsyncIterator
from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends, Header, Path, Request
from fastapi.responses import JSONResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.errors import success_response
from app.api.dependencies import get_current_principal
from app.api.v1.itinerary import _owned_trip_date
from app.db import create_session_factory, get_session
from app.core.config import Settings, get_settings
from app.schemas.auth import ErrorEnvelope
from app.schemas.progress import (
    ProgressEnvelope,
    StartDayProgressRequest,
    UpdateItemProgressStatusRequest,
)
from app.schemas.trip import Trip
from app.core.security import AuthPrincipal
from app.services.progress import ProgressService
from app.services.route import build_route_service

router = APIRouter(prefix="/trips/{tripId}", tags=["progress"])
item_router = APIRouter(prefix="/itinerary-items", tags=["progress"])


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
    responses={401: {"model": ErrorEnvelope}, 403: {"model": ErrorEnvelope}, 404: {"model": ErrorEnvelope}},
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
        401: {"model": ErrorEnvelope}, 403: {"model": ErrorEnvelope}, 404: {"model": ErrorEnvelope},
        409: {"model": ErrorEnvelope}, 422: {"model": ErrorEnvelope},
    },
)
async def start_day_progress(
    payload: StartDayProgressRequest,
    visit_date: Annotated[date, Path(alias="date")],
    request: Request,
    trip: Annotated[Trip, Depends(_owned_trip_date)],
    idempotency_key: Annotated[uuid.UUID, Header(alias="Idempotency-Key")],
    service: Annotated[ProgressService, Depends(_service)],
) -> JSONResponse:
    data = await service.start_day(
        trip_id=trip.trip_id, visit_date=visit_date,
        payload=payload, idempotency_key=idempotency_key,
    )
    return success_response(request, data)


@item_router.patch(
    "/{itemId}/status",
    response_model=ProgressEnvelope,
    responses={
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
    return success_response(request, data)


__all__ = ["item_router", "router"]
