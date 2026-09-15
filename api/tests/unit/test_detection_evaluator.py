"""변수 감지 재생성 정책을 검증한다."""

import pytest

from app.services.detection.evaluator import _should_redetect_dismissed


@pytest.mark.parametrize(
    ("dismissed_primary", "dismissed_blocked", "current_primary", "current_blocked", "expected"),
    [
        ("CONGESTION", False, "CONGESTION", False, False),
        ("CONGESTION", False, "WEATHER", False, True),
        ("OPERATING_HOURS", False, "OPERATING_HOURS", True, True),
        ("OPERATING_HOURS", True, "OPERATING_HOURS", True, False),
    ],
)
def test_dismissed_detection_is_recreated_only_for_a_new_or_worse_risk(
    dismissed_primary: str,
    dismissed_blocked: bool,
    current_primary: str,
    current_blocked: bool,
    expected: bool,
) -> None:
    snapshot = {
        "variables": {"operatingHours": {"visitBlocked": dismissed_blocked}}
    }

    assert _should_redetect_dismissed(
        dismissed_primary_type=dismissed_primary,
        dismissed_snapshot=snapshot,
        current_primary_type=current_primary,
        current_visit_blocked=current_blocked,
    ) is expected
