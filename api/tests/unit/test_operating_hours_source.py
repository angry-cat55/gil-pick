from datetime import datetime, timedelta, timezone

import pytest

from app.services.detection.operating_hours import evaluate_operating_hours
from app.services.detection.operating_hours_source import (
    BusinessStatus,
    OperatingHoursSource,
)
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


def test_operating_hours_parses_text_search_place_payload() -> None:
    payload = {"businessStatus":"OPERATIONAL","utcOffsetMinutes":540,
        "regularOpeningHours":{"periods":[{"open":{"day":2,"hour":9},"close":{"day":2,"hour":18,"minute":30}}]}}
    eta = datetime(2026, 9, 8, 17, 0, tzinfo=timezone(timedelta(hours=9)))
    result = OperatingHoursSource(_settings(), StubPlaces()).parse(payload, eta)
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


def _current_period(
    open_hour: int,
    close_hour: int | None,
    *,
    open_day: int = 8,
    close_day: int = 8,
) -> dict:
    period = {
        "open": {
            "date": {"year": 2026, "month": 9, "day": open_day},
            "day": 2 if open_day == 8 else 3,
            "hour": open_hour,
        }
    }
    if close_hour is not None:
        period["close"] = {
            "date": {"year": 2026, "month": 9, "day": close_day},
            "day": 2 if close_day == 8 else 3,
            "hour": close_hour,
        }
    return period


def _special_hours_payload(periods: list[dict]) -> dict:
    return {
        "businessStatus": "OPERATIONAL",
        "utcOffsetMinutes": 540,
        "currentOpeningHours": {"periods": periods},
        "regularOpeningHours": {
            "periods": [
                {
                    "open": {"day": 2, "hour": 9},
                    "close": {"day": 2, "hour": 18},
                }
            ]
        },
    }


def test_current_hours_override_regular_hours_for_early_close() -> None:
    eta = datetime(2026, 9, 8, 16, tzinfo=timezone(timedelta(hours=9)))

    result = OperatingHoursSource(_settings(), StubPlaces()).parse(
        _special_hours_payload([_current_period(9, 15)]), eta
    )

    assert result.closes_at == datetime(
        2026, 9, 8, 15, tzinfo=timezone(timedelta(hours=9))
    )
    assert result.is_open is False


def test_current_hours_override_regular_hours_for_extended_opening() -> None:
    eta = datetime(2026, 9, 8, 19, tzinfo=timezone(timedelta(hours=9)))

    result = OperatingHoursSource(_settings(), StubPlaces()).parse(
        _special_hours_payload([_current_period(9, 21)]), eta
    )

    assert result.closes_at == datetime(
        2026, 9, 8, 21, tzinfo=timezone(timedelta(hours=9))
    )
    assert result.is_open is True


def test_empty_current_periods_mean_closed() -> None:
    eta = datetime(2026, 9, 8, 12, tzinfo=timezone(timedelta(hours=9)))

    result = OperatingHoursSource(_settings(), StubPlaces()).parse(
        _special_hours_payload([]), eta
    )

    assert result.known is True
    assert result.is_open is False
    assert result.closes_at is None


@pytest.mark.asyncio
async def test_temporary_closure_is_blocked() -> None:
    eta = datetime(2026, 9, 8, 12, tzinfo=timezone(timedelta(hours=9)))
    payload = _special_hours_payload([])
    payload["businessStatus"] = "CLOSED_TEMPORARILY"
    source = OperatingHoursSource(_settings(), StubPlaces(payload))

    verdict = await evaluate_operating_hours(source, place_id="place", eta=eta)

    assert verdict.available is True
    assert verdict.visit_blocked is True
    assert verdict.temp_closed is True


def test_current_hours_without_close_mean_open_24_hours() -> None:
    eta = datetime(2026, 9, 8, 12, tzinfo=timezone(timedelta(hours=9)))

    result = OperatingHoursSource(_settings(), StubPlaces()).parse(
        _special_hours_payload([_current_period(0, None)]), eta
    )

    assert result.known is True
    assert result.is_open is True
    assert result.closes_at is None


def test_current_hours_preserve_cross_midnight_close_date() -> None:
    eta = datetime(2026, 9, 9, 1, tzinfo=timezone(timedelta(hours=9)))

    result = OperatingHoursSource(_settings(), StubPlaces()).parse(
        _special_hours_payload(
            [_current_period(18, 2, open_day=8, close_day=9)]
        ),
        eta,
    )

    assert result.is_open is True
    assert result.closes_at == datetime(
        2026, 9, 9, 2, tzinfo=timezone(timedelta(hours=9))
    )


@pytest.mark.asyncio
async def test_special_hours_flow_from_google_adapter_to_evaluator() -> None:
    eta = datetime(2026, 9, 8, 16, tzinfo=timezone(timedelta(hours=9)))
    source = OperatingHoursSource(
        _settings(), StubPlaces(_special_hours_payload([_current_period(9, 15)]))
    )

    verdict = await evaluate_operating_hours(source, place_id="place", eta=eta)

    assert verdict.available is True
    assert verdict.closes_at == datetime(
        2026, 9, 8, 15, tzinfo=timezone(timedelta(hours=9))
    )
    assert verdict.visit_blocked is True
