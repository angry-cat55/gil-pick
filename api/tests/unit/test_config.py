"""Configuration validation tests."""

import pytest
from pydantic import ValidationError

from app.core.config import Settings


def valid_settings(**overrides: str) -> dict[str, str]:
    """Return valid explicit settings with selected overrides."""
    values = {
        "database_url": "postgresql+asyncpg://user:password@localhost/gilpick",
        "jwt_signing_secret": "x" * 32,
        "jwt_issuer": "https://api.gilpick.example",
        "jwt_audience": "gilpick-android",
        "kakao_rest_api_key": "test-rest-key",
        "kakao_client_secret": "test-client-secret",
        "kakao_redirect_uri": "https://api.gilpick.example/api/v1/auth/kakao/callback",
        "android_app_link_base_url": "https://app.gilpick.example/auth/kakao/complete",
        "android_app_link_host": "app.gilpick.example",
    }
    values.update(overrides)
    return values


def test_required_value_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("KAKAO_CLIENT_SECRET", raising=False)
    values = valid_settings()
    del values["kakao_client_secret"]

    with pytest.raises(ValidationError):
        Settings(_env_file=None, **values)


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("jwt_issuer", "http://api.gilpick.example"),
        ("kakao_redirect_uri", "http://api.gilpick.example/callback"),
        ("android_app_link_base_url", "http://app.gilpick.example/complete"),
    ],
)
def test_https_urls_are_required(field: str, value: str) -> None:
    with pytest.raises(ValidationError):
        Settings(_env_file=None, **valid_settings(**{field: value}))


def test_app_link_host_must_match_base_url() -> None:
    with pytest.raises(ValidationError):
        Settings(
            _env_file=None,
            **valid_settings(android_app_link_host="other.gilpick.example"),
        )


def test_documented_jwt_placeholder_is_rejected() -> None:
    with pytest.raises(ValidationError):
        Settings(
            _env_file=None,
            **valid_settings(
                jwt_signing_secret="replace-with-at-least-32-random-characters"
            ),
        )
