"""PostgreSQL에서 경로 계산의 transaction 분리와 version 전이를 검증한다."""

from __future__ import annotations

import asyncio
import os
import uuid
from collections.abc import AsyncIterator
from datetime import UTC, date, datetime

import pytest
from geoalchemy2.elements import WKTElement
import httpx2
from sqlalchemy import func, select, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.clients.route_provider import (
    Coordinate,
    NormalizedRoute,
    Provider,
    RouteProviderError,
    TransportMode,
)
from app.db import transaction_session
from app.api.dependencies import get_current_principal
from app.api.errors import AppError
from app.api.v1.itinerary import _save_route_service, _trip_service
from app.core.security import AuthPrincipal
from app.db import get_session
from app.main import app
from app.models.auth import User
from app.models.itinerary import ItineraryItem, Place, TripDay
from app.models.route import Route as RouteModel
from app.models.trip import Trip
from app.schemas.trip import Trip as TripSchema, TripStatus
from app.services.route import RouteCalculationService, RouteService
from app.services.itinerary import ItineraryService


@pytest.fixture
async def session_factory() -> AsyncIterator[async_sessionmaker[AsyncSession]]:
    database_url = os.getenv("TEST_DATABASE_URL") or os.getenv("DATABASE_URL")
    if not database_url:
        pytest.fail("TEST_DATABASE_URL 또는 DATABASE_URL이 필요합니다")
    engine = create_async_engine(database_url)
    yield async_sessionmaker(engine, expire_on_commit=False)
    await engine.dispose()


class FixedProvider:
    def __init__(self, provider: Provider, *, failure: RouteProviderError | None = None) -> None:
        self.provider = provider
        self.failure = failure
        self.calls = 0

    async def calculate(
        self,
        origin: Coordinate,
        destination: Coordinate,
        transport_mode: TransportMode,
        *,
        deadline: float,
    ) -> NormalizedRoute:
        self.calls += 1
        if self.failure:
            raise self.failure
        return NormalizedRoute(
            provider=self.provider,
            transport_mode=transport_mode,
            duration_seconds=600,
            distance_meters=800,
            coordinates=[origin, destination],
            attribution=self.provider.value,
        )


async def _seed(
    factory: async_sessionmaker[AsyncSession],
    *,
    item_count: int = 2,
) -> tuple[uuid.UUID, uuid.UUID, date]:
    visit_date = date(2026, 9, 6)
    async with transaction_session(factory) as session:
        user = User(social_provider="KAKAO", social_subject=f"route-{uuid.uuid4()}")
        session.add(user)
        await session.flush()
        trip = Trip(
            user_id=user.user_id,
            name="경로 계산 여행",
            start_date=visit_date,
            end_date=visit_date,
        )
        session.add(trip)
        await session.flush()
        day = TripDay(
            trip_id=trip.trip_id,
            visit_date=visit_date,
            day_number=1,
            schedule_version=1,
        )
        session.add(day)
        await session.flush()
        for index in range(item_count):
            place = Place(
                tour_content_id=f"route-{uuid.uuid4()}",
                name=f"장소 {index + 1}",
                category="OTHER",
                location=WKTElement(f"POINT({127 + index / 100} 37.5)", srid=4326),
            )
            session.add(place)
            await session.flush()
            session.add(
                ItineraryItem(
                    trip_day_id=day.trip_day_id,
                    place_id=place.place_id,
                    sequence=index + 1,
                    planned_stay_minutes=60,
                    stay_source="RECOMMENDED",
                    transport_mode_to_next=("WALK" if index < item_count - 1 else None),
                    status="PLANNED",
                )
            )
        return trip.trip_id, day.trip_day_id, visit_date


def _service(
    factory: async_sessionmaker[AsyncSession],
    provider: FixedProvider,
) -> RouteService:
    calculation = RouteCalculationService(
        tmap=provider,
        odsay=FixedProvider(Provider.ODSAY),
        concurrency=3,
        deadline_seconds=10,
    )
    return RouteService(factory, calculation)


@pytest.mark.asyncio
async def test_calculation_persists_ready_route_and_get_returns_same_result(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, _, visit_date = await _seed(session_factory)
    service = _service(session_factory, FixedProvider(Provider.TMAP))

    calculated = await service.calculate_current(trip_id=trip_id, visit_date=visit_date)
    loaded = await service.get_current(trip_id=trip_id, visit_date=visit_date)

    assert calculated == loaded
    assert loaded.route_status == "READY"
    assert loaded.route is not None
    assert loaded.route.total_duration_seconds == 600
    assert loaded.route.total_distance_meters == 800

    async with transaction_session(session_factory) as session:
        itinerary = await ItineraryService(session).get_day(
            trip_id=trip_id,
            visit_date=visit_date,
            start_date=visit_date,
        )
    assert itinerary.route_status == "READY"
    assert itinerary.route == loaded.route


@pytest.mark.asyncio
async def test_provider_failure_is_stored_without_changing_itinerary(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, day_id, visit_date = await _seed(session_factory)
    provider = FixedProvider(
        Provider.TMAP,
        failure=RouteProviderError("ROUTE_NOT_FOUND", retryable=False),
    )

    result = await _service(session_factory, provider).calculate_current(
        trip_id=trip_id,
        visit_date=visit_date,
    )

    assert result.route_status == "FAILED"
    assert result.failure is not None and result.failure.code == "ROUTE_NOT_FOUND"
    async with session_factory() as session:
        assert await session.scalar(
            select(func.count()).select_from(ItineraryItem).where(
                ItineraryItem.trip_day_id == day_id
            )
        ) == 2


@pytest.mark.asyncio
async def test_zero_items_has_no_route_row_and_one_item_is_ready(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    empty_trip, empty_day, visit_date = await _seed(session_factory, item_count=0)
    one_trip, one_day, _ = await _seed(session_factory, item_count=1)
    provider = FixedProvider(Provider.TMAP)
    service = _service(session_factory, provider)

    empty = await service.calculate_current(trip_id=empty_trip, visit_date=visit_date)
    one = await service.calculate_current(trip_id=one_trip, visit_date=visit_date)

    assert empty.route_status == "NOT_CALCULATED"
    assert one.route_status == "READY" and one.route is not None
    assert one.route.total_duration_seconds == one.route.total_distance_meters == 0
    assert provider.calls == 0
    async with session_factory() as session:
        assert await session.scalar(
            select(func.count()).select_from(RouteModel).where(
                RouteModel.trip_day_id == empty_day
            )
        ) == 0
        assert await session.scalar(
            select(func.count()).select_from(RouteModel).where(
                RouteModel.trip_day_id == one_day
            )
        ) == 1


@pytest.mark.asyncio
async def test_changed_version_discards_finished_calculation(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, day_id, visit_date = await _seed(session_factory)

    class VersionChangingProvider(FixedProvider):
        async def calculate(self, *args, **kwargs) -> NormalizedRoute:  # type: ignore[no-untyped-def]
            async with transaction_session(session_factory) as session:
                await session.execute(
                    update(TripDay)
                    .where(TripDay.trip_day_id == day_id)
                    .values(schedule_version=2)
                )
            return await super().calculate(*args, **kwargs)

    result = await _service(
        session_factory,
        VersionChangingProvider(Provider.TMAP),
    ).calculate_current(trip_id=trip_id, visit_date=visit_date)

    assert result.schedule_version == 2
    assert result.route_status == "NOT_CALCULATED"
    async with session_factory() as session:
        assert await session.scalar(
            select(func.count()).select_from(RouteModel).where(
                RouteModel.trip_day_id == day_id
            )
        ) == 0


@pytest.mark.asyncio
async def test_changed_route_input_histories_previous_route_and_keeps_one_active(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, day_id, visit_date = await _seed(session_factory)
    service = _service(session_factory, FixedProvider(Provider.TMAP))
    first = await service.calculate_current(trip_id=trip_id, visit_date=visit_date)
    assert first.route_status == "READY"

    async with transaction_session(session_factory) as session:
        await session.execute(
            update(TripDay)
            .where(TripDay.trip_day_id == day_id)
            .values(schedule_version=2)
        )
        await session.execute(
            update(RouteModel)
            .where(
                RouteModel.trip_day_id == day_id,
                RouteModel.is_active.is_(True),
            )
            .values(status="HISTORICAL", is_active=False)
        )

    second = await service.calculate_current(trip_id=trip_id, visit_date=visit_date)

    assert second.route_status == "READY"
    assert second.schedule_version == 2
    async with session_factory() as session:
        routes = list(
            (
                await session.scalars(
                    select(RouteModel)
                    .where(RouteModel.trip_day_id == day_id)
                    .order_by(RouteModel.schedule_version)
                )
            ).all()
        )
    assert [(route.schedule_version, route.status, route.is_active) for route in routes] == [
        (1, "HISTORICAL", False),
        (2, "READY", True),
    ]


@pytest.mark.asyncio
async def test_itinerary_http_commit_precedes_route_calculation(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, _, visit_date = await _seed(session_factory, item_count=0)

    class OwnedTripService:
        async def get_trip(self, *, user_id: uuid.UUID, trip_id: uuid.UUID) -> TripSchema:
            return TripSchema(
                tripId=trip_id,
                name="경로 계산 여행",
                startDate=visit_date,
                endDate=visit_date,
                status=TripStatus.UPCOMING,
                dayCount=1,
                version=1,
                createdAt=datetime.now(UTC),
            )

    async def request_session():
        async with transaction_session(session_factory) as session:
            yield session

    app.dependency_overrides[get_current_principal] = lambda: AuthPrincipal(
        user_id=uuid.uuid4(),
        session_id=uuid.uuid4(),
        token_id=uuid.uuid4(),
    )
    app.dependency_overrides[get_session] = request_session
    app.dependency_overrides[_trip_service] = lambda: OwnedTripService()
    app.dependency_overrides[_save_route_service] = lambda: _service(
        session_factory,
        FixedProvider(Provider.TMAP),
    )
    try:
        async with httpx2.AsyncClient(
            transport=httpx2.ASGITransport(app=app),
            base_url="http://testserver",
        ) as client:
            response = await client.put(
                f"/api/v1/trips/{trip_id}/days/{visit_date.isoformat()}/itinerary",
                headers={"Idempotency-Key": str(uuid.uuid4())},
                json={
                    "version": 1,
                    "items": [
                        {
                            "itemId": None,
                            "placeId": "tourapi:http-route-1",
                            "place": {
                                "name": "경복궁",
                                "category": "HISTORY_CULTURE",
                                "tourApiCategory": None,
                                "address": "서울 종로구",
                                "latitude": 37.5796,
                                "longitude": 126.977,
                                "imageUrl": None,
                            },
                            "sequence": 1,
                            "plannedStayMinutes": 60,
                            "staySource": "RECOMMENDED",
                            "transportModeToNext": None,
                        }
                    ],
                },
            )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    assert response.json()["data"]["routeStatus"] == "READY"
    assert response.json()["data"]["route"]["totalDurationSeconds"] == 0


async def _failed_route(
    factory: async_sessionmaker[AsyncSession],
) -> tuple[uuid.UUID, uuid.UUID, date]:
    trip_id, day_id, visit_date = await _seed(factory)
    failed = FixedProvider(
        Provider.TMAP,
        failure=RouteProviderError("ROUTE_PROVIDER_UNAVAILABLE", retryable=True),
    )
    result = await _service(factory, failed).calculate_current(
        trip_id=trip_id,
        visit_date=visit_date,
    )
    assert result.route_status == "FAILED"
    return trip_id, day_id, visit_date


@pytest.mark.asyncio
async def test_failed_route_retry_recalculates_same_version(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, day_id, visit_date = await _failed_route(session_factory)
    provider = FixedProvider(Provider.TMAP)

    result = await _service(session_factory, provider).retry_current(
        trip_id=trip_id,
        visit_date=visit_date,
        schedule_version=1,
    )

    assert result.route_status == "READY"
    assert result.schedule_version == 1
    assert provider.calls == 1
    async with session_factory() as session:
        assert await session.scalar(
            select(func.count()).select_from(RouteModel).where(
                RouteModel.trip_day_id == day_id,
                RouteModel.schedule_version == 1,
            )
        ) == 1


@pytest.mark.asyncio
async def test_retry_rejects_stale_version_and_ready_route(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, _, visit_date = await _failed_route(session_factory)
    service = _service(session_factory, FixedProvider(Provider.TMAP))

    with pytest.raises(AppError) as stale:
        await service.retry_current(
            trip_id=trip_id,
            visit_date=visit_date,
            schedule_version=2,
        )
    assert (stale.value.status_code, stale.value.code) == (409, "VERSION_CONFLICT")

    await service.retry_current(
        trip_id=trip_id,
        visit_date=visit_date,
        schedule_version=1,
    )
    with pytest.raises(AppError) as ready:
        await service.retry_current(
            trip_id=trip_id,
            visit_date=visit_date,
            schedule_version=1,
        )
    assert (ready.value.status_code, ready.value.code) == (409, "ROUTE_NOT_FAILED")


@pytest.mark.asyncio
async def test_retry_reports_version_conflict_when_schedule_changes_after_snapshot(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, day_id, visit_date = await _failed_route(session_factory)

    class VersionChangingRouteService(RouteService):
        async def _load_snapshot(self, **kwargs):  # type: ignore[no-untyped-def]
            snapshot = await super()._load_snapshot(**kwargs)
            async with transaction_session(session_factory) as session:
                await session.execute(
                    update(TripDay)
                    .where(TripDay.trip_day_id == day_id)
                    .values(schedule_version=2)
                )
                await session.execute(
                    update(RouteModel)
                    .where(
                        RouteModel.trip_day_id == day_id,
                        RouteModel.is_active.is_(True),
                    )
                    .values(status="HISTORICAL", is_active=False)
                )
            return snapshot

    calculation = RouteCalculationService(
        tmap=FixedProvider(Provider.TMAP),
        odsay=FixedProvider(Provider.ODSAY),
        concurrency=3,
        deadline_seconds=10,
    )
    service = VersionChangingRouteService(session_factory, calculation)

    with pytest.raises(AppError) as conflict:
        await service.retry_current(
            trip_id=trip_id,
            visit_date=visit_date,
            schedule_version=1,
        )

    assert (conflict.value.status_code, conflict.value.code) == (
        409,
        "VERSION_CONFLICT",
    )


@pytest.mark.asyncio
async def test_concurrent_retries_keep_one_ready_route_row(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, day_id, visit_date = await _failed_route(session_factory)

    class ConcurrentProvider(FixedProvider):
        def __init__(self) -> None:
            super().__init__(Provider.TMAP)
            self.both_started = asyncio.Event()

        async def calculate(self, *args, **kwargs) -> NormalizedRoute:  # type: ignore[no-untyped-def]
            self.calls += 1
            if self.calls == 2:
                self.both_started.set()
            await asyncio.wait_for(self.both_started.wait(), timeout=1)
            self.calls -= 1  # FixedProvider가 실제 호출 횟수를 한 번 기록한다.
            return await super().calculate(*args, **kwargs)

    provider = ConcurrentProvider()
    service = _service(session_factory, provider)
    results = await asyncio.gather(
        *(
            service.retry_current(
                trip_id=trip_id,
                visit_date=visit_date,
                schedule_version=1,
            )
            for _ in range(2)
        )
    )

    assert [result.route_status for result in results] == ["READY", "READY"]
    async with session_factory() as session:
        routes = list(
            (
                await session.scalars(
                    select(RouteModel).where(RouteModel.trip_day_id == day_id)
                )
            ).all()
        )
    assert len(routes) == 1
    assert routes[0].status == "READY"
    assert routes[0].is_active is True


@pytest.mark.asyncio
async def test_retry_final_failure_returns_failed_and_keeps_one_row(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    trip_id, day_id, visit_date = await _failed_route(session_factory)
    provider = FixedProvider(
        Provider.TMAP,
        failure=RouteProviderError("ROUTE_NOT_FOUND", retryable=False),
    )

    result = await _service(session_factory, provider).retry_current(
        trip_id=trip_id,
        visit_date=visit_date,
        schedule_version=1,
    )

    assert result.route_status == "FAILED"
    assert result.failure is not None
    assert result.failure.code == "ROUTE_NOT_FOUND"
    async with session_factory() as session:
        assert await session.scalar(
            select(func.count()).select_from(RouteModel).where(
                RouteModel.trip_day_id == day_id
            )
        ) == 1
