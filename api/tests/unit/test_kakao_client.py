"""Kakao client 오류 mapping과 재시도 경계 테스트."""

import httpx2
import pytest

from app.clients.kakao import KakaoClient, KakaoClientError
from app.core.config import Settings


def settings() -> Settings:
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
    )


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("status", "code", "retryable"),
    [(400, "INVALID_AUTHORIZATION_CODE", False), (429, "KAKAO_RATE_LIMITED", True), (500, "KAKAO_API_FAILED", True)],
)
async def test_exchange_maps_provider_status(status: int, code: str, retryable: bool) -> None:
    async def handler(_: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(status, json={"error": "provider detail"})

    client = KakaoClient(settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler)))
    with pytest.raises(KakaoClientError) as captured:
        await client.exchange_code("one-time-code")

    assert captured.value.code == code
    assert captured.value.retryable is retryable


@pytest.mark.asyncio
async def test_profile_maps_nullable_fields() -> None:
    async def handler(request: httpx2.Request) -> httpx2.Response:
        assert request.headers["Authorization"] == "Bearer kakao-token"
        return httpx2.Response(200, json={"id": 42, "kakao_account": {}})

    client = KakaoClient(settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler)))
    assert await client.get_user_profile("kakao-token") == {
        "id": "42",
        "nickname": None,
        "profile_image_url": None,
    }


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("provider_url", "expected"),
    [
        ("http://k.kakaocdn.net/dn/abc/img_640x640.jpg", "https://k.kakaocdn.net/dn/abc/img_640x640.jpg"),
        ("https://k.kakaocdn.net/dn/abc/img_640x640.jpg", "https://k.kakaocdn.net/dn/abc/img_640x640.jpg"),
    ],
)
async def test_profile_normalizes_http_image_url(provider_url: str, expected: str) -> None:
    """Kakao가 http로 주는 profile image URL을 계약대로 https로 맞춘다."""

    async def handler(_: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(
            200,
            json={"id": 42, "kakao_account": {"profile": {"profile_image_url": provider_url}}},
        )

    client = KakaoClient(settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler)))

    assert (await client.get_user_profile("kakao-token"))["profile_image_url"] == expected
