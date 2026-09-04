"""TourAPI client 요청·응답·오류 경계 테스트."""

import json
from pathlib import Path

import httpx2
import pytest

from app.clients.tour_api import TourApiClient, TourApiClientError
from app.core.config import Settings

FIXTURES = Path(__file__).parents[1] / "fixtures" / "tour_api"


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
        tour_api_service_key="encoded%2Btour%3D",
        google_places_api_key="google-key",
    )


def fixture(name: str) -> dict:
    """이름에 해당하는 고정 provider 응답을 읽는다."""
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


@pytest.mark.asyncio
async def test_search_adds_common_params_and_decodes_service_key() -> None:
    async def handler(request: httpx2.Request) -> httpx2.Response:
        assert request.url.path.endswith("/searchKeyword2")
        assert request.url.params["serviceKey"] == "encoded+tour="
        assert request.url.params["MobileOS"] == "ETC"
        assert request.url.params["MobileApp"] == "Gilpick"
        assert request.url.params["_type"] == "json"
        return httpx2.Response(200, json=fixture("search_success.json"))

    client = TourApiClient(
        settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler))
    )

    result = await client.search_keyword(keyword="서울", pageNo=1, numOfRows=20)

    assert result["response"]["body"]["items"]["item"][0]["contentid"] == "1000001"


@pytest.mark.asyncio
async def test_empty_response_is_returned_without_fabricating_items() -> None:
    async def handler(_: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(200, json=fixture("empty.json"))

    client = TourApiClient(
        settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler))
    )

    result = await client.search_by_area(pageNo=1, numOfRows=20)

    assert result["response"]["body"]["totalCount"] == 0
    assert result["response"]["body"]["items"] == ""


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("status", "code", "retryable"),
    [
        (400, "TOUR_API_FAILED", False),
        (429, "TOUR_API_RATE_LIMITED", False),
        (500, "TOUR_API_FAILED", True),
    ],
)
async def test_http_error_is_classified(
    status: int, code: str, retryable: bool
) -> None:
    async def handler(_: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(status, json={"provider": "detail"})

    client = TourApiClient(
        settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler))
    )

    with pytest.raises(TourApiClientError) as captured:
        await client.search_keyword(keyword="서울")

    assert captured.value.code == code
    assert captured.value.retryable is retryable


@pytest.mark.asyncio
async def test_application_error_is_classified_without_exposing_body() -> None:
    calls = 0

    async def handler(_: httpx2.Request) -> httpx2.Response:
        nonlocal calls
        calls += 1
        return httpx2.Response(200, json=fixture("service_key_error.json"))

    client = TourApiClient(
        settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler))
    )

    with pytest.raises(TourApiClientError) as captured:
        await client.search_keyword(keyword="서울")

    assert captured.value.code == "TOUR_API_FAILED"
    assert str(captured.value) == "TOUR_API_FAILED"
    assert calls == 1


@pytest.mark.asyncio
async def test_invalid_json_is_failed() -> None:
    calls = 0

    async def handler(_: httpx2.Request) -> httpx2.Response:
        nonlocal calls
        calls += 1
        return httpx2.Response(200, text="not-json")

    client = TourApiClient(
        settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler))
    )

    with pytest.raises(TourApiClientError, match="TOUR_API_FAILED"):
        await client.get_common_detail("1000001")

    assert calls == 1


@pytest.mark.asyncio
async def test_timeout_is_retryable() -> None:
    calls = 0

    async def handler(request: httpx2.Request) -> httpx2.Response:
        nonlocal calls
        calls += 1
        raise httpx2.ReadTimeout("timeout", request=request)

    client = TourApiClient(
        settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler))
    )

    with pytest.raises(TourApiClientError) as captured:
        await client.search_keyword(keyword="서울")

    assert captured.value.code == "TOUR_API_TIMEOUT"
    assert captured.value.retryable is True
    assert calls == 2


@pytest.mark.asyncio
async def test_transient_server_error_is_retried_once() -> None:
    calls = 0

    async def handler(_: httpx2.Request) -> httpx2.Response:
        nonlocal calls
        calls += 1
        if calls == 1:
            return httpx2.Response(503, json={})
        return httpx2.Response(200, json=fixture("search_success.json"))

    client = TourApiClient(
        settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler))
    )

    result = await client.search_keyword(keyword="서울")

    assert result["response"]["header"]["resultCode"] == "0000"
    assert calls == 2


@pytest.mark.asyncio
@pytest.mark.parametrize("status", [400, 429])
async def test_permanent_http_error_is_not_retried(status: int) -> None:
    calls = 0

    async def handler(_: httpx2.Request) -> httpx2.Response:
        nonlocal calls
        calls += 1
        return httpx2.Response(status, json={})

    client = TourApiClient(
        settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler))
    )

    with pytest.raises(TourApiClientError):
        await client.search_keyword(keyword="서울")

    assert calls == 1


@pytest.mark.asyncio
async def test_application_quota_error_is_rate_limited() -> None:
    calls = 0

    async def handler(_: httpx2.Request) -> httpx2.Response:
        nonlocal calls
        calls += 1
        return httpx2.Response(
            200,
            json={
                "OpenAPI_ServiceResponse": {
                    "cmmMsgHeader": {"errMsg": "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR"}
                }
            },
        )

    client = TourApiClient(
        settings(), httpx2.AsyncClient(transport=httpx2.MockTransport(handler))
    )

    with pytest.raises(TourApiClientError) as captured:
        await client.search_keyword(keyword="서울")

    assert captured.value.code == "TOUR_API_RATE_LIMITED"
    assert captured.value.retryable is False
    assert calls == 1
