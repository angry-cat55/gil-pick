"""Access Token and protected route dependency tests."""

import uuid
from datetime import UTC, datetime, timedelta

import jwt
import pytest
from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient

from app.api.dependencies import get_current_principal
from app.core.config import Settings, get_settings
from app.core.security import (
    ACCESS_TOKEN_TTL,
    AccessTokenError,
    AuthPrincipal,
    create_access_token,
    create_opaque_token,
    decode_access_token,
    parse_opaque_token,
)


def settings() -> Settings:
    """Return isolated valid settings for security tests."""
    return Settings(
        _env_file=None,
        database_url="postgresql+asyncpg://user:password@localhost/gilpick",
        jwt_signing_secret="test-signing-secret-at-least-32-bytes",
        jwt_issuer="https://api.gilpick.example",
        jwt_audience="gilpick-android",
        kakao_rest_api_key="rest-key",
        kakao_client_secret="client-secret",
        kakao_redirect_uri="https://api.gilpick.example/api/v1/auth/kakao/callback",
        android_app_link_base_url="https://app.gilpick.example/auth/kakao/complete",
        android_app_link_host="app.gilpick.example",
        tour_api_service_key="test-tour-api-key",
        google_places_api_key="test-google-places-key",
    )


def test_access_token_has_one_hour_lifetime_and_verified_identity() -> None:
    config = settings()
    now = datetime.now(UTC).replace(microsecond=0)
    user_id, session_id = uuid.uuid4(), uuid.uuid4()
    token = create_access_token(user_id, session_id, config, now=now)

    claims = jwt.decode(
        token,
        config.jwt_signing_secret.get_secret_value(),
        algorithms=["HS256"],
        issuer=config.jwt_issuer,
        audience=config.jwt_audience,
    )
    principal = decode_access_token(token, config)

    assert claims["exp"] - claims["iat"] == int(ACCESS_TOKEN_TTL.total_seconds())
    assert claims["type"] == "access"
    assert principal.user_id == user_id
    assert principal.session_id == session_id


@pytest.mark.parametrize("claim", ["iss", "aud", "type", "exp"])
def test_invalid_access_claim_is_rejected(claim: str) -> None:
    config = settings()
    now = datetime.now(UTC)
    payload = {
        "sub": str(uuid.uuid4()),
        "sid": str(uuid.uuid4()),
        "iss": config.jwt_issuer,
        "aud": config.jwt_audience,
        "iat": now,
        "exp": now + timedelta(hours=1),
        "jti": str(uuid.uuid4()),
        "type": "access",
    }
    replacements = {
        "iss": "https://other.example",
        "aud": "other-client",
        "type": "refresh",
        "exp": now - timedelta(seconds=1),
    }
    payload[claim] = replacements[claim]
    token = jwt.encode(
        payload,
        config.jwt_signing_secret.get_secret_value(),
        algorithm="HS256",
    )

    with pytest.raises(AccessTokenError):
        decode_access_token(token, config)


def test_wrong_signature_is_rejected() -> None:
    token = create_access_token(uuid.uuid4(), uuid.uuid4(), settings())
    values = settings().model_dump(mode="python")
    values["jwt_signing_secret"] = "other-secret-at-least-32-characters"
    other = Settings(_env_file=None, **values)

    with pytest.raises(AccessTokenError):
        decode_access_token(token, other)


def test_opaque_token_is_fixed_width_and_only_hash_is_persistable() -> None:
    token = create_opaque_token()
    parsed = parse_opaque_token(token.encoded)

    assert len(token.encoded) == 80
    assert parsed == token
    assert len(token.secret_hash) == 64
    assert token.secret not in token.secret_hash


def test_protected_handler_is_not_called_for_invalid_token() -> None:
    app = FastAPI()
    app.dependency_overrides[get_settings] = settings
    calls = 0

    @app.get("/protected")
    def protected(_: AuthPrincipal = Depends(get_current_principal)) -> dict[str, bool]:
        nonlocal calls
        calls += 1
        return {"called": True}

    response = TestClient(app).get("/protected", headers={"Authorization": "Bearer invalid"})

    assert response.status_code == 401
    assert calls == 0
