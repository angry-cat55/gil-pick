"""서울시 도시데이터를 ETA 시점 혼잡 위험으로 평가한다."""

from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone

from sqlalchemy.ext.asyncio import AsyncSession

from app.clients.seoul_citydata import SeoulCityDataClient
from app.schemas.detection import CongestionVerdict
from app.services.detection.congestion_areas import find_nearest_congestion_area
from app.services.detection.policy import (
    CONGESTION_CURRENT_ETA_TOLERANCE_MINUTES,
    CONGESTION_CURRENT_MAX_AGE_MINUTES,
    CONGESTION_FORECAST_TOLERANCE_MINUTES,
    CONGESTION_RISK_LEVEL,
    congestion_sensitivity,
)

_ORDER = {"RELAXED": 0, "NORMAL": 1, "SLIGHTLY_CROWDED": 2, "CROWDED": 3}
KST = timezone(timedelta(hours=9))
logger = logging.getLogger(__name__)


async def evaluate_congestion(
    session: AsyncSession,
    client: SeoulCityDataClient,
    *,
    category: str | None,
    latitude: float,
    longitude: float,
    eta: datetime,
    now: datetime | None = None,
) -> CongestionVerdict:
    """500m 이내 지원 지점의 ETA 예측 혼잡도를 평가한다."""
    area = await find_nearest_congestion_area(
        session, latitude=latitude, longitude=longitude
    )
    if area is None:
        return CongestionVerdict(
            available=False, unavailable_reason="NOT_IN_SUPPORT_AREA"
        )
    population = await client.get_population(area.area_code)
    if population is None:
        return CongestionVerdict(available=False, unavailable_reason="TIMEOUT")
    forecast = (
        min(population.forecasts, key=lambda value: abs(value.forecast_at - eta))
        if population.forecasts
        else None
    )
    if forecast and abs(forecast.forecast_at - eta) <= timedelta(
        minutes=CONGESTION_FORECAST_TOLERANCE_MINUTES
    ):
        level = forecast.level
    else:
        clock = now or datetime.now(KST)
        current_is_usable = (
            population.current_at is not None
            and abs(eta - clock)
            <= timedelta(minutes=CONGESTION_CURRENT_ETA_TOLERANCE_MINUTES)
            and timedelta(0) <= clock - population.current_at
            <= timedelta(minutes=CONGESTION_CURRENT_MAX_AGE_MINUTES)
        )
        if not current_is_usable:
            logger.info(
                "ETA 허용 범위 안의 혼잡 예보나 최신 현재값이 없어 혼잡 변수를 제외합니다.",
                extra={"reason": "STALE", "eta": eta.isoformat()},
            )
            return CongestionVerdict(
                available=False, unavailable_reason="NO_FORECAST"
            )
        level = population.current_level
    sensitivity = congestion_sensitivity(category)
    return CongestionVerdict(
        available=True,
        level=level.value,
        sensitivity=sensitivity,
        crowded=_ORDER[level.value] >= _ORDER[CONGESTION_RISK_LEVEL[sensitivity]],
    )
