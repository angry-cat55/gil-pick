"""Route ORM metadata가 F005 저장 계약과 일치하는지 확인한다."""

from app.models.itinerary import TripDay
from app.models.route import Route


def test_route_model_exposes_columns_and_day_relationship() -> None:
    assert set(Route.__table__.columns.keys()) >= {
        "route_id", "trip_day_id", "schedule_version", "status", "is_active",
        "provider", "total_duration_seconds", "total_distance_meters",
        "route_payload", "failure_code", "calculated_at",
    }
    assert Route.trip_day.property.mapper.class_ is TripDay
    assert TripDay.routes.property.mapper.class_ is Route
    assert TripDay.routes.property.cascade.delete_orphan


def test_route_model_declares_database_invariants() -> None:
    constraints = {constraint.name for constraint in Route.__table__.constraints}
    indexes = {index.name for index in Route.__table__.indexes}

    assert {
        "uq_routes_day_schedule_version", "ck_routes_schedule_version",
        "ck_routes_status", "ck_routes_provider", "ck_routes_state_fields",
    } <= constraints
    assert "uq_routes_active_day" in indexes
    assert next(index for index in Route.__table__.indexes if index.name == "uq_routes_active_day").unique
