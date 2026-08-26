"""Minimal Kakao OAuth REST client."""

from __future__ import annotations

from typing import Any

import httpx2

from app.core.config import Settings


class KakaoClientError(RuntimeError):
    """Kakao 요청 실패를 안정적인 분류와 함께 나타낸다."""

    def __init__(self, code: str, *, retryable: bool = False, status_code: int | None = None):
        super().__init__(code)
        self.code = code
        self.retryable = retryable
        self.status_code = status_code


class KakaoClient:
    """인가 코드 교환과 사용자 조회만 담당하는 Kakao client."""

    def __init__(self, settings: Settings, client: httpx2.AsyncClient | None = None) -> None:
        self.settings = settings
        self.client = client or httpx2.AsyncClient(timeout=5.0)

    async def exchange_code(self, code: str) -> str:
        """인가 코드를 한 번만 Kakao Access Token으로 교환한다.

        Args:
            code: Callback에서 받은 일회용 Kakao 인가 코드.

        Returns:
            사용자 조회에만 사용하는 Kakao Access Token.

        Raises:
            KakaoClientError: timeout 또는 Kakao 오류 응답인 경우.
        """
        try:
            response = await self.client.post(
                "https://kauth.kakao.com/oauth/token",
                data={
                    "grant_type": "authorization_code",
                    "client_id": self.settings.kakao_rest_api_key.get_secret_value(),
                    "client_secret": self.settings.kakao_client_secret.get_secret_value(),
                    "redirect_uri": self.settings.kakao_redirect_uri,
                    "code": code,
                },
            )
        except httpx2.RequestError as exc:
            raise KakaoClientError("KAKAO_API_TIMEOUT", retryable=True) from exc
        self._raise_for_status(response)
        try:
            token = response.json().get("access_token")
        except (TypeError, ValueError) as exc:
            raise KakaoClientError("KAKAO_AUTH_FAILED") from exc
        if not isinstance(token, str) or not token:
            raise KakaoClientError("KAKAO_AUTH_FAILED")
        return token

    async def get_user_profile(self, access_token: str) -> dict[str, Any]:
        """Kakao 사용자 식별자와 선택 profile을 조회한다.

        Args:
            access_token: 직전에 교환한 Kakao Access Token.

        Returns:
            ``id``, nullable ``nickname``, nullable ``profile_image_url`` mapping.

        Raises:
            TimeoutError: 호출 시간 초과로 service가 한 번 재시도할 수 있는 경우.
            KakaoClientError: 재시도 대상이 아닌 Kakao 오류인 경우.
        """
        try:
            response = await self.client.get(
                "https://kapi.kakao.com/v2/user/me",
                headers={"Authorization": f"Bearer {access_token}"},
            )
        except httpx2.RequestError as exc:
            raise KakaoClientError("KAKAO_API_TIMEOUT", retryable=True) from exc
        self._raise_for_status(response)
        try:
            payload = response.json()
            subject = str(payload["id"])
            account = payload.get("kakao_account") or {}
            profile = account.get("profile") or {}
        except (AttributeError, KeyError, TypeError, ValueError) as exc:
            raise KakaoClientError("KAKAO_AUTH_FAILED") from exc
        return {
            "id": subject,
            "nickname": profile.get("nickname"),
            "profile_image_url": _normalize_profile_image_url(profile.get("profile_image_url")),
        }

    @staticmethod
    def _raise_for_status(response: httpx2.Response) -> None:
        if response.status_code < 400:
            return
        if response.status_code == 429:
            raise KakaoClientError("KAKAO_RATE_LIMITED", retryable=True, status_code=429)
        if 400 <= response.status_code < 500:
            raise KakaoClientError("INVALID_AUTHORIZATION_CODE", status_code=response.status_code)
        raise KakaoClientError("KAKAO_API_FAILED", retryable=True, status_code=response.status_code)


def _normalize_profile_image_url(url: str | None) -> str | None:
    """Kakao가 http로 내려주는 profile image URL을 https로 맞춘다.

    Kakao는 `k.kakaocdn.net` profile image를 http URL로 반환하지만 같은 경로를
    https로도 제공한다. API 계약은 `profileImageUrl`을 https로 제한하고 Android는
    `targetSdk 36`이라 cleartext 이미지를 로드할 수 없으므로, provider 응답이
    들어오는 이 경계에서 scheme만 맞춘다. 경로와 host는 바꾸지 않는다.

    Args:
        url: Kakao가 반환한 profile image URL. 미동의 시 ``None``이다.

    Returns:
        https scheme으로 맞춘 URL. 입력이 ``None``이면 ``None``이다.
    """
    if url is not None and url.startswith("http://"):
        return f"https://{url.removeprefix('http://')}"
    return url
