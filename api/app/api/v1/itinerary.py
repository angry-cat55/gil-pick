"""일정 구성 API의 인증·여행 소유권 경계."""

from __future__ import annotations

import uuid
from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends, Path
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import get_current_principal
from app.api.errors import AppError
from app.core.config import Settings, get_settings
from app.core.security import AuthPrincipal
from app.db import get_session
from app.schemas.itinerary import SaveDayItineraryRequest
from app.schemas.trip import Trip
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
    return await service.get_trip(user_id=principal.user_id, trip_id=trip_id)


async def _owned_trip_date(
    visit_date: Annotated[date, Path(alias="date")],
    trip: Annotated[Trip, Depends(_owned_trip)],
) -> Trip:
    """요청 날짜가 소유한 여행 기간 안인지 검증한다."""
    if not trip.start_date <= visit_date <= trip.end_date:
        raise AppError(404, "TRIP_NOT_FOUND", "여행 기간에 포함되지 않은 날짜입니다.")
    return trip


def _not_implemented() -> None:
    """후속 user story가 채울 endpoint 본문임을 명시한다."""
    raise AppError(501, "NOT_IMPLEMENTED", "일정 endpoint는 후속 작업에서 구현됩니다.")


@router.get("/itinerary", dependencies=[Depends(_owned_trip)])
async def get_itinerary_overview() -> None:
    """여행 전체 일정 조회 경계이며 응답 구현은 US3에서 추가한다."""
    _not_implemented()


@router.get("/days/{date}/itinerary", dependencies=[Depends(_owned_trip_date)])
async def get_day_itinerary() -> None:
    """날짜별 일정 조회 경계이며 응답 구현은 US1에서 추가한다."""
    _not_implemented()


@router.put("/days/{date}/itinerary", dependencies=[Depends(_owned_trip_date)])
async def save_day_itinerary(payload: SaveDayItineraryRequest) -> None:
    """날짜별 일정 저장 경계이며 저장 구현은 US1에서 추가한다."""
    _not_implemented()
