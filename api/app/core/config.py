"""Environment-backed application configuration."""

from functools import lru_cache
from urllib.parse import urlparse

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Validated runtime settings loaded from environment variables."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    database_url: str
    jwt_signing_secret: SecretStr = Field(min_length=32)
    jwt_issuer: str
    jwt_audience: str
    kakao_rest_api_key: SecretStr
    kakao_client_secret: SecretStr
    kakao_redirect_uri: str
    android_app_link_base_url: str
    android_app_link_host: str

    @field_validator("jwt_signing_secret")
    @classmethod
    def validate_signing_secret(cls, value: SecretStr) -> SecretStr:
        """Reject documented placeholder values that would make JWTs forgeable."""
        secret = value.get_secret_value().lower()
        if secret.startswith(("replace-with", "change-me")):
            raise ValueError("JWT_SIGNING_SECRET must be replaced with a random secret")
        return value

    @field_validator("database_url")
    @classmethod
    def validate_database_url(cls, value: str) -> str:
        """Require SQLAlchemy's async PostgreSQL driver URL."""
        if not value.startswith("postgresql+asyncpg://"):
            raise ValueError("DATABASE_URL must use postgresql+asyncpg")
        return value

    @field_validator("jwt_issuer", "kakao_redirect_uri", "android_app_link_base_url")
    @classmethod
    def validate_https_url(cls, value: str) -> str:
        """Require an absolute HTTPS URL without embedded credentials."""
        parsed = urlparse(value)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
            raise ValueError("value must be an absolute HTTPS URL without credentials")
        return value.rstrip("/")

    @field_validator("android_app_link_host")
    @classmethod
    def validate_app_link_host(cls, value: str) -> str:
        """Require a bare DNS host for Android App Link verification."""
        parsed = urlparse(f"//{value}")
        if parsed.hostname != value or parsed.port or "/" in value:
            raise ValueError("ANDROID_APP_LINK_HOST must be a bare DNS host")
        return value.lower()

    @model_validator(mode="after")
    def validate_app_link_pair(self) -> "Settings":
        """Ensure the App Link URL uses the configured verification host."""
        if urlparse(self.android_app_link_base_url).hostname != self.android_app_link_host:
            raise ValueError("ANDROID_APP_LINK_BASE_URL host must match ANDROID_APP_LINK_HOST")
        return self


@lru_cache
def get_settings() -> Settings:
    """Load and cache validated runtime settings.

    Returns:
        Validated settings shared by the application.

    Raises:
        pydantic.ValidationError: If required configuration is missing or invalid.
    """
    return Settings()  # type: ignore[call-arg]
