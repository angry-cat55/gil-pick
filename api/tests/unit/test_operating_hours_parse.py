"""Google Places 폐점 시각 파생 경계를 검증한다."""

from datetime import datetime, timedelta, timezone

import pytest

from app.services.detection.operating_hours_source import OperatingHoursSource
from tests.unit.test_kma_client import _settings


class _Places:
    async def get_place(self, _: str) -> dict[str, object]:
        return {
            "businessStatus": "OPERATIONAL",
            "utcOffsetMinutes": 540,
            "regularOpeningHours": {
                "periods": [
                    {
                        "open": {"day": 1, "hour": 18},
                        "close": {"day": 2, "hour": 2},
                    }
                ]
            },
        }


@pytest.mark.asyncio
async def test_close_time_matches_weekday_across_midnight() -> None:
    kst = timezone(timedelta(hours=9))
    result = await OperatingHoursSource(_settings(), _Places()).get(
        "place", datetime(2026, 9, 8, 1, tzinfo=kst)
    )

    assert result.is_open is True
    assert result.closes_at == datetime(2026, 9, 8, 2, tzinfo=kst)
