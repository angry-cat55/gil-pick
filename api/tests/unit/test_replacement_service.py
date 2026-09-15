"""REPL-001 경로 비교의 마감 시간 조회 규칙을 검증한다(#584)."""

from __future__ import annotations

from datetime import UTC, datetime

import pytest

from app.models.detection import Detection
from app.services.detection.operating_hours_source import BusinessStatus, OperatingHours
from app.services.replacement import ReplacementService, _PlaceSnapshot, _snapshot_closes_at

SECRET = "replacement-service-test-secret-value"
ETA = datetime(2026, 9, 16, 12, 0, tzinfo=UTC)
CLOSES_AT = datetime(2026, 9, 16, 22, 49, tzinfo=UTC)


class _FakeOperatingHoursSource:
    def __init__(self, hours: OperatingHours) -> None:
        self.hours = hours
        self.calls: list[str] = []

    async def get(self, place_id: str, eta: datetime) -> OperatingHours:
        self.calls.append(place_id)
        return self.hours


def _service(source: _FakeOperatingHoursSource) -> ReplacementService:
    return ReplacementService(
        session=object(),
        calculator=object(),
        place_service=object(),
        operating_hours_source=source,
        candidate_secret=SECRET,
    )


def _place(*, public_id: str, google_place_id: str | None) -> _PlaceSnapshot:
    return _PlaceSnapshot(
        database_id=None, public_id=public_id, name="장소", category="OTHER",
        latitude=37.5, longitude=127.0, google_place_id=google_place_id,
    )


@pytest.mark.asyncio
async def test_closing_time_uses_matched_google_place_id_for_tourapi_place() -> None:
    """TourAPI 장소도 병합된 google_place_id가 있으면 마감 시간을 조회한다(#584)."""
    source = _FakeOperatingHoursSource(
        OperatingHours(status=BusinessStatus.OPERATIONAL, closes_at=CLOSES_AT, is_open=True)
    )
    place = _place(public_id="tourapi:126508", google_place_id="g-matched")

    closes_at = await _service(source)._closing_time(place, ETA)

    assert closes_at == CLOSES_AT
    assert source.calls == ["g-matched"]


@pytest.mark.asyncio
async def test_closing_time_returns_none_without_google_match() -> None:
    """Google 매칭이 없는 TourAPI 장소는 지금처럼 null이다."""
    source = _FakeOperatingHoursSource(
        OperatingHours(status=BusinessStatus.OPERATIONAL, closes_at=CLOSES_AT, is_open=True)
    )
    place = _place(public_id="tourapi:126508", google_place_id=None)

    closes_at = await _service(source)._closing_time(place, ETA)

    assert closes_at is None
    assert source.calls == []


@pytest.mark.asyncio
async def test_closing_time_still_works_for_pure_google_place() -> None:
    """순수 Google 장소는 기존처럼 동작한다(회귀 방지)."""
    source = _FakeOperatingHoursSource(
        OperatingHours(status=BusinessStatus.OPERATIONAL, closes_at=CLOSES_AT, is_open=True)
    )
    place = _place(public_id="google:g-matched", google_place_id="g-matched")

    closes_at = await _service(source)._closing_time(place, ETA)

    assert closes_at == CLOSES_AT
    assert source.calls == ["g-matched"]


@pytest.mark.asyncio
async def test_closing_time_returns_none_without_eta() -> None:
    source = _FakeOperatingHoursSource(
        OperatingHours(status=BusinessStatus.OPERATIONAL, closes_at=CLOSES_AT, is_open=True)
    )
    place = _place(public_id="tourapi:126508", google_place_id="g-matched")

    closes_at = await _service(source)._closing_time(place, None)

    assert closes_at is None
    assert source.calls == []


def _detection(evaluation_snapshot: dict) -> Detection:
    return Detection(
        trip_day_id=None, item_id=None, primary_type="OPERATING_HOURS", status="ACTIVE",
        eta=ETA, reason="영업이 곧 끝나요", evaluation_snapshot=evaluation_snapshot,
        fingerprint="fp",
    )


def test_snapshot_closes_at_reuses_detection_evaluation_value() -> None:
    """감지 화면과 경로 비교의 기존 마감 시간이 같은 값을 쓰도록 snapshot을 재사용한다."""
    detection = _detection(
        {"variables": {"operatingHours": {"closesAt": CLOSES_AT.isoformat()}}}
    )

    assert _snapshot_closes_at(detection) == CLOSES_AT


@pytest.mark.parametrize(
    "evaluation_snapshot",
    [
        {"variables": {}},
        {"variables": {"operatingHours": {}}},
        {"variables": {"operatingHours": {"closesAt": None}}},
        {"variables": {"operatingHours": {"closesAt": "not-a-datetime"}}},
        {},
    ],
)
def test_snapshot_closes_at_returns_none_when_missing_or_invalid(
    evaluation_snapshot: dict,
) -> None:
    assert _snapshot_closes_at(_detection(evaluation_snapshot)) is None
