"""Authentication API schemas matching the F001 OpenAPI contract."""

from __future__ import annotations

import uuid
from typing import Any, Generic, Literal, TypeVar

from pydantic import BaseModel, ConfigDict, Field, HttpUrl, field_validator
from pydantic.alias_generators import to_camel

OPAQUE_SELECTOR_PATTERN = (
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\.[A-Za-z0-9_-]{43}$"
)


class ApiModel(BaseModel):
    """Strict camelCase model shared by public API payloads."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


class CreateLoginTransactionRequest(ApiModel):
    """Device-bound Kakao login transaction request."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=False,
        validate_by_alias=True,
        validate_by_name=False,
        extra="forbid",
    )

    device_id: uuid.UUID
    platform: Literal["ANDROID"]


class LoginTicketExchangeRequest(ApiModel):
    """One-time login ticket exchange request."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=False,
        validate_by_alias=True,
        validate_by_name=False,
        extra="forbid",
    )

    login_ticket: str = Field(
        min_length=80,
        max_length=80,
        pattern=OPAQUE_SELECTOR_PATTERN,
    )
    device_id: uuid.UUID


class RefreshTokenRequest(ApiModel):
    """Device-bound Refresh Token rotation or revocation request."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=False,
        validate_by_alias=True,
        validate_by_name=False,
        extra="forbid",
    )

    refresh_token: str = Field(
        min_length=80,
        max_length=80,
        pattern=OPAQUE_SELECTOR_PATTERN,
    )
    device_id: uuid.UUID


class UserSummary(ApiModel):
    """Minimal authenticated user information returned to Android."""

    user_id: uuid.UUID
    nickname: str | None = Field(default=None, max_length=80)
    profile_image_url: HttpUrl | None = None
    provider: Literal["KAKAO"] = "KAKAO"

    @field_validator("profile_image_url")
    @classmethod
    def validate_profile_https(cls, value: HttpUrl | None) -> HttpUrl | None:
        """Reject non-HTTPS profile image URLs."""
        if value is not None and value.scheme != "https":
            raise ValueError("profileImageUrl must use HTTPS")
        return value


class LoginTransactionData(ApiModel):
    """Kakao authorization URL and transaction lifetime."""

    transaction_id: uuid.UUID
    authorization_url: HttpUrl
    expires_in: Literal[600] = 600

    @field_validator("authorization_url")
    @classmethod
    def validate_authorization_https(cls, value: HttpUrl) -> HttpUrl:
        """Reject non-HTTPS Kakao authorization URLs."""
        if value.scheme != "https":
            raise ValueError("authorizationUrl must use HTTPS")
        return value


class AuthTokenData(ApiModel):
    """Token pair and user returned after ticket exchange."""

    access_token: str
    expires_in: Literal[3600] = 3600
    refresh_token: str = Field(
        min_length=80,
        max_length=80,
        pattern=OPAQUE_SELECTOR_PATTERN,
    )
    refresh_expires_in: Literal[2592000] = 2592000
    user: UserSummary


class RefreshTokenData(ApiModel):
    """Rotated Token pair returned for an existing session."""

    access_token: str
    expires_in: Literal[3600] = 3600
    refresh_token: str = Field(
        min_length=80,
        max_length=80,
        pattern=OPAQUE_SELECTOR_PATTERN,
    )
    refresh_expires_in: Literal[2592000] = 2592000


class ResponseMeta(ApiModel):
    """Request correlation metadata included in every JSON response."""

    request_id: uuid.UUID


class ErrorBody(ApiModel):
    """Stable client-facing error details and retry guidance."""

    code: str
    message: str
    details: dict[str, Any] = Field(default_factory=dict)
    retryable: bool


DataT = TypeVar("DataT")


class SuccessEnvelope(ApiModel, Generic[DataT]):
    """Common successful JSON response envelope."""

    success: Literal[True] = True
    data: DataT
    meta: ResponseMeta


class ErrorEnvelope(ApiModel):
    """Common failed JSON response envelope."""

    success: Literal[False] = False
    error: ErrorBody
    meta: ResponseMeta
