"""F001 authentication HTTP contract tests."""

from urllib.parse import parse_qs, urlparse

import pytest
from fastapi.testclient import TestClient

from app.api.v1.auth import _service
from app.core.config import get_settings
from app.main import app
from app.services.auth import AuthServiceError, RefreshResult


US1_RESPONSES = {
    "/api/v1/auth/kakao/transactions": {"201", "400", "500"},
    "/api/v1/auth/kakao/callback": {"302", "400"},
    "/api/v1/auth/kakao/exchange": {"200", "201", "401", "403", "500"},
}

SESSION_RESPONSES = {
    "/api/v1/auth/token/refresh": {"200", "401", "403", "500"},
    "/api/v1/auth/logout": {"204", "401", "403", "500"},
}


@pytest.fixture(scope="module")
def openapi() -> dict:
    return app.openapi()


def _operation(openapi: dict, path: str, method: str) -> dict:
    return openapi["paths"][path][method]


def _header(response: dict, name: str) -> dict:
    return response["headers"][name]["schema"]


@pytest.mark.parametrize(("path", "statuses"), US1_RESPONSES.items())
def test_us1_endpoints_declare_expected_statuses(
    openapi: dict, path: str, statuses: set[str]
) -> None:
    method = "get" if path.endswith("/callback") else "post"

    assert set(_operation(openapi, path, method)["responses"]) == statuses


@pytest.mark.parametrize(
    ("path", "status"),
    [
        ("/api/v1/auth/kakao/transactions", "201"),
        ("/api/v1/auth/kakao/exchange", "200"),
        ("/api/v1/auth/kakao/exchange", "201"),
    ],
)
def test_json_success_contract_has_request_id_and_no_cache_headers(
    openapi: dict, path: str, status: str
) -> None:
    response = _operation(openapi, path, "post")["responses"][status]
    schema = response["content"]["application/json"]["schema"]
    components = openapi["components"]["schemas"]

    envelope = components[schema["$ref"].rsplit("/", 1)[-1]]
    meta_ref = envelope["properties"]["meta"]["$ref"]
    meta = components[meta_ref.rsplit("/", 1)[-1]]

    assert {"success", "data", "meta"} <= set(envelope["required"])
    assert "requestId" in meta["required"]
    assert _header(response, "Cache-Control")["const"] == "no-store"
    assert _header(response, "Pragma")["const"] == "no-cache"


@pytest.mark.parametrize(
    ("path", "method", "status"),
    [
        ("/api/v1/auth/kakao/transactions", "post", "400"),
        ("/api/v1/auth/kakao/transactions", "post", "500"),
        ("/api/v1/auth/kakao/exchange", "post", "401"),
        ("/api/v1/auth/kakao/exchange", "post", "403"),
        ("/api/v1/auth/kakao/exchange", "post", "500"),
    ],
)
def test_json_error_contract_has_request_id(
    openapi: dict, path: str, method: str, status: str
) -> None:
    response = _operation(openapi, path, method)["responses"][status]
    schema = response["content"]["application/json"]["schema"]
    components = openapi["components"]["schemas"]

    envelope = components[schema["$ref"].rsplit("/", 1)[-1]]
    meta_ref = envelope["properties"]["meta"]["$ref"]
    meta = components[meta_ref.rsplit("/", 1)[-1]]

    assert {"success", "error", "meta"} <= set(envelope["required"])
    assert "requestId" in meta["required"]


@pytest.mark.parametrize(("path", "statuses"), SESSION_RESPONSES.items())
def test_session_endpoints_declare_expected_statuses(
    openapi: dict, path: str, statuses: set[str]
) -> None:
    assert set(_operation(openapi, path, "post")["responses"]) == statuses


def test_refresh_success_contract_has_sliding_expiry_and_no_cache_headers(
    openapi: dict,
) -> None:
    response = _operation(openapi, "/api/v1/auth/token/refresh", "post")[
        "responses"
    ]["200"]
    schema = response["content"]["application/json"]["schema"]
    components = openapi["components"]["schemas"]
    envelope = components[schema["$ref"].rsplit("/", 1)[-1]]
    data = components[envelope["properties"]["data"]["$ref"].rsplit("/", 1)[-1]]

    assert data["properties"]["refreshExpiresIn"]["const"] == 2_592_000
    assert "requestId" in components[
        envelope["properties"]["meta"]["$ref"].rsplit("/", 1)[-1]
    ]["required"]
    assert _header(response, "Cache-Control")["const"] == "no-store"
    assert _header(response, "Pragma")["const"] == "no-cache"


@pytest.mark.parametrize(
    ("path", "status"),
    [
        ("/api/v1/auth/token/refresh", "401"),
        ("/api/v1/auth/token/refresh", "403"),
        ("/api/v1/auth/token/refresh", "500"),
        ("/api/v1/auth/logout", "401"),
        ("/api/v1/auth/logout", "403"),
        ("/api/v1/auth/logout", "500"),
    ],
)
def test_session_error_contract_has_request_id(
    openapi: dict, path: str, status: str
) -> None:
    response = _operation(openapi, path, "post")["responses"][status]
    schema = response["content"]["application/json"]["schema"]
    components = openapi["components"]["schemas"]
    envelope = components[schema["$ref"].rsplit("/", 1)[-1]]
    meta = components[envelope["properties"]["meta"]["$ref"].rsplit("/", 1)[-1]]

    assert "requestId" in meta["required"]


def test_logout_contract_requires_no_bearer_security(openapi: dict) -> None:
    operation = _operation(openapi, "/api/v1/auth/logout", "post")

    assert operation.get("security", []) == []
    assert "requestBody" in operation


def test_refresh_endpoint_returns_rotated_pair_and_request_id(monkeypatch) -> None:
    token = "00000000-0000-4000-8000-000000000000." + "A" * 43

    async def rotate(*_args, **_kwargs) -> RefreshResult:
        return RefreshResult(access_token="access", refresh_token=token)

    monkeypatch.setattr("app.api.v1.auth.rotate_refresh_token", rotate)
    response = TestClient(app).post(
        "/api/v1/auth/token/refresh",
        json={
            "refreshToken": token,
            "deviceId": "00000000-0000-4000-8000-000000000001",
        },
    )

    assert response.status_code == 200
    assert response.json()["data"]["refreshExpiresIn"] == 2_592_000
    assert response.json()["meta"]["requestId"] == response.headers["X-Request-ID"]
    assert response.headers["Cache-Control"] == "no-store"
    assert response.headers["Pragma"] == "no-cache"


@pytest.mark.parametrize(
    ("code", "status"),
    [("INVALID_REFRESH_TOKEN", 401), ("TOKEN_EXPIRED", 401), ("DEVICE_MISMATCH", 403)],
)
def test_refresh_endpoint_maps_auth_errors(monkeypatch, code: str, status: int) -> None:
    async def reject(*_args, **_kwargs) -> None:
        raise AuthServiceError(code, status_code=status)

    monkeypatch.setattr("app.api.v1.auth.rotate_refresh_token", reject)
    token = "00000000-0000-4000-8000-000000000000." + "A" * 43
    response = TestClient(app).post(
        "/api/v1/auth/token/refresh",
        json={
            "refreshToken": token,
            "deviceId": "00000000-0000-4000-8000-000000000001",
        },
    )

    assert response.status_code == status
    assert response.json()["error"]["code"] == code
    assert response.json()["meta"]["requestId"] == response.headers["X-Request-ID"]
    assert response.json()["meta"]["requestId"] == response.headers["X-Request-ID"]


def test_logout_endpoint_is_bearer_free_and_returns_204(monkeypatch) -> None:
    async def revoke(*_args, **_kwargs) -> None:
        return None

    monkeypatch.setattr("app.api.v1.auth.logout_device_session", revoke)
    token = "00000000-0000-4000-8000-000000000000." + "A" * 43
    response = TestClient(app).post(
        "/api/v1/auth/logout",
        json={
            "refreshToken": token,
            "deviceId": "00000000-0000-4000-8000-000000000001",
        },
    )

    assert response.status_code == 204
    assert not response.content


@pytest.mark.parametrize(
    ("code", "status"),
    [("INVALID_REFRESH_TOKEN", 401), ("DEVICE_MISMATCH", 403)],
)
def test_logout_endpoint_maps_auth_errors(monkeypatch, code: str, status: int) -> None:
    async def reject(*_args, **_kwargs) -> None:
        raise AuthServiceError(code, status_code=status)

    monkeypatch.setattr("app.api.v1.auth.logout_device_session", reject)
    token = "00000000-0000-4000-8000-000000000000." + "A" * 43
    response = TestClient(app).post(
        "/api/v1/auth/logout",
        json={
            "refreshToken": token,
            "deviceId": "00000000-0000-4000-8000-000000000001",
        },
    )

    assert response.status_code == status
    assert response.json()["error"]["code"] == code


def test_callback_redirect_uses_fragment_and_security_headers(openapi: dict) -> None:
    response = _operation(
        openapi, "/api/v1/auth/kakao/callback", "get"
    )["responses"]["302"]
    examples = response["headers"]["Location"]["examples"]
    success = urlparse(examples["success"]["value"])
    failure = urlparse(examples["failure"]["value"])

    assert success.scheme == "https"
    assert set(parse_qs(success.fragment)) == {"loginTicket"}
    assert "loginTicket" not in parse_qs(success.query)
    assert not failure.fragment
    assert set(parse_qs(failure.query)) == {"error"}
    assert _header(response, "Cache-Control")["const"] == "no-store"
    assert _header(response, "Referrer-Policy")["const"] == "no-referrer"


def test_invalid_callback_contract_is_plain_text_and_not_referrable(openapi: dict) -> None:
    response = _operation(
        openapi, "/api/v1/auth/kakao/callback", "get"
    )["responses"]["400"]

    assert response["content"]["text/plain"]["schema"]["const"] == "Invalid callback"
    assert _header(response, "Cache-Control")["const"] == "no-store"
    assert _header(response, "Referrer-Policy")["const"] == "no-referrer"


class _CallbackService:
    def __init__(self, *, reject_state: bool = False) -> None:
        self.reject_state = reject_state
        self.failures: list[tuple[str, str]] = []

    async def fail_kakao_callback(self, *, state: str, error_code: str) -> None:
        if self.reject_state:
            raise AuthServiceError("LOGIN_TRANSACTION_EXPIRED", status_code=400)
        self.failures.append((state, error_code))


def test_callback_missing_or_unknown_state_is_plain_text_400() -> None:
    service = _CallbackService(reject_state=True)
    app.dependency_overrides[_service] = lambda: service
    try:
        client = TestClient(app)
        for query in ({"error": "access_denied"}, {"state": "unknown", "error": "access_denied"}):
            response = client.get("/api/v1/auth/kakao/callback", params=query, follow_redirects=False)
            assert response.status_code == 400
            assert response.text == "Invalid callback"
            assert response.headers["Cache-Control"] == "no-store"
            assert response.headers["Referrer-Policy"] == "no-referrer"
    finally:
        app.dependency_overrides.clear()


def test_provider_failure_claims_state_before_app_link_redirect() -> None:
    service = _CallbackService()
    app.dependency_overrides[_service] = lambda: service
    try:
        response = TestClient(app).get(
            "/api/v1/auth/kakao/callback",
            params={"state": "trusted", "error": "access_denied"},
            follow_redirects=False,
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 302
    assert service.failures == [("trusted", "ACCESS_DENIED")]
    assert response.headers["location"].endswith("?error=ACCESS_DENIED")
