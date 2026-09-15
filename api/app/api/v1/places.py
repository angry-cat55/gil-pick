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

AreaCode = Literal["1"]

router = APIRouter(
    prefix="/places",
    tags=["places"],
    dependencies=[Depends(get_current_principal)],
)


async def _place_service(
    settings: Annotated[Settings, Depends(get_settings)],
) -> AsyncIterator[PlaceService]:
    """한 요청에서 두 provider가 공유하는 HTTP client를 제공한다.

    Args:
        settings: 외부 provider와 cursor 서명에 사용할 애플리케이션 설정.

    Yields:
        요청 종료 시 HTTP client가 함께 닫히는 장소 service.
    """
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
    area_code: Annotated[AreaCode, Query(alias="areaCode")] = "1",
    latitude: Annotated[float | None, Query(ge=-90, le=90)] = None,
    longitude: Annotated[float | None, Query(ge=-180, le=180)] = None,
    radius_meters: Annotated[int, Query(alias="radiusMeters", ge=1, le=20_000)] = 5000,
    cursor: Annotated[str | None, Query(min_length=1)] = None,
    limit: Annotated[int, Query(ge=1, le=20)] = 20,
) -> JSONResponse:
    """키워드 또는 카테고리 조건으로 장소를 검색한다.

    Args:
        request: request ID를 포함하는 HTTP 요청.
        service: 외부 provider를 조합하는 장소 service.
        query: 두 글자 이상의 검색어.
        category: 길픽 장소 카테고리 필터.
        area_code: 서울로 고정된 TourAPI 지역 코드.
        latitude: 주변 검색의 기준 위도.
        longitude: 주변 검색의 기준 경도.
        radius_meters: 주변 검색 반경. 기본 5km.
        cursor: 이전 응답에서 받은 pagination cursor.
        limit: 한 페이지에 반환할 최대 장소 수.

    Returns:
        공통 envelope에 담긴 장소 검색 응답.

    Raises:
        AppError: 검색 조건 또는 cursor가 잘못됐거나 provider가 실패한 경우.
    """
    normalized_query = query.strip() if query is not None else None
    if normalized_query is not None and len(normalized_query) < 2:
        raise AppError(400, "INVALID_REQUEST", "검색어는 2글자 이상이어야 합니다.")
    has_location = latitude is not None and longitude is not None
    if (latitude is None) != (longitude is None):
        raise AppError(400, "INVALID_REQUEST", "위도와 경도를 함께 입력해야 합니다.")
    if normalized_query is None and not has_location:
        raise AppError(400, "INVALID_REQUEST", "검색어가 없으면 현재 위치가 필요합니다.")

    items, next_cursor, has_next = await service.search_places(
        query=normalized_query,
        category=category,
        area_code=area_code,
        latitude=latitude,
        longitude=longitude,
        radius_meters=radius_meters,
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
    """provider 이름공간 ID로 장소 상세를 조회한다.

    Args:
        request: request ID를 포함하는 HTTP 요청.
        service: 외부 provider를 조합하는 장소 service.
        place_id: provider prefix가 포함된 장소 ID.

    Returns:
        공통 envelope에 담긴 장소 상세 응답.

    Raises:
        AppError: 장소가 없거나 provider 요청에 실패한 경우.
    """
    return success_response(request, await service.get_place(place_id))
