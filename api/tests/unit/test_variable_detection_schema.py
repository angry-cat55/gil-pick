import uuid
from datetime import UTC, datetime

from app.schemas.detection import (
    CongestionVerdict,
    DetectionDetail,
    DetectionErrorCode,
    DetectionStatus,
    DetectionType,
    OperatingHoursVerdict,
    UnavailableReason,
    VariableVerdicts,
    WeatherVerdict,
)


def test_detection_enums_match_contract() -> None:
    assert {v.value for v in DetectionStatus} == {"ACTIVE", "RESOLVED", "DISMISSED", "INVALIDATED"}
    assert {v.value for v in DetectionType} == {"CONGESTION", "WEATHER", "OPERATING_HOURS"}
    assert {v.value for v in UnavailableReason} == {"NO_FORECAST", "NOT_IN_SUPPORT_AREA", "HOURS_UNKNOWN", "INDOOR", "TIMEOUT"}
    assert len(DetectionErrorCode) == 6


def test_detection_detail_serializes_camel_case() -> None:
    now = datetime.now(UTC)
    detail = DetectionDetail(
        detection_id=uuid.uuid4(), trip_id=uuid.uuid4(), item_id=uuid.uuid4(),
        place_name="북촌", primary_type="WEATHER", status="ACTIVE", eta=now,
        total_risk_score=80, reason="비가 와요",
        variables=VariableVerdicts(
            congestion=CongestionVerdict(available=False, unavailable_reason="NOT_IN_SUPPORT_AREA"),
            weather=WeatherVerdict(available=True, precipitation_probability=80,
                precipitation_mm_per_hour=1.0, precipitation_type="RAIN", at_risk=True),
            operating_hours=OperatingHoursVerdict(available=False, unavailable_reason="HOURS_UNKNOWN"),
        ), read=False, created_at=now, last_evaluated_at=now,
    )
    payload = detail.model_dump(mode="json", by_alias=True)
    assert payload["totalRiskScore"] == 80
    assert payload["variables"]["weather"]["precipitationType"] == "RAIN"
