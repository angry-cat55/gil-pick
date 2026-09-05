"""일정 ORM metadata가 migration 계약과 일치하는지 확인한다."""

from geoalchemy2 import Geography

from app.models.itinerary import ItineraryItem, Place, TripDay


def test_itinerary_models_expose_expected_columns_and_relationships() -> None:
    assert set(TripDay.__table__.columns.keys()) >= {
        "trip_day_id", "trip_id", "visit_date", "day_number", "schedule_version"
    }
    assert set(Place.__table__.columns.keys()) >= {
        "place_id", "tour_content_id", "google_place_id", "location"
    }
    assert set(ItineraryItem.__table__.columns.keys()) >= {
        "item_id", "trip_day_id", "place_id", "sequence", "planned_stay_minutes"
    }
    assert TripDay.trip.property.mapper.class_.__name__ == "Trip"
    assert TripDay.items.property.mapper.class_ is ItineraryItem
    assert ItineraryItem.place.property.mapper.class_ is Place


def test_place_geography_mapping_uses_point_4326() -> None:
    location_type = Place.__table__.c.location.type

    assert isinstance(location_type, Geography)
    assert location_type.geometry_type == "POINT"
    assert location_type.srid == 4326


def test_model_constraints_cover_documented_invariants() -> None:
    trip_day_constraints = {constraint.name for constraint in TripDay.__table__.constraints}
    place_indexes = {index.name for index in Place.__table__.indexes}
    item_constraints = {constraint.name for constraint in ItineraryItem.__table__.constraints}

    assert {"uq_trip_days_trip_visit_date", "uq_trip_days_trip_day_number"} <= trip_day_constraints
    assert {"uq_places_tour_content_id", "uq_places_google_place_id", "ix_places_location"} <= place_indexes
    assert {"uq_itinerary_items_day_sequence", "ck_itinerary_items_stay_minutes"} <= item_constraints
