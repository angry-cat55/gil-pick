"""여행 변수 감지 정책의 조정 가능한 기본값을 검증한다."""

from app.services.detection.policy import (
    CLOSING_SOON_MINUTES,
    CONGESTION_RISK_LEVEL,
    CONGESTION_SEVERITY,
    OPERATING_HOURS_SEVERITY,
    PRECIPITATION_MM_PER_HOUR_THRESHOLD,
    PRECIPITATION_PROBABILITY_THRESHOLD,
    PRECIPITATION_RISK_TYPES,
    VARIABLE_WEIGHTS,
    congestion_sensitivity,
    weather_exposure,
)


def test_weather_policy_uses_documented_thresholds() -> None:
    assert PRECIPITATION_PROBABILITY_THRESHOLD == 70
    assert PRECIPITATION_MM_PER_HOUR_THRESHOLD == 1.0
    assert PRECIPITATION_RISK_TYPES == frozenset({1, 2, 3, 4})
    assert CLOSING_SOON_MINUTES == 30


def test_category_policy_maps_sensitivity_and_exposure() -> None:
    assert congestion_sensitivity("SHOPPING") == "HIGH"
    assert congestion_sensitivity("CAFE") == "HIGH"
    assert congestion_sensitivity("FOOD") == "HIGH"
    assert congestion_sensitivity("NATURE") == "MEDIUM"
    assert congestion_sensitivity(None) == "MEDIUM"

    assert weather_exposure("NATURE") == "OUTDOOR"
    assert weather_exposure("SHOPPING") == "INDOOR"
    assert weather_exposure("CAFE") == "INDOOR"
    assert weather_exposure("FOOD") == "INDOOR"
    assert weather_exposure("HISTORY_CULTURE") == "UNKNOWN"
    assert weather_exposure(None) == "UNKNOWN"


def test_risk_policy_keeps_weights_and_severity_in_one_module() -> None:
    assert VARIABLE_WEIGHTS == {
        "OPERATING_HOURS": 0.5,
        "WEATHER": 0.25,
        "CONGESTION": 0.25,
    }
    assert sum(VARIABLE_WEIGHTS.values()) == 1.0
    assert CONGESTION_RISK_LEVEL == {
        "HIGH": "SLIGHTLY_CROWDED",
        "MEDIUM": "CROWDED",
    }
    assert OPERATING_HOURS_SEVERITY == {
        "VISIT_BLOCKED": 1.0,
        "CLOSING_SOON": 0.5,
    }
    assert CONGESTION_SEVERITY == {
        "CROWDED": 1.0,
        "SLIGHTLY_CROWDED": 0.6,
    }
