"""날짜별 계획 경로 조회 API의 인증·소유권 경계."""

from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends, Path, Request
from fastapi.responses import JSONResponse

from app.api.errors import success_response
from app.api.v1.itinerary import _owned_trip_date
from app.core.config import Settings, get_settings
from app.schemas.auth import ErrorEnvelope
from app.schemas.route import RetryRouteRequest, RouteEnvelope
from app.schemas.trip import Trip
from app.services.route import RouteService, build_route_service

router = APIRouter(prefix="/trips/{tripId}/days/{date}/route", tags=["route"])


async def _route_service(
    settings: Annotated[Settings, Depends(get_settings)],
) -> AsyncIterator[RouteService]:
    """설정된 Provider와 DB session factory를 사용하는 경로 서비스를 제공한다."""
    service = build_route_service(settings)
    try:
        yield service
    finally:
        await service.close()


@router.get(
    "",
    response_model=RouteEnvelope,
    responses={
        400: {"model": ErrorEnvelope},
        401: {"model": ErrorEnvelope},
        403: {"model": ErrorEnvelope},
        404: {"model": ErrorEnvelope},
    },
)
async def get_day_route(
    request: Request,
    visit_date: Annotated[date, Path(alias="date")],
    trip: Annotated[Trip, Depends(_owned_trip_date)],
    service: Annotated[RouteService, Depends(_route_service)],
) -> JSONResponse:
    """소유 여행 날짜의 현재 일정 version에 해당하는 경로만 반환한다."""
    data = await service.get_current(
        trip_id=trip.trip_id,
        visit_date=visit_date,
    )
    return success_response(request, data)


@router.post(
    "/retry",
    response_model=RouteEnvelope,
    responses={
        400: {"model": ErrorEnvelope},
        401: {"model": ErrorEnvelope},
        403: {"model": ErrorEnvelope},
        404: {"model": ErrorEnvelope},
        409: {"model": ErrorEnvelope},
    },
)
async def retry_failed_day_route(
    payload: RetryRouteRequest,
    request: Request,
    visit_date: Annotated[date, Path(alias="date")],
    trip: Annotated[Trip, Depends(_owned_trip_date)],
    service: Annotated[RouteService, Depends(_route_service)],
) -> JSONResponse:
    """현재 일정 version의 FAILED 경로 전체를 같은 입력으로 다시 계산한다."""
    data = await service.retry_current(
        trip_id=trip.trip_id,
        visit_date=visit_date,
        schedule_version=payload.schedule_version,
    )
    return success_response(request, data)


__all__ = ["router"]
