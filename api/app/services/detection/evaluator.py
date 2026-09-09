"""진행 중인 당일 일정의 변수 위험을 평가하고 ACTIVE 결과를 upsert한다."""

from __future__ import annotations

import logging
import uuid
from datetime import datetime, timedelta, timezone
from decimal import Decimal

from geoalchemy2 import Geometry
from sqlalchemy import cast, func, select, update
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.clients.kma import KmaClient
from app.clients.seoul_citydata import SeoulCityDataClient
from app.core.config import get_settings
from app.models.detection import Detection
from app.models.itinerary import ItineraryItem, Place, TripDay
from app.schemas.detection import (
    CongestionVerdict,
    OperatingHoursVerdict,
    VariableVerdicts,
    WeatherVerdict,
)
from app.services.detection.congestion import evaluate_congestion
from app.services.detection.operating_hours import evaluate_operating_hours
from app.services.detection.operating_hours_source import OperatingHoursSource
from app.services.detection.policy import VARIABLE_WEIGHTS
from app.services.detection.scoring import score_variables
from app.services.detection.weather import evaluate_weather

KST = timezone(timedelta(hours=9))
logger = logging.getLogger(__name__)
_REASONS = {
    "OPERATING_HOURS": "도착 시각에 영업이 어렵거나 곧 문을 닫아요",
    "WEATHER": "도착 시각에 비나 눈이 예상돼요",
    "CONGESTION": "도착 시각에 혼잡할 수 있어요",
}


async def evaluate_all_active(session: AsyncSession) -> int:
    """현재 평가 가능한 일정 항목을 조회해 위험 결과를 원자적으로 갱신한다."""
    now = datetime.now(KST)
    await session.execute(
        update(Detection)
        .where(
            Detection.status == "ACTIVE",
            Detection.item_id.in_(
                select(ItineraryItem.item_id)
                .join(TripDay, TripDay.trip_day_id == ItineraryItem.trip_day_id)
                .where(
                    (TripDay.status == "COMPLETED")
                    | ItineraryItem.status.in_(("COMPLETED", "SKIPPED"))
                )
            ),
        )
        .values(status="INVALIDATED", resolved_at=now)
    )
    rows = await _load_eligible_rows(session)
    return await _evaluate_rows(session, rows)


async def reevaluate_day(
    session_factory: async_sessionmaker[AsyncSession], trip_day_id: uuid.UUID
) -> int:
    """진행 변경이 저장된 뒤 한 날짜를 즉시 재평가하며 실패를 요청과 격리한다."""
    try:
        async with session_factory() as session:
            rows = await _load_eligible_rows(session, trip_day_id=trip_day_id)
            count = await _evaluate_rows(session, rows)
            await session.commit()
            return count
    except Exception:
        logger.exception("날짜 단위 변수 재평가에 실패했습니다.", extra={"trip_day_id": str(trip_day_id)})
        return 0


async def _load_eligible_rows(
    session: AsyncSession, *, trip_day_id: uuid.UUID | None = None
) -> list:
    point = cast(Place.location, Geometry(geometry_type="POINT", srid=4326))
    statement = (
        select(ItineraryItem, Place, TripDay, func.ST_Y(point), func.ST_X(point))
        .join(TripDay, TripDay.trip_day_id == ItineraryItem.trip_day_id)
        .join(Place, Place.place_id == ItineraryItem.place_id)
        .where(
            TripDay.detection_active.is_(True),
            TripDay.status == "IN_PROGRESS",
            ItineraryItem.status.in_(("PLANNED", "EN_ROUTE")),
            ItineraryItem.estimated_arrival_at.is_not(None),
        )
    )
    if trip_day_id is None:
        statement = statement.where(TripDay.visit_date == datetime.now(KST).date())
    else:
        statement = statement.where(TripDay.trip_day_id == trip_day_id)
    return list((await session.execute(statement)).all())


async def _evaluate_rows(session: AsyncSession, rows: list) -> int:
    """조회된 일정 항목을 공통 provider 정책으로 평가한다."""
    settings = get_settings()
    kma = KmaClient(settings)
    seoul = SeoulCityDataClient(settings)
    hours = OperatingHoursSource(settings)
    count = 0
    try:
        for item, place, day, latitude, longitude in rows:
            eta = item.estimated_arrival_at
            try:
                weather = await evaluate_weather(
                    kma,
                    category=place.category,
                    latitude=float(latitude),
                    longitude=float(longitude),
                    eta=eta,
                )
            except Exception:
                weather = WeatherVerdict(available=False, unavailable_reason="TIMEOUT")
            try:
                async with session.begin_nested():
                    congestion = await evaluate_congestion(
                        session,
                        seoul,
                        category=place.category,
                        latitude=float(latitude),
                        longitude=float(longitude),
                        eta=eta,
                    )
            except Exception:
                congestion = CongestionVerdict(available=False, unavailable_reason="TIMEOUT")
            try:
                operating = (
                    await evaluate_operating_hours(
                        hours, place_id=place.google_place_id, eta=eta
                    )
                    if place.google_place_id
                    else OperatingHoursVerdict(available=False, unavailable_reason="HOURS_UNKNOWN")
                )
            except Exception:
                operating = OperatingHoursVerdict(available=False, unavailable_reason="TIMEOUT")
            try:
                async with session.begin_nested():
                    await _store_detection(
                        session,
                        item=item,
                        day=day,
                        eta=eta,
                        congestion=congestion,
                        weather=weather,
                        operating=operating,
                    )
                count += 1
            except _NoRisk:
                continue
            except Exception:
                continue
    finally:
        await kma.aclose()
        await seoul.aclose()
        await hours.aclose()
    return count


class _NoRisk(Exception):
    """저장할 위험이 없는 정상 평가를 장소 단위 savepoint에서 제외한다."""


async def _store_detection(
    session: AsyncSession,
    *,
    item: ItineraryItem,
    day: TripDay,
    eta: datetime,
    congestion: CongestionVerdict,
    weather: WeatherVerdict,
    operating: OperatingHoursVerdict,
) -> None:
    """한 장소의 평가 결과를 ACTIVE upsert로 저장한다."""
    score = score_variables(
        congestion=congestion,
        weather=weather,
        operating_hours=operating,
    )
    now = datetime.now(KST)
    variables = VariableVerdicts(
        congestion=congestion,
        weather=weather,
        operating_hours=operating,
    )
    available_weight = sum(
        VARIABLE_WEIGHTS[key]
        for key, verdict in {
            "CONGESTION": congestion,
            "WEATHER": weather,
            "OPERATING_HOURS": operating,
        }.items()
        if verdict.available
    )
    snapshot = {
        "evaluatedAt": now.isoformat(),
        "eta": eta.isoformat(),
        "variables": variables.model_dump(mode="json", by_alias=True),
        "weights": {
            key: {
                "original": weight,
                "normalized": weight / available_weight if available_weight else None,
            }
            for key, weight in VARIABLE_WEIGHTS.items()
        },
    }
    if score.primary_type is None or score.total_risk_score == 0:
        result = await session.execute(
            update(Detection)
            .where(
                Detection.fingerprint
                == Detection.make_fingerprint(day.trip_day_id, item.item_id),
                Detection.status == "ACTIVE",
            )
            .values(
                eta=eta,
                score=Decimal(str(score.score)),
                evaluation_snapshot=snapshot,
                last_evaluated_at=now,
            )
        )
        if result.rowcount == 0:
            raise _NoRisk
        return
    values = {
        "trip_day_id": day.trip_day_id,
        "item_id": item.item_id,
        "primary_type": score.primary_type.value,
        "status": "ACTIVE",
        "eta": eta,
        "score": Decimal(str(score.score)),
        "reason": _REASONS[score.primary_type.value],
        "evaluation_snapshot": snapshot,
        "fingerprint": Detection.make_fingerprint(day.trip_day_id, item.item_id),
        "detected_at": now,
        "last_evaluated_at": now,
    }
    statement = insert(Detection).values(**values)
    statement = statement.on_conflict_do_update(
        index_elements=[Detection.fingerprint],
        index_where=Detection.status == "ACTIVE",
        set_={
            key: values[key]
            for key in (
                "primary_type",
                "eta",
                "score",
                "reason",
                "evaluation_snapshot",
                "last_evaluated_at",
            )
        },
    )
    await session.execute(statement)
