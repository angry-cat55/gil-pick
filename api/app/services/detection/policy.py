"""여행 변수 감지에서 조정 가능한 정책값을 한곳에 둔다."""

from __future__ import annotations

from typing import Literal

PRECIPITATION_PROBABILITY_THRESHOLD = 70
PRECIPITATION_MM_PER_HOUR_THRESHOLD = 1.0
PRECIPITATION_RISK_TYPES = frozenset({1, 2, 3, 4})
CLOSING_SOON_MINUTES = 30

VARIABLE_WEIGHTS = {
    "OPERATING_HOURS": 0.5,
    "WEATHER": 0.25,
    "CONGESTION": 0.25,
}
OPERATING_HOURS_SEVERITY = {"VISIT_BLOCKED": 1.0, "CLOSING_SOON": 0.5}
CONGESTION_SEVERITY = {"CROWDED": 1.0, "SLIGHTLY_CROWDED": 0.6}
WEATHER_SEVERITY = {"AT_RISK": 1.0}
CONGESTION_RISK_LEVEL = {"HIGH": "SLIGHTLY_CROWDED", "MEDIUM": "CROWDED"}

_HIGH_SENSITIVITY_CATEGORIES = frozenset({"SHOPPING", "CAFE", "FOOD"})
_INDOOR_CATEGORIES = frozenset({"SHOPPING", "CAFE", "FOOD"})


def congestion_sensitivity(category: str | None) -> Literal["HIGH", "MEDIUM"]:
    """장소 카테고리를 혼잡 민감도로 변환한다."""
    return "HIGH" if category in _HIGH_SENSITIVITY_CATEGORIES else "MEDIUM"


def weather_exposure(
    category: str | None,
) -> Literal["OUTDOOR", "INDOOR", "UNKNOWN"]:
    """장소 카테고리를 날씨 노출도로 변환한다."""
    if category == "NATURE":
        return "OUTDOOR"
    if category in _INDOOR_CATEGORIES:
        return "INDOOR"
    return "UNKNOWN"
