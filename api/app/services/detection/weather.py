"""ETA 시점 단기예보를 날씨 위험 판정으로 변환한다."""

from __future__ import annotations

import logging
import math
import re
from datetime import datetime, timedelta

from app.clients.kma import KmaClient
from app.schemas.detection import WeatherVerdict
from app.services.detection.policy import (
    PRECIPITATION_MM_PER_HOUR_THRESHOLD,
    PRECIPITATION_PROBABILITY_THRESHOLD,
    PRECIPITATION_RISK_TYPES,
    WEATHER_FORECAST_TOLERANCE_MINUTES,
    weather_exposure,
)

_PTY = {0: "NONE", 1: "RAIN", 2: "RAIN_SNOW", 3: "SNOW", 4: "SHOWER"}
logger = logging.getLogger(__name__)


def _millimeters(value: str | None) -> float | None:
    if value == "강수없음":
        return 0.0
    if not value:
        return None
    match = re.search(r"\d+(?:\.\d+)?", value)
    if not match:
        return None
    amount = float(match.group())
    return math.nextafter(amount, 0.0) if "미만" in value else amount


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
    if abs(slot.forecast_at - eta) > timedelta(
        minutes=WEATHER_FORECAST_TOLERANCE_MINUTES
    ):
        logger.info(
            "ETA 허용 범위 안의 기상 예보가 없어 날씨 변수를 제외합니다.",
            extra={"reason": "STALE", "eta": eta.isoformat()},
        )
        return WeatherVerdict(available=False, unavailable_reason="NO_FORECAST")
    if (
        slot.pop is None
        or not 0 <= slot.pop <= 100
        or slot.pcp is None
        or slot.pty not in _PTY
        or _millimeters(slot.pcp) is None
    ):
        logger.info(
            "기상 예보 필수 필드가 불완전해 날씨 변수를 제외합니다.",
            extra={"reason": "INCOMPLETE", "eta": eta.isoformat()},
        )
        return WeatherVerdict(available=False, unavailable_reason="NO_FORECAST")
    millimeters = _millimeters(slot.pcp)
    assert millimeters is not None
    at_risk = (
        slot.pop >= PRECIPITATION_PROBABILITY_THRESHOLD
        or millimeters >= PRECIPITATION_MM_PER_HOUR_THRESHOLD
        or slot.pty in PRECIPITATION_RISK_TYPES
    )
    return WeatherVerdict(
        available=True,
        precipitation_probability=slot.pop,
        precipitation_mm_per_hour=millimeters,
        precipitation_type=_PTY.get(slot.pty, "NONE"),
        at_risk=at_risk,
    )
