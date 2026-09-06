"""Configuration validation tests."""

import pytest
from pydantic import ValidationError

from app.core.config import Settings


def valid_settings(**overrides: object) -> dict[str, object]:
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
        "tour_api_service_key": "test-tour-api-key",
        "google_places_api_key": "test-google-places-key",
        "tmap_api_key": "tmap-secret",
        "odsay_api_key": "odsay-secret",
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


def test_place_provider_defaults_and_secrets() -> None:
    settings = Settings(_env_file=None, **valid_settings())

    assert settings.tour_api_base_url == "https://apis.data.go.kr/B551011/KorService2"
    assert settings.google_places_base_url == "https://places.googleapis.com/v1"
    assert settings.place_provider_timeout_seconds == 5.0
    assert str(settings.tour_api_service_key) == "**********"
    assert str(settings.google_places_api_key) == "**********"


def test_route_provider_settings_have_documented_defaults() -> None:
    settings = Settings(_env_file=None, **valid_settings())

    assert settings.route_provider_timeout_seconds == 5
    assert settings.route_calculation_deadline_seconds == 10
    assert settings.route_provider_concurrency == 3
    assert str(settings.tmap_api_key) == "**********"
    assert str(settings.odsay_api_key) == "**********"


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("route_provider_timeout_seconds", 0),
        ("route_calculation_deadline_seconds", 0),
        ("route_provider_concurrency", 0),
    ],
)
def test_route_provider_settings_reject_non_positive_values(
    field: str, value: int
) -> None:
    with pytest.raises(ValidationError):
        Settings(_env_file=None, **valid_settings(**{field: value}))
