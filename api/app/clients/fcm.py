"""Firebase Cloud Messaging HTTP v1 client."""

from __future__ import annotations

import json
import time
from dataclasses import dataclass
from enum import Enum
from typing import Any

import httpx2
import jwt

from app.core.config import Settings


class FcmSendResult(str, Enum):
    OK = "OK"
    INVALID_TOKEN = "INVALID_TOKEN"
    RETRYABLE = "RETRYABLE"
    FATAL = "FATAL"


class FcmClientError(RuntimeError):
    """FCM 인증 또는 요청 구성 실패."""

    def __init__(self, code: str, *, retryable: bool = False, status_code: int | None = None):
        super().__init__(code)
        self.code, self.retryable, self.status_code = code, retryable, status_code


@dataclass(slots=True)
class _AccessToken:
    value: str
    expires_at: float


class FcmClient:
    """서비스 계정 OAuth2와 data-only FCM 발송을 담당한다."""

    def __init__(self, settings: Settings, client: httpx2.AsyncClient | None = None) -> None:
        self.settings = settings
        self._owns_client = client is None
        self.client = client or httpx2.AsyncClient(timeout=settings.fcm_request_timeout_seconds)
        self._access_token: _AccessToken | None = None

    async def aclose(self) -> None:
        """내부에서 생성한 HTTP connection pool을 닫는다."""
        if self._owns_client:
            await self.client.aclose()

    async def send(self, token: str, data: dict[str, str]) -> FcmSendResult:
        """한 기기에 최소 식별자와 문구만 담은 data message를 보낸다."""
        if not self.settings.fcm_enabled:
            return FcmSendResult.OK
        access_token = await self._get_access_token()
        try:
            response = await self.client.post(
                f"https://fcm.googleapis.com/v1/projects/{self.settings.fcm_project_id}/messages:send",
                headers={"Authorization": f"Bearer {access_token}"},
                json={"message": {"token": token, "data": data}},
            )
        except httpx2.RequestError:
            return FcmSendResult.RETRYABLE
        if response.status_code < 300:
            return FcmSendResult.OK
        if response.status_code >= 500 or response.status_code == 429:
            return FcmSendResult.RETRYABLE
        try:
            details = response.json().get("error", {}).get("details", [])
        except (AttributeError, TypeError, ValueError):
            details = []
        if any(item.get("errorCode") in {"UNREGISTERED", "INVALID_ARGUMENT"} for item in details if isinstance(item, dict)):
            return FcmSendResult.INVALID_TOKEN
        return FcmSendResult.FATAL

    async def _get_access_token(self) -> str:
        now = time.time()
        if self._access_token and self._access_token.expires_at > now + 60:
            return self._access_token.value
        try:
            account = json.loads(self.settings.fcm_service_account_json.get_secret_value())
            issued_at = int(now)
            assertion = jwt.encode(
                {"iss": account["client_email"], "scope": "https://www.googleapis.com/auth/firebase.messaging", "aud": account["token_uri"], "iat": issued_at, "exp": issued_at + 3600},
                account["private_key"], algorithm="RS256",
            )
            response = await self.client.post(account["token_uri"], data={"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": assertion})
            payload: dict[str, Any] = response.json()
            if response.status_code >= 400:
                raise FcmClientError("FCM_OAUTH_FAILED", retryable=response.status_code >= 500, status_code=response.status_code)
            token = str(payload["access_token"])
            self._access_token = _AccessToken(token, now + min(int(payload.get("expires_in", 3600)), 3300))
            return token
        except FcmClientError:
            raise
        except (KeyError, TypeError, ValueError, jwt.PyJWTError, httpx2.RequestError) as exc:
            raise FcmClientError("FCM_OAUTH_FAILED", retryable=isinstance(exc, httpx2.RequestError)) from exc
