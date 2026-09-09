"""F008 변수별 판정과 종합 점수 규칙을 검증한다."""

from datetime import UTC, datetime, timedelta

import pytest

from app.clients.kma import ForecastSlot
from app.clients.seoul_citydata import CongestionLevel, PopulationData, PopulationForecast
from app.schemas.detection import CongestionVerdict, OperatingHoursVerdict, WeatherVerdict
from app.services.detection.congestion import evaluate_congestion
from app.services.detection.operating_hours import evaluate_operating_hours
from app.services.detection.operating_hours_source import BusinessStatus, OperatingHours
from app.services.detection.scoring import score_variables
from app.services.detection.weather import evaluate_weather


ETA = datetime(2026, 9, 8, 4, 30, tzinfo=UTC)


class _Kma:
    async def get_forecast(self, latitude: float, longitude: float):
        return [
            ForecastSlot(ETA - timedelta(hours=1), pop=10, pcp="강수없음", pty=0),
            ForecastSlot(ETA + timedelta(minutes=20), pop=70, pcp="1.0mm", pty=1),
        ]


class _AreaSession:
    async def execute(self, statement, params):
        class Result:
            def mappings(self): return self
            def first(self): return {"area_code": "FIXTURE-001", "distance_meters": 100.0}
        return Result()


class _Seoul:
    async def get_population(self, area_code: str):
        return PopulationData(
            CongestionLevel.NORMAL,
            [PopulationForecast(ETA + timedelta(minutes=10), CongestionLevel.SLIGHTLY_CROWDED)],
        )


class _Hours:
    def __init__(self, value: OperatingHours): self.value = value
    async def get(self, place_id: str, eta: datetime): return self.value


@pytest.mark.asyncio
async def test_weather_uses_nearest_eta_slot_and_policy_threshold() -> None:
    verdict = await evaluate_weather(_Kma(), category="NATURE", latitude=37.5, longitude=127.0, eta=ETA)
    assert verdict == WeatherVerdict(
        available=True,
        precipitation_probability=70,
        precipitation_mm_per_hour=1.0,
        precipitation_type="RAIN",
        at_risk=True,
    )


@pytest.mark.asyncio
async def test_indoor_weather_is_unavailable_without_provider_call() -> None:
    verdict = await evaluate_weather(_Kma(), category="CAFE", latitude=37.5, longitude=127.0, eta=ETA)
    assert verdict == WeatherVerdict(available=False, unavailable_reason="INDOOR")


@pytest.mark.asyncio
async def test_congestion_uses_eta_forecast_and_category_sensitivity() -> None:
    verdict = await evaluate_congestion(_AreaSession(), _Seoul(), category="CAFE", latitude=37.5752, longitude=126.9768, eta=ETA)
    assert verdict == CongestionVerdict(
        available=True,
        level="SLIGHTLY_CROWDED",
        sensitivity="HIGH",
        crowded=True,
    )


@pytest.mark.asyncio
async def test_operating_hours_blocks_eta_at_close() -> None:
    verdict = await evaluate_operating_hours(
        _Hours(OperatingHours(BusinessStatus.OPERATIONAL, ETA, is_open=True)),
        place_id="google-place",
        eta=ETA,
    )
    assert verdict == OperatingHoursVerdict(
        available=True,
        closes_at=ETA,
        closing_soon=False,
        visit_blocked=True,
        temp_closed=False,
    )


def test_scoring_renormalizes_available_weights_and_breaks_tie_by_priority() -> None:
    result = score_variables(
        congestion=CongestionVerdict(available=False, unavailable_reason="NOT_IN_SUPPORT_AREA"),
        weather=WeatherVerdict(available=True, at_risk=True),
        operating_hours=OperatingHoursVerdict(
            available=True, closing_soon=True, visit_blocked=False, temp_closed=False
        ),
    )
    assert result.score == pytest.approx(2 / 3)
    assert result.total_risk_score == 67
    assert result.primary_type == "OPERATING_HOURS"


@pytest.mark.parametrize(
    ("hours", "expected_score", "expected_primary"),
    [(False, 0.0, None), (True, 1.0, "OPERATING_HOURS")],
)
def test_scoring_handles_zero_and_single_available_variable_boundaries(
    hours: bool, expected_score: float, expected_primary: str | None
) -> None:
    result = score_variables(
        congestion=CongestionVerdict(available=False, unavailable_reason="TIMEOUT"),
        weather=WeatherVerdict(available=False, unavailable_reason="TIMEOUT"),
        operating_hours=OperatingHoursVerdict(
            available=hours,
            unavailable_reason=None if hours else "TIMEOUT",
            closing_soon=False if hours else None,
            visit_blocked=hours or None,
            temp_closed=False if hours else None,
        ),
    )

    assert result.score == expected_score
    assert result.total_risk_score == round(expected_score * 100)
    assert result.primary_type == expected_primary
