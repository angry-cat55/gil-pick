"""Access JWT and opaque selector Token primitives."""

from __future__ import annotations

import base64
import hashlib
import re
import secrets
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

import jwt
from jwt import InvalidTokenError

from app.core.config import Settings

ACCESS_TOKEN_TTL = timedelta(hours=1)
OPAQUE_TOKEN_PATTERN = re.compile(
    r"^(?P<selector>[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\."
    r"(?P<secret>[A-Za-z0-9_-]{43})$",
    re.IGNORECASE,
)


class AccessTokenError(ValueError):
    """Raised when an Access Token is absent, malformed, or untrusted."""


class OpaqueTokenError(ValueError):
    """Raised when a Refresh Token or login ticket has an invalid wire format."""


@dataclass(frozen=True, slots=True)
class AuthPrincipal:
    """Trusted identity extracted from a verified Access Token."""

    user_id: uuid.UUID
    session_id: uuid.UUID
    token_id: uuid.UUID


@dataclass(frozen=True, slots=True)
class OpaqueToken:
    """Parsed selector and secret for a database-backed opaque credential."""

    selector: uuid.UUID
    secret: str

    @property
    def encoded(self) -> str:
        """Return the fixed-width wire representation."""
        return f"{self.selector}.{self.secret}"

    @property
    def secret_hash(self) -> str:
        """Return the SHA-256 hex digest safe for database storage."""
        return hashlib.sha256(self.secret.encode("ascii")).hexdigest()


def create_access_token(
    user_id: uuid.UUID,
    session_id: uuid.UUID,
    settings: Settings,
    *,
    now: datetime | None = None,
) -> str:
    """Issue a one-hour HS256 Access Token.

    Args:
        user_id: Authenticated 길픽 user identifier.
        session_id: Device session identifier bound to the token.
        settings: JWT issuer, audience, and signing secret.
        now: Optional UTC clock override for deterministic tests.

    Returns:
        Signed JWT containing only authentication identifiers.
    """
    issued_at = (now or datetime.now(UTC)).astimezone(UTC)
    payload = {
        "sub": str(user_id),
        "sid": str(session_id),
        "iss": settings.jwt_issuer,
        "aud": settings.jwt_audience,
        "iat": issued_at,
        "exp": issued_at + ACCESS_TOKEN_TTL,
        "jti": str(uuid.uuid4()),
        "type": "access",
    }
    return jwt.encode(
        payload,
        settings.jwt_signing_secret.get_secret_value(),
        algorithm="HS256",
    )


def decode_access_token(token: str, settings: Settings) -> AuthPrincipal:
    """Verify an Access Token and return its trusted identifiers.

    Args:
        token: Encoded JWT received from a Bearer header.
        settings: Expected JWT issuer, audience, and signing secret.

    Returns:
        Verified user, session, and token identifiers.

    Raises:
        AccessTokenError: If signature, claims, expiry, issuer, audience, or type is invalid.
    """
    try:
        claims = jwt.decode(
            token,
            settings.jwt_signing_secret.get_secret_value(),
            algorithms=["HS256"],
            issuer=settings.jwt_issuer,
            audience=settings.jwt_audience,
            options={
                "require": ["sub", "sid", "iss", "aud", "iat", "exp", "jti", "type"]
            },
        )
        if claims["type"] != "access":
            raise AccessTokenError("invalid token type")
        return AuthPrincipal(
            user_id=uuid.UUID(claims["sub"]),
            session_id=uuid.UUID(claims["sid"]),
            token_id=uuid.UUID(claims["jti"]),
        )
    except (InvalidTokenError, KeyError, TypeError, ValueError) as exc:
        if isinstance(exc, AccessTokenError):
            raise
        raise AccessTokenError("invalid access token") from exc


def create_opaque_token(selector: uuid.UUID | None = None) -> OpaqueToken:
    """Create a fixed-width selector Token with 256 bits of secret entropy.

    Args:
        selector: Optional database row identifier; generated when omitted.

    Returns:
        Opaque Token whose encoded form is exactly 80 characters.
    """
    secret = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode("ascii")
    return OpaqueToken(selector=selector or uuid.uuid4(), secret=secret)


def parse_opaque_token(value: str) -> OpaqueToken:
    """Parse and validate a Refresh Token or login ticket.

    Args:
        value: Fixed-width ``UUID.base64url-secret`` credential.

    Returns:
        Parsed selector and secret.

    Raises:
        OpaqueTokenError: If the credential does not match the public wire format.
    """
    match = OPAQUE_TOKEN_PATTERN.fullmatch(value)
    if not match:
        raise OpaqueTokenError("invalid opaque token")
    return OpaqueToken(
        selector=uuid.UUID(match.group("selector")),
        secret=match.group("secret"),
    )
