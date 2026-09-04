"""TourAPI 중심 장소 검색과 Google Places 보완 로직."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import logging
import math
from html import unescape
from html.parser import HTMLParser
from typing import Any
from urllib.parse import urlsplit, urlunsplit

from app.api.errors import AppError
from app.clients.google_places import GooglePlacesClientError
from app.clients.tour_api import TourApiClientError
from app.schemas.place import (
    BusinessStatus,
    PlaceCategory,
    PlaceSource,
    PlaceDetail,
    PlaceSummary,
    TourApiCategory,
)

logger = logging.getLogger("gilpick.place")

_STAY_MINUTES = {
    PlaceCategory.NATURE: 120,
    PlaceCategory.HISTORY_CULTURE: 90,
    PlaceCategory.FOOD: 60,
    PlaceCategory.CAFE: 60,
    PlaceCategory.SHOPPING: 90,
    PlaceCategory.OTHER: 60,
}
_COMMERCIAL = {PlaceCategory.FOOD, PlaceCategory.CAFE, PlaceCategory.SHOPPING}
_TOUR_LARGE = {
    PlaceCategory.NATURE: "NA",
    PlaceCategory.HISTORY_CULTURE: "HS",
    PlaceCategory.FOOD: "FD",
    PlaceCategory.CAFE: "FD",
    PlaceCategory.SHOPPING: "SH",
}
_CATEGORY_LABEL = {
    PlaceCategory.FOOD: "음식점",
    PlaceCategory.CAFE: "카페",
    PlaceCategory.SHOPPING: "쇼핑",
}


class _PlainTextParser(HTMLParser):
    """HTML markup을 제외한 text node만 모은다."""

    def __init__(self) -> None:
        super().__init__()
        self.parts: list[str] = []

    def handle_data(self, data: str) -> None:
        self.parts.append(data)


class PlaceService:
    """외부 provider 결과를 길픽 장소 계약으로 정규화한다."""

    def __init__(self, tour_client: Any, google_client: Any, *, cursor_secret: str) -> None:
        self.tour_client = tour_client
        self.google_client = google_client
        self._cursor_secret = cursor_secret.encode()

    async def search_places(
        self,
        *,
        query: str | None,
        category: PlaceCategory | None,
        area_code: str | None,
        cursor: str | None,
        limit: int,
    ) -> tuple[list[PlaceSummary], str | None, bool]:
        """검색 조건에 맞는 장소 한 페이지를 반환한다.

        Args:
            query: 두 글자 이상의 검색어. 카테고리 단독 검색이면 ``None``.
            category: 길픽 장소 카테고리 필터.
            area_code: TourAPI 지역 코드.
            cursor: 이전 응답에서 받은 불투명 pagination cursor.
            limit: 한 페이지에 반환할 최대 장소 수.

        Returns:
            장소 목록, 다음 cursor, 다음 페이지 존재 여부.

        Raises:
            AppError: cursor가 잘못됐거나 기준 provider 요청에 실패한 경우.
        """
        criteria = self._criteria_hash(query, category, area_code, limit)
        state = self._decode_cursor(cursor, criteria) if cursor else {}
        page_no = int(state.get("tour_page_no", 1))
        seen = set(state.get("seen_place_ids", []))

        params: dict[str, Any] = {"pageNo": page_no, "numOfRows": limit}
        if area_code:
            params["areaCode"] = area_code
        if category in _TOUR_LARGE:
            params["lclsSystm1"] = _TOUR_LARGE[category]
            if category is PlaceCategory.CAFE:
                params["lclsSystm2"] = "FD05"

        try:
            if query:
                payload = await self.tour_client.search_keyword(keyword=query, **params)
            else:
                payload = await self.tour_client.search_by_area(**params)
        except TourApiClientError as exc:
            status = {"TOUR_API_RATE_LIMITED": 429, "TOUR_API_TIMEOUT": 504}.get(
                exc.code, 502
            )
            raise AppError(
                status, exc.code, "장소 제공자 요청에 실패했습니다.", retryable=exc.retryable
            ) from exc

        body = payload.get("response", {}).get("body", {})
        raw_items = body.get("items", {}).get("item", [])
        if isinstance(raw_items, dict):
            raw_items = [raw_items]
        items: list[PlaceSummary] = []
        for raw in raw_items if isinstance(raw_items, list) else []:
            item = self._tour_place(raw)
            if item is None or item.place_id in seen:
                continue
            if category is not None and item.category is not category:
                continue
            if item.place_id not in {entry.place_id for entry in items}:
                items.append(item)

        google_token = state.get("google_page_token")
        if category in _COMMERCIAL and len(items) < limit:
            params = {"maxResultCount": limit - len(items)}
            if google_token:
                params["pageToken"] = google_token
            try:
                google = await self.google_client.search_text(
                    query or _CATEGORY_LABEL[category], **params
                )
            except GooglePlacesClientError as exc:
                self._log_google_degradation("SEARCH_SUPPLEMENT", exc)
                google_token = None
            else:
                google_token = google.get("nextPageToken")
                for raw in google.get("places", []):
                    candidate = self._google_place(raw, category)
                    if candidate is None or candidate.place_id in seen:
                        continue
                    match, ambiguous = self._find_match(items, candidate)
                    if match:
                        self._merge_google(match, candidate)
                    elif not ambiguous and len(items) < limit:
                        items.append(candidate)

        total = int(body.get("totalCount") or len(raw_items))
        tour_has_next = page_no * limit < total
        has_next = tour_has_next or bool(google_token)
        next_cursor = None
        if has_next:
            next_cursor = self._encode_cursor(
                {
                    "v": 1,
                    "tour_page_no": page_no + 1 if tour_has_next else page_no,
                    "google_page_token": google_token,
                    "seen_place_ids": [*(list(seen)[-80:]), *[i.place_id for i in items]][-100:],
                    "criteria_hash": criteria,
                }
            )
        return items[:limit], next_cursor, has_next

    async def get_place(self, place_id: str) -> PlaceDetail:
        """provider prefix에 따라 장소 상세를 조회한다.

        Args:
            place_id: ``tourapi:`` 또는 ``google:`` prefix가 붙은 장소 ID.

        Returns:
            길픽 장소 상세 계약으로 정규화한 결과.

        Raises:
            AppError: 장소가 없거나 기준 provider 요청에 실패한 경우.

        Notes:
            TourAPI 장소의 Google 보완 실패는 격리하고 TourAPI 상세를 반환한다.
        """
        provider, source_id = place_id.split(":", 1)
        if provider == "google":
            try:
                raw = await self.google_client.get_place(source_id)
            except GooglePlacesClientError as exc:
                raise self._provider_error(exc) from exc
            category = self._google_category(raw.get("types", []))
            item = self._google_place(raw, category)
            if item is None:
                raise AppError(404, "PLACE_NOT_FOUND", "장소를 찾을 수 없습니다.")
            return self._detail(item, phone=raw.get("nationalPhoneNumber"))

        try:
            common_payload = await self.tour_client.get_common_detail(source_id)
        except TourApiClientError as exc:
            raise self._provider_error(exc) from exc
        common = self._first_item(common_payload)
        item = self._tour_place(common) if common else None
        if item is None:
            raise AppError(404, "PLACE_NOT_FOUND", "장소를 찾을 수 없습니다.")
        intro = {}
        content_type = str(common.get("contenttypeid") or "")
        if content_type:
            try:
                intro = self._first_item(
                    await self.tour_client.get_intro_detail(source_id, content_type)
                ) or {}
            except TourApiClientError as exc:
                raise self._provider_error(exc) from exc
        if item.category in _COMMERCIAL:
            try:
                google = await self.google_client.search_text(
                    item.name, maxResultCount=5
                )
            except GooglePlacesClientError as exc:
                self._log_google_degradation("DETAIL_ENRICHMENT", exc)
            else:
                for raw in google.get("places", []):
                    candidate = self._google_place(raw, item.category)
                    if candidate is not None and self._find_match([item], candidate)[0]:
                        self._merge_google(item, candidate)
                        break
        guide = [intro.get(key) for key in ("opentime", "usetime", "restdate", "restdateshopping")]
        return self._detail(
            item,
            description=self._plain_text(common.get("overview")),
            phone=common.get("tel") or None,
            operating_guide=", ".join(value for value in guide if value) or None,
        )

    @staticmethod
    def _detail(item: PlaceSummary, **extra: Any) -> PlaceDetail:
        fields = {"description": None, "phone": None, "operating_guide": None} | extra
        return PlaceDetail(**item.model_dump(), **fields)

    @staticmethod
    def _provider_error(exc: TourApiClientError | GooglePlacesClientError) -> AppError:
        status = 504 if exc.code.endswith("_TIMEOUT") else 429 if exc.code.endswith("_RATE_LIMITED") else 502
        return AppError(status, exc.code, "장소 제공자 요청에 실패했습니다.", retryable=exc.retryable)

    @staticmethod
    def _log_google_degradation(
        operation: str, exc: GooglePlacesClientError
    ) -> None:
        logger.warning(
            "Google Places 보완 실패",
            extra={
                "operation": operation,
                "result": "DEGRADED",
                "error_code": exc.code,
            },
        )

    @staticmethod
    def _first_item(payload: dict[str, Any]) -> dict[str, Any] | None:
        items = payload.get("response", {}).get("body", {}).get("items", {})
        raw = items.get("item", []) if isinstance(items, dict) else []
        if isinstance(raw, dict):
            return raw
        return raw[0] if isinstance(raw, list) and raw else None

    @staticmethod
    def _plain_text(value: Any) -> str | None:
        if not value:
            return None
        parser = _PlainTextParser()
        parser.feed(str(value))
        return " ".join(unescape("".join(parser.parts)).split()) or None

    @staticmethod
    def _google_category(types: list[str]) -> PlaceCategory:
        if "cafe" in types:
            return PlaceCategory.CAFE
        if {"restaurant", "food"} & set(types):
            return PlaceCategory.FOOD
        if {"store", "shopping_mall"} & set(types):
            return PlaceCategory.SHOPPING
        return PlaceCategory.OTHER

    @staticmethod
    def _category(large: str | None, middle: str | None) -> PlaceCategory:
        if large == "NA":
            return PlaceCategory.NATURE
        if large == "HS":
            return PlaceCategory.HISTORY_CULTURE
        if large == "FD" and middle == "FD05":
            return PlaceCategory.CAFE
        if large == "FD":
            return PlaceCategory.FOOD
        if large == "SH":
            return PlaceCategory.SHOPPING
        return PlaceCategory.OTHER

    @classmethod
    def _tour_place(cls, raw: dict[str, Any]) -> PlaceSummary | None:
        source_id = str(raw.get("contentid") or "").strip()
        name = str(raw.get("title") or "").strip()
        if not source_id or not name:
            return None
        large = raw.get("lclsSystm1") or None
        middle = raw.get("lclsSystm2") or None
        category = cls._category(large, middle)
        latitude, longitude = cls._coordinates(raw.get("mapy"), raw.get("mapx"))
        return PlaceSummary(
            place_id=f"tourapi:{source_id}", source=PlaceSource.TOUR_API,
            source_place_id=source_id, name=name, category=category,
            tour_api_category=TourApiCategory(
                large=large, middle=middle, small=raw.get("lclsSystm3") or None
            ),
            address=raw.get("addr1") or None, latitude=latitude, longitude=longitude,
            image_url=cls._https_url(raw.get("firstimage")),
            recommended_stay_minutes=_STAY_MINUTES[category], rating=None,
            user_rating_count=None, business_status=None,
            regular_opening_hours=None, current_opening_hours=None,
            google_attributions=None,
        )

    @classmethod
    def _google_place(
        cls, raw: dict[str, Any], category: PlaceCategory
    ) -> PlaceSummary | None:
        source_id = str(raw.get("id") or "").strip()
        name = str(raw.get("displayName", {}).get("text") or "").strip()
        if not source_id or not name:
            return None
        location = raw.get("location", {})
        latitude, longitude = cls._coordinates(
            location.get("latitude"), location.get("longitude")
        )
        status = raw.get("businessStatus")
        return PlaceSummary(
            place_id=f"google:{source_id}", source=PlaceSource.GOOGLE_PLACES,
            source_place_id=source_id, name=name, category=category,
            tour_api_category=None, address=raw.get("formattedAddress") or None,
            latitude=latitude, longitude=longitude, image_url=None,
            recommended_stay_minutes=_STAY_MINUTES[category], rating=raw.get("rating"),
            user_rating_count=raw.get("userRatingCount"),
            business_status=BusinessStatus(status) if status in BusinessStatus else None,
            regular_opening_hours=raw.get("regularOpeningHours", {}).get("weekdayDescriptions"),
            current_opening_hours=raw.get("currentOpeningHours", {}).get("weekdayDescriptions"),
            google_attributions=cls._attributions(raw.get("attributions")),
        )

    @staticmethod
    def _attributions(values: Any) -> list[str] | None:
        if not isinstance(values, list):
            return None
        result: list[str] = []
        for value in values:
            if isinstance(value, str) and value:
                result.append(value)
            elif isinstance(value, dict):
                provider = str(value.get("provider") or "").strip()
                uri = str(value.get("providerUri") or "").strip()
                text = f"{provider} ({uri})" if provider and uri else provider or uri
                if text:
                    result.append(text)
        return result or None

    @staticmethod
    def _coordinates(latitude: Any, longitude: Any) -> tuple[float | None, float | None]:
        try:
            return float(latitude), float(longitude)
        except (TypeError, ValueError):
            return None, None

    @staticmethod
    def _https_url(value: Any) -> str | None:
        if not value:
            return None
        try:
            parts = urlsplit(str(value))
            if parts.scheme not in {"http", "https"} or not parts.netloc:
                return None
            return urlunsplit(("https", parts.netloc, parts.path, parts.query, parts.fragment))
        except ValueError:
            return None

    @classmethod
    def _find_match(
        cls, items: list[PlaceSummary], candidate: PlaceSummary
    ) -> tuple[PlaceSummary | None, bool]:
        ambiguous = False
        for item in items:
            same_name = cls._normalize(item.name) == cls._normalize(candidate.name)
            same_address = bool(
                item.address and candidate.address
                and cls._normalize(item.address) == cls._normalize(candidate.address)
            )
            close = cls._distance(item, candidate) <= 50
            if same_name and same_address and close:
                return item, False
            ambiguous |= sum((same_name, same_address, close)) >= 2
        return None, ambiguous

    @staticmethod
    def _merge_google(target: PlaceSummary, source: PlaceSummary) -> None:
        for field in (
            "rating", "user_rating_count", "business_status",
            "regular_opening_hours", "current_opening_hours", "google_attributions",
        ):
            setattr(target, field, getattr(source, field))

    @staticmethod
    def _normalize(value: str) -> str:
        return "".join(char.lower() for char in value if char.isalnum())

    @staticmethod
    def _distance(left: PlaceSummary, right: PlaceSummary) -> float:
        if None in (left.latitude, left.longitude, right.latitude, right.longitude):
            return math.inf
        lat1, lat2 = math.radians(left.latitude), math.radians(right.latitude)
        dlat = lat2 - lat1
        dlon = math.radians(right.longitude - left.longitude)
        value = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
        return 6_371_000 * 2 * math.asin(math.sqrt(value))

    @staticmethod
    def _criteria_hash(
        query: str | None, category: PlaceCategory | None, area_code: str | None, limit: int
    ) -> str:
        raw = json.dumps(
            {"query": query, "category": category, "areaCode": area_code, "limit": limit},
            ensure_ascii=False, sort_keys=True, separators=(",", ":"),
        )
        return hashlib.sha256(raw.encode()).hexdigest()

    def _encode_cursor(self, payload: dict[str, Any]) -> str:
        body = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
        signature = hmac.new(self._cursor_secret, body, hashlib.sha256).digest()
        return f"{self._b64encode(body)}.{self._b64encode(signature)}"

    def _decode_cursor(self, cursor: str, criteria: str) -> dict[str, Any]:
        try:
            body_value, signature_value = cursor.split(".")
            body = self._b64decode(body_value)
            signature = self._b64decode(signature_value)
            expected = hmac.new(self._cursor_secret, body, hashlib.sha256).digest()
            payload = json.loads(body)
            if not hmac.compare_digest(signature, expected):
                raise ValueError
            if payload.get("v") != 1 or payload.get("criteria_hash") != criteria:
                raise ValueError
            return payload
        except (ValueError, TypeError, KeyError, json.JSONDecodeError) as exc:
            raise AppError(400, "INVALID_CURSOR", "유효하지 않은 cursor입니다.") from exc

    @staticmethod
    def _b64encode(value: bytes) -> str:
        return base64.urlsafe_b64encode(value).decode().rstrip("=")

    @classmethod
    def _b64decode(cls, value: str) -> bytes:
        decoded = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
        if cls._b64encode(decoded) != value:
            raise ValueError
        return decoded


__all__ = ["PlaceService"]
