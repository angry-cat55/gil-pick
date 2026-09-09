"""F010 일정 변경 ORM이 저장 계약을 따르는지 검증한다."""

from app.models.replacement import PlaceReplacement, RoutePreview


def test_route_preview_exposes_expected_columns_and_invariants() -> None:
    assert set(RoutePreview.__table__.columns.keys()) == {
        "preview_id",
        "detection_id",
        "trip_day_id",
        "item_id",
        "original_place_id",
        "alternative_place_id",
        "schedule_version",
        "idempotency_key",
        "request_fingerprint",
        "route_payload",
        "total_duration_seconds",
        "total_distance_meters",
        "provider",
        "comparison",
        "response_snapshot",
        "status",
        "expires_at",
        "created_at",
    }
    constraints = {constraint.name for constraint in RoutePreview.__table__.constraints}
    indexes = {index.name for index in RoutePreview.__table__.indexes}

    assert "ck_route_previews_status" in constraints
    assert "uq_route_previews_detection_idempotency" in constraints
    assert "uq_route_previews_pending_detection" in indexes
    assert "ix_route_previews_detection" in indexes
    assert next(iter(RoutePreview.__table__.c.detection_id.foreign_keys)).ondelete == "CASCADE"
    assert next(iter(RoutePreview.__table__.c.trip_day_id.foreign_keys)).ondelete == "CASCADE"
    assert next(iter(RoutePreview.__table__.c.item_id.foreign_keys)).ondelete == "CASCADE"


def test_place_replacement_exposes_expected_columns_and_invariants() -> None:
    assert set(PlaceReplacement.__table__.columns.keys()) == {
        "replacement_id",
        "preview_id",
        "detection_id",
        "trip_day_id",
        "item_id",
        "original_place_id",
        "new_place_id",
        "before_schedule_version",
        "approved_schedule_version",
        "approved_at",
        "undo_expires_at",
        "idempotency_key",
        "response_snapshot",
        "undone_at",
        "undo_schedule_version",
    }
    constraints = {constraint.name for constraint in PlaceReplacement.__table__.constraints}
    indexes = {index.name for index in PlaceReplacement.__table__.indexes}

    assert "uq_place_replacements_preview" in constraints
    assert "ix_place_replacements_day" in indexes
    assert next(iter(PlaceReplacement.__table__.c.trip_day_id.foreign_keys)).ondelete == "CASCADE"
