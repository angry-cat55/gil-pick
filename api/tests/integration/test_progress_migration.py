"""F006 진행 migration의 PostgreSQL 계약과 왕복을 검증한다."""

import asyncio
import os
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import text
from sqlalchemy.ext.asyncio import create_async_engine


async def _inspect_schema(database_url: str) -> dict[str, object]:
    engine = create_async_engine(database_url)
    try:
        async with engine.connect() as connection:
            tables = set((await connection.execute(text(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public'"
            ))).scalars())
            progress_default = (await connection.execute(text(
                "SELECT column_default FROM information_schema.columns "
                "WHERE table_name = 'trip_days' AND column_name = 'progress_version'"
            ))).scalar_one_or_none()
            transitions = dict((await connection.execute(text(
                "SELECT conname, contype::text FROM pg_constraint "
                "WHERE conrelid = 'progress_transitions'::regclass"
            ))).all()) if "progress_transitions" in tables else {}
            transition_columns = set((await connection.execute(text(
                "SELECT column_name FROM information_schema.columns "
                "WHERE table_name = 'progress_transitions'"
            ))).scalars()) if "progress_transitions" in tables else set()
            segment_checks = set((await connection.execute(text(
                "SELECT conname FROM pg_constraint "
                "WHERE conrelid = 'progress_segments'::regclass"
            ))).scalars()) if "progress_segments" in tables else set()
            foreign_keys = dict((await connection.execute(text(
                "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint "
                "WHERE conrelid IN ('progress_transitions'::regclass, "
                "'progress_segments'::regclass) AND contype = 'f'"
            ))).all()) if {"progress_transitions", "progress_segments"} <= tables else {}
            indexes = dict((await connection.execute(text(
                "SELECT indexname, indexdef FROM pg_indexes "
                "WHERE tablename = 'progress_segments'"
            ))).all()) if "progress_segments" in tables else {}
        return {
            "tables": tables,
            "progress_default": progress_default,
            "transitions": transitions,
            "transition_columns": transition_columns,
            "segment_checks": segment_checks,
            "foreign_keys": foreign_keys,
            "indexes": indexes,
        }
    finally:
        await engine.dispose()


def test_progress_migration_round_trip_and_database_contract() -> None:
    database_url = os.getenv("TEST_DATABASE_URL") or os.getenv("DATABASE_URL")
    if not database_url:
        pytest.skip("TEST_DATABASE_URL 또는 DATABASE_URL이 필요합니다.")
    config = Config(str(Path(__file__).parents[2] / "alembic.ini"))
    config.set_main_option("path_separator", "os")

    try:
        command.upgrade(config, "006_progress_response_snapshot")
        schema = asyncio.run(_inspect_schema(database_url))

        assert {"progress_transitions", "progress_segments"} <= schema["tables"]
        assert schema["progress_default"] == "0"
        assert schema["transitions"]["uq_progress_transitions_day_idempotency"] == "u"
        assert {"request_target_status", "response_snapshot"} <= schema["transition_columns"]
        assert {"ck_progress_segments_duration", "ck_progress_segments_distance"} <= schema["segment_checks"]
        for name in (
            "progress_transitions_trip_day_id_fkey",
            "progress_segments_trip_day_id_fkey",
            "progress_segments_from_item_id_fkey",
            "progress_segments_to_item_id_fkey",
        ):
            assert "ON DELETE CASCADE" in schema["foreign_keys"][name]

        item_index = schema["indexes"]["uq_progress_segments_items"]
        start_index = schema["indexes"]["uq_progress_segments_start"]
        assert "CREATE UNIQUE INDEX" in item_index
        assert "(trip_day_id, from_item_id, to_item_id)" in item_index
        assert "WHERE (from_item_id IS NOT NULL)" in item_index
        assert "CREATE UNIQUE INDEX" in start_index
        assert "(trip_day_id, to_item_id)" in start_index
        assert "WHERE (from_item_id IS NULL)" in start_index

        command.downgrade(config, "005_create_progress_tables")
        without_snapshot = asyncio.run(_inspect_schema(database_url))
        assert "request_target_status" not in without_snapshot["transition_columns"]
        assert "response_snapshot" not in without_snapshot["transition_columns"]

        command.downgrade(config, "004_create_route_table")
        downgraded = asyncio.run(_inspect_schema(database_url))
        assert "progress_transitions" not in downgraded["tables"]
        assert "progress_segments" not in downgraded["tables"]
        assert downgraded["progress_default"] is None
    finally:
        # 공유 DB를 최신 head까지 되돌린다. 006에서 멈추면 이후 F007 migration(007,
        # progress_events)이 빠져 뒤따르는 감지 테스트가 깨진다.
        command.upgrade(config, "head")
