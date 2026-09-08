from datetime import datetime, timedelta, timezone

import pytest

from app.services.detection.operating_hours_source import BusinessStatus, OperatingHoursSource
from tests.unit.test_kma_client import _settings


class StubPlaces:
    def __init__(self, payload=None, error=None): self.payload, self.error, self.calls = payload, error, 0
    async def get_place(self, _: str):
        self.calls += 1
        if self.error: raise self.error
        return self.payload


@pytest.mark.asyncio
async def test_operating_hours_extracts_matching_weekday_close() -> None:
    places = StubPlaces({"businessStatus":"OPERATIONAL","utcOffsetMinutes":540,
        "regularOpeningHours":{"periods":[{"open":{"day":2,"hour":9,"minute":0},"close":{"day":2,"hour":18,"minute":30}}]}})
    eta = datetime(2026, 9, 8, 17, 0, tzinfo=timezone(timedelta(hours=9)))
    result = await OperatingHoursSource(_settings(), places).get("place", eta)
    assert result.status is BusinessStatus.OPERATIONAL
    assert result.closes_at == datetime(2026, 9, 8, 18, 30, tzinfo=timezone(timedelta(hours=9)))
    assert result.utc_offset_minutes == 540


@pytest.mark.asyncio
async def test_operating_hours_missing_key_or_periods_is_unknown() -> None:
    source = OperatingHoursSource(_settings(), StubPlaces({"businessStatus":"OPERATIONAL"}))
    eta = datetime(2026, 9, 8, 17, 0, tzinfo=timezone(timedelta(hours=9)))
    assert (await source.get("place", eta)).known is False
    empty_key_settings = _settings().model_copy(update={"google_places_api_key": type(_settings().google_places_api_key)("")})
    assert (await OperatingHoursSource(empty_key_settings, StubPlaces({})).get("place", eta)).known is False


@pytest.mark.asyncio
async def test_operating_hours_preserves_cross_midnight_close() -> None:
    places = StubPlaces({"businessStatus":"OPERATIONAL","utcOffsetMinutes":540,
        "regularOpeningHours":{"periods":[{"open":{"day":1,"hour":18},"close":{"day":2,"hour":2}}]}})
    eta = datetime(2026, 9, 7, 23, 0, tzinfo=timezone(timedelta(hours=9)))
    result = await OperatingHoursSource(_settings(), places).get("place", eta)
    assert result.closes_at == datetime(2026, 9, 8, 2, 0, tzinfo=timezone(timedelta(hours=9)))


@pytest.mark.asyncio
async def test_operating_hours_malformed_payload_is_unknown() -> None:
    eta = datetime(2026, 9, 8, 17, 0, tzinfo=timezone(timedelta(hours=9)))
    source = OperatingHoursSource(_settings(), StubPlaces({"regularOpeningHours": []}))
    assert (await source.get("place", eta)).known is False


@pytest.mark.asyncio
async def test_operating_hours_before_open_is_not_in_business_period() -> None:
    places = StubPlaces({"businessStatus":"OPERATIONAL","utcOffsetMinutes":540,
        "regularOpeningHours":{"periods":[{"open":{"day":2,"hour":10},"close":{"day":2,"hour":18}}]}})
    eta = datetime(2026, 9, 8, 9, 0, tzinfo=timezone(timedelta(hours=9)))
    result = await OperatingHoursSource(_settings(), places).get("place", eta)
    assert result.known is True
    assert result.is_open is False
