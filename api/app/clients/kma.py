"""기상청 단기예보 client."""

from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

import httpx2

from app.core.config import Settings


@dataclass(frozen=True)
class ForecastSlot:
    forecast_at: datetime
    pop: int | None = None
    pcp: str | None = None
    pty: int | None = None


def latitude_longitude_to_grid(latitude: float, longitude: float) -> tuple[int, int]:
    """기상청 DFS 공식 LCC 식으로 WGS84 좌표를 격자로 변환한다."""
    if not (-90 <= latitude <= 90 and -180 <= longitude <= 180):
        raise ValueError("위경도 범위를 벗어났습니다.")
    re, grid, slat1, slat2, olon, olat, xo, yo = 6371.00877, 5.0, 30.0, 60.0, 126.0, 38.0, 43.0, 136.0
    degrad = math.pi / 180.0
    re /= grid
    sn = math.log(math.cos(slat1*degrad)/math.cos(slat2*degrad)) / math.log(math.tan(math.pi*0.25+slat2*degrad*0.5)/math.tan(math.pi*0.25+slat1*degrad*0.5))
    sf = math.tan(math.pi*0.25+slat1*degrad*0.5)**sn * math.cos(slat1*degrad)/sn
    ro = re*sf/(math.tan(math.pi*0.25+olat*degrad*0.5)**sn)
    ra = re*sf/(math.tan(math.pi*0.25+latitude*degrad*0.5)**sn)
    theta = (longitude-olon)*degrad*sn
    return int(ra*math.sin(theta)+xo+0.5), int(ro-ra*math.cos(theta)+yo+0.5)


def latest_base_slot(now: datetime | None = None) -> tuple[str, str]:
    """발표 후 10분이 지난 가장 최근 단기예보 기준 시각을 반환한다."""
    kst = timezone(timedelta(hours=9))
    clock = now or datetime.now(kst)
    if clock.tzinfo is None:
        clock = clock.replace(tzinfo=kst)
    else:
        clock = clock.astimezone(kst)
    available = clock - timedelta(minutes=10)
    slots = (2, 5, 8, 11, 14, 17, 20, 23)
    hour = next((candidate for candidate in reversed(slots) if candidate <= available.hour), None)
    if hour is None:
        available -= timedelta(days=1)
        hour = 23
    return available.strftime("%Y%m%d"), f"{hour:02d}00"


class KmaClient:
    """단기예보의 POP·PCP·PTY를 시각별로 묶어 반환한다."""

    def __init__(self, settings: Settings, client: httpx2.AsyncClient | None = None) -> None:
        self.settings = settings
        self._owns_client = client is None
        self.client = client or httpx2.AsyncClient(timeout=settings.detection_provider_timeout_seconds)

    async def aclose(self) -> None:
        """내부에서 만든 HTTP connection pool을 닫는다."""
        if self._owns_client:
            await self.client.aclose()

    async def get_forecast(self, latitude: float, longitude: float) -> list[ForecastSlot] | None:
        """장소 좌표의 최신 단기예보 슬롯을 조회한다."""
        if not self.settings.kma_service_key.get_secret_value(): return None
        try: nx, ny = latitude_longitude_to_grid(latitude, longitude)
        except (ValueError, OverflowError): return None
        base_date, base_time = latest_base_slot()
        params = {"serviceKey": self.settings.kma_service_key.get_secret_value(), "pageNo":1, "numOfRows":1000, "dataType":"JSON", "base_date":base_date, "base_time":base_time, "nx":nx, "ny":ny}
        for attempt in range(2):
            try:
                response = await self.client.get(f"{self.settings.kma_base_url}/getVilageFcst", params=params)
                if response.status_code >= 500: raise httpx2.HTTPStatusError("temporary", request=response.request, response=response)
                if response.status_code >= 400: return None
                break
            except (httpx2.TimeoutException, httpx2.RequestError, httpx2.HTTPStatusError):
                if attempt == 1: return None
        try:
            payload = response.json()
            if str(payload["response"]["header"]["resultCode"]) != "00": return None
            grouped: dict[datetime, dict[str, object]] = {}
            for item in payload["response"]["body"]["items"]["item"]:
                if item["category"] not in {"POP", "PCP", "PTY"}: continue
                slot = datetime.strptime(item["fcstDate"]+item["fcstTime"], "%Y%m%d%H%M").replace(tzinfo=timezone(timedelta(hours=9)))
                grouped.setdefault(slot, {})[item["category"].lower()] = item["fcstValue"]
            return [ForecastSlot(at, int(v["pop"]) if "pop" in v else None, str(v["pcp"]) if "pcp" in v else None, int(v["pty"]) if "pty" in v else None) for at, v in sorted(grouped.items())]
        except (ValueError, KeyError, TypeError):
            return None
