"""Google Places API (New) client."""

from __future__ import annotations

from typing import Any

import httpx2

from app.core.config import Settings

SEARCH_FIELD_MASK = ",".join(
    (
        "places.id",
        "places.displayName",
        "places.formattedAddress",
        "places.location",
        "places.types",
        "places.rating",
        "places.userRatingCount",
        "places.businessStatus",
        "places.regularOpeningHours",
        "places.currentOpeningHours",
        "places.attributions",
        "nextPageToken",
    )
)
DETAIL_FIELD_MASK = ",".join(
    (
        "id",
        "displayName",
        "formattedAddress",
        "location",
        "types",
        "nationalPhoneNumber",
        "rating",
        "userRatingCount",
        "businessStatus",
        "regularOpeningHours",
        "currentOpeningHours",
        "attributions",
    )
)


class GooglePlacesClientError(RuntimeError):
    """안정적인 내부 코드로 분류한 Google Places 호출 오류."""

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


class GooglePlacesClient:
    """Text Search와 Place Details만 최소 field mask로 호출한다."""

    def __init__(
        self,
        settings: Settings,
        client: httpx2.AsyncClient | None = None,
    ) -> None:
        self.settings = settings
        self.client = client or httpx2.AsyncClient(
            timeout=settings.place_provider_timeout_seconds
        )

    async def search_text(self, text_query: str, **params: Any) -> dict[str, Any]:
        """검색어에 일치하는 Google 장소를 조회한다."""
        return await self._request(
            "POST",
            "/places:searchText",
            field_mask=SEARCH_FIELD_MASK,
            json={"textQuery": text_query, **params},
        )

    async def get_place(self, place_id: str) -> dict[str, Any]:
        """Google place ID로 상세정보를 조회한다."""
        return await self._request(
            "GET",
            f"/places/{place_id}",
            field_mask=DETAIL_FIELD_MASK,
        )

    async def _request(
        self,
        method: str,
        path: str,
        *,
        field_mask: str,
        json: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        for attempt in range(2):
            try:
                return await self._request_once(
                    method, path, field_mask=field_mask, json=json
                )
            except GooglePlacesClientError as exc:
                if attempt == 0 and exc.retryable:
                    continue
                raise
        raise AssertionError("unreachable")

    async def _request_once(
        self,
        method: str,
        path: str,
        *,
        field_mask: str,
        json: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        try:
            response = await self.client.request(
                method,
                f"{self.settings.google_places_base_url}{path}",
                headers={
                    "X-Goog-Api-Key": self.settings.google_places_api_key.get_secret_value(),
                    "X-Goog-FieldMask": field_mask,
                },
                json=json,
            )
        except httpx2.TimeoutException as exc:
            raise GooglePlacesClientError(
                "GOOGLE_PLACES_TIMEOUT", retryable=True
            ) from exc
        except httpx2.RequestError as exc:
            raise GooglePlacesClientError(
                "GOOGLE_PLACES_FAILED", retryable=True
            ) from exc
        self._raise_for_status(response)
        try:
            payload = response.json()
        except (TypeError, ValueError) as exc:
            raise GooglePlacesClientError("GOOGLE_PLACES_FAILED") from exc
        if not isinstance(payload, dict):
            raise GooglePlacesClientError("GOOGLE_PLACES_FAILED")
        return payload

    @staticmethod
    def _raise_for_status(response: httpx2.Response) -> None:
        if response.status_code < 400:
            return
        if response.status_code == 429:
            raise GooglePlacesClientError(
                "GOOGLE_PLACES_RATE_LIMITED", status_code=429
            )
        raise GooglePlacesClientError(
            "GOOGLE_PLACES_FAILED",
            retryable=response.status_code >= 500,
            status_code=response.status_code,
        )
