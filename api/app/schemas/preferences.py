"""사용자 알림 설정 API schema."""

from typing import Annotated

from pydantic import ConfigDict, Field

from app.schemas.auth import ApiModel, SuccessEnvelope


StrictPreference = Annotated[bool, Field(strict=True)]


class PreferenceData(ApiModel):
    """현재 사용자에게 저장된 단일 알림 설정."""

    place_change_suggestion_notification_enabled: StrictPreference


class UpdatePreferenceRequest(ApiModel):
    """장소 변경 제안 알림의 절대값 변경 요청."""

    model_config = ConfigDict(
        alias_generator=ApiModel.model_config["alias_generator"],
        populate_by_name=False,
        validate_by_alias=True,
        validate_by_name=False,
        extra="forbid",
    )

    place_change_suggestion_notification_enabled: StrictPreference


PreferenceEnvelope = SuccessEnvelope[PreferenceData]


__all__ = ["PreferenceData", "PreferenceEnvelope", "UpdatePreferenceRequest"]
