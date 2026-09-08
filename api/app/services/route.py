"""일정 snapshot의 날짜별 경로를 제한된 동시성으로 계산한다."""

from __future__ import annotations

import asyncio
import logging
import uuid
from dataclasses import dataclass
from datetime import UTC, date, datetime
from time import monotonic

from geoalchemy2 import Geometry
from sqlalchemy import cast, func, null, select, update
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.api.errors import AppError
from app.clients.route_provider import (
    Coordinate,
    NormalizedRoute,
    Provider as ClientProvider,
    RouteProvider,
    RouteProviderError,
    TransportMode as ClientTransportMode,
)
from app.core.logging import request_id_context
from app.db import transaction_session
from app.models.itinerary import ItineraryItem, Place, TripDay
from app.models.route import Route as RouteModel
from app.services.eta import recalculate_day_eta
from app.schemas.route import (
    FailedRouteData,
    NotCalculatedRouteData,
    Provider,
    Route,
    RouteFailure,
    RouteFailureCode,
    RouteGeometry,
    RouteMarker,
    RouteSegment,
    RouteStatus,
    RouteData,
    TransportMode,
)

logger = logging.getLogger("gilpick.route")


@dataclass(frozen=True, slots=True)
class RouteItemSnapshot:
    """외부 호출 중 DB transaction 없이 유지할 일정 항목 값."""

    item_id: uuid.UUID
    sequence: int
    name: str
    coordinate: Coordinate
    transport_mode_to_next: ClientTransportMode | None


@dataclass(frozen=True, slots=True)
class RouteSnapshot:
    """하나의 일정 version에 고정된 경로 계산 입력."""

    trip_day_id: uuid.UUID
    trip_id: uuid.UUID
    visit_date: date
    schedule_version: int
    items: tuple[RouteItemSnapshot, ...]

    def matches_version(self, current_version: int) -> bool:
        """계산 완료 시점의 일정 version이 입력 snapshot과 같은지 확인한다."""
        return self.schedule_version == current_version


@dataclass(frozen=True, slots=True)
class RouteCalculationResult:
    """영속화 전 READY·FAILED·NOT_CALCULATED 계산 결과."""

    status: RouteStatus
    route: Route | None
    failure: RouteFailure | None


@dataclass(frozen=True, slots=True)
class SingleSegmentResult:
    """진행 ETA에 필요한 단일 구간 계산 결과."""

    duration_seconds: int
    distance_meters: int
    provider: ClientProvider


class RouteCalculationService:
    """Provider 호출을 조율해 날짜 전체의 단일 경로 결과를 만든다."""

    def __init__(
        self,
        *,
        tmap: RouteProvider,
        odsay: RouteProvider,
        concurrency: int,
        deadline_seconds: float,
    ) -> None:
        if concurrency <= 0 or deadline_seconds <= 0:
            raise ValueError("경로 동시성과 deadline은 양수여야 합니다.")
        self.providers = {
            ClientTransportMode.WALK: tmap,
            ClientTransportMode.CAR: tmap,
            ClientTransportMode.TRANSIT: odsay,
        }
        self.concurrency = concurrency
        self.deadline_seconds = deadline_seconds

    async def close(self) -> None:
        """서비스가 소유한 Provider 연결 풀을 닫는다."""
        providers = {id(value): value for value in self.providers.values()}.values()
        for provider in providers:
            close = getattr(provider, "close", None)
            if close is not None:
                await close()

    async def calculate(self, snapshot: RouteSnapshot) -> RouteCalculationResult:
        """일정 snapshot을 전체 성공 또는 전체 실패 경로로 계산한다.

        Args:
            snapshot: DB transaction 밖에서 사용할 immutable 일정 입력.

        Returns:
            장소 수와 Provider 결과에 따른 최종 경로 상태.

        Notes:
            장소가 두 곳 이상이면 모든 구간이 성공할 때만 READY를 반환한다.
        """
        items = tuple(sorted(snapshot.items, key=lambda item: item.sequence))
        if not items:
            return RouteCalculationResult(RouteStatus.NOT_CALCULATED, None, None)
        markers = [_marker(item) for item in items]
        if len(items) == 1:
            return RouteCalculationResult(
                RouteStatus.READY,
                _route(snapshot, markers=markers, segments=[]),
                None,
            )

        deadline = monotonic() + self.deadline_seconds
        semaphore = asyncio.Semaphore(self.concurrency)
        tasks = [
            self._calculate_segment(
                snapshot=snapshot,
                sequence=index + 1,
                origin=origin,
                destination=destination,
                semaphore=semaphore,
                deadline=deadline,
            )
            for index, (origin, destination) in enumerate(zip(items, items[1:]))
        ]
        try:
            async with asyncio.timeout(self.deadline_seconds):
                outcomes = await asyncio.gather(*tasks, return_exceptions=True)
        except TimeoutError:
            return _failed("ROUTE_PROVIDER_TIMEOUT", retryable=True)

        failures = [outcome for outcome in outcomes if isinstance(outcome, RouteProviderError)]
        if failures:
            # gather 결과 순서는 task 순서와 같으므로 가장 앞선 일정 구간의 실패를 대표로 쓴다.
            failure = failures[0]
            return _failed(failure.code, retryable=failure.retryable)
        unexpected = [outcome for outcome in outcomes if isinstance(outcome, BaseException)]
        if unexpected:
            raise unexpected[0]

        segments = [outcome for outcome in outcomes if isinstance(outcome, RouteSegment)]
        return RouteCalculationResult(
            RouteStatus.READY,
            _route(snapshot, markers=markers, segments=segments),
            None,
        )

    async def calculate_single_segment(
        self,
        *,
        origin: Coordinate,
        destination: Coordinate,
        transport_mode: ClientTransportMode,
        overall_deadline_seconds: float = 8.0,
    ) -> SingleSegmentResult:
        """기존 Provider로 진행용 단일 구간을 계산한다.

        Args:
            origin: WGS84 출발 좌표.
            destination: WGS84 도착 좌표.
            transport_mode: 구간 이동수단.
            overall_deadline_seconds: 재시도를 포함한 전체 제한 시간(초).

        Returns:
            ETA 저장에 필요한 이동시간, 거리와 Provider.

        Raises:
            ValueError: 전체 제한 시간이 양수가 아닌 경우.
            RouteProviderError: Provider가 최종 실패하거나 제한 시간을 초과한 경우.

        Notes:
            DB를 읽거나 쓰지 않으며 시도당 최대 5초, 일시 오류는 한 번만 재시도한다.
        """
        if overall_deadline_seconds <= 0:
            raise ValueError("전체 deadline은 양수여야 합니다.")
        overall_deadline = monotonic() + overall_deadline_seconds
        provider = self.providers[transport_mode]

        for attempt in (1, 2):
            started_at = monotonic()
            attempt_deadline = min(started_at + 5.0, overall_deadline)
            if attempt_deadline <= started_at:
                raise RouteProviderError("ROUTE_PROVIDER_TIMEOUT", retryable=True)
            try:
                async with asyncio.timeout(attempt_deadline - started_at):
                    normalized = await provider.calculate(
                        origin,
                        destination,
                        transport_mode,
                        deadline=attempt_deadline,
                    )
                return SingleSegmentResult(
                    duration_seconds=normalized.duration_seconds,
                    distance_meters=normalized.distance_meters,
                    provider=normalized.provider,
                )
            except TimeoutError:
                error = RouteProviderError("ROUTE_PROVIDER_TIMEOUT", retryable=True)
            except RouteProviderError as caught:
                error = caught
            except Exception as caught:
                raise RouteProviderError(
                    "ROUTE_INVALID_RESULT", retryable=False
                ) from caught

            if not error.retryable or attempt == 2 or monotonic() >= overall_deadline:
                raise error

        raise RuntimeError("단일 구간 재시도 상태가 올바르지 않습니다.")

    async def _calculate_segment(
        self,
        *,
        snapshot: RouteSnapshot,
        sequence: int,
        origin: RouteItemSnapshot,
        destination: RouteItemSnapshot,
        semaphore: asyncio.Semaphore,
        deadline: float,
    ) -> RouteSegment:
        mode = origin.transport_mode_to_next
        if mode is None:
            raise RouteProviderError("ROUTE_INVALID_RESULT", retryable=False)
        provider = self.providers[mode]
        async with semaphore:
            for attempt in (1, 2):
                started_at = monotonic()
                try:
                    if started_at >= deadline:
                        raise RouteProviderError("ROUTE_PROVIDER_TIMEOUT", retryable=True)
                    normalized = await provider.calculate(
                        origin.coordinate,
                        destination.coordinate,
                        mode,
                        deadline=deadline,
                    )
                    self._log_attempt(
                        snapshot=snapshot,
                        provider=normalized.provider,
                        attempt=attempt,
                        started_at=started_at,
                        result_code="SUCCESS",
                    )
                    return _segment(sequence, origin, destination, normalized)
                except RouteProviderError as error:
                    self._log_attempt(
                        snapshot=snapshot,
                        provider=_provider_for_mode(mode),
                        attempt=attempt,
                        started_at=started_at,
                        result_code=error.code,
                    )
                    if not error.retryable or attempt == 2 or monotonic() >= deadline:
                        raise
                except Exception as error:
                    self._log_attempt(
                        snapshot=snapshot,
                        provider=_provider_for_mode(mode),
                        attempt=attempt,
                        started_at=started_at,
                        result_code="ROUTE_INVALID_RESULT",
                    )
                    raise RouteProviderError(
                        "ROUTE_INVALID_RESULT",
                        retryable=False,
                    ) from error
        raise RuntimeError("경로 구간 재시도 상태가 올바르지 않습니다.")

    @staticmethod
    def _log_attempt(
        *,
        snapshot: RouteSnapshot,
        provider: ClientProvider,
        attempt: int,
        started_at: float,
        result_code: str,
    ) -> None:
        logger.info(
            {
                "operation": "CALCULATE_ROUTE_SEGMENT",
                "request_id": request_id_context.get(),
                "trip_id": str(snapshot.trip_id),
                "visit_date": snapshot.visit_date.isoformat(),
                "schedule_version": snapshot.schedule_version,
                "provider": provider.value,
                "attempt": attempt,
                "latency_ms": round((monotonic() - started_at) * 1000, 3),
                "result_code": result_code,
            }
        )


class RouteService:
    """경로 계산 입력 조회와 결과 영속화를 짧은 transaction으로 분리한다."""

    def __init__(
        self,
        session_factory: async_sessionmaker[AsyncSession],
        calculator: RouteCalculationService,
    ) -> None:
        self.session_factory = session_factory
        self.calculator = calculator

    async def close(self) -> None:
        """경로 계산기가 소유한 외부 연결을 닫는다."""
        await self.calculator.close()

    async def calculate_current(
        self,
        *,
        trip_id: uuid.UUID,
        visit_date: date,
    ) -> RouteData:
        """현재 일정 snapshot을 계산하고 version이 유지된 경우에만 저장한다.

        Args:
            trip_id: 경로를 계산할 여행 식별자.
            visit_date: 여행 기간 안의 대상 날짜.

        Returns:
            계산 완료 시점의 현재 일정 version에 대응하는 경로 상태.

        Notes:
            입력 조회 transaction은 Provider 호출 전에 종료한다. 결과 저장은
            별도 transaction에서 version을 잠그고 다시 확인한다.
        """
        snapshot = await self._load_snapshot(trip_id=trip_id, visit_date=visit_date)
        if snapshot is None:
            return NotCalculatedRouteData(
                trip_id=trip_id,
                date=visit_date,
                schedule_version=0,
                route_status=RouteStatus.NOT_CALCULATED,
                route=None,
                failure=None,
            )
        deadline = monotonic() + self.calculator.deadline_seconds
        result = await self.calculator.calculate(snapshot)
        try:
            async with asyncio.timeout_at(deadline):
                await self._persist(snapshot, result)
                return await self.get_current(trip_id=trip_id, visit_date=visit_date)
        except TimeoutError:
            return _route_data_from_result(
                snapshot,
                _failed("ROUTE_PROVIDER_TIMEOUT", retryable=True),
            )

    async def get_current(
        self,
        *,
        trip_id: uuid.UUID,
        visit_date: date,
    ) -> RouteData:
        """현재 일정 version과 정확히 일치하는 활성 경로만 조회한다."""
        async with self.session_factory() as session:
            day = await session.scalar(
                select(TripDay).where(
                    TripDay.trip_id == trip_id,
                    TripDay.visit_date == visit_date,
                )
            )
            if day is None:
                return NotCalculatedRouteData(
                    trip_id=trip_id,
                    date=visit_date,
                    schedule_version=0,
                    route_status=RouteStatus.NOT_CALCULATED,
                    route=None,
                    failure=None,
                )
            model = await session.scalar(
                select(RouteModel).where(
                    RouteModel.trip_day_id == day.trip_day_id,
                    RouteModel.schedule_version == day.schedule_version,
                    RouteModel.is_active.is_(True),
                )
            )
            return route_data_from_model(day, model)

    async def retry_current(
        self,
        *,
        trip_id: uuid.UUID,
        visit_date: date,
        schedule_version: int,
    ) -> RouteData:
        """FAILED인 현재 경로만 같은 일정 version으로 전체 재계산한다.

        Args:
            trip_id: 경로를 다시 계산할 여행 식별자.
            visit_date: 여행 기간 안의 대상 날짜.
            schedule_version: 클라이언트가 확인한 현재 일정 version.

        Returns:
            재시도 후 READY 또는 최종 FAILED 경로 상태.

        Raises:
            AppError: 일정 version이 다르거나 현재 경로가 FAILED가 아닌 경우.

        Notes:
            Provider 호출 전 FAILED 상태를 확인하고, 결과 저장 transaction에서
            version과 상태를 다시 잠가 검사한다.
        """
        snapshot = await self._load_snapshot(trip_id=trip_id, visit_date=visit_date)
        if snapshot is None or snapshot.schedule_version != schedule_version:
            raise _retry_version_conflict()
        current = await self.get_current(trip_id=trip_id, visit_date=visit_date)
        if current.schedule_version != schedule_version:
            raise _retry_version_conflict()
        if current.route_status is not RouteStatus.FAILED:
            raise _route_not_failed()

        deadline = monotonic() + self.calculator.deadline_seconds
        result = await self.calculator.calculate(snapshot)
        try:
            async with asyncio.timeout_at(deadline):
                await self._persist_retry(snapshot, result)
                return await self.get_current(trip_id=trip_id, visit_date=visit_date)
        except TimeoutError:
            return _route_data_from_result(
                snapshot,
                _failed("ROUTE_PROVIDER_TIMEOUT", retryable=True),
            )


    async def _load_snapshot(
        self,
        *,
        trip_id: uuid.UUID,
        visit_date: date,
    ) -> RouteSnapshot | None:
        async with self.session_factory() as session:
            day = await session.scalar(
                select(TripDay).where(
                    TripDay.trip_id == trip_id,
                    TripDay.visit_date == visit_date,
                )
            )
            if day is None:
                return None
            point = cast(Place.location, Geometry(geometry_type="POINT", srid=4326))
            rows = (
                await session.execute(
                    select(
                        ItineraryItem.item_id,
                        ItineraryItem.sequence,
                        Place.name,
                        func.ST_X(point),
                        func.ST_Y(point),
                        ItineraryItem.transport_mode_to_next,
                    )
                    .join(Place, Place.place_id == ItineraryItem.place_id)
                    .where(ItineraryItem.trip_day_id == day.trip_day_id)
                    .order_by(ItineraryItem.sequence)
                )
            ).all()
            return RouteSnapshot(
                trip_day_id=day.trip_day_id,
                trip_id=trip_id,
                visit_date=visit_date,
                schedule_version=day.schedule_version,
                items=tuple(
                    RouteItemSnapshot(
                        item_id=row[0],
                        sequence=row[1],
                        name=row[2],
                        coordinate=Coordinate(longitude=row[3], latitude=row[4]),
                        transport_mode_to_next=(
                            ClientTransportMode(row[5]) if row[5] is not None else None
                        ),
                    )
                    for row in rows
                ),
            )

    async def _persist(
        self,
        snapshot: RouteSnapshot,
        result: RouteCalculationResult,
    ) -> bool:
        async with transaction_session(self.session_factory) as session:
            current_version = await session.scalar(
                select(TripDay.schedule_version)
                .where(TripDay.trip_day_id == snapshot.trip_day_id)
                .with_for_update()
            )
            if current_version is None or not snapshot.matches_version(current_version):
                return False
            await session.execute(
                update(RouteModel)
                .where(
                    RouteModel.trip_day_id == snapshot.trip_day_id,
                    RouteModel.is_active.is_(True),
                )
                .values(status="HISTORICAL", is_active=False)
            )
            if result.status is RouteStatus.NOT_CALCULATED:
                return True

            values = _route_values(snapshot, result)
            statement = pg_insert(RouteModel).values(**values)
            statement = statement.on_conflict_do_update(
                constraint="uq_routes_day_schedule_version",
                set_={key: value for key, value in values.items() if key != "route_id"},
            )
            await session.execute(statement)
            if result.status is RouteStatus.READY:
                await recalculate_day_eta(session, snapshot.trip_day_id)
            return True

    async def _persist_retry(
        self,
        snapshot: RouteSnapshot,
        result: RouteCalculationResult,
    ) -> bool:
        """재시도 결과를 version별 기존 행에 한 번만 적용한다."""
        async with transaction_session(self.session_factory) as session:
            current_version = await session.scalar(
                select(TripDay.schedule_version)
                .where(TripDay.trip_day_id == snapshot.trip_day_id)
                .with_for_update()
            )
            if current_version is None or not snapshot.matches_version(current_version):
                raise _retry_version_conflict()
            current_route = await session.scalar(
                select(RouteModel)
                .where(
                    RouteModel.trip_day_id == snapshot.trip_day_id,
                    RouteModel.schedule_version == snapshot.schedule_version,
                    RouteModel.is_active.is_(True),
                )
                .with_for_update()
            )
            if current_route is None:
                raise _route_not_failed()
            if current_route.status != "FAILED":
                return False

            values = _route_values(snapshot, result)
            statement = pg_insert(RouteModel).values(**values)
            statement = statement.on_conflict_do_update(
                constraint="uq_routes_day_schedule_version",
                set_={key: value for key, value in values.items() if key != "route_id"},
            )
            await session.execute(statement)
            if result.status is RouteStatus.READY:
                await recalculate_day_eta(session, snapshot.trip_day_id)
            return True


def build_route_service(settings):  # type: ignore[no-untyped-def]
    """공용 설정으로 실제 DB·TMAP·ODsay 경로 서비스를 구성한다."""
    from app.clients.odsay import OdsayClient
    from app.clients.tmap import TmapClient
    from app.db import create_session_factory

    return RouteService(
        create_session_factory(),
        RouteCalculationService(
            tmap=TmapClient(settings),
            odsay=OdsayClient(settings),
            concurrency=settings.route_provider_concurrency,
            deadline_seconds=settings.route_calculation_deadline_seconds,
        ),
    )


def _marker(item: RouteItemSnapshot) -> RouteMarker:
    return RouteMarker(
        item_id=item.item_id,
        sequence=item.sequence,
        name=item.name,
        latitude=item.coordinate.latitude,
        longitude=item.coordinate.longitude,
    )


def _segment(
    sequence: int,
    origin: RouteItemSnapshot,
    destination: RouteItemSnapshot,
    value: NormalizedRoute,
) -> RouteSegment:
    return RouteSegment(
        sequence=sequence,
        from_item_id=origin.item_id,
        to_item_id=destination.item_id,
        transport_mode=TransportMode(value.transport_mode.value),
        provider=Provider(value.provider.value),
        duration_seconds=value.duration_seconds,
        distance_meters=value.distance_meters,
        geometry=RouteGeometry(
            type="LineString",
            coordinates=[(point.longitude, point.latitude) for point in value.coordinates],
        ),
        provider_attribution=value.attribution,
    )


def _route(
    snapshot: RouteSnapshot,
    *,
    markers: list[RouteMarker],
    segments: list[RouteSegment],
) -> Route:
    return Route(
        route_id=uuid.uuid4(),
        schedule_version=snapshot.schedule_version,
        total_duration_seconds=sum(segment.duration_seconds for segment in segments),
        total_distance_meters=sum(segment.distance_meters for segment in segments),
        markers=markers,
        segments=segments,
        provider_attributions=list(
            dict.fromkeys(segment.provider_attribution for segment in segments)
        ),
        calculated_at=datetime.now(UTC),
    )


def _failed(code: str, *, retryable: bool) -> RouteCalculationResult:
    messages = {
        "ROUTE_PROVIDER_TIMEOUT": "경로 계산 시간이 초과되었습니다.",
        "ROUTE_PROVIDER_RATE_LIMITED": "경로 제공자 요청 한도를 초과했습니다.",
        "ROUTE_PROVIDER_UNAVAILABLE": "경로 제공자를 일시적으로 사용할 수 없습니다.",
        "ROUTE_NOT_FOUND": "이동 가능한 경로를 찾지 못했습니다.",
        "ROUTE_INVALID_RESULT": "경로 계산 결과가 올바르지 않습니다.",
    }
    failure_code = RouteFailureCode(code)
    return RouteCalculationResult(
        RouteStatus.FAILED,
        None,
        RouteFailure(code=failure_code, message=messages[code], retryable=retryable),
    )


def _route_values(
    snapshot: RouteSnapshot,
    result: RouteCalculationResult,
) -> dict[str, object | None]:
    if result.status is RouteStatus.READY and result.route is not None:
        providers = {segment.provider.value for segment in result.route.segments}
        provider = None if not providers else next(iter(providers)) if len(providers) == 1 else "MIXED"
        return {
            "route_id": result.route.route_id,
            "trip_day_id": snapshot.trip_day_id,
            "schedule_version": snapshot.schedule_version,
            "status": "READY",
            "is_active": True,
            "provider": provider,
            "total_duration_seconds": result.route.total_duration_seconds,
            "total_distance_meters": result.route.total_distance_meters,
            "route_payload": {
                "markers": [
                    marker.model_dump(mode="json", by_alias=True)
                    for marker in result.route.markers
                ],
                "segments": [
                    segment.model_dump(mode="json", by_alias=True)
                    for segment in result.route.segments
                ],
                "providerAttributions": result.route.provider_attributions,
            },
            "failure_code": None,
            "calculated_at": result.route.calculated_at,
        }
    if result.status is RouteStatus.FAILED and result.failure is not None:
        return {
            "route_id": uuid.uuid4(),
            "trip_day_id": snapshot.trip_day_id,
            "schedule_version": snapshot.schedule_version,
            "status": "FAILED",
            "is_active": True,
            "provider": None,
            "total_duration_seconds": None,
            "total_distance_meters": None,
            "route_payload": null(),
            "failure_code": result.failure.code.value,
            "calculated_at": datetime.now(UTC),
        }
    raise ValueError("영속화할 수 없는 경로 상태입니다.")


def route_data_from_model(day: TripDay, model: RouteModel | None) -> RouteData:
    if model is None:
        return NotCalculatedRouteData(
            trip_id=day.trip_id,
            date=day.visit_date,
            schedule_version=day.schedule_version,
            route_status=RouteStatus.NOT_CALCULATED,
            route=None,
            failure=None,
        )
    if model.status == "FAILED":
        code = RouteFailureCode(model.failure_code)
        failure = _failed(
            code.value,
            retryable=code
            in {
                RouteFailureCode.ROUTE_PROVIDER_TIMEOUT,
                RouteFailureCode.ROUTE_PROVIDER_RATE_LIMITED,
                RouteFailureCode.ROUTE_PROVIDER_UNAVAILABLE,
            },
        ).failure
        return FailedRouteData(
            trip_id=day.trip_id,
            date=day.visit_date,
            schedule_version=day.schedule_version,
            route_status=RouteStatus.FAILED,
            route=None,
            failure=failure,
        )
    if model.status != "READY" or model.route_payload is None or model.calculated_at is None:
        return NotCalculatedRouteData(
            trip_id=day.trip_id,
            date=day.visit_date,
            schedule_version=day.schedule_version,
            route_status=RouteStatus.NOT_CALCULATED,
            route=None,
            failure=None,
        )
    payload = model.route_payload
    route = Route.model_validate(
        {
            "routeId": model.route_id,
            "scheduleVersion": model.schedule_version,
            "totalDurationSeconds": model.total_duration_seconds,
            "totalDistanceMeters": model.total_distance_meters,
            "markers": payload["markers"],
            "segments": payload["segments"],
            "providerAttributions": payload["providerAttributions"],
            "calculatedAt": model.calculated_at,
        }
    )
    from app.schemas.route import ReadyRouteData

    return ReadyRouteData(
        trip_id=day.trip_id,
        date=day.visit_date,
        schedule_version=day.schedule_version,
        route_status=RouteStatus.READY,
        route=route,
        failure=None,
    )


def _route_data_from_result(
    snapshot: RouteSnapshot,
    result: RouteCalculationResult,
) -> RouteData:
    if result.status is RouteStatus.FAILED and result.failure is not None:
        return FailedRouteData(
            trip_id=snapshot.trip_id,
            date=snapshot.visit_date,
            schedule_version=snapshot.schedule_version,
            route_status=RouteStatus.FAILED,
            route=None,
            failure=result.failure,
        )
    raise ValueError("응답으로 변환할 수 없는 경로 계산 결과입니다.")


def _provider_for_mode(mode: ClientTransportMode) -> ClientProvider:
    return ClientProvider.ODSAY if mode is ClientTransportMode.TRANSIT else ClientProvider.TMAP


def _retry_version_conflict() -> AppError:
    return AppError(409, "VERSION_CONFLICT", "다른 곳에서 일정이 변경되었습니다.")


def _route_not_failed() -> AppError:
    return AppError(409, "ROUTE_NOT_FAILED", "실패한 경로만 다시 시도할 수 있습니다.")


__all__ = [
    "RouteCalculationResult",
    "RouteCalculationService",
    "RouteItemSnapshot",
    "RouteService",
    "RouteSnapshot",
    "build_route_service",
    "route_data_from_model",
]
