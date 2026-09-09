"""FCM 기기 토큰 API schema."""

from __future__ import annotations

from typing import Literal

from pydantic import Field

from app.schemas.auth import ApiModel, SuccessEnvelope


class FcmTokenRegisterRequest(ApiModel):
    """현재 활성 기기에 저장할 FCM token."""

    device_id: str = Field(max_length=255)
    fcm_token: str = Field(min_length=1)
    platform: Literal["ANDROID"]


class FcmTokenRegisterResult(ApiModel):
    """FCM token 등록 결과."""

    device_id: str
    registered: Literal[True]


FcmTokenRegisterEnvelope = SuccessEnvelope[FcmTokenRegisterResult]


__all__ = [
    "FcmTokenRegisterEnvelope",
    "FcmTokenRegisterRequest",
    "FcmTokenRegisterResult",
]
