"""F012 사용자 설정 API schema 계약 테스트."""

import pytest
from pydantic import ValidationError

from app.schemas.preferences import PreferenceData, UpdatePreferenceRequest


@pytest.mark.parametrize("value", [None, "false", 0, 1])
def test_update_request_requires_strict_boolean(value: object) -> None:
    with pytest.raises(ValidationError):
        UpdatePreferenceRequest(
            placeChangeSuggestionNotificationEnabled=value,
        )


def test_update_request_rejects_missing_and_extra_fields() -> None:
    with pytest.raises(ValidationError):
        UpdatePreferenceRequest()
    with pytest.raises(ValidationError):
        UpdatePreferenceRequest(
            placeChangeSuggestionNotificationEnabled=True,
            unexpected=True,
        )


def test_preference_data_serializes_camel_case() -> None:
    data = PreferenceData(place_change_suggestion_notification_enabled=False)

    assert data.model_dump(by_alias=True) == {
        "placeChangeSuggestionNotificationEnabled": False,
    }
