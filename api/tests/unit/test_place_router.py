"""장소 router의 F001 인증 경계 검증."""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta

import jwt
from fastapi import APIRouter, FastAPI
from fastapi.testclient import TestClient

from app.api.dependencies import get_current_principal
from app.api.errors import install_error_handling
from app.api.v1.places import router
from app.core.config import Settings, get_settings


def settings() -> Settings:
    """인증 경계 test에 사용할 격리 설정을 만든다."""
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


def protected_probe_client() -> TestClient:
    """장소 router와 같은 dependency를 적용한 검증용 endpoint를 만든다."""
    app = FastAPI()
    install_error_handling(app)
    app.dependency_overrides[get_settings] = settings
    probe = APIRouter(dependencies=router.dependencies)
    probe.add_api_route("/places-probe", lambda: {"ok": True}, methods=["GET"])
    app.include_router(probe)
    return TestClient(app)


def expired_access_token(config: Settings) -> str:
    """서명은 유효하지만 만료된 F001 Access Token을 만든다."""
    now = datetime.now(UTC)
    return jwt.encode(
        {
            "sub": str(uuid.uuid4()),
            "sid": str(uuid.uuid4()),
            "iss": config.jwt_issuer,
            "aud": config.jwt_audience,
            "iat": now - timedelta(hours=2),
            "exp": now - timedelta(hours=1),
            "jti": str(uuid.uuid4()),
            "type": "access",
        },
        config.jwt_signing_secret.get_secret_value(),
        algorithm="HS256",
    )


def test_place_router_uses_f001_authentication_dependency() -> None:
    """향후 모든 장소 endpoint에 F001 인증이 공통 적용된다."""
    assert any(
        dependency.dependency is get_current_principal
        for dependency in router.dependencies
    )


def test_place_boundary_rejects_missing_and_expired_tokens() -> None:
    """누락·만료 token을 공통 401 envelope로 거부한다."""
    client = protected_probe_client()
    expired = expired_access_token(settings())

    for headers in ({}, {"Authorization": f"Bearer {expired}"}):
        response = client.get("/places-probe", headers=headers)

        assert response.status_code == 401
        assert response.json()["error"] == {
            "code": "INVALID_ACCESS_TOKEN",
            "message": "요청을 처리할 수 없습니다.",
            "details": {},
            "retryable": False,
        }
        assert response.headers["WWW-Authenticate"] == "Bearer"
