"""장소 검색·상세 endpoint가 공유하는 인증 router."""

from collections.abc import AsyncIterator
from typing import Annotated, Literal

import httpx2
from fastapi import APIRouter, Depends, Path, Query, Request
from fastapi.responses import JSONResponse

from app.api.dependencies import get_current_principal
from app.api.errors import AppError, get_request_id, success_response
from app.clients.google_places import GooglePlacesClient
from app.clients.tour_api import TourApiClient
from app.core.config import Settings, get_settings
from app.schemas.place import (
    ErrorEnvelope,
    PaginationMeta,
    PlaceCategory,
    PlaceListData,
    PlaceListEnvelope,
    PlaceListMeta,
    PlaceEnvelope,
)
from app.services.place import PlaceService

AreaCode = Literal[
    "1", "2", "3", "4", "5", "6", "7", "8",
    "31", "32", "33", "34", "35", "36", "37", "38", "39",
]

router = APIRouter(
    prefix="/places",
    tags=["places"],
    dependencies=[Depends(get_current_principal)],
)


async def _place_service(
    settings: Annotated[Settings, Depends(get_settings)],
) -> AsyncIterator[PlaceService]:
    """한 요청에서 두 provider가 공유하는 HTTP client를 제공한다."""
    async with httpx2.AsyncClient(
        timeout=settings.place_provider_timeout_seconds
    ) as client:
        yield PlaceService(
            TourApiClient(settings, client),
            GooglePlacesClient(settings, client),
            cursor_secret=settings.jwt_signing_secret.get_secret_value(),
        )


@router.get(
    "/search",
    response_model=PlaceListEnvelope,
    responses={
        400: {"model": ErrorEnvelope},
        401: {"model": ErrorEnvelope},
        429: {"model": ErrorEnvelope},
        502: {"model": ErrorEnvelope},
        504: {"model": ErrorEnvelope},
    },
)
async def search_places(
    request: Request,
    service: Annotated[PlaceService, Depends(_place_service)],
    query: Annotated[str | None, Query()] = None,
    category: Annotated[PlaceCategory | None, Query()] = None,
    area_code: Annotated[AreaCode | None, Query(alias="areaCode")] = None,
    cursor: Annotated[str | None, Query(min_length=1)] = None,
    limit: Annotated[int, Query(ge=1, le=20)] = 20,
) -> JSONResponse:
    """키워드 또는 카테고리 조건으로 장소를 검색한다."""
    normalized_query = query.strip() if query is not None else None
    if normalized_query is not None and len(normalized_query) < 2:
        raise AppError(400, "INVALID_REQUEST", "검색어는 2글자 이상이어야 합니다.")
    if normalized_query is None and category is None:
        raise AppError(400, "INVALID_REQUEST", "검색어 또는 카테고리가 필요합니다.")

    items, next_cursor, has_next = await service.search_places(
        query=normalized_query,
        category=category,
        area_code=area_code,
        cursor=cursor,
        limit=limit,
    )
    response = PlaceListEnvelope(
        success=True,
        data=PlaceListData(items=items),
        meta=PlaceListMeta(
            request_id=get_request_id(request),
            pagination=PaginationMeta(next_cursor=next_cursor, has_next=has_next),
        ),
    )
    return JSONResponse(content=response.model_dump(mode="json", by_alias=True))


@router.get("/{placeId}", response_model=PlaceEnvelope, responses={
    400: {"model": ErrorEnvelope}, 401: {"model": ErrorEnvelope},
    404: {"model": ErrorEnvelope}, 429: {"model": ErrorEnvelope},
    502: {"model": ErrorEnvelope}, 504: {"model": ErrorEnvelope},
})
async def get_place(
    request: Request,
    service: Annotated[PlaceService, Depends(_place_service)],
    place_id: Annotated[str, Path(alias="placeId", pattern=r"^(tourapi|google):[A-Za-z0-9_-]+$")],
) -> JSONResponse:
    """provider 이름공간 ID로 장소 상세를 조회한다."""
    return success_response(request, await service.get_place(place_id))
