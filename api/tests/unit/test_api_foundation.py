"""Common response envelope, request ID, and log redaction tests."""

import logging
import uuid

import pytest
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient
from pydantic import ValidationError
from uvicorn.logging import AccessFormatter

from app.api.errors import AppError, install_error_handling, success_response
from app.core.logging import (
    SensitiveDataFilter,
    configure_logging,
    log_auth_event,
    request_id_context,
)
from app.core.security import create_opaque_token
from app.schemas.auth import (
    CreateLoginTransactionRequest,
    LoginTicketExchangeRequest,
    LoginTransactionData,
    UserSummary,
)


def test_success_and_error_responses_include_correlated_request_id() -> None:
    app = FastAPI()
    install_error_handling(app)

    @app.get("/success")
    def success(request: Request):
        return success_response(request, {"operation": "TEST"})

    @app.get("/error")
    def error() -> None:
        raise AppError(400, "TEST_ERROR", "테스트 오류")

    client = TestClient(app)
    request_id = str(uuid.uuid4())
    success_result = client.get("/success", headers={"X-Request-ID": request_id})
    error_result = client.get("/error", headers={"X-Request-ID": request_id})

    assert success_result.headers["X-Request-ID"] == request_id
    assert success_result.json()["meta"]["requestId"] == request_id
    assert error_result.headers["X-Request-ID"] == request_id
    assert error_result.json()["meta"]["requestId"] == request_id
    assert error_result.json()["error"]["code"] == "TEST_ERROR"


def test_auth_event_links_request_operation_result_and_transaction(caplog) -> None:
    logger = logging.getLogger("test.auth.event")
    request_id = str(uuid.uuid4())
    transaction_id = str(uuid.uuid4())
    token = request_id_context.set(request_id)
    try:
        with caplog.at_level(logging.INFO, logger=logger.name):
            log_auth_event(
                logger,
                operation="KAKAO_CALLBACK",
                result="VERIFIED",
                transaction_id=transaction_id,
            )
    finally:
        request_id_context.reset(token)

    message = caplog.records[-1].getMessage()
    assert request_id in message
    assert "KAKAO_CALLBACK" in message
    assert "VERIFIED" in message
    assert transaction_id in message


def test_sensitive_log_values_are_redacted() -> None:
    logger = logging.getLogger("test.auth.redaction")
    logger.setLevel(logging.INFO)
    record = logging.LogRecord(
        logger.name,
        logging.INFO,
        __file__,
        1,
        {
            "refresh_token": create_opaque_token().encoded,
            "code": "provider-code",
            "state": "raw-state",
            "profile": {"nickname": "private-name"},
        },
        (),
        None,
    )

    SensitiveDataFilter().filter(record)
    message = record.getMessage()

    assert "provider-code" not in message
    assert "raw-state" not in message
    assert "private-name" not in message
    assert "[REDACTED]" in message


def test_process_logging_factory_redacts_child_logger(caplog) -> None:
    configure_logging()
    logger = logging.getLogger("gilpick.test.child")
    raw_ticket = create_opaque_token().encoded

    with caplog.at_level(logging.INFO, logger=logger.name):
        logger.info("login_ticket=%s", raw_ticket)

    assert raw_ticket not in caplog.records[-1].getMessage()
    assert "[REDACTED]" in caplog.records[-1].getMessage()


def test_process_logging_factory_redacts_plain_interpolation_value(caplog) -> None:
    configure_logging()
    logger = logging.getLogger("gilpick.test.plain-secret")

    with caplog.at_level(logging.INFO, logger=logger.name):
        logger.info("api_key=%s", "plain-secret-value")

    assert "plain-secret-value" not in caplog.records[-1].getMessage()
    assert "api_key=[REDACTED]" in caplog.records[-1].getMessage()


def test_sensitive_filter_preserves_uvicorn_access_log_arguments() -> None:
    raw_token = "eyJheader.payload.signature"
    record = logging.LogRecord(
        "uvicorn.access",
        logging.INFO,
        __file__,
        1,
        '%s - "%s %s HTTP/%s" %d',
        (
            "127.0.0.1:54321",
            "GET",
            f"/api/v1/trips?code=%2Fprovider-secret&token={raw_token}",
            "1.1",
            200,
        ),
        None,
    )
    formatter = AccessFormatter(
        '%(client_addr)s - "%(request_line)s" %(status_code)s'
    )

    SensitiveDataFilter().filter(record)
    message = formatter.format(record)

    assert len(record.args) == 5
    assert "GET /api/v1/trips?code=[REDACTED]&token=[REDACTED] HTTP/1.1" in message
    assert raw_token not in message
    assert "%2Fprovider-secret" not in message
    assert "200 OK" in message


def test_unexpected_error_keeps_request_id_in_envelope_header_and_log(caplog) -> None:
    app = FastAPI()
    install_error_handling(app)

    @app.get("/explode")
    def explode() -> None:
        raise RuntimeError("internal detail")

    request_id = str(uuid.uuid4())
    with caplog.at_level(logging.ERROR, logger="gilpick.api"):
        response = TestClient(app).get("/explode", headers={"X-Request-ID": request_id})

    assert response.status_code == 500
    assert response.headers["X-Request-ID"] == request_id
    assert response.json()["meta"]["requestId"] == request_id
    assert response.json()["error"]["code"] == "INTERNAL_ERROR"
    assert "internal detail" not in response.text
    assert request_id in caplog.records[-1].getMessage()
    assert "INTERNAL_ERROR" in caplog.records[-1].getMessage()


@pytest.mark.parametrize(
    "payload",
    [
        {"loginTicket": "invalid", "deviceId": str(uuid.uuid4())},
        {"loginTicket": create_opaque_token().encoded, "deviceId": "not-a-uuid"},
    ],
)
def test_auth_request_schema_rejects_invalid_credentials(payload: dict[str, str]) -> None:
    with pytest.raises(ValidationError):
        LoginTicketExchangeRequest.model_validate(payload)


def test_request_schema_rejects_snake_case_contract_fields() -> None:
    with pytest.raises(ValidationError):
        CreateLoginTransactionRequest.model_validate(
            {"device_id": str(uuid.uuid4()), "platform": "ANDROID"}
        )


def test_logging_redacts_secret_keys_and_extra_fields(caplog) -> None:
    configure_logging()
    logger = logging.getLogger("gilpick.test.secrets")

    with caplog.at_level(logging.INFO, logger=logger.name):
        logger.info(
            "kakao_client_secret=super-secret",
            extra={"refresh_token": "raw-refresh", "api_key": "raw-api-key"},
        )

    record = caplog.records[-1]
    assert "super-secret" not in record.getMessage()
    assert record.refresh_token == "[REDACTED]"
    assert record.api_key == "[REDACTED]"


def test_logging_redacts_place_provider_query_key_and_response(caplog) -> None:
    configure_logging()
    logger = logging.getLogger("gilpick.test.place-provider")

    with caplog.at_level(logging.INFO, logger=logger.name):
        logger.info(
            "url=https://provider.example/search?serviceKey=raw-tour-key",
            extra={
                "google_places_api_key": "raw-google-key",
                "provider_response": {"private": "raw-provider-body"},
            },
        )

    record = caplog.records[-1]
    assert "raw-tour-key" not in record.getMessage()
    assert "serviceKey=[REDACTED]" in record.getMessage()
    assert record.google_places_api_key == "[REDACTED]"
    assert record.provider_response == "[REDACTED]"


@pytest.mark.parametrize(
    ("model", "values"),
    [
        (
            LoginTransactionData,
            {
                "transactionId": str(uuid.uuid4()),
                "authorizationUrl": "http://kauth.kakao.example/authorize",
            },
        ),
        (
            UserSummary,
            {
                "userId": str(uuid.uuid4()),
                "profileImageUrl": "http://images.example/profile.png",
                "provider": "KAKAO",
            },
        ),
    ],
)
def test_public_urls_require_https(model, values: dict[str, str]) -> None:
    with pytest.raises(ValidationError):
        model.model_validate(values)
