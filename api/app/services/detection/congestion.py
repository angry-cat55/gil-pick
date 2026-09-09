"""서울시 도시데이터를 ETA 시점 혼잡 위험으로 평가한다."""

from __future__ import annotations

from datetime import datetime

from sqlalchemy.ext.asyncio import AsyncSession

from app.clients.seoul_citydata import SeoulCityDataClient
from app.schemas.detection import CongestionVerdict
from app.services.detection.congestion_areas import find_nearest_congestion_area
from app.services.detection.policy import CONGESTION_RISK_LEVEL, congestion_sensitivity

_ORDER = {"RELAXED": 0, "NORMAL": 1, "SLIGHTLY_CROWDED": 2, "CROWDED": 3}


async def evaluate_congestion(
    session: AsyncSession,
    client: SeoulCityDataClient,
    *,
    category: str | None,
    latitude: float,
    longitude: float,
    eta: datetime,
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
    level = (
        min(population.forecasts, key=lambda value: abs(value.forecast_at - eta)).level
        if population.forecasts
        else population.current_level
    )
    sensitivity = congestion_sensitivity(category)
    return CongestionVerdict(
        available=True,
        level=level.value,
        sensitivity=sensitivity,
        crowded=_ORDER[level.value] >= _ORDER[CONGESTION_RISK_LEVEL[sensitivity]],
    )
