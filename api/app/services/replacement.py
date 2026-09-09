"""변경 경로 미리보기 생성과 폐기 서비스."""

from __future__ import annotations

import hashlib
import json
import uuid
from dataclasses import dataclass, replace
from datetime import UTC, date, datetime, timedelta
from typing import Callable

from geoalchemy2 import Geometry, WKTElement
from sqlalchemy import cast, func, select, update
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.errors import AppError
from app.clients.route_provider import Coordinate, TransportMode as ClientTransportMode
from app.models.detection import Detection
from app.models.itinerary import ItineraryItem, Place, TripDay
from app.models.progress import ProgressSegment
from app.models.replacement import RoutePreview as RoutePreviewModel
from app.models.route import Route as RouteModel
from app.models.trip import Trip
from app.schemas.place import PlaceDetail
from app.schemas.replacement import (
    ComparisonValue,
    CreatePreviewRequest,
    PreviewComparison,
    ReplacedPlace,
    RoutePreview,
)
from app.schemas.route import RouteStatus
from app.services.alternatives.candidate_token import verify_candidate_token
from app.services.detection.operating_hours import evaluate_operating_hours
from app.services.detection.operating_hours_source import OperatingHoursSource
from app.services.place import PlaceService
from app.services.route import RouteCalculationService, RouteItemSnapshot, RouteSnapshot

PREVIEW_TTL_MINUTES = 5
UNDO_WINDOW_SECONDS = 30


@dataclass(frozen=True, slots=True)
class _PlaceSnapshot:
    database_id: uuid.UUID | None
    public_id: str
    name: str
    category: str
    latitude: float
    longitude: float
    detail: PlaceDetail | None = None


@dataclass(frozen=True, slots=True)
class _ItemSnapshot:
    item_id: uuid.UUID
    sequence: int
    status: str
    planned_stay_minutes: int
    transport_mode_to_next: str | None
    estimated_arrival_at: datetime | None
    actual_arrived_at: datetime | None
    actual_departed_at: datetime | None
    completed_at: datetime | None
    place: _PlaceSnapshot


@dataclass(frozen=True, slots=True)
class _PreviewContext:
    trip_id: uuid.UUID
    trip_day_id: uuid.UUID
    visit_date: date
    day_status: str
    schedule_version: int
    actual_started_at: datetime | None
    has_start_location: bool
    detection_reason: str
    detection_status: str
    target: _ItemSnapshot
    items: tuple[_ItemSnapshot, ...]
    current_duration: int | None
    current_distance: int | None
    progress_durations: dict[tuple[uuid.UUID | None, uuid.UUID], int]


class ReplacementService:
    """실제 일정과 분리된 대체 경로를 계산하고 저장한다."""

    def __init__(
        self, session: AsyncSession, *, calculator: RouteCalculationService,
        place_service: PlaceService, operating_hours_source: OperatingHoursSource,
        candidate_secret: str, now: Callable[[], datetime] | None = None,
    ) -> None:
        self.session = session
        self.calculator = calculator
        self.place_service = place_service
        self.operating_hours_source = operating_hours_source
        self.candidate_secret = candidate_secret
        self.now = now or (lambda: datetime.now(UTC))

    async def create_preview(
        self, *, detection_id: uuid.UUID, user_id: uuid.UUID,
        payload: CreatePreviewRequest, idempotency_key: str,
    ) -> RoutePreview:
        """대체 장소 경로를 계산하되 일정·경로·감지 상태는 변경하지 않는다."""
        key = idempotency_key.strip()
        if not key or len(key) > 255:
            raise AppError(400, "INVALID_REQUEST", "Idempotency-Key 형식이 올바르지 않습니다.")
        fingerprint = _request_fingerprint(payload)
        existing_row = (await self.session.execute(
            select(RoutePreviewModel, Trip.user_id)
            .join(TripDay, TripDay.trip_day_id == RoutePreviewModel.trip_day_id)
            .join(Trip, Trip.trip_id == TripDay.trip_id)
            .where(
                RoutePreviewModel.detection_id == detection_id,
                RoutePreviewModel.idempotency_key == key,
                Trip.deleted_at.is_(None),
            )
        )).one_or_none()
        if existing_row is not None:
            existing, owner_id = existing_row
            if owner_id != user_id:
                raise AppError(
                    403, "DETECTION_FORBIDDEN",
                    "다른 사용자의 감지 결과에는 접근할 수 없습니다.",
                )
            if existing.request_fingerprint != fingerprint:
                raise AppError(400, "INVALID_REQUEST", "같은 Idempotency-Key를 다른 요청에 사용할 수 없습니다.")
            return RoutePreview.model_validate(existing.response_snapshot)

        now = self.now()
        if payload.candidate_id is not None:
            claims = verify_candidate_token(payload.candidate_id, self.candidate_secret, now)
            if claims is None or claims.detection_id != detection_id or claims.place_id != payload.place_id:
                raise AppError(400, "INVALID_CANDIDATE", "후보 식별자가 유효하지 않습니다.")

        context = await self._load_context(detection_id, user_id)
        _validate_context(context, payload)
        alternative = await self._load_place(payload.place_id)
        if alternative.public_id in {item.place.public_id for item in context.items}:
            raise AppError(409, "PLACE_ALREADY_IN_SCHEDULE", "이미 일정에 포함된 장소입니다.")

        snapshot = RouteSnapshot(
            trip_day_id=context.trip_day_id, trip_id=context.trip_id,
            visit_date=context.visit_date, schedule_version=context.schedule_version,
            items=tuple(RouteItemSnapshot(
                item_id=item.item_id, sequence=item.sequence,
                name=alternative.name if item.item_id == context.target.item_id else item.place.name,
                coordinate=Coordinate(
                    longitude=(
                        alternative.longitude
                        if item.item_id == context.target.item_id else item.place.longitude
                    ),
                    latitude=(
                        alternative.latitude
                        if item.item_id == context.target.item_id else item.place.latitude
                    ),
                ),
                transport_mode_to_next=(
                    ClientTransportMode(item.transport_mode_to_next)
                    if item.transport_mode_to_next else None
                ),
            ) for item in context.items),
        )
        calculated = await self.calculator.calculate(snapshot)
        if calculated.status is not RouteStatus.READY or calculated.route is None:
            code = calculated.failure.code.value if calculated.failure else "ROUTE_PROVIDER_ERROR"
            if code == "ROUTE_PROVIDER_TIMEOUT":
                raise AppError(504, code, "경로 계산 시간이 초과되었습니다.", retryable=True)
            raise AppError(502, "ROUTE_PROVIDER_ERROR", "경로를 계산하지 못했습니다.", retryable=True)

        route = calculated.route
        route_payload = {
            "markers": [item.model_dump(mode="json", by_alias=True) for item in route.markers],
            "segments": [item.model_dump(mode="json", by_alias=True) for item in route.segments],
            "providerAttributions": route.provider_attributions,
        }
        after_eta = _preview_eta(context, route_payload)
        comparison = PreviewComparison(
            total_duration_seconds=ComparisonValue(
                before=context.current_duration, after=route.total_duration_seconds
            ),
            total_distance_meters=ComparisonValue(
                before=context.current_distance, after=route.total_distance_meters
            ),
            estimated_arrival_at=ComparisonValue(
                before=_iso(context.target.estimated_arrival_at), after=_iso(after_eta)
            ),
            closes_at=ComparisonValue(
                before=_iso(await self._closing_time(context.target.place, context.target.estimated_arrival_at)),
                after=_iso(await self._closing_time(alternative, after_eta)),
            ),
        )
        expires_at = now + timedelta(minutes=PREVIEW_TTL_MINUTES)
        response = RoutePreview(
            preview_id=uuid.uuid4(), detection_id=detection_id, trip_id=context.trip_id,
            date=context.visit_date, item_id=context.target.item_id,
            original_place=_public_place(context.target.place), alternative_place=_public_place(alternative),
            detection_reason=context.detection_reason, comparison=comparison,
            route=route.model_dump(mode="json", by_alias=True), schedule_version=context.schedule_version,
            expires_at=expires_at,
        )

        alternative_id = await self._upsert_place(alternative)
        await self._revalidate(detection_id, user_id, context, payload, alternative_id)
        concurrent = await self.session.scalar(select(RoutePreviewModel).where(
            RoutePreviewModel.detection_id == detection_id,
            RoutePreviewModel.idempotency_key == key,
        ))
        if concurrent is not None:
            if concurrent.request_fingerprint != fingerprint:
                raise AppError(
                    400, "INVALID_REQUEST",
                    "같은 Idempotency-Key를 다른 요청에 사용할 수 없습니다.",
                )
            return RoutePreview.model_validate(concurrent.response_snapshot)
        await self.session.execute(update(RoutePreviewModel).where(
            RoutePreviewModel.detection_id == detection_id, RoutePreviewModel.status == "PENDING"
        ).values(status="SUPERSEDED"))
        providers = {segment.provider.value for segment in route.segments}
        self.session.add(RoutePreviewModel(
            preview_id=response.preview_id, detection_id=detection_id, trip_day_id=context.trip_day_id,
            item_id=context.target.item_id, original_place_id=context.target.place.database_id,
            alternative_place_id=alternative_id, schedule_version=context.schedule_version,
            idempotency_key=key, request_fingerprint=fingerprint, route_payload=route_payload,
            total_duration_seconds=route.total_duration_seconds, total_distance_meters=route.total_distance_meters,
            provider=next(iter(providers)) if len(providers) == 1 else "MIXED",
            comparison=comparison.model_dump(mode="json", by_alias=True),
            response_snapshot=response.model_dump(mode="json", by_alias=True), expires_at=expires_at,
        ))
        await self.session.flush()
        return response

    async def reject_preview(self, *, preview_id: uuid.UUID, user_id: uuid.UUID) -> None:
        """소유한 미리보기를 자연 멱등으로 폐기한다."""
        row = (await self.session.execute(
            select(RoutePreviewModel, Trip.user_id)
            .join(TripDay, TripDay.trip_day_id == RoutePreviewModel.trip_day_id)
            .join(Trip, Trip.trip_id == TripDay.trip_id)
            .where(RoutePreviewModel.preview_id == preview_id, Trip.deleted_at.is_(None))
            .with_for_update()
        )).one_or_none()
        if row is None:
            raise AppError(404, "PREVIEW_NOT_FOUND", "미리보기를 찾을 수 없습니다.")
        preview, owner_id = row
        if owner_id != user_id:
            raise AppError(403, "TRIP_FORBIDDEN", "다른 사용자의 여행에는 접근할 수 없습니다.")
        if preview.status == "APPROVED":
            raise AppError(409, "ALREADY_APPROVED", "승인한 미리보기는 폐기할 수 없습니다.")
        if preview.status == "PENDING":
            preview.status = "REJECTED"
            await self.session.flush()

    async def _load_context(self, detection_id: uuid.UUID, user_id: uuid.UUID) -> _PreviewContext:
        point = cast(Place.location, Geometry(geometry_type="POINT", srid=4326))
        row = (await self.session.execute(
            select(Detection, TripDay, Trip.user_id)
            .join(TripDay, TripDay.trip_day_id == Detection.trip_day_id)
            .join(Trip, Trip.trip_id == TripDay.trip_id)
            .where(Detection.detection_id == detection_id, Trip.deleted_at.is_(None))
        )).one_or_none()
        if row is None:
            raise AppError(404, "DETECTION_NOT_FOUND", "감지 결과를 찾을 수 없습니다.")
        detection, day, owner_id = row
        if owner_id != user_id:
            raise AppError(403, "DETECTION_FORBIDDEN", "다른 사용자의 감지 결과에는 접근할 수 없습니다.")
        rows = (await self.session.execute(
            select(ItineraryItem, Place, func.ST_Y(point), func.ST_X(point))
            .join(Place, Place.place_id == ItineraryItem.place_id)
            .where(ItineraryItem.trip_day_id == day.trip_day_id).order_by(ItineraryItem.sequence)
        )).all()
        items = tuple(_item_snapshot(*item) for item in rows)
        target = next((item for item in items if item.item_id == detection.item_id), None)
        if target is None:
            raise AppError(404, "DETECTION_NOT_FOUND", "감지 대상 일정을 찾을 수 없습니다.")
        current = await self.session.scalar(select(RouteModel).where(
            RouteModel.trip_day_id == day.trip_day_id, RouteModel.schedule_version == day.schedule_version,
            RouteModel.is_active.is_(True), RouteModel.status == "READY",
        ))
        progress = (await self.session.scalars(select(ProgressSegment).where(
            ProgressSegment.trip_day_id == day.trip_day_id
        ))).all()
        context = _PreviewContext(
            trip_id=day.trip_id, trip_day_id=day.trip_day_id, visit_date=day.visit_date,
            day_status=day.status, schedule_version=day.schedule_version,
            actual_started_at=day.actual_started_at, has_start_location=day.start_location is not None,
            detection_reason=detection.reason, detection_status=detection.status, target=target, items=items,
            current_duration=current.total_duration_seconds if current else None,
            current_distance=current.total_distance_meters if current else None,
            progress_durations={(item.from_item_id, item.to_item_id): item.duration_seconds for item in progress},
        )
        await self.session.rollback()
        return context

    async def _load_place(self, public_id: str) -> _PlaceSnapshot:
        try:
            provider, provider_id = public_id.split(":", 1)
        except ValueError as exc:
            raise AppError(404, "PLACE_NOT_FOUND", "장소를 찾을 수 없습니다.") from exc
        if provider not in {"tourapi", "google"} or not provider_id:
            raise AppError(404, "PLACE_NOT_FOUND", "장소를 찾을 수 없습니다.")
        column = Place.tour_content_id if provider == "tourapi" else Place.google_place_id
        point = cast(Place.location, Geometry(geometry_type="POINT", srid=4326))
        row = (await self.session.execute(
            select(Place, func.ST_Y(point), func.ST_X(point)).where(column == provider_id)
        )).one_or_none()
        if row is not None:
            snapshot = _place_snapshot(*row)
            await self.session.rollback()
            return snapshot
        await self.session.rollback()
        detail = await self.place_service.get_place(public_id)
        if detail.latitude is None or detail.longitude is None:
            raise AppError(404, "PLACE_NOT_FOUND", "좌표가 있는 장소를 찾을 수 없습니다.")
        return _PlaceSnapshot(None, detail.place_id, detail.name, detail.category.value,
                              float(detail.latitude), float(detail.longitude), detail)

    async def _closing_time(self, place: _PlaceSnapshot, eta: datetime | None) -> datetime | None:
        if eta is None or not place.public_id.startswith("google:"):
            return None
        try:
            verdict = await evaluate_operating_hours(
                self.operating_hours_source, place_id=place.public_id.split(":", 1)[1], eta=eta
            )
            return verdict.closes_at if verdict.available else None
        except Exception:
            return None

    async def _upsert_place(self, place: _PlaceSnapshot) -> uuid.UUID:
        if place.database_id is not None:
            return place.database_id
        assert place.detail is not None
        provider, provider_id = place.public_id.split(":", 1)
        detail = place.detail
        category = detail.tour_api_category
        values = {
            "name": detail.name, "category": detail.category.value,
            "tour_category_1": category.large if category else None,
            "tour_category_2": category.middle if category else None,
            "tour_category_3": category.small if category else None,
            "address": detail.address,
            "location": WKTElement(f"POINT({place.longitude} {place.latitude})", srid=4326),
            "image_url": str(detail.image_url) if detail.image_url else None,
        }
        column = Place.tour_content_id if provider == "tourapi" else Place.google_place_id
        statement = pg_insert(Place).values(**values, **{column.key: provider_id}).on_conflict_do_update(
            index_elements=[column], index_where=column.is_not(None), set_=values
        ).returning(Place.place_id)
        return (await self.session.execute(statement)).scalar_one()

    async def _revalidate(
        self, detection_id: uuid.UUID, user_id: uuid.UUID, context: _PreviewContext,
        payload: CreatePreviewRequest, alternative_id: uuid.UUID,
    ) -> None:
        row = (await self.session.execute(
            select(Detection, TripDay, ItineraryItem, Trip.user_id)
            .join(TripDay, TripDay.trip_day_id == Detection.trip_day_id)
            .join(Trip, Trip.trip_id == TripDay.trip_id)
            .join(ItineraryItem, ItineraryItem.item_id == Detection.item_id)
            .where(Detection.detection_id == detection_id).with_for_update()
        )).one_or_none()
        if row is None:
            raise AppError(404, "DETECTION_NOT_FOUND", "감지 결과를 찾을 수 없습니다.")
        detection, day, item, owner_id = row
        if owner_id != user_id:
            raise AppError(403, "DETECTION_FORBIDDEN", "다른 사용자의 감지 결과에는 접근할 수 없습니다.")
        refreshed = replace(
            context, day_status=day.status, schedule_version=day.schedule_version,
            detection_status=detection.status, target=_replace_item_state(context.target, item),
        )
        _validate_context(refreshed, payload)
        duplicate = await self.session.scalar(select(ItineraryItem.item_id).where(
            ItineraryItem.trip_day_id == day.trip_day_id, ItineraryItem.place_id == alternative_id,
        ))
        if duplicate is not None:
            raise AppError(409, "PLACE_ALREADY_IN_SCHEDULE", "이미 일정에 포함된 장소입니다.")


def _request_fingerprint(payload: CreatePreviewRequest) -> str:
    value = json.dumps(payload.model_dump(mode="json", by_alias=True), sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(value.encode()).hexdigest()


def _provider_id(place: Place) -> str:
    if place.tour_content_id:
        return f"tourapi:{place.tour_content_id}"
    if place.google_place_id:
        return f"google:{place.google_place_id}"
    raise AppError(404, "PLACE_NOT_FOUND", "장소 식별자를 찾을 수 없습니다.")


def _place_snapshot(place: Place, latitude: float, longitude: float) -> _PlaceSnapshot:
    return _PlaceSnapshot(place.place_id, _provider_id(place), place.name, place.category,
                          float(latitude), float(longitude))


def _item_snapshot(item: ItineraryItem, place: Place, latitude: float, longitude: float) -> _ItemSnapshot:
    return _ItemSnapshot(
        item.item_id, item.sequence, item.status, item.planned_stay_minutes,
        item.transport_mode_to_next, item.estimated_arrival_at, item.actual_arrived_at,
        item.actual_departed_at, item.completed_at, _place_snapshot(place, latitude, longitude),
    )


def _replace_item_state(snapshot: _ItemSnapshot, item: ItineraryItem) -> _ItemSnapshot:
    return replace(snapshot, status=item.status, estimated_arrival_at=item.estimated_arrival_at,
                   actual_arrived_at=item.actual_arrived_at, actual_departed_at=item.actual_departed_at,
                   completed_at=item.completed_at)


def _validate_context(context: _PreviewContext, payload: CreatePreviewRequest) -> None:
    if context.detection_status != "ACTIVE":
        raise AppError(409, "DETECTION_NOT_ACTIVE", "이미 처리된 감지 결과입니다.")
    if context.day_status != "IN_PROGRESS":
        raise AppError(409, "DAY_NOT_IN_PROGRESS", "진행 중인 날짜가 아닙니다.")
    if context.schedule_version != payload.schedule_version:
        raise AppError(409, "VERSION_CONFLICT", "일정 버전이 일치하지 않습니다.")
    target = context.target
    if target.status not in {"PLANNED", "EN_ROUTE"} or target.actual_arrived_at or target.completed_at:
        raise AppError(409, "ITEM_ALREADY_VISITED", "이미 방문을 시작한 장소입니다.")


def _preview_eta(context: _PreviewContext, route_payload: dict[str, object]) -> datetime | None:
    segments = route_payload["segments"]
    assert isinstance(segments, list)
    durations = {
        (uuid.UUID(segment["fromItemId"]), uuid.UUID(segment["toItemId"])): int(segment["durationSeconds"])
        for segment in segments if isinstance(segment, dict)
    }
    previous = next(
        (item for item in reversed(context.items) if item.status == "COMPLETED"), None
    )
    current_eta: datetime | None = None
    for item in (
        item for item in context.items
        if item.status in {"PLANNED", "EN_ROUTE", "ARRIVED"}
    ):
        if previous is None:
            base = context.actual_started_at
            duration = (
                0 if not context.has_start_location
                else context.progress_durations.get((None, item.item_id))
            )
        elif previous.status == "COMPLETED":
            base = previous.actual_departed_at
            duration = durations.get(
                (previous.item_id, item.item_id),
                context.progress_durations.get((previous.item_id, item.item_id)),
            )
        elif previous.status == "ARRIVED":
            base = (
                previous.actual_arrived_at + timedelta(minutes=previous.planned_stay_minutes)
                if previous.actual_arrived_at else None
            )
            duration = durations.get(
                (previous.item_id, item.item_id),
                context.progress_durations.get((previous.item_id, item.item_id)),
            )
        else:
            base = current_eta + timedelta(minutes=previous.planned_stay_minutes) if current_eta else None
            duration = durations.get(
                (previous.item_id, item.item_id),
                context.progress_durations.get((previous.item_id, item.item_id)),
            )
        current_eta = (
            base + timedelta(seconds=duration)
            if base is not None and duration is not None else None
        )
        if item.item_id == context.target.item_id:
            return item.estimated_arrival_at if item.status == "ARRIVED" else current_eta
        previous = item
    return None


def _public_place(place: _PlaceSnapshot) -> ReplacedPlace:
    return ReplacedPlace(place_id=place.public_id, name=place.name, category=place.category,
                         latitude=place.latitude, longitude=place.longitude)


def _iso(value: datetime | None) -> str | None:
    return value.isoformat() if value is not None else None


__all__ = ["PREVIEW_TTL_MINUTES", "ReplacementService", "UNDO_WINDOW_SECONDS"]
