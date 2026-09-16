"""F011 알림 migration의 PostgreSQL 계약과 왕복을 검증한다."""

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


async def _inspect_schema(database_url: str) -> dict[str, object]:
    engine = create_async_engine(database_url)
    try:
        async with engine.connect() as connection:
            table_exists = bool((await connection.execute(text(
                "SELECT to_regclass('public.notifications')"
            ))).scalar())
            constraints = dict((await connection.execute(text(
                "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint "
                "WHERE conrelid = 'notifications'::regclass"
            ))).all()) if table_exists else {}
            indexes = dict((await connection.execute(text(
                "SELECT indexname, indexdef FROM pg_indexes "
                "WHERE tablename = 'notifications'"
            ))).all()) if table_exists else {}
            columns = set((await connection.execute(text(
                "SELECT column_name FROM information_schema.columns "
                "WHERE table_schema = 'public' AND table_name = 'notifications'"
            ))).scalars()) if table_exists else set()
            device_indexes = dict((await connection.execute(text(
                "SELECT indexname, indexdef FROM pg_indexes "
                "WHERE tablename = 'device_sessions'"
            ))).all())
        return {
            "table_exists": table_exists,
            "constraints": constraints,
            "indexes": indexes,
            "columns": columns,
            "device_indexes": device_indexes,
        }
    finally:
        await engine.dispose()


def test_notification_migration_is_single_alembic_head() -> None:
    script = ScriptDirectory.from_config(_config())
    assert len(script.get_heads()) == 1
    assert (
        script.get_revision("017_notification_delivery_state").down_revision
        == "016_defer_itinerary_sequence"
    )
    assert (
        script.get_revision("012_add_kakao_route_provider").down_revision
        == "011_create_notifications"
    )
    assert (
        script.get_revision("011_create_notifications").down_revision
        == "010_replacement_approval"
    )


def test_notification_migration_round_trip() -> None:
    database_url = os.getenv("TEST_DATABASE_URL") or os.getenv("DATABASE_URL")
    if not database_url:
        pytest.skip("TEST_DATABASE_URL 또는 DATABASE_URL이 필요합니다.")
    config = _config()

    try:
        command.upgrade(config, "head")
        head_schema = asyncio.run(_inspect_schema(database_url))
        assert {"delivery_status", "delivery_attempts", "next_attempt_at"} <= head_schema["columns"]
        assert "ck_notifications_delivery_status" in head_schema["constraints"]
        assert "delivery_status" in head_schema["indexes"]["ix_notifications_pending"]
        command.downgrade(config, "010_replacement_approval")
        command.upgrade(config, "011_create_notifications")
        schema = asyncio.run(_inspect_schema(database_url))

        assert schema["table_exists"] is True
        check = schema["constraints"]["ck_notifications_type"]
        for notification_type in (
            "PLACE_CHANGE_SUGGESTION",
            "ARRIVAL_CHECK",
            "DEPARTURE_CHECK",
            "ARRIVAL_AUTO_CONFIRMED",
            "DEPARTURE_AUTO_CONFIRMED",
        ):
            assert notification_type in check

        assert {
            "ix_notifications_user_unread",
            "ix_notifications_retention",
            "uq_notifications_dedup",
            "ix_notifications_pending",
        } <= schema["indexes"].keys()
        assert "CREATE UNIQUE INDEX" in schema["indexes"]["uq_notifications_dedup"]
        assert "WHERE (dedup_key IS NOT NULL)" in schema["indexes"]["uq_notifications_dedup"]
        assert "WHERE (sent_at IS NULL)" in schema["indexes"]["ix_notifications_pending"]

        fcm_index = schema["device_indexes"]["uq_device_sessions_fcm_token"]
        assert "CREATE UNIQUE INDEX" in fcm_index
        assert "fcm_token IS NOT NULL" in fcm_index
        assert "revoked_at IS NULL" in fcm_index

        command.downgrade(config, "010_replacement_approval")
        downgraded = asyncio.run(_inspect_schema(database_url))
        assert downgraded["table_exists"] is False
        assert "uq_device_sessions_fcm_token" not in downgraded["device_indexes"]
    finally:
        command.upgrade(config, "head")
