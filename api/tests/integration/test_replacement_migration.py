"""F010 신규 테이블 migration의 생성·원복을 검증한다."""

import asyncio
import os
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import inspect
from sqlalchemy.ext.asyncio import create_async_engine


async def _schema(database_url: str) -> dict[str, object]:
    engine = create_async_engine(database_url)
    try:
        async with engine.connect() as connection:
            def inspect_schema(sync_connection):
                inspector = inspect(sync_connection)
                return {
                    "tables": set(inspector.get_table_names()),
                    "preview_checks": {item["name"] for item in inspector.get_check_constraints("route_previews")},
                    "preview_indexes": {item["name"]: item for item in inspector.get_indexes("route_previews")},
                    "preview_uniques": {
                        item["name"] for item in inspector.get_unique_constraints("route_previews")
                    },
                    "replacement_uniques": {
                        item["name"]
                        for item in inspector.get_unique_constraints("place_replacements")
                    },
                    "replacement_indexes": {item["name"] for item in inspector.get_indexes("place_replacements")},
                }

            return await connection.run_sync(inspect_schema)
    finally:
        await engine.dispose()


async def _tables(database_url: str) -> set[str]:
    engine = create_async_engine(database_url)
    try:
        async with engine.connect() as connection:
            return await connection.run_sync(lambda sync_connection: set(inspect(sync_connection).get_table_names()))
    finally:
        await engine.dispose()


async def _existing_columns(database_url: str) -> dict[str, tuple[str, ...]]:
    engine = create_async_engine(database_url)
    try:
        async with engine.connect() as connection:
            def inspect_columns(sync_connection):
                inspector = inspect(sync_connection)
                return {
                    table: tuple(column["name"] for column in inspector.get_columns(table))
                    for table in inspector.get_table_names()
                }

            return await connection.run_sync(inspect_columns)
    finally:
        await engine.dispose()


def test_replacement_migration_round_trip() -> None:
    database_url = os.getenv("TEST_DATABASE_URL") or os.getenv("DATABASE_URL")
    if not database_url:
        pytest.skip("TEST_DATABASE_URL 또는 DATABASE_URL이 필요합니다.")
    config = Config(str(Path(__file__).parents[2] / "alembic.ini"))
    config.set_main_option("path_separator", "os")

    command.downgrade(config, "008_create_detections")
    existing_columns = asyncio.run(_existing_columns(database_url))
    command.upgrade(config, "head")
    schema = asyncio.run(_schema(database_url))
    assert {"route_previews", "place_replacements"} <= schema["tables"]
    assert "ck_route_previews_status" in schema["preview_checks"]
    assert "uq_route_previews_detection_idempotency" in schema["preview_uniques"]
    assert schema["preview_indexes"]["uq_route_previews_pending_detection"]["unique"]
    assert "ix_route_previews_detection" in schema["preview_indexes"]
    assert "uq_place_replacements_preview" in schema["replacement_uniques"]
    assert "ix_place_replacements_day" in schema["replacement_indexes"]
    migrated_columns = asyncio.run(_existing_columns(database_url))
    assert {table: migrated_columns[table] for table in existing_columns} == existing_columns

    command.downgrade(config, "008_create_detections")
    assert not {"route_previews", "place_replacements"} & asyncio.run(_tables(database_url))
    command.upgrade(config, "head")
