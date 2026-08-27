"""여행 관리 HTTP 엔드포인트 시그니처."""

from __future__ import annotations

import uuid
from typing import Annotated, NoReturn

from fastapi import APIRouter, Depends, Header, Path, Query

from app.api.dependencies import get_current_principal
from app.api.errors import AppError
from app.core.security import AuthPrincipal
from app.schemas.trip import (
    CreateTripRequest,
    ErrorEnvelope,
    TripEnvelope,
    TripListResponse,
    TripStatus,
    UpdateTripRequest,
)

router = APIRouter(prefix="/trips", tags=["trips"])


def _not_implemented() -> NoReturn:
    """담당 Issue에서 구현할 때까지 기반 라우트의 미구현 상태를 명시한다."""
    raise AppError(501, "NOT_IMPLEMENTED", "아직 구현되지 않은 여행 API입니다.")


@router.get(
    "",
    response_model=TripListResponse,
    responses={400: {"model": ErrorEnvelope}, 401: {"model": ErrorEnvelope}},
)
async def list_trips(
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    query: Annotated[str | None, Query()] = None,
    trip_status: Annotated[TripStatus | None, Query(alias="status")] = None,
    cursor: Annotated[str | None, Query()] = None,
    limit: Annotated[int, Query(ge=1, le=100)] = 20,
) -> NoReturn:
    """Issue #98에서 구현할 인증된 여행 목록 계약을 선언한다."""
    _not_implemented()


@router.post(
    "",
    status_code=201,
    response_model=TripEnvelope,
    responses={
        400: {"model": ErrorEnvelope},
        401: {"model": ErrorEnvelope},
        422: {"model": ErrorEnvelope},
    },
)
async def create_trip(
    payload: CreateTripRequest,
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    idempotency_key: Annotated[str, Header(alias="Idempotency-Key")],
) -> NoReturn:
    """Issue #97에서 구현할 인증된 여행 생성 계약을 선언한다."""
    _not_implemented()


@router.get(
    "/{tripId}",
    response_model=TripEnvelope,
    responses={
        401: {"model": ErrorEnvelope},
        403: {"model": ErrorEnvelope},
        404: {"model": ErrorEnvelope},
    },
)
async def get_trip(
    trip_id: Annotated[uuid.UUID, Path(alias="tripId")],
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
) -> NoReturn:
    """Issue #99에서 구현할 인증된 여행 상세 조회 계약을 선언한다."""
    _not_implemented()


@router.patch(
    "/{tripId}",
    response_model=TripEnvelope,
    responses={
        400: {"model": ErrorEnvelope},
        401: {"model": ErrorEnvelope},
        403: {"model": ErrorEnvelope},
        404: {"model": ErrorEnvelope},
        409: {"model": ErrorEnvelope},
        422: {"model": ErrorEnvelope},
    },
)
async def update_trip(
    trip_id: Annotated[uuid.UUID, Path(alias="tripId")],
    payload: UpdateTripRequest,
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
) -> NoReturn:
    """Issue #100에서 구현할 인증된 여행 수정 계약을 선언한다."""
    _not_implemented()


@router.delete(
    "/{tripId}",
    status_code=204,
    response_model=None,
    responses={
        401: {"model": ErrorEnvelope},
        403: {"model": ErrorEnvelope},
        404: {"model": ErrorEnvelope},
        409: {"model": ErrorEnvelope},
    },
)
async def delete_trip(
    trip_id: Annotated[uuid.UUID, Path(alias="tripId")],
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
) -> NoReturn:
    """Issue #101에서 구현할 인증된 멱등 삭제 계약을 선언한다."""
    _not_implemented()
