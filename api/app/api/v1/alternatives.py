"""대체 장소 추천 API."""

from __future__ import annotations

import uuid
from collections.abc import AsyncIterator
from typing import Annotated

import httpx2
from fastapi import APIRouter, Depends, Path, Query, Request
from fastapi.responses import JSONResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import AuthPrincipal, get_current_principal
from app.api.errors import AppError, get_request_id
from app.clients.google_places import GooglePlacesClient
from app.clients.kma import KmaClient
from app.clients.seoul_citydata import SeoulCityDataClient
from app.clients.tour_api import TourApiClient
from app.core.config import Settings, get_settings
from app.db import get_session
from app.schemas.alternatives import (
    AlternativeListEnvelope,
    AlternativeSearchData,
    AlternativeSearchEnvelope,
    ErrorEnvelope,
)
from app.schemas.auth import ResponseMeta
from app.schemas.detection import PaginatedMeta, Pagination
from app.services.alternatives import AlternativeService
from app.services.detection.operating_hours_source import OperatingHoursSource

router = APIRouter(prefix="/detections", tags=["alternatives"])


async def get_alternative_service(
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> AsyncIterator[AlternativeService]:
    """한 후보 요청에서 외부 provider connection을 공유한다."""
    async with httpx2.AsyncClient(
        timeout=settings.place_provider_timeout_seconds
    ) as client:
        google = GooglePlacesClient(settings, client)
        yield AlternativeService(
            session,
            tour_client=TourApiClient(settings, client),
            google_client=google,
            operating_hours_source=OperatingHoursSource(settings, google),
            kma_client=KmaClient(settings, client),
            seoul_client=SeoulCityDataClient(settings, client),
            candidate_secret=settings.jwt_signing_secret.get_secret_value(),
        )


@router.get(
    "/{detectionId}/alternatives",
    response_model=AlternativeListEnvelope,
    responses={
        401: {"model": ErrorEnvelope},
        403: {"model": ErrorEnvelope},
        404: {"model": ErrorEnvelope},
        409: {"model": ErrorEnvelope},
        502: {"model": ErrorEnvelope},
        504: {"model": ErrorEnvelope},
    },
)
async def list_alternatives(
    request: Request,
    detection_id: Annotated[uuid.UUID, Path(alias="detectionId")],
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    service: Annotated[AlternativeService, Depends(get_alternative_service)],
) -> JSONResponse:
    """ACTIVE 감지 결과의 현재 대체 후보를 점수 순으로 반환한다."""
    try:
        data = await service.list_candidates(detection_id, principal.user_id)
    except AppError as exc:
        if exc.status_code == 403 and exc.code == "DETECTION_FORBIDDEN":
            raise AppError(403, "TRIP_FORBIDDEN", exc.message) from exc
        raise
    envelope = AlternativeListEnvelope(
        success=True,
        data=data,
        meta=ResponseMeta(request_id=get_request_id(request)),
    )
    return JSONResponse(content=envelope.model_dump(mode="json", by_alias=True))


@router.get(
    "/{detectionId}/alternatives/search",
    response_model=AlternativeSearchEnvelope,
    responses={
        400: {"model": ErrorEnvelope},
        401: {"model": ErrorEnvelope},
        403: {"model": ErrorEnvelope},
        404: {"model": ErrorEnvelope},
        409: {"model": ErrorEnvelope},
        429: {"model": ErrorEnvelope},
        502: {"model": ErrorEnvelope},
        504: {"model": ErrorEnvelope},
    },
)
async def search_alternatives(
    request: Request,
    detection_id: Annotated[uuid.UUID, Path(alias="detectionId")],
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    service: Annotated[AlternativeService, Depends(get_alternative_service)],
    query: Annotated[str, Query(min_length=1)],
    cursor: Annotated[str | None, Query(min_length=1)] = None,
    limit: Annotated[int, Query(ge=1, le=20)] = 20,
) -> JSONResponse:
    """ACTIVE 감지 기준으로 장소를 직접 검색하고 거리·방문 가능 정보를 덧붙여 반환한다."""
    normalized_query = query.strip()
    if len(normalized_query) < 2:
        raise AppError(400, "INVALID_REQUEST", "검색어는 2글자 이상이어야 합니다.")
    try:
        items, next_cursor, has_next = await service.search(
            detection_id,
            principal.user_id,
            query=normalized_query,
            cursor=cursor,
            limit=limit,
        )
    except AppError as exc:
        if exc.status_code == 403 and exc.code == "DETECTION_FORBIDDEN":
            raise AppError(403, "TRIP_FORBIDDEN", exc.message) from exc
        raise
    envelope = AlternativeSearchEnvelope(
        success=True,
        data=AlternativeSearchData(items=items),
        meta=PaginatedMeta(
            request_id=get_request_id(request),
            pagination=Pagination(next_cursor=next_cursor, has_next=has_next),
        ),
    )
    return JSONResponse(content=envelope.model_dump(mode="json", by_alias=True))


__all__ = ["get_alternative_service", "router"]
