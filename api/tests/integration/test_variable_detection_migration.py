"""F008 detections migration 계보와 PostgreSQL 계약 검증."""

import asyncio
import os
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from alembic.script import ScriptDirectory
from sqlalchemy import text
from sqlalchemy.ext.asyncio import create_async_engine


def _config() -> Config:
    config = Config(str(Path(__file__).parents[2] / "alembic.ini"))
    config.set_main_option("path_separator", "os")
    return config


def test_detection_migration_is_single_alembic_head() -> None:
    script = ScriptDirectory.from_config(_config())
    assert script.get_heads() == ["009_create_replacement_tables"]
    assert script.get_revision("008_create_detections").down_revision == "007_create_progress_events"


async def _inspect(database_url: str) -> dict[str, object]:
    engine = create_async_engine(database_url)
    try:
        async with engine.connect() as connection:
            columns = set((await connection.execute(text("SELECT column_name FROM information_schema.columns WHERE table_name='detections'"))).scalars())
            constraints = dict((await connection.execute(text("SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='detections'::regclass"))).all())
            indexes = dict((await connection.execute(text("SELECT indexname, indexdef FROM pg_indexes WHERE tablename='detections'"))).all())
        return {"columns": columns, "constraints": constraints, "indexes": indexes}
    finally:
        await engine.dispose()


async def _table_exists(database_url: str) -> bool:
    engine = create_async_engine(database_url)
    try:
        async with engine.connect() as connection:
            return bool((await connection.execute(text("SELECT to_regclass('public.detections')"))).scalar())
    finally:
        await engine.dispose()


def test_detection_migration_round_trip() -> None:
    database_url = os.getenv("TEST_DATABASE_URL") or os.getenv("DATABASE_URL")
    if not database_url:
        pytest.skip("TEST_DATABASE_URL 또는 DATABASE_URL이 필요합니다.")
    config = _config()
    try:
        command.upgrade(config, "008_create_detections")
        schema = asyncio.run(_inspect(database_url))
        assert schema["columns"] == {
            "detection_id", "trip_day_id", "item_id", "primary_type", "status",
            "eta", "score", "reason", "evaluation_snapshot", "fingerprint",
            "detected_at", "last_evaluated_at", "read_at", "resolved_at",
        }
        assert {"ck_detections_score", "ck_detections_primary_type", "ck_detections_status"} <= schema["constraints"]
        assert "ON DELETE CASCADE" in schema["constraints"]["detections_trip_day_id_fkey"]
        assert "ON DELETE CASCADE" in schema["constraints"]["detections_item_id_fkey"]
        active_index = schema["indexes"]["uq_detections_active_fingerprint"]
        assert "CREATE UNIQUE INDEX" in active_index
        assert "WHERE" in active_index and "ACTIVE" in active_index
        assert {"ix_detections_day_status_detected", "ix_detections_item"} <= schema["indexes"]
        command.downgrade(config, "007_create_progress_events")
        assert asyncio.run(_table_exists(database_url)) is False
    finally:
        command.upgrade(config, "008_create_detections")
