"""일정 구성 API의 인증·여행 소유권 경계."""

from __future__ import annotations

import uuid
from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends, Header, Path, Request
from fastapi.responses import JSONResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import get_current_principal
from app.api.errors import AppError, success_response
from app.core.config import Settings, get_settings
from app.core.security import AuthPrincipal
from app.db import get_session
from app.schemas.auth import ErrorEnvelope
from app.schemas.itinerary import (
    DayItineraryEnvelope,
    ItineraryOverviewEnvelope,
    SaveDayItineraryRequest,
)
from app.schemas.trip import Trip
from app.services.itinerary import ItineraryService
from app.services.trip import TripService

router = APIRouter(prefix="/trips/{tripId}", tags=["itinerary"])


def _trip_service(
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> TripService:
    """기존 여행 조회 규칙을 사용하는 요청 단위 서비스를 제공한다."""
    return TripService(
        session,
        cursor_secret=settings.jwt_signing_secret.get_secret_value(),
    )


async def _owned_trip(
    trip_id: Annotated[uuid.UUID, Path(alias="tripId")],
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    service: Annotated[TripService, Depends(_trip_service)],
) -> Trip:
    """활성 여행의 존재와 요청 사용자의 소유권을 기존 규칙으로 검증한다."""
    try:
        return await service.get_trip(user_id=principal.user_id, trip_id=trip_id)
    except AppError as error:
        if error.status_code == 403:
            raise AppError(403, "TRIP_FORBIDDEN", error.message) from error
        raise


async def _owned_trip_date(
    visit_date: Annotated[date, Path(alias="date")],
    trip: Annotated[Trip, Depends(_owned_trip)],
) -> Trip:
    """요청 날짜가 소유한 여행 기간 안인지 검증한다."""
    if not trip.start_date <= visit_date <= trip.end_date:
        raise AppError(404, "TRIP_NOT_FOUND", "여행 기간에 포함되지 않은 날짜입니다.")
    return trip


def _itinerary_service(
    session: Annotated[AsyncSession, Depends(get_session)],
) -> ItineraryService:
    """현재 요청 transaction을 사용하는 일정 서비스를 제공한다."""
    return ItineraryService(session)


@router.get(
    "/itinerary",
    response_model=ItineraryOverviewEnvelope,
    responses={
        400: {"model": ErrorEnvelope},
        401: {"model": ErrorEnvelope},
        403: {"model": ErrorEnvelope},
        404: {"model": ErrorEnvelope},
    },
)
async def get_itinerary_overview(
    request: Request,
    trip: Annotated[Trip, Depends(_owned_trip)],
    service: Annotated[ItineraryService, Depends(_itinerary_service)],
) -> JSONResponse:
    """여행 기간의 모든 날짜와 빈 날짜를 포함한 일정 개요를 조회한다."""
    overview = await service.get_overview(
        trip_id=trip.trip_id,
        start_date=trip.start_date,
        end_date=trip.end_date,
    )
    return success_response(request, overview)


@router.get(
    "/days/{date}/itinerary",
    response_model=DayItineraryEnvelope,
    responses={
        400: {"model": ErrorEnvelope},
        401: {"model": ErrorEnvelope},
        403: {"model": ErrorEnvelope},
        404: {"model": ErrorEnvelope},
    },
)
async def get_day_itinerary(
    visit_date: Annotated[date, Path(alias="date")],
    request: Request,
    trip: Annotated[Trip, Depends(_owned_trip_date)],
    service: Annotated[ItineraryService, Depends(_itinerary_service)],
) -> JSONResponse:
    """소유 여행의 날짜별 일정 또는 version 0의 빈 일정을 조회한다."""
    day = await service.get_day(
        trip_id=trip.trip_id,
        visit_date=visit_date,
        start_date=trip.start_date,
    )
    return success_response(request, day)


@router.put(
    "/days/{date}/itinerary",
    response_model=DayItineraryEnvelope,
    responses={
        201: {"model": DayItineraryEnvelope},
        400: {"model": ErrorEnvelope},
        401: {"model": ErrorEnvelope},
        403: {"model": ErrorEnvelope},
        404: {"model": ErrorEnvelope},
        409: {"model": ErrorEnvelope},
        422: {"model": ErrorEnvelope},
    },
)
async def save_day_itinerary(
    payload: SaveDayItineraryRequest,
    visit_date: Annotated[date, Path(alias="date")],
    request: Request,
    trip: Annotated[Trip, Depends(_owned_trip_date)],
    idempotency_key: Annotated[uuid.UUID, Header(alias="Idempotency-Key")],
    service: Annotated[ItineraryService, Depends(_itinerary_service)],
) -> JSONResponse:
    """한 날짜의 일정 전체를 version과 멱등 키로 저장한다."""
    day, created = await service.save_day(
        trip_id=trip.trip_id,
        visit_date=visit_date,
        start_date=trip.start_date,
        payload=payload,
        idempotency_key=idempotency_key,
    )
    return success_response(request, day, status_code=201 if created else 200)
