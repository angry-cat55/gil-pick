"""여행 진행 장소의 도착 예정 시각을 계산한다."""

from __future__ import annotations

import uuid
from collections.abc import Mapping, Sequence
from datetime import datetime, timedelta

from app.models.itinerary import ItineraryItem
from app.models.itinerary import TripDay
from app.models.progress import ProgressSegment
from app.models.route import Route as RouteModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

SegmentKey = tuple[uuid.UUID | None, uuid.UUID]


def recalculate_eta(
    items: Sequence[ItineraryItem],
    *,
    actual_started_at: datetime | None,
    route_durations: Mapping[SegmentKey, int],
    progress_durations: Mapping[SegmentKey, int],
    has_start_location: bool,
) -> None:
    """남은 일정 항목의 ETA를 명세 규칙으로 갱신한다.

    Args:
        items: 날짜에 속한 항목. sequence 순서와 무관하게 정렬한다.
        actual_started_at: 서버가 저장한 당일 시작 시각.
        route_durations: F005 활성 경로의 인접 구간 이동시간(초).
        progress_durations: 진행 중 별도 계산한 구간 이동시간(초).
        has_start_location: 시작 위치가 유효하게 저장되었는지 여부.

    Notes:
        완료·건너뜀 항목과 도착 항목 자신의 저장된 ETA는 보존한다. 계획 경로가
        우선이며, 없는 구간만 progress_segments 값을 사용한다. 두 값이 모두
        없으면 해당 항목부터 ETA를 알 수 없는 상태로 둔다.
    """
    ordered = sorted(items, key=lambda item: item.sequence)
    remaining = [
        item for item in ordered if item.status in {"PLANNED", "EN_ROUTE", "ARRIVED"}
    ]
    previous = next(
        (item for item in reversed(ordered) if item.status == "COMPLETED"),
        None,
    )

    for item in remaining:
        inbound = _duration(
            previous,
            item,
            route_durations=route_durations,
            progress_durations=progress_durations,
            has_start_location=has_start_location,
        )
        base = _base_time(previous, actual_started_at=actual_started_at)

        if item.status != "ARRIVED":
            eta = (
                base + timedelta(seconds=inbound)
                if base is not None and inbound is not None
                else None
            )
            item.estimated_arrival_at = eta
            item.estimated_departure_at = (
                eta + timedelta(minutes=item.planned_stay_minutes)
                if eta is not None
                else None
            )
        previous = item


async def recalculate_day_eta(session: AsyncSession, trip_day_id: uuid.UUID) -> None:
    """활성 F005 경로와 진행 구간을 읽어 한 transaction에서 ETA를 갱신한다."""
    day = await session.scalar(
        select(TripDay).options(selectinload(TripDay.items)).where(TripDay.trip_day_id == trip_day_id)
    )
    if day is None or day.actual_started_at is None:
        return
    route = await session.scalar(select(RouteModel).where(
        RouteModel.trip_day_id == trip_day_id,
        RouteModel.schedule_version == day.schedule_version,
        RouteModel.is_active.is_(True), RouteModel.status == "READY",
    ))
    route_durations: dict[SegmentKey, int] = {}
    if route and route.route_payload:
        for segment in route.route_payload.get("segments", []):
            from_id = segment.get("fromItemId")
            route_durations[(uuid.UUID(from_id) if from_id else None, uuid.UUID(segment["toItemId"]))] = int(segment["durationSeconds"])
    rows = (await session.scalars(select(ProgressSegment).where(ProgressSegment.trip_day_id == trip_day_id))).all()
    progress_durations = {(row.from_item_id, row.to_item_id): row.duration_seconds for row in rows}
    recalculate_eta(day.items, actual_started_at=day.actual_started_at,
                    route_durations=route_durations, progress_durations=progress_durations,
                    has_start_location=day.start_location is not None)
    await session.flush()


def _base_time(
    previous: ItineraryItem | None, *, actual_started_at: datetime | None
) -> datetime | None:
    if previous is None:
        return actual_started_at
    if previous.status == "COMPLETED":
        return previous.actual_departed_at
    if previous.status == "ARRIVED":
        return (
            previous.actual_arrived_at
            + timedelta(minutes=previous.planned_stay_minutes)
            if previous.actual_arrived_at is not None
            else None
        )
    return (
        previous.estimated_arrival_at
        + timedelta(minutes=previous.planned_stay_minutes)
        if previous.estimated_arrival_at is not None
        else None
    )


def _duration(
    previous: ItineraryItem | None,
    item: ItineraryItem,
    *,
    route_durations: Mapping[SegmentKey, int],
    progress_durations: Mapping[SegmentKey, int],
    has_start_location: bool,
) -> int | None:
    key = (previous.item_id if previous is not None else None, item.item_id)
    if previous is None and not has_start_location:
        return 0
    if key in route_durations:
        return route_durations[key]
    return progress_durations.get(key)


__all__ = ["recalculate_day_eta", "recalculate_eta"]
