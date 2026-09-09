"""기상청 격자 known-value와 PCP 범주 파싱을 검증한다."""

import pytest

from app.clients.kma import latitude_longitude_to_grid
from app.services.detection.weather import _millimeters


def test_seoul_city_hall_grid_known_value() -> None:
    assert latitude_longitude_to_grid(37.5665, 126.9780) == (60, 127)


@pytest.mark.parametrize(
    ("value", "expected"),
    [("강수없음", 0.0), ("1.0mm", 1.0), ("30.0~50.0mm", 30.0)],
)
def test_pcp_category_string_extracts_first_numeric_amount(
    value: str, expected: float
) -> None:
    assert _millimeters(value) == expected
