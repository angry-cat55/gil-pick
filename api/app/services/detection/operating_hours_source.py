"""Google Places 운영시간 응답을 평가 가능한 최소 구조로 변환한다."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from enum import StrEnum

from app.clients.google_places import GooglePlacesClient
from app.core.config import Settings


class BusinessStatus(StrEnum):
    OPERATIONAL = "OPERATIONAL"
    CLOSED_TEMPORARILY = "CLOSED_TEMPORARILY"
    CLOSED_PERMANENTLY = "CLOSED_PERMANENTLY"
    UNKNOWN = "UNKNOWN"


@dataclass(frozen=True)
class OperatingHours:
    status: BusinessStatus = BusinessStatus.UNKNOWN
    closes_at: datetime | None = None
    utc_offset_minutes: int | None = None
    is_open: bool | None = None

    @property
    def known(self) -> bool:
        return self.status in {BusinessStatus.CLOSED_TEMPORARILY, BusinessStatus.CLOSED_PERMANENTLY} or self.is_open is not None


class OperatingHoursSource:
    def __init__(self, settings: Settings, client: GooglePlacesClient | None = None) -> None:
        self.settings = settings
        self._owns_client = client is None
        self.client = client or GooglePlacesClient(settings)

    async def aclose(self) -> None:
        """내부에서 만든 Google Places client를 닫는다."""
        if self._owns_client:
            await self.client.client.aclose()

    async def get(self, place_id: str, eta: datetime) -> OperatingHours:
        """ETA가 속한 영업 구간의 폐점 시각과 영업 상태를 반환한다."""
        if not self.settings.google_places_api_key.get_secret_value(): return OperatingHours()
        return self.parse(await self.client.get_place(place_id), eta)

    def parse(self, payload: dict, eta: datetime) -> OperatingHours:
        try:
            status = BusinessStatus(payload.get("businessStatus", "UNKNOWN"))
            offset = int(payload.get("utcOffsetMinutes", 540))
            local_eta = eta.astimezone(timezone(timedelta(minutes=offset)))
            current = payload.get("currentOpeningHours")
            use_current = isinstance(current, dict) and "periods" in current
            hours = current if use_current else payload.get("regularOpeningHours", {})
            periods = hours.get("periods") if isinstance(hours, dict) else None
            if periods == [] and use_current:
                return OperatingHours(status, None, offset, False)
            if not periods:
                return OperatingHours(status, None, offset)

            candidates: list[tuple[datetime, datetime]] = []
            eta_day = (local_eta.weekday() + 1) % 7
            for period in periods:
                opened = period["open"]
                closed = period.get("close")
                start = self._dated_point(opened, local_eta)
                if closed is None:
                    if start is None or start <= local_eta:
                        return OperatingHours(status, None, offset, True)
                    continue

                end = self._dated_point(closed, local_eta)
                if start is not None and end is not None:
                    candidates.append((start, end))
                    continue

                open_day, close_day = int(opened["day"]), int(closed["day"])
                start_date = local_eta.date() - timedelta(
                    days=(eta_day - open_day) % 7
                )
                start = datetime.combine(
                    start_date, datetime.min.time(), local_eta.tzinfo
                ).replace(
                    hour=int(opened.get("hour", 0)),
                    minute=int(opened.get("minute", 0)),
                )
                end = datetime.combine(
                    start_date + timedelta(days=(close_day - open_day) % 7),
                    datetime.min.time(),
                    local_eta.tzinfo,
                ).replace(
                    hour=int(closed.get("hour", 0)),
                    minute=int(closed.get("minute", 0)),
                )
                if end <= start:
                    end += timedelta(days=7)
                candidates.append((start, end))

            containing = [
                end for start, end in candidates if start <= local_eta <= end
            ]
            same_day = [
                end for start, end in candidates if start.date() == local_eta.date()
            ]
            closes_at = min(containing or same_day) if containing or same_day else None
            is_open = (
                bool(containing)
                if containing or same_day
                else False if use_current else None
            )
            return OperatingHours(status, closes_at, offset, is_open)
        except (AttributeError, KeyError, TypeError, ValueError, OverflowError):
            return OperatingHours()

    @staticmethod
    def _dated_point(point: dict, local_eta: datetime) -> datetime | None:
        value = point.get("date")
        if not isinstance(value, dict):
            return None
        local_date = date(
            int(value["year"]), int(value["month"]), int(value["day"])
        )
        return datetime.combine(
            local_date, datetime.min.time(), local_eta.tzinfo
        ).replace(
            hour=int(point.get("hour", 0)), minute=int(point.get("minute", 0))
        )
