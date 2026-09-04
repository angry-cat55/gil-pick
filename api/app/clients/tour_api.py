"""한국관광공사 TourAPI ``KorService2`` client."""

from __future__ import annotations

from typing import Any
from urllib.parse import unquote

import httpx2

from app.core.config import Settings


class TourApiClientError(RuntimeError):
    """안정적인 내부 코드로 분류한 TourAPI 호출 오류."""

    def __init__(
        self,
        code: str,
        *,
        retryable: bool = False,
        status_code: int | None = None,
    ) -> None:
        super().__init__(code)
        self.code = code
        self.retryable = retryable
        self.status_code = status_code


class TourApiClient:
    """장소 검색과 상세 조회에 필요한 TourAPI endpoint만 호출한다."""

    def __init__(
        self,
        settings: Settings,
        client: httpx2.AsyncClient | None = None,
    ) -> None:
        self.settings = settings
        self.client = client or httpx2.AsyncClient(
            timeout=settings.place_provider_timeout_seconds
        )

    async def search_keyword(self, **params: Any) -> dict[str, Any]:
        """키워드로 장소를 검색한다."""
        return await self._get("searchKeyword2", params)

    async def search_by_area(self, **params: Any) -> dict[str, Any]:
        """분류와 지역 조건으로 장소를 검색한다."""
        return await self._get("areaBasedList2", params)

    async def get_common_detail(self, content_id: str) -> dict[str, Any]:
        """장소의 공통 상세정보를 조회한다."""
        return await self._get("detailCommon2", {"contentId": content_id})

    async def get_intro_detail(
        self, content_id: str, content_type_id: str
    ) -> dict[str, Any]:
        """장소 유형별 상세정보를 조회한다."""
        return await self._get(
            "detailIntro2",
            {"contentId": content_id, "contentTypeId": content_type_id},
        )

    async def get_classifications(self) -> dict[str, Any]:
        """TourAPI 신분류 코드 목록을 조회한다."""
        return await self._get("lclsSystmCode2", {"lclsSystmListYn": "Y"})

    async def _get(self, endpoint: str, params: dict[str, Any]) -> dict[str, Any]:
        for attempt in range(2):
            try:
                return await self._get_once(endpoint, params)
            except TourApiClientError as exc:
                if attempt == 0 and exc.retryable:
                    continue
                raise
        raise AssertionError("unreachable")

    async def _get_once(
        self, endpoint: str, params: dict[str, Any]
    ) -> dict[str, Any]:
        request_params = {
            "serviceKey": unquote(
                self.settings.tour_api_service_key.get_secret_value()
            ),
            "MobileOS": "ETC",
            "MobileApp": "Gilpick",
            "_type": "json",
            **params,
        }
        try:
            response = await self.client.get(
                f"{self.settings.tour_api_base_url}/{endpoint}",
                params=request_params,
            )
        except httpx2.TimeoutException as exc:
            raise TourApiClientError("TOUR_API_TIMEOUT", retryable=True) from exc
        except httpx2.RequestError as exc:
            raise TourApiClientError("TOUR_API_FAILED", retryable=True) from exc
        self._raise_for_status(response)
        try:
            payload = response.json()
        except (TypeError, ValueError) as exc:
            raise TourApiClientError("TOUR_API_FAILED") from exc
        if not isinstance(payload, dict):
            raise TourApiClientError("TOUR_API_FAILED")
        self._raise_for_application_error(payload)
        return payload

    @staticmethod
    def _raise_for_status(response: httpx2.Response) -> None:
        if response.status_code < 400:
            return
        if response.status_code == 429:
            raise TourApiClientError("TOUR_API_RATE_LIMITED", status_code=429)
        raise TourApiClientError(
            "TOUR_API_FAILED",
            retryable=response.status_code >= 500,
            status_code=response.status_code,
        )

    @staticmethod
    def _raise_for_application_error(payload: dict[str, Any]) -> None:
        header = payload.get("OpenAPI_ServiceResponse", {}).get("cmmMsgHeader", {})
        if header:
            message = str(header.get("errMsg", ""))
            code = (
                "TOUR_API_RATE_LIMITED"
                if "LIMIT" in message or "QUOTA" in message
                else "TOUR_API_FAILED"
            )
            raise TourApiClientError(code)

        response_header = payload.get("response", {}).get("header", {})
        if response_header.get("resultCode") not in (None, "0000"):
            message = str(response_header.get("resultMsg", ""))
            code = (
                "TOUR_API_RATE_LIMITED"
                if "LIMIT" in message or "QUOTA" in message
                else "TOUR_API_FAILED"
            )
            raise TourApiClientError(code)
