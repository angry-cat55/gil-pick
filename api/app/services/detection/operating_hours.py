"""Google Places 운영시간을 ETA 방문 가능성으로 평가한다."""

from __future__ import annotations

from datetime import datetime, timedelta

from app.schemas.detection import OperatingHoursVerdict
from app.services.detection.operating_hours_source import BusinessStatus, OperatingHoursSource
from app.services.detection.policy import CLOSING_SOON_MINUTES


async def evaluate_operating_hours(
    source: OperatingHoursSource, *, place_id: str, eta: datetime
) -> OperatingHoursVerdict:
    """휴업 상태와 ETA 대비 폐점 시각을 판정한다."""
    hours = await source.get(place_id, eta)
    closed = hours.status in {
        BusinessStatus.CLOSED_TEMPORARILY,
        BusinessStatus.CLOSED_PERMANENTLY,
    }
    if not hours.known:
        return OperatingHoursVerdict(
            available=False, unavailable_reason="HOURS_UNKNOWN"
        )
    blocked = closed or hours.is_open is False or (hours.closes_at is not None and eta >= hours.closes_at)
    closing_soon = bool(
        not blocked
        and hours.closes_at is not None
        and hours.closes_at - timedelta(minutes=CLOSING_SOON_MINUTES) <= eta
    )
    return OperatingHoursVerdict(
        available=True,
        closes_at=hours.closes_at,
        closing_soon=closing_soon,
        visit_blocked=blocked,
        temp_closed=hours.status == BusinessStatus.CLOSED_TEMPORARILY,
    )
