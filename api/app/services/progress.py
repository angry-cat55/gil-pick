"""여행 진행 시작과 진행 현황 조회 서비스."""

from __future__ import annotations

import uuid
import logging
import asyncio
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
from app.models.trip import Trip
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


def apply_manual_transition(
    day: TripDay,
    target: ItineraryItem,
    target_status: str,
    now: datetime,
) -> tuple[list[dict[str, str]], str]:
    """US2·US3 수동 전환과 파생 상태를 메모리의 동일 aggregate에 적용한다.

    Args:
        day: 변경할 날짜와 전체 일정 항목 aggregate.
        target: 사용자가 지정한 일정 항목.
        target_status: PROG-006으로 요청한 목표 상태.
        now: 실제 시각 필드에 기록할 서버 수신 시각.

    Returns:
        감사 기록에 저장할 변경 전후 목록과 transition type.

    Raises:
        AppError: US2·US3에서 허용하지 않는 상태 조합인 경우.

    Notes:
        호출자는 같은 transaction에서 version과 transition 기록을 저장해야 한다.
    """
    allowed = {
        ("EN_ROUTE", "ARRIVED"): "ARRIVE",
        ("PLANNED", "ARRIVED"): "ARRIVE",
        ("ARRIVED", "COMPLETED"): "DEPART",
        ("PLANNED", "SKIPPED"): "SKIP",
        ("EN_ROUTE", "SKIPPED"): "SKIP",
        ("COMPLETED", "ARRIVED"): "UNDO_COMPLETE",
        ("SKIPPED", "PLANNED"): "UNDO_SKIP",
    }
    transition_type = allowed.get((target.status, target_status))
    if transition_type is None:
        raise AppError(
            422,
            "INVALID_STATUS_TRANSITION",
            "현재 상태에서는 요청한 진행 상태로 변경할 수 없습니다.",
        )
    if day.status == "COMPLETED" and transition_type not in {
        "UNDO_COMPLETE",
        "UNDO_SKIP",
    }:
        raise AppError(
            422,
            "INVALID_STATUS_TRANSITION",
            "완료된 날짜에서는 완료 또는 건너뛰기 취소만 할 수 있습니다.",
        )

    ordered = sorted(day.items, key=lambda item: item.sequence)
    affected: list[dict[str, str]] = []

    def change(item: ItineraryItem, status: str) -> None:
        before = item.status
        if before == status:
            return
        item.status = status
        affected.append({
            "itemId": str(item.item_id),
            "beforeStatus": before,
            "afterStatus": status,
        })

    def reset_to_planned(item: ItineraryItem) -> None:
        change(item, "PLANNED")
        item.actual_arrived_at = None
        item.actual_departed_at = None
        item.completed_at = None

    def complete(item: ItineraryItem) -> None:
        change(item, "COMPLETED")
        item.actual_departed_at = now
        item.completed_at = now

    original_status = target.status
    if transition_type == "UNDO_COMPLETE":
        change(target, "ARRIVED")
        target.actual_departed_at = None
        target.completed_at = None
        for item in ordered:
            if item.sequence > target.sequence:
                reset_to_planned(item)
    elif transition_type == "UNDO_SKIP":
        reset_to_planned(target)
        if not any(item.status in {"EN_ROUTE", "ARRIVED"} for item in ordered):
            first_planned = next(
                (item for item in ordered if item.status == "PLANNED"),
                None,
            )
            if first_planned is not None:
                change(first_planned, "EN_ROUTE")
    elif target_status == "ARRIVED":
        for item in ordered:
            if item.item_id == target.item_id:
                continue
            if item.sequence < target.sequence and item.status == "ARRIVED":
                complete(item)
            elif item.sequence < target.sequence and item.status == "EN_ROUTE":
                reset_to_planned(item)
            elif item.sequence > target.sequence:
                reset_to_planned(item)
        change(target, "ARRIVED")
        target.actual_arrived_at = now
        target.actual_departed_at = None
        target.completed_at = None
    elif target_status == "COMPLETED":
        complete(target)
        next_item = next(
            (item for item in ordered if item.sequence > target.sequence and item.status == "PLANNED"),
            None,
        )
        if next_item is not None:
            change(next_item, "EN_ROUTE")
    elif target_status == "SKIPPED":
        change(target, "SKIPPED")
    if target_status == "SKIPPED" and original_status == "EN_ROUTE":
        next_item = next(
            (item for item in ordered if item.sequence > target.sequence and item.status == "PLANNED"),
            None,
        )
        if next_item is not None:
            change(next_item, "EN_ROUTE")

    if transition_type in {"UNDO_COMPLETE", "UNDO_SKIP"}:
        before = day.status
        day.status = "IN_PROGRESS"
        day.completed_at = None
        day.detection_active = True
        if before != day.status:
            affected.append({
                "dayStatusBefore": before,
                "dayStatusAfter": day.status,
            })
    elif not any(item.status in {"PLANNED", "EN_ROUTE"} for item in ordered):
        before = day.status
        day.status = "COMPLETED"
        day.completed_at = now
        day.detection_active = False
        if before != day.status:
            affected.append({
                "dayStatusBefore": before,
                "dayStatusAfter": day.status,
            })

    if sum(item.status == "EN_ROUTE" for item in ordered) > 1 or sum(
        item.status == "ARRIVED" for item in ordered
    ) > 1:
        raise AppError(
            422,
            "INVALID_STATUS_TRANSITION",
            "진행 상태 불변식을 만족하지 않습니다.",
        )
    return affected, transition_type


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

    async def update_item_status(
        self,
        *,
        user_id: uuid.UUID,
        item_id: uuid.UUID,
        target_status: str,
        progress_version: int,
        idempotency_key: uuid.UUID,
    ) -> ProgressData:
        """사용자 소유 항목의 수동 진행 상태와 파생 변경을 원자적으로 저장한다."""
        day = await self.session.scalar(
            select(TripDay)
            .join(ItineraryItem, ItineraryItem.trip_day_id == TripDay.trip_day_id)
            .join(Trip, Trip.trip_id == TripDay.trip_id)
            .options(
                selectinload(TripDay.items),
                selectinload(TripDay.routes),
                selectinload(TripDay.progress_segments),
            )
            .where(ItineraryItem.item_id == item_id, Trip.user_id == user_id)
            .with_for_update()
        )
        if day is None:
            exists = await self.session.scalar(
                select(ItineraryItem.item_id).where(ItineraryItem.item_id == item_id)
            )
            if exists is not None:
                raise AppError(403, "TRIP_FORBIDDEN", "다른 사용자의 일정에는 접근할 수 없습니다.")
            raise AppError(404, "ITINERARY_ITEM_NOT_FOUND", "일정 항목을 찾을 수 없습니다.")
        if day.status == "NOT_STARTED":
            raise AppError(409, "DAY_NOT_STARTED", "시작되지 않은 날짜의 상태는 변경할 수 없습니다.")
        existing = await self.session.scalar(
            select(ProgressTransition).where(
                ProgressTransition.trip_day_id == day.trip_day_id,
                ProgressTransition.idempotency_key == idempotency_key,
            )
        )
        if existing is not None:
            if (
                existing.primary_item_id != item_id
                or existing.request_target_status != target_status
            ):
                raise AppError(
                    409,
                    "IDEMPOTENCY_KEY_CONFLICT",
                    "같은 Idempotency-Key를 다른 상태 전환 요청에 사용할 수 없습니다.",
                )
            if existing.response_snapshot is not None:
                return ProgressData.model_validate(existing.response_snapshot)
            transition_id = existing.transition_id
            trip_id = day.trip_id
            visit_date = day.visit_date
            # 최초 요청의 외부 계산이 day 잠금을 다시 사용할 수 있도록 해제한다.
            await self.session.rollback()
            for _ in range(200):
                await asyncio.sleep(0.05)
                snapshot = await self.session.scalar(
                    select(ProgressTransition.response_snapshot).where(
                        ProgressTransition.transition_id == transition_id
                    )
                )
                if snapshot is not None:
                    return ProgressData.model_validate(snapshot)
            # 외부 계산 제한 시간 이후에도 미완료라면 현재 상태로 복구한다.
            recovered = await self.get_day(trip_id=trip_id, visit_date=visit_date)
            stored_transition = await self.session.get(
                ProgressTransition, transition_id
            )
            if stored_transition is not None:
                stored_transition.response_snapshot = recovered.model_dump(
                    mode="json", by_alias=True
                )
            await self.session.commit()
            return recovered
        if day.progress_version != progress_version:
            raise AppError(409, "VERSION_CONFLICT", "진행 버전이 일치하지 않습니다.")

        target = next(item for item in day.items if item.item_id == item_id)
        skip_segment = None
        if target_status == "SKIPPED" and self.calculator is not None:
            ordered = sorted(day.items, key=lambda item: item.sequence)
            previous = next(
                (
                    item for item in reversed(ordered)
                    if item.sequence < target.sequence
                    and item.status != "SKIPPED"
                ),
                None,
            )
            following = next(
                (
                    item for item in ordered
                    if item.sequence > target.sequence
                    and item.status in {"PLANNED", "EN_ROUTE", "ARRIVED"}
                ),
                None,
            )
            if previous is not None and following is not None and previous.transport_mode_to_next:
                key = (previous.item_id, following.item_id)
                known = key in _route_durations(day) or key in _progress_segments(day)
                if not known:
                    point = cast(Place.location, Geometry(geometry_type="POINT", srid=4326))
                    rows = (
                        await self.session.execute(
                            select(ItineraryItem.item_id, func.ST_X(point), func.ST_Y(point))
                            .join(Place, Place.place_id == ItineraryItem.place_id)
                            .where(ItineraryItem.item_id.in_([previous.item_id, following.item_id]))
                        )
                    ).all()
                    coordinates = {
                        row[0]: Coordinate(longitude=row[1], latitude=row[2])
                        for row in rows
                    }
                    skip_segment = (
                        previous,
                        following,
                        ClientTransportMode(previous.transport_mode_to_next),
                        coordinates[previous.item_id],
                        coordinates[following.item_id],
                    )
        now = datetime.now(UTC)
        affected, transition_type = apply_manual_transition(
            day, target, target_status, now
        )
        day.progress_version += 1
        transition = ProgressTransition(
            trip_day_id=day.trip_day_id,
            primary_item_id=target.item_id,
            transition_type=transition_type,
            status="CONFIRMED",
            source="MANUAL",
            affected_items=affected,
            detected_at=now,
            confirmed_at=now,
            schedule_version_before=day.schedule_version,
            schedule_version_after=day.schedule_version,
            progress_version_after=day.progress_version,
            idempotency_key=idempotency_key,
            request_target_status=target_status,
        )
        self.session.add(transition)
        await recalculate_day_eta(self.session, day.trip_day_id)
        await self._attach_start_location(day)
        response = self._to_data(day)
        if skip_segment is None:
            transition.response_snapshot = response.model_dump(mode="json", by_alias=True)
        await self.session.commit()
        logger.info({
            "operation": "UPDATE_ITEM_PROGRESS_STATUS",
            "request_id": request_id_context.get(),
            "trip_id": str(day.trip_id),
            "visit_date": day.visit_date.isoformat(),
            "transition_type": transition_type,
            "result": "SUCCESS",
        })
        if skip_segment is not None and self.session_factory is not None:
            previous, following, mode, origin, destination = skip_segment
            try:
                calculated = await self.calculator.calculate_single_segment(
                    origin=origin,
                    destination=destination,
                    transport_mode=mode,
                    overall_deadline_seconds=8.0,
                )
            except RouteProviderError as error:
                logger.info({
                    "operation": "CALCULATE_PROGRESS_SEGMENT",
                    "request_id": request_id_context.get(),
                    "trip_id": str(day.trip_id),
                    "visit_date": day.visit_date.isoformat(),
                    "result_code": error.code,
                })
                async with transaction_session(self.session_factory) as result_session:
                    stored_transition = await result_session.get(
                        ProgressTransition, transition.transition_id
                    )
                    if stored_transition is not None:
                        stored_transition.response_snapshot = response.model_dump(
                            mode="json", by_alias=True
                        )
            else:
                async with transaction_session(self.session_factory) as segment_session:
                    statement = pg_insert(ProgressSegment).values(
                        trip_day_id=day.trip_day_id,
                        from_item_id=previous.item_id,
                        to_item_id=following.item_id,
                        transport_mode=mode.value,
                        provider=calculated.provider.value,
                        duration_seconds=calculated.duration_seconds,
                        distance_meters=calculated.distance_meters,
                        computed_at=datetime.now(UTC),
                    ).on_conflict_do_update(
                        index_elements=[
                            ProgressSegment.trip_day_id,
                            ProgressSegment.from_item_id,
                            ProgressSegment.to_item_id,
                        ],
                        index_where=ProgressSegment.from_item_id.is_not(None),
                        set_={
                            "transport_mode": mode.value,
                            "provider": calculated.provider.value,
                            "duration_seconds": calculated.duration_seconds,
                            "distance_meters": calculated.distance_meters,
                            "computed_at": datetime.now(UTC),
                        },
                    )
                    await segment_session.execute(statement)
                    await recalculate_day_eta(segment_session, day.trip_day_id)
                    result = await ProgressService(segment_session).get_day(
                        trip_id=day.trip_id,
                        visit_date=day.visit_date,
                    )
                    stored_transition = await segment_session.get(
                        ProgressTransition, transition.transition_id
                    )
                    if stored_transition is not None:
                        stored_transition.response_snapshot = result.model_dump(
                            mode="json", by_alias=True
                        )
                    return result
        return response

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


__all__ = ["ProgressService", "apply_manual_transition"]
