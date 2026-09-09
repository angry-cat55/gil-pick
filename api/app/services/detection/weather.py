"""ETA 시점 단기예보를 날씨 위험 판정으로 변환한다."""

from __future__ import annotations

import re
from datetime import datetime

from app.clients.kma import KmaClient
from app.schemas.detection import WeatherVerdict
from app.services.detection.policy import (
    PRECIPITATION_MM_PER_HOUR_THRESHOLD,
    PRECIPITATION_PROBABILITY_THRESHOLD,
    PRECIPITATION_RISK_TYPES,
    weather_exposure,
)

_PTY = {0: "NONE", 1: "RAIN", 2: "RAIN_SNOW", 3: "SNOW", 4: "SHOWER"}


def _millimeters(value: str | None) -> float:
    if not value or value == "강수없음":
        return 0.0
    match = re.search(r"\d+(?:\.\d+)?", value)
    return float(match.group()) if match else 0.0


async def evaluate_weather(
    client: KmaClient,
    *,
    category: str | None,
    latitude: float,
    longitude: float,
    eta: datetime,
) -> WeatherVerdict:
    """ETA에 가장 가까운 예보 슬롯을 정책 임계값으로 평가한다."""
    if weather_exposure(category) == "INDOOR":
        return WeatherVerdict(available=False, unavailable_reason="INDOOR")
    slots = await client.get_forecast(latitude, longitude)
    if not slots:
        return WeatherVerdict(available=False, unavailable_reason="NO_FORECAST")
    slot = min(slots, key=lambda value: abs(value.forecast_at - eta))
    millimeters = _millimeters(slot.pcp)
    at_risk = (
        (slot.pop or 0) >= PRECIPITATION_PROBABILITY_THRESHOLD
        or millimeters >= PRECIPITATION_MM_PER_HOUR_THRESHOLD
        or (slot.pty or 0) in PRECIPITATION_RISK_TYPES
    )
    return WeatherVerdict(
        available=True,
        precipitation_probability=slot.pop,
        precipitation_mm_per_hour=millimeters,
        precipitation_type=_PTY.get(slot.pty or 0, "NONE"),
        at_risk=at_risk,
    )
