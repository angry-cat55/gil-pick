"""여행 관리 HTTP 엔드포인트 시그니처."""

from __future__ import annotations

import uuid
from typing import Annotated, NoReturn

from fastapi import APIRouter, Depends, Header, Path, Query, Request
from fastapi.responses import JSONResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import get_current_principal
from app.api.errors import AppError, get_request_id, success_response
from app.core.config import Settings, get_settings
from app.core.security import AuthPrincipal
from app.db import get_session
from app.schemas.trip import (
    CreateTripRequest,
    ErrorEnvelope,
    Pagination,
    TripEnvelope,
    TripListData,
    TripListMeta,
    TripListResponse,
    TripStatus,
    UpdateTripRequest,
)
from app.services.trip import TripService

router = APIRouter(prefix="/trips", tags=["trips"])


def _not_implemented() -> NoReturn:
    """담당 Issue에서 구현할 때까지 기반 라우트의 미구현 상태를 명시한다."""
    raise AppError(501, "NOT_IMPLEMENTED", "아직 구현되지 않은 여행 API입니다.")


def _trip_service(
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> TripService:
    """요청 transaction을 사용하는 여행 service를 제공한다."""
    return TripService(
        session,
        cursor_secret=settings.jwt_signing_secret.get_secret_value(),
    )


@router.get(
    "",
    response_model=TripListResponse,
    responses={400: {"model": ErrorEnvelope}, 401: {"model": ErrorEnvelope}},
)
async def list_trips(
    request: Request,
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    service: Annotated[TripService, Depends(_trip_service)],
    query: Annotated[str | None, Query()] = None,
    trip_status: Annotated[TripStatus | None, Query(alias="status")] = None,
    cursor: Annotated[str | None, Query()] = None,
    limit: Annotated[int, Query(ge=1, le=100)] = 20,
) -> JSONResponse:
    """인증된 사용자의 여행 목록을 검색·상태 필터와 cursor로 조회한다.

    Args:
        request: 공통 응답의 request ID를 제공하는 HTTP 요청.
        principal: Access Token에서 검증한 여행 소유자.
        query: 여행명 부분 검색어.
        trip_status: 선택한 파생 여행 상태.
        cursor: 이전 응답이 반환한 다음 페이지 식별자.
        limit: 한 페이지에 반환할 최대 여행 수.
        service: 현재 요청 transaction을 사용하는 여행 service.

    Returns:
        여행 목록과 다음 페이지 정보를 담은 공통 성공 envelope.

    Raises:
        AppError: cursor가 올바르지 않은 경우.
    """
    items, next_cursor, has_next = await service.list_trips(
        user_id=principal.user_id,
        query=query,
        status=trip_status,
        cursor=cursor,
        limit=limit,
    )
    response = TripListResponse(
        success=True,
        data=TripListData(items=items),
        meta=TripListMeta(
            request_id=get_request_id(request),
            pagination=Pagination(next_cursor=next_cursor, has_next=has_next),
        ),
    )
    return JSONResponse(content=response.model_dump(mode="json", by_alias=True))


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
    request: Request,
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    idempotency_key: Annotated[
        str,
        Header(alias="Idempotency-Key", min_length=1, max_length=255),
    ],
    service: Annotated[TripService, Depends(_trip_service)],
) -> JSONResponse:
    """인증된 사용자의 여행을 멱등하게 생성한다.

    Args:
        payload: trim과 기간 검증을 적용할 여행 생성 입력.
        request: 공통 응답의 request ID를 제공하는 HTTP 요청.
        principal: Access Token에서 검증한 여행 소유자.
        idempotency_key: 중복 생성을 방지하는 client 요청 식별자.
        service: 현재 요청 transaction을 사용하는 여행 service.

    Returns:
        생성된 여행을 담은 공통 성공 envelope.

    Raises:
        AppError: 여행명·기간 또는 멱등 키가 유효하지 않은 경우.
    """
    trip = await service.create_trip(
        user_id=principal.user_id,
        payload=payload,
        idempotency_key=idempotency_key,
    )
    return success_response(request, trip, status_code=201)


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
