"""날짜별 일정 조회와 통째 저장 규칙을 처리한다."""

from __future__ import annotations

import logging
import uuid
from datetime import UTC, date, datetime

from geoalchemy2.elements import WKTElement
from sqlalchemy import delete, select, update
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.api.errors import AppError
from app.core.logging import request_id_context
from app.models.itinerary import ItineraryItem as ItemModel
from app.models.itinerary import Place, TripDay
from app.schemas.itinerary import (
    DayItinerary,
    ItineraryItem,
    ItineraryPlaceSummary,
    SaveDayItineraryRequest,
    SaveItem,
)
from app.schemas.route import RouteStatus

logger = logging.getLogger("gilpick.itinerary")


class ItineraryService:
    """한 날짜의 일정 조회·저장을 요청 transaction 안에서 수행한다."""

    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def get_day(
        self, *, trip_id: uuid.UUID, visit_date: date, start_date: date
    ) -> DayItinerary:
        """저장된 날짜를 조회하고, 없으면 DB 변경 없이 version 0을 반환한다."""
        day = await self._load_day(trip_id=trip_id, visit_date=visit_date)
        if day is None:
            return _empty_day(visit_date, start_date)
        return _to_schema(day)

    async def save_day(
        self,
        *,
        trip_id: uuid.UUID,
        visit_date: date,
        start_date: date,
        payload: SaveDayItineraryRequest,
        idempotency_key: uuid.UUID,
    ) -> tuple[DayItinerary, bool]:
        """일정을 검증해 멱등하게 저장하고 최초 생성 여부를 반환한다.

        Raises:
            AppError: 일정 규칙 또는 schedule version이 맞지 않는 경우.

        Notes:
            호출자의 transaction 안에서 날짜, 장소, 항목을 원자적으로 변경한다.
        """
        _validate_items(payload.items)
        day = await self._load_day(trip_id=trip_id, visit_date=visit_date)
        created = day is None
        if day is None:
            if payload.version != 0:
                raise _version_conflict()
            day = TripDay(
                trip_day_id=uuid.uuid5(trip_id, visit_date.isoformat()),
                trip_id=trip_id,
                visit_date=visit_date,
                day_number=(visit_date - start_date).days + 1,
                schedule_version=1,
                items=[],
            )
            self.session.add(day)
            await self.session.flush()

        await self._validate_existing_items(day, payload.items)
        places = await self._upsert_places(payload.items)
        desired = [
            _desired_item(item, day.trip_day_id, idempotency_key, places[item.place_id])
            for item in payload.items
        ]
        current = [_item_state(item) for item in day.items]
        desired_state = [_item_state(item) for item in desired]

        same_retry = payload.version == 0 and any(i.item_id is None for i in payload.items)
        if not created and payload.version != day.schedule_version:
            if not (same_retry and desired_state == current):
                raise _version_conflict()
        if not created and desired_state == current:
            return _to_schema(day), False

        version_before = 0 if created else day.schedule_version
        if not created:
            result = await self.session.execute(
                update(TripDay)
                .where(
                    TripDay.trip_day_id == day.trip_day_id,
                    TripDay.schedule_version == payload.version,
                )
                .values(schedule_version=TripDay.schedule_version + 1)
                .returning(TripDay.schedule_version)
            )
            version = result.scalar_one_or_none()
            if version is None:
                raise _version_conflict()
            day.schedule_version = version

        current_by_id = {item.item_id: item for item in day.items}
        unchanged_ids = {
            item.item_id
            for item in desired
            if item.item_id in current_by_id
            and _item_state(item) == _item_state(current_by_id[item.item_id])
        }
        delete_statement = delete(ItemModel).where(ItemModel.trip_day_id == day.trip_day_id)
        if unchanged_ids:
            delete_statement = delete_statement.where(ItemModel.item_id.not_in(unchanged_ids))
        await self.session.execute(delete_statement)
        await self.session.flush()
        self.session.add_all([item for item in desired if item.item_id not in unchanged_ids])
        await self.session.flush()
        day = await self._load_day(trip_id=trip_id, visit_date=visit_date, refresh=True)
        if day is None:  # pragma: no cover - transaction invariant
            raise RuntimeError("저장한 일정을 다시 조회할 수 없습니다.")
        logger.info(
            {
                "operation": "SAVE_DAY_ITINERARY",
                "result": "SUCCESS",
                "request_id": request_id_context.get(),
                "saved_at": datetime.now(UTC).isoformat(),
                "trip_id": str(trip_id),
                "visit_date": visit_date.isoformat(),
                "version_before": version_before,
                "version_after": day.schedule_version,
                "item_count": len(desired),
            }
        )
        return _to_schema(day), created

    async def _load_day(
        self, *, trip_id: uuid.UUID, visit_date: date, refresh: bool = False
    ) -> TripDay | None:
        statement = (
            select(TripDay)
            .where(TripDay.trip_id == trip_id, TripDay.visit_date == visit_date)
            .options(selectinload(TripDay.items).selectinload(ItemModel.place))
        )
        if refresh:
            statement = statement.execution_options(populate_existing=True)
        return await self.session.scalar(statement)

    async def _upsert_places(self, items: list[SaveItem]) -> dict[str, uuid.UUID]:
        result: dict[str, uuid.UUID] = {}
        for item in items:
            if item.place is None:
                stored = await self._find_place(item.place_id)
                if stored is None:
                    raise _invalid("items.place", "기존 장소 정보를 찾을 수 없습니다.")
                result[item.place_id] = stored
                continue
            provider, provider_id = item.place_id.split(":", 1)
            snapshot = item.place
            category = snapshot.tour_api_category
            values = {
                "name": snapshot.name,
                "category": snapshot.category.value,
                "tour_category_1": category.large if category else None,
                "tour_category_2": category.middle if category else None,
                "tour_category_3": category.small if category else None,
                "address": snapshot.address,
                "location": WKTElement(
                    f"POINT({snapshot.longitude} {snapshot.latitude})", srid=4326
                ),
                "image_url": str(snapshot.image_url) if snapshot.image_url else None,
            }
            column = Place.tour_content_id if provider == "tourapi" else Place.google_place_id
            statement = (
                pg_insert(Place)
                .values(**values, **{column.key: provider_id})
                .on_conflict_do_update(
                    index_elements=[column],
                    index_where=column.is_not(None),
                    set_=values,
                )
                .returning(Place.place_id)
            )
            result[item.place_id] = (await self.session.execute(statement)).scalar_one()
        return result

    async def _validate_existing_items(
        self, day: TripDay, items: list[SaveItem]
    ) -> None:
        existing_ids = {item.item_id for item in items if item.item_id is not None}
        owned_ids = {item.item_id for item in day.items}
        if existing_ids <= owned_ids:
            return
        invalid_id = next(iter(existing_ids - owned_ids))
        raise _invalid("items.itemId", f"현재 날짜에 속하지 않은 항목입니다: {invalid_id}")

    async def _find_place(self, place_id: str) -> uuid.UUID | None:
        provider, provider_id = place_id.split(":", 1)
        column = Place.tour_content_id if provider == "tourapi" else Place.google_place_id
        return await self.session.scalar(select(Place.place_id).where(column == provider_id))


def _validate_items(items: list[SaveItem]) -> None:
    violations: list[dict[str, object]] = []
    if len(items) > 10:
        violations.append({"field": "items", "itemIndex": None, "reason": "하루 최대 10곳입니다."})
    expected = list(range(1, len(items) + 1))
    if [item.sequence for item in items] != expected:
        violations.append({"field": "sequence", "itemIndex": None, "reason": "순서는 1부터 연속되어야 합니다."})
    for index, item in enumerate(items):
        if not 30 <= item.planned_stay_minutes <= 360 or item.planned_stay_minutes % 30:
            violations.append({"field": "plannedStayMinutes", "itemIndex": index, "reason": "30~360분 범위의 30분 단위여야 합니다."})
        should_be_null = index == len(items) - 1
        if should_be_null != (item.transport_mode_to_next is None):
            violations.append({"field": "transportModeToNext", "itemIndex": index, "reason": "마지막 장소만 이동 수단이 없어야 합니다."})
        if item.item_id is None and item.place is None:
            violations.append({"field": "place", "itemIndex": index, "reason": "새 항목에는 장소 스냅샷이 필요합니다."})
    if violations:
        raise AppError(422, "INVALID_ITINERARY", "일정 값이 올바르지 않습니다.", details={"violations": violations})


def _invalid(field: str, reason: str) -> AppError:
    return AppError(422, "INVALID_ITINERARY", "일정 값이 올바르지 않습니다.", details={"violations": [{"field": field, "itemIndex": None, "reason": reason}]})


def _version_conflict() -> AppError:
    return AppError(409, "VERSION_CONFLICT", "다른 곳에서 일정이 변경되었습니다.")


def _desired_item(
    item: SaveItem,
    trip_day_id: uuid.UUID,
    idempotency_key: uuid.UUID,
    place_id: uuid.UUID,
) -> ItemModel:
    return ItemModel(
        item_id=item.item_id or uuid.uuid5(trip_day_id, f"{idempotency_key}:{item.sequence}"),
        trip_day_id=trip_day_id,
        place_id=place_id,
        sequence=item.sequence,
        planned_stay_minutes=item.planned_stay_minutes,
        stay_source=item.stay_source.value,
        transport_mode_to_next=(item.transport_mode_to_next.value if item.transport_mode_to_next else None),
        status="PLANNED",
    )


def _item_state(item: ItemModel) -> tuple[object, ...]:
    return (
        item.item_id,
        item.place_id,
        item.sequence,
        item.planned_stay_minutes,
        item.stay_source,
        item.transport_mode_to_next,
    )


def _empty_day(visit_date: date, start_date: date) -> DayItinerary:
    return DayItinerary(
        date=visit_date,
        dayNumber=(visit_date - start_date).days + 1,
        version=0,
        routeStatus=RouteStatus.NOT_CALCULATED,
        items=[],
        route=None,
    )


def _to_schema(day: TripDay) -> DayItinerary:
    items = [
        ItineraryItem(
            itemId=item.item_id,
            place=ItineraryPlaceSummary(
                placeId=(f"tourapi:{item.place.tour_content_id}" if item.place.tour_content_id else f"google:{item.place.google_place_id}"),
                name=item.place.name,
                category=item.place.category,
                address=item.place.address,
                imageUrl=item.place.image_url,
            ),
            sequence=item.sequence,
            plannedStayMinutes=item.planned_stay_minutes,
            staySource=item.stay_source,
            transportModeToNext=item.transport_mode_to_next,
            status=item.status,
        )
        for item in sorted(day.items, key=lambda value: value.sequence)
    ]
    return DayItinerary(
        date=day.visit_date,
        dayNumber=day.day_number,
        version=day.schedule_version,
        routeStatus=RouteStatus.NOT_CALCULATED,
        items=items,
        route=None,
    )
