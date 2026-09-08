"""여행 진행 시작과 진행 현황 조회 서비스."""

from __future__ import annotations

import uuid
import logging
from datetime import UTC, date, datetime, timedelta, timezone

from geoalchemy2 import Geometry, WKTElement
from sqlalchemy import cast, func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from sqlalchemy.orm import selectinload
from sqlalchemy.dialects.postgresql import insert as pg_insert

from app.api.errors import AppError
from app.core.logging import request_id_context
from app.models.itinerary import ItineraryItem, Place, TripDay
from app.models.progress import ProgressSegment, ProgressTransition
from app.clients.route_provider import Coordinate, RouteProviderError, TransportMode as ClientTransportMode
from app.schemas.progress import (
    DayStatus,
    InboundTravel,
    InboundTravelSource,
    ProgressData,
    ProgressItem,
    StartDayProgressRequest,
    StartLocation,
)
from app.schemas.route import TransportMode
from app.services.eta import recalculate_day_eta, recalculate_eta
from app.services.route import RouteCalculationService
from app.db import transaction_session

logger = logging.getLogger("gilpick.progress")


class ProgressService:
    """진행 API의 원자적 상태 변경과 응답 파생을 담당한다."""

    def __init__(
        self,
        session: AsyncSession,
        calculator: RouteCalculationService | None = None,
        session_factory: async_sessionmaker[AsyncSession] | None = None,
    ) -> None:
        self.session = session
        self.calculator = calculator
        self.session_factory = session_factory

    async def get_day(self, *, trip_id: uuid.UUID, visit_date: date) -> ProgressData:
        day = await self._load_day(trip_id, visit_date)
        return self._to_data(day)

    async def start_day(
        self,
        *,
        trip_id: uuid.UUID,
        visit_date: date,
        payload: StartDayProgressRequest,
        idempotency_key: uuid.UUID,
    ) -> ProgressData:
        day = await self.session.scalar(
            select(TripDay)
            .options(selectinload(TripDay.items), selectinload(TripDay.routes), selectinload(TripDay.progress_segments))
            .where(TripDay.trip_id == trip_id, TripDay.visit_date == visit_date)
            .with_for_update()
        )
        if day is None:
            raise AppError(404, "TRIP_NOT_FOUND", "여행 날짜를 찾을 수 없습니다.")
        if visit_date != datetime.now(UTC).astimezone(timezone(timedelta(hours=9))).date():
            raise AppError(409, "DAY_NOT_TODAY", "오늘 날짜만 시작할 수 있습니다.")
        existing = await self.session.scalar(select(ProgressTransition).where(
            ProgressTransition.trip_day_id == day.trip_day_id,
            ProgressTransition.idempotency_key == idempotency_key,
        ))
        if existing is not None:
            await self._attach_start_location(day)
            return self._to_data(day)
        items = sorted(day.items, key=lambda item: item.sequence)
        if not items:
            raise AppError(422, "DAY_EMPTY", "장소가 없는 날짜는 시작할 수 없습니다.")
        if day.status != "NOT_STARTED":
            await self._attach_start_location(day)
            return self._to_data(day)
        if payload.progress_version != day.progress_version:
            raise AppError(409, "VERSION_CONFLICT", "진행 버전이 일치하지 않습니다.")

        now = datetime.now(UTC)
        location = payload.current_location
        if location is not None:
            occurred = location.occurred_at.astimezone(UTC)
            if location.accuracy_meters > 100 or abs((now - occurred).total_seconds()) > 120:
                location = None
        day.status = "IN_PROGRESS"
        day.actual_started_at = now
        day.detection_active = True
        day.progress_version += 1
        if location is not None:
            day.start_location = WKTElement(
                f"POINT({location.longitude} {location.latitude})", srid=4326
            )
            day._progress_start_location = (location.latitude, location.longitude)
            day.start_accuracy_meters = location.accuracy_meters
            day.start_captured_at = location.occurred_at
        first = next((item for item in items if item.status == "PLANNED"), None)
        if first is not None:
            first.status = "EN_ROUTE"
        destination = None
        if location is not None and first is not None and self.calculator is not None:
            point = cast(Place.location, Geometry(geometry_type="POINT", srid=4326))
            destination = (await self.session.execute(
                select(func.ST_X(point), func.ST_Y(point)).join(ItineraryItem, ItineraryItem.place_id == Place.place_id).where(ItineraryItem.item_id == first.item_id)
            )).one()
        transition = ProgressTransition(
            trip_day_id=day.trip_day_id,
            primary_item_id=first.item_id if first else None,
            transition_type="START",
            status="CONFIRMED",
            source="MANUAL",
            affected_items=(
                [{"itemId": str(first.item_id), "beforeStatus": "PLANNED", "afterStatus": "EN_ROUTE"}]
                if first is not None else []
            ) + [{"dayStatusBefore": "NOT_STARTED", "dayStatusAfter": "IN_PROGRESS"}],
            detected_at=now,
            confirmed_at=now,
            schedule_version_before=day.schedule_version,
            schedule_version_after=day.schedule_version,
            progress_version_after=day.progress_version,
            idempotency_key=idempotency_key,
        )
        self.session.add(transition)
        await self.session.flush()
        recalculate_eta(
            items,
            actual_started_at=now,
            route_durations={key: value[0] for key, value in _route_durations(day).items()},
            progress_durations={},
            has_start_location=location is not None,
        )
        await self.session.commit()
        logger.info({
            "operation": "START_DAY_PROGRESS",
            "request_id": request_id_context.get(),
            "trip_id": str(trip_id),
            "visit_date": visit_date.isoformat(),
            "transition_type": "START",
            "result": "SUCCESS",
        })
        if destination is not None and location is not None and first is not None:
            try:
                segment = await self.calculator.calculate_single_segment(
                    origin=Coordinate(longitude=location.longitude, latitude=location.latitude),
                    destination=Coordinate(longitude=destination[0], latitude=destination[1]),
                    transport_mode=ClientTransportMode.WALK,
                    overall_deadline_seconds=8.0,
                )
            except RouteProviderError as error:
                segment = None
                logger.info({
                    "operation": "CALCULATE_PROGRESS_SEGMENT",
                    "request_id": request_id_context.get(),
                    "trip_id": str(trip_id),
                    "visit_date": visit_date.isoformat(),
                    "result_code": error.code,
                })
            else:
                logger.info({
                    "operation": "CALCULATE_PROGRESS_SEGMENT",
                    "request_id": request_id_context.get(),
                    "trip_id": str(trip_id),
                    "visit_date": visit_date.isoformat(),
                    "result_code": "SUCCESS",
                })
            if segment is not None and self.session_factory is not None:
                async with transaction_session(self.session_factory) as segment_session:
                    statement = pg_insert(ProgressSegment).values(
                        progress_segment_id=uuid.uuid4(), trip_day_id=day.trip_day_id,
                        from_item_id=None, to_item_id=first.item_id,
                        transport_mode="WALK", provider=segment.provider.value,
                        duration_seconds=segment.duration_seconds,
                        distance_meters=segment.distance_meters, computed_at=datetime.now(UTC),
                    ).on_conflict_do_update(
                        index_elements=[ProgressSegment.trip_day_id, ProgressSegment.to_item_id],
                        index_where=ProgressSegment.from_item_id.is_(None),
                        set_={"provider": segment.provider.value, "duration_seconds": segment.duration_seconds,
                              "distance_meters": segment.distance_meters, "computed_at": datetime.now(UTC)},
                    )
                    await segment_session.execute(statement)
                    await recalculate_day_eta(segment_session, day.trip_day_id)
                async with self.session_factory() as result_session:
                    return await ProgressService(result_session).get_day(
                        trip_id=trip_id, visit_date=visit_date
                    )
        return self._to_data(day)

    async def _load_day(self, trip_id: uuid.UUID, visit_date: date) -> TripDay:
        day = await self.session.scalar(
            select(TripDay)
            .options(selectinload(TripDay.items), selectinload(TripDay.routes), selectinload(TripDay.progress_segments))
            .where(TripDay.trip_id == trip_id, TripDay.visit_date == visit_date)
        )
        if day is None:
            raise AppError(404, "TRIP_NOT_FOUND", "여행 날짜를 찾을 수 없습니다.")
        await self._attach_start_location(day)
        return day

    async def _attach_start_location(self, day: TripDay) -> None:
        if day.start_location is None:
            return
        point = cast(TripDay.start_location, Geometry(geometry_type="POINT", srid=4326))
        coords = (await self.session.execute(
            select(func.ST_X(point), func.ST_Y(point)).where(TripDay.trip_day_id == day.trip_day_id)
        )).one()
        day._progress_start_location = (coords[1], coords[0])

    def _to_data(self, day: TripDay) -> ProgressData:
        items = sorted(day.items, key=lambda item: item.sequence)
        routes = _route_durations(day)
        computed = _progress_segments(day)
        remaining_keys = _remaining_inbound_keys(items)
        progress_items = []
        for index, item in enumerate(items):
            inbound = None
            if item.item_id in remaining_keys:
                key = remaining_keys[item.item_id]
            elif index == 0:
                key = (None, item.item_id)
            else:
                key = (items[index - 1].item_id, item.item_id)
            value = routes.get(key)
            source = InboundTravelSource.PLANNED_ROUTE
            if value is None:
                value = computed.get(key)
                source = InboundTravelSource.COMPUTED
            if value is not None:
                duration, distance, mode = value
                inbound = InboundTravel(
                    from_item_id=key[0], transport_mode=TransportMode(mode),
                    duration_seconds=duration, distance_meters=distance,
                    source=source,
                )
            progress_items.append(ProgressItem(
                item_id=item.item_id, sequence=item.sequence, status=item.status,
                estimated_arrival_at=item.estimated_arrival_at,
                estimated_departure_at=item.estimated_departure_at,
                actual_arrived_at=item.actual_arrived_at,
                completed_at=item.completed_at, inbound_travel=inbound,
            ))
        arrived = next((item.item_id for item in items if item.status == "ARRIVED"), None)
        en_route = next((item.item_id for item in items if item.status == "EN_ROUTE"), None)
        next_item = en_route or next((item.item_id for item in items if item.status == "PLANNED"), None)
        return ProgressData(
            trip_id=day.trip_id, date=day.visit_date,
            day_status=DayStatus(day.status), progress_version=day.progress_version,
            schedule_version=day.schedule_version, actual_started_at=day.actual_started_at,
            completed_at=day.completed_at,
            start_location=(StartLocation(latitude=day._progress_start_location[0], longitude=day._progress_start_location[1])
                            if hasattr(day, "_progress_start_location") else None),
            current_item_id=arrived, next_item_id=next_item, items=progress_items,
        )


def _route_durations(day: TripDay) -> dict[tuple[uuid.UUID | None, uuid.UUID], tuple[int, int, str]]:
    active = next((r for r in day.routes if r.is_active and r.status == "READY" and r.schedule_version == day.schedule_version), None)
    if active is None or not active.route_payload:
        return {}
    result = {}
    for segment in active.route_payload.get("segments", []):
        from_id = segment.get("fromItemId")
        result[(uuid.UUID(from_id) if from_id else None, uuid.UUID(segment["toItemId"]))] = (
            int(segment["durationSeconds"]), int(segment["distanceMeters"]), segment["transportMode"]
        )
    return result


def _progress_segments(day: TripDay) -> dict[tuple[uuid.UUID | None, uuid.UUID], tuple[int, int, str]]:
    return {(segment.from_item_id, segment.to_item_id): (
        segment.duration_seconds, segment.distance_meters, segment.transport_mode
    ) for segment in day.progress_segments}


def _remaining_inbound_keys(
    items: list[ItineraryItem],
) -> dict[uuid.UUID, tuple[uuid.UUID | None, uuid.UUID]]:
    previous = next(
        (item for item in reversed(items) if item.status == "COMPLETED"),
        None,
    )
    result = {}
    for item in items:
        if item.status not in {"PLANNED", "EN_ROUTE", "ARRIVED"}:
            continue
        result[item.item_id] = (
            previous.item_id if previous is not None else None,
            item.item_id,
        )
        previous = item
    return result


__all__ = ["ProgressService"]
