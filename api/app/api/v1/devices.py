"""FCM 기기 토큰 등록·해제 API."""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Path, Request, Response
from fastapi.responses import JSONResponse

from app.api.dependencies import AuthPrincipal, get_current_principal
from app.api.errors import success_response
from app.api.v1.notifications import get_notification_service
from app.schemas.auth import ErrorEnvelope
from app.schemas.device import (
    FcmTokenRegisterEnvelope,
    FcmTokenRegisterRequest,
    FcmTokenRegisterResult,
)
from app.services.notification import NotificationService

router = APIRouter(prefix="/devices", tags=["devices"])


@router.put(
    "/fcm-token",
    response_model=FcmTokenRegisterEnvelope,
    responses={
        400: {"model": ErrorEnvelope},
        401: {"model": ErrorEnvelope},
        403: {"model": ErrorEnvelope},
        404: {"model": ErrorEnvelope},
    },
)
async def register_fcm_token(
    payload: FcmTokenRegisterRequest,
    request: Request,
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    service: Annotated[NotificationService, Depends(get_notification_service)],
) -> JSONResponse:
    """현재 사용자의 활성 기기 session에 FCM token을 등록한다."""
    await service.register_fcm_token(
        principal.user_id,
        payload.device_id,
        payload.fcm_token,
        payload.platform,
    )
    return success_response(
        request,
        FcmTokenRegisterResult(device_id=payload.device_id, registered=True),
    )


@router.delete(
    "/{deviceId}/fcm-token",
    status_code=204,
    responses={
        401: {"model": ErrorEnvelope},
        403: {"model": ErrorEnvelope},
        404: {"model": ErrorEnvelope},
    },
)
async def unregister_fcm_token(
    device_id: Annotated[str, Path(alias="deviceId", max_length=255)],
    principal: Annotated[AuthPrincipal, Depends(get_current_principal)],
    service: Annotated[NotificationService, Depends(get_notification_service)],
) -> Response:
    """소유한 활성 기기 session의 FCM token을 해제한다."""
    await service.unregister_fcm_token(principal.user_id, device_id)
    return Response(status_code=204)


__all__ = ["router"]
