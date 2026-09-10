"""변경 경로 미리보기 생성·폐기 API."""

from __future__ import annotations

import uuid
from collections.abc import AsyncIterator
from typing import Annotated

import httpx2
from fastapi import APIRouter, Depends, Header, Path, Request, Response
from fastapi.responses import JSONResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import get_current_principal
from app.api.errors import AppError, success_response
from app.clients.google_places import GooglePlacesClient
from app.clients.odsay import OdsayClient
from app.clients.tmap import TmapClient
from app.clients.tour_api import TourApiClient
from app.core.config import Settings, get_settings
from app.core.security import AuthPrincipal
from app.db import get_session
from app.schemas.auth import ErrorEnvelope
from app.schemas.replacement import (
    CreatePreviewRequest,
    ReplacementEnvelope,
    RoutePreviewEnvelope,
    UndoEnvelope,
)
from app.services.detection.operating_hours_source import OperatingHoursSource
from app.services.place import PlaceService
from app.services.replacement import ReplacementService
from app.services.route import RouteCalculationService

router = APIRouter(tags=["replacements"])


async def get_replacement_service(
    session: Annotated[AsyncSession, Depends(get_session)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> AsyncIterator[ReplacementService]:
    """장소·경로 Provider 연결을 한 미리보기 요청 안에서 공유한다."""
    async with httpx2.AsyncClient(timeout=settings.route_calculation_deadline_seconds) as client:
        google = GooglePlacesClient(settings, client)
        yield ReplacementService(
            session,
            calculator=RouteCalculationService(
                tmap=TmapClient(settings, client), odsay=OdsayClient(settings, client),
                concurrency=settings.route_provider_concurrency,
                deadline_seconds=settings.route_calculation_deadline_seconds,
            ),
            place_service=PlaceService(
                TourApiClient(settings, client), google,
                cursor_secret=settings.jwt_signing_secret.get_secret_value(),
            ),
            operating_hours_source=OperatingHoursSource(settings, google),
            candidate_secret=settings.jwt_signing_secret.get_secret_value(),
        )


@router.post(
    "/detections/{detectionId}/route-previews",
    operation_id="createRoutePreview",
    response_model=RoutePreviewEnvelope,
    responses={
        400: {"model": ErrorEnvelope}, 401: {"model": ErrorEnvelope},
        403: {"model": ErrorEnvelope}, 404: {"model": ErrorEnvelope},
        409: {"model": ErrorEnvelope}, 502: {"model": ErrorEnvelope},
        504: {"model": ErrorEnvelope},
    },
)
async def create_route_preview(
    payload: CreatePreviewRequest,
    request: Request,
    detection_id: Annotated[uuid.UUID, Path(alias="detectionId")],
    idempotency_key: Annotated[str, Header(alias="Idempotency-Key", min_length=1, max_length=255)],
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    service: Annotated[ReplacementService, Depends(get_replacement_service)],
) -> JSONResponse:
    """소유한 ACTIVE 감지의 대체 경로를 실제 일정과 분리해 계산한다."""
    data = await service.create_preview(
        detection_id=detection_id, user_id=principal.user_id,
        payload=payload, idempotency_key=idempotency_key,
    )
    return success_response(request, data)


@router.post(
    "/route-previews/{previewId}/approve",
    operation_id="approveRoutePreview",
    response_model=ReplacementEnvelope,
    responses={
        400: {"model": ErrorEnvelope, "description": "`INVALID_REQUEST`"},
        401: {"model": ErrorEnvelope},
        403: {"model": ErrorEnvelope, "description": "`TRIP_FORBIDDEN`"},
        404: {"model": ErrorEnvelope, "description": "`PREVIEW_NOT_FOUND`"},
        409: {
            "model": ErrorEnvelope,
            "description": (
                "`PREVIEW_EXPIRED`, `PREVIEW_SUPERSEDED`, `PREVIEW_REJECTED`, "
                "`ALREADY_APPROVED`, `VERSION_CONFLICT`, `ITEM_ALREADY_VISITED`, "
                "`ALTERNATIVE_UNAVAILABLE`, `DETECTION_NOT_ACTIVE`"
            ),
        },
    },
)
async def approve_route_preview(
    request: Request,
    preview_id: Annotated[uuid.UUID, Path(alias="previewId")],
    idempotency_key: Annotated[str, Header(alias="Idempotency-Key", min_length=1, max_length=255)],
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    service: Annotated[ReplacementService, Depends(get_replacement_service)],
) -> JSONResponse:
    """소유한 미리보기의 장소·경로·감지 상태를 원자적으로 확정한다."""
    data = await service.approve_preview(
        preview_id=preview_id,
        user_id=principal.user_id,
        idempotency_key=idempotency_key,
    )
    return success_response(request, data)


@router.post(
    "/replacements/{replacementId}/undo",
    operation_id="undoReplacement",
    response_model=UndoEnvelope,
    responses={
        401: {"model": ErrorEnvelope},
        403: {"model": ErrorEnvelope, "description": "`TRIP_FORBIDDEN`"},
        404: {"model": ErrorEnvelope, "description": "`REPLACEMENT_NOT_FOUND`"},
        409: {
            "model": ErrorEnvelope,
            "description": "`UNDO_EXPIRED`, `FOLLOW_UP_CHANGE_EXISTS`",
        },
    },
)
async def undo_replacement(
    request: Request,
    replacement_id: Annotated[uuid.UUID, Path(alias="replacementId")],
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    service: Annotated[ReplacementService, Depends(get_replacement_service)],
) -> JSONResponse:
    """소유한 장소 변경의 일정·경로·감지 결과를 원자적으로 복원한다."""
    data = await service.undo_replacement(
        replacement_id=replacement_id,
        user_id=principal.user_id,
    )
    return success_response(request, data)


@router.post(
    "/route-previews/{previewId}/reject",
    operation_id="rejectRoutePreview",
    status_code=204,
    responses={
        401: {"model": ErrorEnvelope}, 403: {"model": ErrorEnvelope},
        404: {"model": ErrorEnvelope}, 409: {"model": ErrorEnvelope},
    },
)
async def reject_route_preview(
    preview_id: Annotated[uuid.UUID, Path(alias="previewId")],
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    service: Annotated[ReplacementService, Depends(get_replacement_service)],
) -> Response:
    """미리보기만 폐기하고 일정·경로·감지 결과는 유지한다."""
    await service.reject_preview(preview_id=preview_id, user_id=principal.user_id)
    return Response(status_code=204)


__all__ = ["get_replacement_service", "router"]
