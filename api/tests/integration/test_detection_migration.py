"""F007 위치 이벤트 migration의 제약·외래키·멱등 인덱스를 검증한다."""

import asyncio
import os
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import text
from sqlalchemy.ext.asyncio import create_async_engine


async def _schema(database_url: str) -> dict[str, set[str]]:
    engine = create_async_engine(database_url)
    try:
        async with engine.connect() as connection:
            constraints = set((await connection.execute(text(
                "SELECT conname FROM pg_constraint "
                "WHERE conrelid = 'progress_events'::regclass"
            ))).scalars())
            foreign_keys = set((await connection.execute(text(
                "SELECT conname FROM pg_constraint "
                "WHERE conrelid IN ('progress_events'::regclass, "
                "'progress_transitions'::regclass) AND contype = 'f'"
            ))).scalars())
            indexes = set((await connection.execute(text(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'progress_events'"
            ))).scalars())
        return {"constraints": constraints, "foreign_keys": foreign_keys, "indexes": indexes}
    finally:
        await engine.dispose()


def test_detection_migration_round_trip() -> None:
    database_url = os.getenv("TEST_DATABASE_URL") or os.getenv("DATABASE_URL")
    if not database_url:
        pytest.skip("TEST_DATABASE_URL 또는 DATABASE_URL이 필요합니다.")
    config = Config(str(Path(__file__).parents[2] / "alembic.ini"))
    config.set_main_option("path_separator", "os")
    command.upgrade(config, "007_create_progress_events")
    schema = asyncio.run(_schema(database_url))
    assert "uq_progress_events_client_event" in schema["constraints"]
    assert "ck_progress_events_type" in schema["constraints"]
    assert "ck_progress_events_accuracy" in schema["constraints"]
    assert "fk_progress_transitions_trigger_event" in schema["foreign_keys"]
    assert "ix_progress_events_day_occurred" in schema["indexes"]
