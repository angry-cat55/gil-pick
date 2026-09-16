"""서울시 실시간 도시데이터 혼잡도 client."""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from enum import StrEnum

import httpx2

from app.core.config import Settings

logger = logging.getLogger(__name__)


class CongestionLevel(StrEnum):
    RELAXED="RELAXED"; NORMAL="NORMAL"; SLIGHTLY_CROWDED="SLIGHTLY_CROWDED"; CROWDED="CROWDED"


LEVELS = {"여유": CongestionLevel.RELAXED, "보통": CongestionLevel.NORMAL, "약간 붐빔": CongestionLevel.SLIGHTLY_CROWDED, "붐빔": CongestionLevel.CROWDED}


@dataclass(frozen=True)
class PopulationForecast:
    forecast_at: datetime
    level: CongestionLevel


@dataclass(frozen=True)
class PopulationData:
    current_level: CongestionLevel
    forecasts: list[PopulationForecast]
    current_at: datetime | None = None


class SeoulCityDataProviderError(RuntimeError):
    """재시도 후에도 서울시 도시데이터 요청을 완료하지 못했다."""


class SeoulCityDataClient:
    """지원 지점의 현재·예측 혼잡 수준을 조회한다."""

    def __init__(self, settings: Settings, client: httpx2.AsyncClient | None = None) -> None:
        self.settings = settings
        self._owns_client = client is None
        self.client = client or httpx2.AsyncClient(timeout=settings.detection_provider_timeout_seconds)

    async def aclose(self) -> None:
        """내부에서 만든 HTTP connection pool을 닫는다."""
        if self._owns_client:
            await self.client.aclose()

    async def get_population(self, area_code: str) -> PopulationData | None:
        """서울시 지원 지점 코드의 혼잡 데이터를 반환한다."""
        key = self.settings.seoul_citydata_api_key.get_secret_value()
        if not key: return None
        url = f"{self.settings.seoul_citydata_base_url}/{key}/json/citydata_ppltn/1/5/{area_code}"
        for attempt in range(2):
            try:
                response = await self.client.get(url)
                if response.status_code >= 500: raise httpx2.HTTPStatusError("temporary", request=response.request, response=response)
                if response.status_code >= 400: return None
                break
            except (httpx2.TimeoutException, httpx2.RequestError, httpx2.HTTPStatusError):
                if attempt == 1:
                    raise SeoulCityDataProviderError("SEOUL_CITYDATA_PROVIDER_FAILED")
        try:
            row = response.json()["SeoulRtd.citydata_ppltn"][0]
            kst = timezone(timedelta(hours=9))
            forecasts = [PopulationForecast(datetime.fromisoformat(v["FCST_TIME"]).replace(tzinfo=kst), LEVELS[v["FCST_CONGEST_LVL"]]) for v in row.get("FCST_PPLTN", [])]
            current_at = None
            if row.get("PPLTN_TIME"):
                current_at = datetime.fromisoformat(row["PPLTN_TIME"])
                current_at = (
                    current_at.replace(tzinfo=kst)
                    if current_at.tzinfo is None
                    else current_at.astimezone(kst)
                )
            return PopulationData(LEVELS[row["AREA_CONGEST_LVL"]], forecasts, current_at)
        except (ValueError, KeyError, TypeError, IndexError):
            logger.info(
                "서울시 혼잡 응답의 필수 필드가 불완전해 사용하지 않습니다.",
                extra={"reason": "INCOMPLETE"},
            )
            return None
