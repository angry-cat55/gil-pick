"""대체 장소 추천 service."""

from __future__ import annotations

import uuid
from datetime import UTC, datetime
from typing import Any

from geoalchemy2 import Geometry
from sqlalchemy import cast, func, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.errors import AppError
from app.api.v1.detections import owned_detection
from app.clients.tour_api import TourApiClientError
from app.models.detection import Detection
from app.models.itinerary import ItineraryItem, Place
from app.schemas.alternatives import AlternativeListData
from app.schemas.detection import DetectionDismissData, DetectionStatus
from app.schemas.place import PlaceCategory, PlaceSource, PlaceSummary, TourApiCategory
from app.services.alternatives.candidates import build_candidates

_STAY_MINUTES = {
    PlaceCategory.NATURE: 120,
    PlaceCategory.HISTORY_CULTURE: 90,
    PlaceCategory.FOOD: 60,
    PlaceCategory.CAFE: 60,
    PlaceCategory.SHOPPING: 90,
    PlaceCategory.OTHER: 60,
}


class AlternativeService:
    """감지 소유권과 일정 정보를 후보 탐색 파이프라인에 연결한다."""

    def __init__(
        self,
        session: AsyncSession,
        *,
        tour_client: Any,
        google_client: Any,
        operating_hours_source: Any,
        kma_client: Any,
        seoul_client: Any,
        candidate_secret: str,
    ) -> None:
        self.session = session
        self.tour_client = tour_client
        self.google_client = google_client
        self.operating_hours_source = operating_hours_source
        self.kma_client = kma_client
        self.seoul_client = seoul_client
        self.candidate_secret = candidate_secret

    async def list_candidates(
        self, detection_id: uuid.UUID, user_id: uuid.UUID
    ) -> AlternativeListData:
        """사용자 소유 ACTIVE 감지의 대체 후보를 현재 외부 데이터로 계산한다."""
        detection, _, _ = await owned_detection(detection_id, user_id, self.session)
        if detection.status != "ACTIVE":
            raise AppError(
                409,
                "DETECTION_NOT_ACTIVE",
                "이미 처리된 감지 결과입니다.",
                details={"status": detection.status},
            )

        point = cast(Place.location, Geometry(geometry_type="POINT", srid=4326))
        place, latitude, longitude = (
            await self.session.execute(
                select(Place, func.ST_Y(point), func.ST_X(point))
                .join(ItineraryItem, ItineraryItem.place_id == Place.place_id)
                .where(ItineraryItem.item_id == detection.item_id)
            )
        ).one()
        origin = self._place_summary(place, float(latitude), float(longitude))
        scheduled = {
            provider_id
            for scheduled_place in (
                await self.session.execute(
                    select(Place)
                    .join(ItineraryItem, ItineraryItem.place_id == Place.place_id)
                    .where(ItineraryItem.trip_day_id == detection.trip_day_id)
                )
            ).scalars()
            if (provider_id := self._provider_place_id(scheduled_place)) is not None
        }
        evaluated_at = datetime.now(UTC)
        try:
            result = await build_candidates(
                session=self.session,
                detection_id=detection_id,
                origin=origin,
                eta=detection.eta,
                scheduled_place_ids=scheduled,
                tour_client=self.tour_client,
                google_client=self.google_client,
                operating_hours_source=self.operating_hours_source,
                kma_client=self.kma_client,
                seoul_client=self.seoul_client,
                candidate_secret=self.candidate_secret,
                evaluated_at=evaluated_at,
            )
        except TourApiClientError as exc:
            status = 504 if exc.code == "TOUR_API_TIMEOUT" else 502
            raise AppError(
                status,
                exc.code if status == 504 else "TOUR_API_FAILED",
                "대체 장소 제공자 요청에 실패했습니다.",
                retryable=exc.retryable,
            ) from exc
        return result

    @staticmethod
    def _provider_place_id(place: Place) -> str | None:
        if place.tour_content_id:
            return f"tourapi:{place.tour_content_id}"
        if place.google_place_id:
            return f"google:{place.google_place_id}"
        return None

    @classmethod
    def _place_summary(
        cls, place: Place, latitude: float, longitude: float
    ) -> PlaceSummary:
        category = PlaceCategory(place.category)
        provider_id = cls._provider_place_id(place)
        if provider_id is None:
            raise AppError(404, "DETECTION_NOT_FOUND", "기준 장소를 찾을 수 없습니다.")
        source, source_id = provider_id.split(":", 1)
        return PlaceSummary(
            place_id=provider_id,
            source=PlaceSource.TOUR_API if source == "tourapi" else PlaceSource.GOOGLE_PLACES,
            source_place_id=source_id,
            name=place.name,
            category=category,
            tour_api_category=TourApiCategory(
                large=place.tour_category_1,
                middle=place.tour_category_2,
                small=place.tour_category_3,
            ) if place.tour_content_id else None,
            address=place.address,
            latitude=latitude,
            longitude=longitude,
            image_url=place.image_url,
            recommended_stay_minutes=_STAY_MINUTES[category],
            rating=None,
            user_rating_count=None,
            business_status=None,
            regular_opening_hours=None,
            current_opening_hours=None,
            google_attributions=None,
        )


async def dismiss_detection(
    detection_id: uuid.UUID, user_id: uuid.UUID, session: AsyncSession
) -> DetectionDismissData:
    """`기존 일정 그대로 진행`(DETECT-004): ACTIVE 감지를 거절 상태로 바꾼다.

    상태 기반 멱등이다. `ACTIVE`이면 `status=DISMISSED`, `resolved_at=now`를 한 번만 기록하고,
    이미 `DISMISSED`·`RESOLVED`·`INVALIDATED`이면 아무것도 바꾸지 않고 현재 상태를 돌려준다.
    일정·경로는 건드리지 않는다.

    Args:
        detection_id: 거절할 감지 ID.
        user_id: 인증된 사용자 ID.
        session: 현재 DB session.

    Returns:
        감지 ID, 반영 후 상태, 거절·처리 시각(`INVALIDATED`처럼 사용자 결정이 없으면 None).

    Raises:
        AppError: 감지가 없거나(404) 요청 사용자가 소유자가 아닌 경우(403).
    """
    detection, _, _ = await owned_detection(detection_id, user_id, session)
    now = datetime.now(UTC)
    # WHERE status='ACTIVE' 조건으로 동시 요청이 있어도 전이는 한 번만 반영된다.
    await session.execute(
        update(Detection)
        .where(Detection.detection_id == detection_id, Detection.status == "ACTIVE")
        .values(status="DISMISSED", resolved_at=now)
    )
    await session.refresh(detection)
    decided_at = None if detection.status == DetectionStatus.INVALIDATED else detection.resolved_at
    return DetectionDismissData(
        detection_id=detection_id,
        status=DetectionStatus(detection.status),
        decided_at=decided_at,
    )


__all__ = ["AlternativeService", "dismiss_detection"]

