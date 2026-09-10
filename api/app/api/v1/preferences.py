"""현재 사용자의 장소 변경 제안 알림 설정 API."""

from typing import Annotated

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import AuthPrincipal, get_current_principal
from app.api.errors import success_response
from app.db import get_session
from app.schemas.auth import ErrorEnvelope
from app.schemas.preferences import (
    PreferenceData,
    PreferenceEnvelope,
    UpdatePreferenceRequest,
)
from app.services.preferences import PreferencesService

router = APIRouter(prefix="/users/me/preferences", tags=["preferences"])


def get_preferences_service(
    session: Annotated[AsyncSession, Depends(get_session)],
) -> PreferencesService:
    """요청 transaction을 사용하는 사용자 설정 service를 반환한다."""
    return PreferencesService(session)


@router.get(
    "",
    response_model=PreferenceEnvelope,
    responses={401: {"model": ErrorEnvelope}},
)
async def get_my_preferences(
    request: Request,
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    service: Annotated[PreferencesService, Depends(get_preferences_service)],
) -> JSONResponse:
    """인증 사용자의 저장된 장소 변경 제안 알림 설정을 조회한다."""
    enabled = await service.get(principal.user_id)
    return success_response(
        request,
        PreferenceData(place_change_suggestion_notification_enabled=enabled),
    )


@router.patch(
    "",
    response_model=PreferenceEnvelope,
    responses={400: {"model": ErrorEnvelope}, 401: {"model": ErrorEnvelope}},
)
async def update_my_preferences(
    request: Request,
    payload: UpdatePreferenceRequest,
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    service: Annotated[PreferencesService, Depends(get_preferences_service)],
) -> JSONResponse:
    """인증 사용자의 설정을 절대값으로 멱등하게 갱신한다."""
    enabled = await service.update(
        principal.user_id,
        payload.place_change_suggestion_notification_enabled,
    )
    return success_response(
        request,
        PreferenceData(place_change_suggestion_notification_enabled=enabled),
    )


__all__ = ["get_preferences_service", "router"]
