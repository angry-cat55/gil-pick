"""Authentication-safe structured logging helpers."""

from __future__ import annotations

import logging
import re
from contextvars import ContextVar
from typing import Any

request_id_context: ContextVar[str | None] = ContextVar("request_id", default=None)

SENSITIVE_KEYS = {
    "access_token",
    "accesstoken",
    "authorization",
    "api_key",
    "apikey",
    "client_secret",
    "clientsecret",
    "code",
    "kakao_token",
    "kakao_client_secret",
    "kakao_rest_api_key",
    "login_ticket",
    "loginticket",
    "profile",
    "profile_image_url",
    "rest_api_key",
    "refresh_token",
    "refreshtoken",
    "state",
    "secret",
    "ticket",
    "token",
}
OPAQUE_TOKEN_RE = re.compile(
    r"[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\."
    r"[A-Za-z0-9_-]{43}",
    re.IGNORECASE,
)
JWT_RE = re.compile(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b")
KEY_VALUE_RE = re.compile(
    r"(?i)\b(access[_-]?token|api[_-]?key|authorization|client[_-]?secret|code|"
    r"jwt[_-]?signing[_-]?secret|kakao[_-]?(?:client[_-]?secret|rest[_-]?api[_-]?key|token)|"
    r"login[_-]?ticket|profile(?:_image_url)?|refresh[_-]?token|rest[_-]?api[_-]?key|"
    r"secret|state|ticket|token)\b([\s'\"=:]+)([^\s,}&]+)"
)


def _redact(value: Any, key: str | None = None) -> Any:
    """Recursively redact known credential fields and Token wire formats."""
    normalized_key = key.replace("-", "_").lower() if key else None
    if normalized_key in SENSITIVE_KEYS:
        return "[REDACTED]"
    if isinstance(value, dict):
        return {item_key: _redact(item, str(item_key)) for item_key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return type(value)(_redact(item) for item in value)
    if isinstance(value, str):
        value = OPAQUE_TOKEN_RE.sub("[REDACTED]", value)
        value = JWT_RE.sub("[REDACTED]", value)
        return KEY_VALUE_RE.sub(r"\1\2[REDACTED]", value)
    return value


class SensitiveDataFilter(logging.Filter):
    """Remove authentication credentials from structured and free-form log records."""

    def filter(self, record: logging.LogRecord) -> bool:
        """Redact the message and interpolation arguments before handlers format them."""
        if isinstance(record.msg, (dict, list, tuple)) and not record.args:
            record.msg = str(_redact(record.msg))
        else:
            record.msg = _redact(record.getMessage())
        record.args = ()
        for key, value in vars(record).items():
            if key not in {"msg", "args"}:
                setattr(record, key, _redact(value, key))
        return True


def configure_logging() -> None:
    """Install process-wide credential redaction once."""
    current_make_record = logging.Logger.makeRecord
    if getattr(current_make_record, "_gilpick_redacting", False):
        return

    def redacting_make_record(
        logger: logging.Logger, *args: Any, **kwargs: Any
    ) -> logging.LogRecord:
        record = current_make_record(logger, *args, **kwargs)
        SensitiveDataFilter().filter(record)
        return record

    redacting_make_record._gilpick_redacting = True  # type: ignore[attr-defined]
    logging.Logger.makeRecord = redacting_make_record


def log_auth_event(
    logger: logging.Logger,
    *,
    operation: str,
    result: str,
    transaction_id: str | None = None,
    session_id: str | None = None,
    error_code: str | None = None,
    provider_status: int | None = None,
    latency_ms: int | None = None,
) -> None:
    """Log one authentication result using only approved correlation fields.

    Args:
        logger: Destination application logger.
        operation: Stable authentication operation name.
        result: Stable outcome name such as ``SUCCEEDED`` or ``FAILED``.
        transaction_id: Optional login transaction identifier.
        session_id: Optional device session identifier.
        error_code: Optional stable internal error code.
        provider_status: Optional Kakao HTTP status without response content.
        latency_ms: Optional operation latency in milliseconds.
    """
    logger.info(
        {
            key: value
            for key, value in {
                "request_id": request_id_context.get(),
                "operation": operation,
                "result": result,
                "transaction_id": transaction_id,
                "session_id": session_id,
                "error_code": error_code,
                "provider_status": provider_status,
                "latency_ms": latency_ms,
            }.items()
            if value is not None
        }
    )
