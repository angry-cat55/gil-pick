"""사용 가능한 변수만으로 종합 위험 점수를 산출한다."""

from __future__ import annotations

from dataclasses import dataclass

from app.schemas.detection import CongestionVerdict, DetectionType, OperatingHoursVerdict, WeatherVerdict
from app.services.detection.policy import CONGESTION_SEVERITY, OPERATING_HOURS_SEVERITY, VARIABLE_WEIGHTS


@dataclass(frozen=True)
class RiskScore:
    score: float
    total_risk_score: int
    primary_type: DetectionType | None


def score_variables(
    *,
    congestion: CongestionVerdict,
    weather: WeatherVerdict,
    operating_hours: OperatingHoursVerdict,
) -> RiskScore:
    """가용 변수 가중치를 재정규화하고 최대 기여 변수를 고른다."""
    severity = {
        "OPERATING_HOURS": (
            OPERATING_HOURS_SEVERITY["VISIT_BLOCKED"]
            if operating_hours.visit_blocked
            else OPERATING_HOURS_SEVERITY["CLOSING_SOON"]
            if operating_hours.closing_soon
            else 0.0
        ),
        "WEATHER": 1.0 if weather.at_risk else 0.0,
        "CONGESTION": CONGESTION_SEVERITY.get(congestion.level or "", 0.0)
        if congestion.crowded
        else 0.0,
    }
    available = {
        "OPERATING_HOURS": operating_hours.available,
        "WEATHER": weather.available,
        "CONGESTION": congestion.available,
    }
    denominator = sum(VARIABLE_WEIGHTS[key] for key, value in available.items() if value)
    if not denominator:
        return RiskScore(0.0, 0, None)
    contributions = {
        key: VARIABLE_WEIGHTS[key] / denominator * severity[key]
        for key, value in available.items()
        if value
    }
    score = sum(contributions.values())
    priority = {"OPERATING_HOURS": 2, "WEATHER": 1, "CONGESTION": 0}
    primary = max(contributions, key=lambda key: (contributions[key], priority[key])) if score else None
    return RiskScore(score, round(score * 100), DetectionType(primary) if primary else None)
