"""진행 ORM metadata가 migration 계약과 일치하는지 확인한다."""

from sqlalchemy.dialects import postgresql
from sqlalchemy.dialects.postgresql import JSONB

from app.models.itinerary import ItineraryItem, TripDay
from app.models.progress import ProgressSegment, ProgressTransition


def test_progress_models_expose_expected_columns_and_relationships() -> None:
    assert "progress_version" in TripDay.__table__.columns
    assert set(ProgressTransition.__table__.columns.keys()) >= {
        "transition_id", "trip_day_id", "primary_item_id", "trigger_event_id",
        "transition_type", "status", "source", "decision", "affected_items",
        "detected_at", "auto_finalize_at", "confirmed_at", "undo_deadline",
        "cancelled_at", "undone_at", "schedule_version_before",
        "schedule_version_after", "progress_version_after", "idempotency_key",
    }
    assert set(ProgressSegment.__table__.columns.keys()) >= {
        "progress_segment_id", "trip_day_id", "from_item_id", "to_item_id",
        "transport_mode", "provider", "duration_seconds", "distance_meters",
        "computed_at",
    }
    assert isinstance(
        ProgressTransition.__table__.c.affected_items.type.dialect_impl(
            postgresql.dialect()
        ),
        JSONB,
    )
    assert ProgressTransition.trip_day.property.mapper.class_ is TripDay
    assert ProgressSegment.to_item.property.mapper.class_ is ItineraryItem
    assert TripDay.progress_transitions.property.cascade.delete_orphan
    assert TripDay.progress_segments.property.cascade.delete_orphan


def test_progress_models_declare_database_invariants() -> None:
    day_constraints = {constraint.name for constraint in TripDay.__table__.constraints}
    transition_constraints = {
        constraint.name for constraint in ProgressTransition.__table__.constraints
    }
    segment_constraints = {
        constraint.name for constraint in ProgressSegment.__table__.constraints
    }
    segment_indexes = {index.name for index in ProgressSegment.__table__.indexes}

    assert "ck_trip_days_progress_version" in day_constraints
    assert "uq_progress_transitions_day_idempotency" in transition_constraints
    assert {"ck_progress_segments_duration", "ck_progress_segments_distance"} <= segment_constraints
    assert {"uq_progress_segments_items", "uq_progress_segments_start"} <= segment_indexes
