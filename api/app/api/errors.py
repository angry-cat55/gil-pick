"""Common API errors and request ID response handling."""

from __future__ import annotations

import logging
import uuid
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.responses import Response

from app.core.logging import request_id_context
from app.schemas.auth import ErrorBody, ErrorEnvelope, ResponseMeta, SuccessEnvelope

REQUEST_ID_HEADER = "X-Request-ID"
logger = logging.getLogger("gilpick.api")


class AppError(Exception):
    """Stable application error mapped to the common JSON envelope."""

    def __init__(
        self,
        status_code: int,
        code: str,
        message: str,
        *,
        retryable: bool = False,
        details: dict[str, Any] | None = None,
        headers: dict[str, str] | None = None,
    ) -> None:
        """Store public error fields without sensitive request data."""
        super().__init__(code)
        self.status_code = status_code
        self.code = code
        self.message = message
        self.retryable = retryable
        self.details = details or {}
        self.headers = headers or {}


def get_request_id(request: Request) -> uuid.UUID:
    """Return the request ID assigned by middleware."""
    return request.state.request_id


class RequestIdMiddleware(BaseHTTPMiddleware):
    """Assign one UUID to response metadata, headers, and application logs."""

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        """Correlate a request and clear its logging context after completion."""
        try:
            request_id = uuid.UUID(request.headers.get(REQUEST_ID_HEADER, ""))
        except ValueError:
            request_id = uuid.uuid4()
        request.state.request_id = request_id
        token = request_id_context.set(str(request_id))
        try:
            try:
                response = await call_next(request)
            except Exception as exc:
                logger.error(
                    {
                        "request_id": str(request_id),
                        "operation": "HTTP_REQUEST",
                        "result": "FAILED",
                        "error_code": "INTERNAL_ERROR",
                        "exception_type": type(exc).__name__,
                    }
                )
                response = error_response(
                    request,
                    AppError(
                        500,
                        "INTERNAL_ERROR",
                        "서버 오류가 발생했습니다.",
                        retryable=True,
                    ),
                )
        finally:
            request_id_context.reset(token)
        response.headers[REQUEST_ID_HEADER] = str(request_id)
        return response


def error_response(request: Request, error: AppError) -> JSONResponse:
    """Serialize an application error without leaking internal exception data."""
    envelope = ErrorEnvelope(
        success=False,
        error=ErrorBody(
            code=error.code,
            message=error.message,
            details=error.details,
            retryable=error.retryable,
        ),
        meta=ResponseMeta(request_id=get_request_id(request)),
    )
    return JSONResponse(
        status_code=error.status_code,
        content=envelope.model_dump(mode="json", by_alias=True),
        headers=error.headers,
    )


def success_response(request: Request, data: Any, *, status_code: int = 200) -> JSONResponse:
    """Serialize a successful payload with the correlated request ID."""
    envelope = SuccessEnvelope(
        success=True,
        data=data,
        meta=ResponseMeta(request_id=get_request_id(request)),
    )
    return JSONResponse(
        status_code=status_code,
        content=envelope.model_dump(mode="json", by_alias=True),
    )


def install_error_handling(app: FastAPI) -> None:
    """Install request ID middleware and common exception mappings."""
    app.add_middleware(RequestIdMiddleware)

    @app.exception_handler(AppError)
    async def handle_app_error(request: Request, exc: AppError) -> JSONResponse:
        return error_response(request, exc)

    @app.exception_handler(RequestValidationError)
    async def handle_validation_error(request: Request, _: RequestValidationError) -> JSONResponse:
        return error_response(
            request,
            AppError(400, "INVALID_REQUEST", "요청 형식이 올바르지 않습니다."),
        )

    @app.exception_handler(StarletteHTTPException)
    async def handle_http_error(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        code = (
            exc.detail
            if isinstance(exc.detail, str) and exc.detail.isupper()
            else "INVALID_REQUEST"
        )
        return error_response(
            request,
            AppError(exc.status_code, code, "요청을 처리할 수 없습니다.", headers=exc.headers),
        )

    @app.exception_handler(Exception)
    async def handle_unexpected_error(request: Request, _: Exception) -> JSONResponse:
        return error_response(
            request,
            AppError(500, "INTERNAL_ERROR", "서버 오류가 발생했습니다.", retryable=True),
        )
