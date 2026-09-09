"""F010 승인 재검증 규칙."""

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import pytest

from app.api.errors import AppError
from app.services.replacement import (
    PREVIEW_TTL_MINUTES,
    UNDO_WINDOW_SECONDS,
    _validate_approval,
)


def _valid(now: datetime):
    preview = SimpleNamespace(
        status="PENDING",
        expires_at=now + timedelta(minutes=PREVIEW_TTL_MINUTES),
        schedule_version=1,
        alternative_place_id="alternative",
        comparison={"closesAt": {"before": None, "after": None}},
    )
    day = SimpleNamespace(schedule_version=1)
    item = SimpleNamespace(
        status="PLANNED", actual_arrived_at=None, completed_at=None,
    )
    detection = SimpleNamespace(status="ACTIVE")
    return preview, day, item, detection


@pytest.mark.parametrize(
    ("change", "error_code"),
    [
        (lambda p, d, i, x, now: setattr(p, "status", "SUPERSEDED"), "PREVIEW_SUPERSEDED"),
        (lambda p, d, i, x, now: setattr(p, "status", "REJECTED"), "PREVIEW_REJECTED"),
        (lambda p, d, i, x, now: setattr(p, "status", "APPROVED"), "ALREADY_APPROVED"),
        (lambda p, d, i, x, now: setattr(p, "expires_at", now - timedelta(seconds=1)), "PREVIEW_EXPIRED"),
        (lambda p, d, i, x, now: setattr(p, "expires_at", now), "PREVIEW_EXPIRED"),
        (lambda p, d, i, x, now: setattr(d, "schedule_version", 2), "VERSION_CONFLICT"),
        (lambda p, d, i, x, now: setattr(i, "status", "ARRIVED"), "ITEM_ALREADY_VISITED"),
        (lambda p, d, i, x, now: setattr(x, "status", "DISMISSED"), "DETECTION_NOT_ACTIVE"),
        (
            lambda p, d, i, x, now: setattr(
                p,
                "comparison",
                {"closesAt": {"before": None, "after": (now - timedelta(seconds=1)).isoformat()}},
            ),
            "ALTERNATIVE_UNAVAILABLE",
        ),
    ],
)
def test_approval_revalidation_distinguishes_failure_reason(change, error_code: str) -> None:
    now = datetime.now(UTC)
    preview, day, item, detection = _valid(now)
    change(preview, day, item, detection, now)

    with pytest.raises(AppError, match=error_code):
        _validate_approval(preview, day, item, detection, now)


def test_approval_revalidation_accepts_a_valid_preview() -> None:
    now = datetime.now(UTC)
    _validate_approval(*_valid(now), now)
    assert UNDO_WINDOW_SECONDS == 30
