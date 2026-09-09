"""F010 미리보기 생성·폐기의 DB 무결성 검증."""

import os
import uuid
from datetime import UTC, datetime, timedelta

import pytest
from geoalchemy2 import WKTElement
from sqlalchemy import select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.api.errors import AppError
from app.models.auth import User
from app.models.detection import Detection
from app.models.itinerary import ItineraryItem, Place, TripDay
from app.models.replacement import RoutePreview as RoutePreviewModel
from app.models.route import Route as RouteModel
from app.models.trip import Trip
from app.schemas.replacement import CreatePreviewRequest
from app.schemas.route import Provider, Route, RouteGeometry, RouteMarker, RouteSegment, RouteStatus, TransportMode
from app.services.alternatives.candidate_token import issue_candidate_token
from app.services.replacement import PREVIEW_TTL_MINUTES, ReplacementService
from app.services.route import RouteCalculationResult

pytestmark = pytest.mark.asyncio
SECRET = "replacement-preview-test-secret-value"


class _Calculator:
    async def calculate(self, snapshot):
        markers = [
            RouteMarker(
                item_id=item.item_id, sequence=item.sequence, name=item.name,
                latitude=item.coordinate.latitude, longitude=item.coordinate.longitude,
            )
            for item in snapshot.items
        ]
        segments = [
            RouteSegment(
                sequence=index, from_item_id=left.item_id, to_item_id=right.item_id,
                transport_mode=TransportMode.WALK, provider=Provider.TMAP,
                duration_seconds=600, distance_meters=800,
                geometry=RouteGeometry(type="LineString", coordinates=[
                    (left.coordinate.longitude, left.coordinate.latitude),
                    (right.coordinate.longitude, right.coordinate.latitude),
                ]), provider_attribution="TMAP",
            )
            for index, (left, right) in enumerate(zip(snapshot.items, snapshot.items[1:]), 1)
        ]
        return RouteCalculationResult(RouteStatus.READY, Route(
            route_id=uuid.uuid4(), schedule_version=snapshot.schedule_version,
            total_duration_seconds=sum(item.duration_seconds for item in segments),
            total_distance_meters=sum(item.distance_meters for item in segments),
            markers=markers, segments=segments, provider_attributions=["TMAP"],
            calculated_at=datetime.now(UTC),
        ), None)


class _Unused:
    class _Result:
        def one_or_none(self):
            return None

    async def execute(self, _statement):
        return self._Result()

    async def scalar(self, _statement):
        return None


async def _seed(factory):
    now = datetime.now(UTC)
    async with factory.begin() as session:
        user = User(social_provider="KAKAO", social_subject=f"replacement-{uuid.uuid4()}")
        session.add(user)
        await session.flush()
        trip = Trip(user_id=user.user_id, name="미리보기", start_date=now.date(), end_date=now.date())
        original = Place(tour_content_id=f"original-{uuid.uuid4()}", name="기존 장소", category="OTHER",
                         location=WKTElement("POINT(126.97 37.57)", srid=4326))
        following = Place(tour_content_id=f"following-{uuid.uuid4()}", name="다음 장소", category="OTHER",
                          location=WKTElement("POINT(126.98 37.58)", srid=4326))
        alternative = Place(tour_content_id=f"alternative-{uuid.uuid4()}", name="대체 장소", category="OTHER",
                            location=WKTElement("POINT(126.99 37.59)", srid=4326))
        session.add_all([trip, original, following, alternative])
        await session.flush()
        day = TripDay(trip_id=trip.trip_id, visit_date=now.date(), day_number=1, status="IN_PROGRESS",
                      actual_started_at=now, detection_active=True)
        session.add(day)
        await session.flush()
        target = ItineraryItem(
            trip_day_id=day.trip_day_id, place_id=original.place_id, sequence=1, status="PLANNED",
            planned_stay_minutes=60, stay_source="RECOMMENDED", transport_mode_to_next="WALK",
            estimated_arrival_at=now,
        )
        second = ItineraryItem(
            trip_day_id=day.trip_day_id, place_id=following.place_id, sequence=2, status="PLANNED",
            planned_stay_minutes=60, stay_source="RECOMMENDED", estimated_arrival_at=now + timedelta(minutes=70),
        )
        session.add_all([target, second])
        await session.flush()
        detection = Detection(
            trip_day_id=day.trip_day_id, item_id=target.item_id, primary_type="WEATHER", status="ACTIVE",
            eta=now, reason="비 예보", evaluation_snapshot={"variables": {}},
            fingerprint=Detection.make_fingerprint(day.trip_day_id, target.item_id),
        )
        route = RouteModel(
            trip_day_id=day.trip_day_id, schedule_version=1, status="READY", is_active=True,
            provider="TMAP", total_duration_seconds=900, total_distance_meters=1000,
            route_payload={"markers": [], "segments": [], "providerAttributions": ["TMAP"]},
            calculated_at=now,
        )
        session.add_all([detection, route])
        await session.flush()
        return {
            "user_id": user.user_id, "trip_day_id": day.trip_day_id, "item_id": target.item_id,
            "original_place_id": original.place_id, "detection_id": detection.detection_id,
            "alternative_public_id": f"tourapi:{alternative.tour_content_id}", "now": now,
        }


def _service(session, now):
    return ReplacementService(
        session, calculator=_Calculator(), place_service=_Unused(),
        operating_hours_source=_Unused(), candidate_secret=SECRET, now=lambda: now,
    )


async def test_create_preview_preserves_schedule_route_and_detection_and_supersedes() -> None:
    url = os.getenv("TEST_DATABASE_URL") or os.getenv("DATABASE_URL")
    if not url:
        pytest.skip("TEST_DATABASE_URL 또는 DATABASE_URL이 필요합니다.")
    engine = create_async_engine(url)
    factory = async_sessionmaker(engine, expire_on_commit=False)
    try:
        seeded = await _seed(factory)
        token = issue_candidate_token(
            seeded["detection_id"], seeded["alternative_public_id"], seeded["now"], SECRET
        )
        payload = CreatePreviewRequest(
            place_id=seeded["alternative_public_id"], candidate_id=token, schedule_version=1
        )
        async with factory() as session:
            first = await _service(session, seeded["now"]).create_preview(
                detection_id=seeded["detection_id"], user_id=seeded["user_id"],
                payload=payload, idempotency_key="first",
            )
            await session.commit()
            repeated = await _service(session, seeded["now"]).create_preview(
                detection_id=seeded["detection_id"], user_id=seeded["user_id"],
                payload=payload, idempotency_key="first",
            )
            assert repeated == first
            second = await _service(session, seeded["now"]).create_preview(
                detection_id=seeded["detection_id"], user_id=seeded["user_id"],
                payload=payload.model_copy(update={"candidate_id": None}), idempotency_key="second",
            )
            await session.commit()

            day = await session.get(TripDay, seeded["trip_day_id"])
            item = await session.get(ItineraryItem, seeded["item_id"])
            detection = await session.get(Detection, seeded["detection_id"])
            route = await session.scalar(select(RouteModel).where(
                RouteModel.trip_day_id == seeded["trip_day_id"], RouteModel.is_active.is_(True)
            ))
            previews = list((await session.scalars(select(RoutePreviewModel).where(
                RoutePreviewModel.detection_id == seeded["detection_id"]
            ).order_by(RoutePreviewModel.created_at))).all())

            assert day.schedule_version == 1
            assert item.place_id == seeded["original_place_id"]
            assert detection.status == "ACTIVE"
            assert route.total_duration_seconds == 900
            assert [preview.status for preview in previews] == ["SUPERSEDED", "PENDING"]
            assert second.expires_at == seeded["now"] + timedelta(minutes=PREVIEW_TTL_MINUTES)
            assert set(second.comparison.model_dump(by_alias=True)) == {
                "totalDurationSeconds", "totalDistanceMeters", "estimatedArrivalAt", "closesAt",
            }

            await _service(session, seeded["now"]).reject_preview(
                preview_id=second.preview_id, user_id=seeded["user_id"]
            )
            await _service(session, seeded["now"]).reject_preview(
                preview_id=second.preview_id, user_id=seeded["user_id"]
            )
            await session.commit()
            assert (await session.get(RoutePreviewModel, second.preview_id)).status == "REJECTED"
            previews[0].status = "APPROVED"
            await session.flush()
            with pytest.raises(AppError, match="ALREADY_APPROVED"):
                await _service(session, seeded["now"]).reject_preview(
                    preview_id=previews[0].preview_id, user_id=seeded["user_id"]
                )
    finally:
        await engine.dispose()


@pytest.mark.parametrize("mismatch", ["detection", "place", "expired"])
async def test_invalid_candidate_is_rejected_before_route_calculation(mismatch: str) -> None:
    now = datetime.now(UTC)
    detection_id = uuid.uuid4()
    token = issue_candidate_token(
        uuid.uuid4() if mismatch == "detection" else detection_id,
        "tourapi:different" if mismatch == "place" else "tourapi:other",
        now - timedelta(minutes=16) if mismatch == "expired" else now,
        SECRET,
    )
    service = ReplacementService(
        _Unused(), calculator=_Unused(), place_service=_Unused(),
        operating_hours_source=_Unused(), candidate_secret=SECRET, now=lambda: now,
    )
    with pytest.raises(AppError, match="INVALID_CANDIDATE") as caught:
        await service.create_preview(
            detection_id=detection_id, user_id=uuid.uuid4(),
            payload=CreatePreviewRequest(place_id="tourapi:other", candidate_id=token, schedule_version=1),
            idempotency_key="invalid",
        )
    assert caught.value.status_code == 400
