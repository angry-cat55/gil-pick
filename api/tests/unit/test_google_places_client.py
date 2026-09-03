"""Google Places client 요청·응답·오류 경계 테스트."""

import json
from pathlib import Path

import httpx2
import pytest

from app.clients.google_places import (
    DETAIL_FIELD_MASK,
    SEARCH_FIELD_MASK,
    GooglePlacesClient,
    GooglePlacesClientError,
)
from app.core.config import Settings

FIXTURES = Path(__file__).parents[1] / "fixtures" / "google_places"


def settings() -> Settings:
    """외부 환경에 의존하지 않는 테스트 설정을 반환한다."""
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
    )


def fixture(name: str) -> dict:
    """이름에 해당하는 고정 provider 응답을 읽는다."""
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


@pytest.mark.asyncio
async def test_text_search_uses_minimum_field_mask() -> None:
    async def handler(request: httpx2.Request) -> httpx2.Response:
        assert request.url.path.endswith("/places:searchText")
        assert request.headers["X-Goog-Api-Key"] == "google-key"
        assert request.headers["X-Goog-FieldMask"] == SEARCH_FIELD_MASK
        assert "photos" not in SEARCH_FIELD_MASK
        assert "reviews" not in SEARCH_FIELD_MASK
        return httpx2.Response(200, json=fixture("text_search_success.json"))

    client = GooglePlacesClient(
        settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler))
    )

    result = await client.search_text("서울 카페", pageSize=20)

    assert result["places"][0]["id"] == "test-google-place-id"


@pytest.mark.asyncio
async def test_details_and_empty_response_are_preserved() -> None:
    responses = [fixture("place_details_success.json"), fixture("empty.json")]

    async def handler(request: httpx2.Request) -> httpx2.Response:
        if request.method == "GET":
            assert request.headers["X-Goog-FieldMask"] == DETAIL_FIELD_MASK
        return httpx2.Response(200, json=responses.pop(0))

    client = GooglePlacesClient(
        settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler))
    )

    detail = await client.get_place("test-google-place-id")
    empty = await client.search_text("결과 없음")

    assert detail["nationalPhoneNumber"] == "02-0000-0000"
    assert empty == {}


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("status", "code", "retryable"),
    [
        (400, "GOOGLE_PLACES_FAILED", False),
        (429, "GOOGLE_PLACES_RATE_LIMITED", False),
        (500, "GOOGLE_PLACES_FAILED", True),
    ],
)
async def test_http_error_is_classified(
    status: int, code: str, retryable: bool
) -> None:
    async def handler(_: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(status, json={"error": {"message": "provider detail"}})

    client = GooglePlacesClient(
        settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler))
    )

    with pytest.raises(GooglePlacesClientError) as captured:
        await client.search_text("서울 카페")

    assert captured.value.code == code
    assert captured.value.retryable is retryable
    assert "provider detail" not in str(captured.value)


@pytest.mark.asyncio
async def test_invalid_json_is_failed() -> None:
    async def handler(_: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(200, text="not-json")

    client = GooglePlacesClient(
        settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler))
    )

    with pytest.raises(GooglePlacesClientError, match="GOOGLE_PLACES_FAILED"):
        await client.get_place("test-google-place-id")


@pytest.mark.asyncio
async def test_timeout_is_retryable() -> None:
    async def handler(request: httpx2.Request) -> httpx2.Response:
        raise httpx2.ReadTimeout("timeout", request=request)

    client = GooglePlacesClient(
        settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler))
    )

    with pytest.raises(GooglePlacesClientError) as captured:
        await client.search_text("서울 카페")

    assert captured.value.code == "GOOGLE_PLACES_TIMEOUT"
    assert captured.value.retryable is True
