"""FCM HTTP v1 클라이언트의 요청 형태와 결과 분류 테스트."""

import json
from pathlib import Path

import httpx2
import pytest
from pydantic import SecretStr
from unittest.mock import AsyncMock

import app.clients.fcm as fcm_module
from app.clients.fcm import FcmClient, FcmSendResult
from app.core.config import Settings

FIXTURES = Path(__file__).parents[1] / "fixtures" / "fcm"


def _fixture(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def _settings() -> Settings:
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
        tour_api_service_key="tour-key",
        google_places_api_key="google-key",
        fcm_enabled=True,
        fcm_project_id="gilpick-test",
        fcm_service_account_json="{}",
    )


@pytest.mark.asyncio
async def test_send_posts_data_only_message(monkeypatch: pytest.MonkeyPatch) -> None:
    async def handler(request: httpx2.Request) -> httpx2.Response:
        assert request.url.path == "/v1/projects/gilpick-test/messages:send"
        assert request.headers["Authorization"] == "Bearer test-access-token"
        assert json.loads(request.content) == {
            "message": {
                "token": "device-token",
                "data": {"type": "ARRIVAL_CHECK", "tripId": "trip-1"},
            }
        }
        return httpx2.Response(200, json=_fixture("send_ok.json"))

    client = FcmClient(
        _settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler))
    )
    async def access_token() -> str:
        return "test-access-token"

    monkeypatch.setattr(client, "_get_access_token", access_token)

    result = await client.send(
        "device-token", {"type": "ARRIVAL_CHECK", "tripId": "trip-1"}
    )

    assert result is FcmSendResult.OK


@pytest.mark.asyncio
async def test_send_is_skipped_when_fcm_is_disabled() -> None:
    client = FcmClient(
        _settings().model_copy(update={"fcm_enabled": False}), AsyncMock()
    )

    assert await client.send("device-token", {}) is FcmSendResult.OK
    client.client.post.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("fixture_name", "status", "expected"),
    [
        ("invalid_token.json", 404, FcmSendResult.INVALID_TOKEN),
        ("unavailable.json", 503, FcmSendResult.RETRYABLE),
    ],
)
async def test_send_classifies_provider_error(
    monkeypatch: pytest.MonkeyPatch,
    fixture_name: str,
    status: int,
    expected: FcmSendResult,
) -> None:
    async def handler(_: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(status, json=_fixture(fixture_name))

    client = FcmClient(
        _settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler))
    )
    async def access_token() -> str:
        return "test-access-token"

    monkeypatch.setattr(client, "_get_access_token", access_token)

    assert await client.send("device-token", {"type": "ARRIVAL_CHECK"}) is expected


@pytest.mark.asyncio
async def test_send_classifies_non_retryable_provider_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def handler(_: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(403, json={"error": {"status": "PERMISSION_DENIED"}})

    client = FcmClient(
        _settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler))
    )
    async def access_token() -> str:
        return "test-access-token"

    monkeypatch.setattr(client, "_get_access_token", access_token)

    assert await client.send("device-token", {}) is FcmSendResult.FATAL


@pytest.mark.asyncio
async def test_oauth_access_token_is_exchanged_once_and_cached(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls = 0

    async def handler(request: httpx2.Request) -> httpx2.Response:
        nonlocal calls
        calls += 1
        assert request.url == "https://oauth2.googleapis.com/token"
        assert b"grant_type=" in request.content
        assert b"assertion=signed-jwt" in request.content
        return httpx2.Response(200, json=_fixture("oauth_token.json"))

    settings = _settings().model_copy(
        update={
            "fcm_service_account_json": SecretStr(
                json.dumps(
                    {
                        "client_email": "firebase@gilpick-test.iam.gserviceaccount.com",
                        "private_key": "test-private-key",
                        "token_uri": "https://oauth2.googleapis.com/token",
                    }
                )
            ),
        }
    )
    monkeypatch.setattr(fcm_module.jwt, "encode", lambda *args, **kwargs: "signed-jwt")
    client = FcmClient(
        settings, httpx2.AsyncClient(transport=httpx2.MockTransport(handler))
    )

    assert await client._get_access_token() == "test-access-token"
    assert await client._get_access_token() == "test-access-token"
    assert calls == 1
