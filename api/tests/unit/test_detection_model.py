"""F007 위치 이벤트 ORM이 저장 계약을 따르는지 검증한다."""

from geoalchemy2 import Geography

from app.models.itinerary import ItineraryItem, TripDay
from app.models.progress import ProgressEvent


def test_progress_event_exposes_expected_columns_and_relationships() -> None:
    assert set(ProgressEvent.__table__.columns.keys()) == {
        "progress_event_id",
        "client_event_id",
        "trip_day_id",
        "item_id",
        "event_type",
        "geofence_id",
        "location",
        "accuracy_meters",
        "occurred_at",
        "received_at",
        "accepted",
        "rejection_reason",
    }
    location_type = ProgressEvent.__table__.c.location.type
    assert isinstance(location_type, Geography)
    assert location_type.geometry_type == "POINT"
    assert location_type.srid == 4326
    assert ProgressEvent.trip_day.property.mapper.class_ is TripDay
    assert ProgressEvent.item.property.mapper.class_ is ItineraryItem


def test_progress_event_declares_database_invariants() -> None:
    constraints = {
        constraint.name for constraint in ProgressEvent.__table__.constraints
    }
    indexes = {index.name for index in ProgressEvent.__table__.indexes}

    assert "uq_progress_events_client_event_id" in constraints
    assert "ck_progress_events_event_type" in constraints
    assert "ix_progress_events_trip_day_occurred_at" in indexes
    assert next(iter(ProgressEvent.__table__.c.trip_day_id.foreign_keys)).ondelete == "CASCADE"
    assert next(iter(ProgressEvent.__table__.c.item_id.foreign_keys)).ondelete == "CASCADE"
