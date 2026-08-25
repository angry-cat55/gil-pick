"""PostgreSQL transaction smoke tests."""

import os
import uuid

import pytest
from sqlalchemy import inspect, select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.db import transaction_session
from app.models.auth import User


@pytest.mark.asyncio
async def test_transaction_session_rolls_back_on_error() -> None:
    database_url = os.getenv("TEST_DATABASE_URL") or os.getenv("DATABASE_URL")
    if not database_url:
        pytest.fail("TEST_DATABASE_URL 또는 DATABASE_URL이 필요합니다")

    engine = create_async_engine(database_url)
    factory = async_sessionmaker(engine, expire_on_commit=False)
    social_subject = f"rollback-{uuid.uuid4()}"

    with pytest.raises(RuntimeError, match="rollback"):
        async with transaction_session(factory) as session:
            session.add(User(social_provider="KAKAO", social_subject=social_subject))
            await session.flush()
            raise RuntimeError("rollback")

    async with factory() as session:
        result = await session.scalar(
            select(User.user_id).where(User.social_subject == social_subject)
        )
    await engine.dispose()

    assert result is None


@pytest.mark.asyncio
async def test_auth_schema_constraints_and_indexes_exist() -> None:
    database_url = os.getenv("TEST_DATABASE_URL") or os.getenv("DATABASE_URL")
    if not database_url:
        pytest.fail("TEST_DATABASE_URL 또는 DATABASE_URL이 필요합니다")

    engine = create_async_engine(database_url)

    def schema_snapshot(connection) -> tuple[set[str], set[str], set[str]]:
        inspector = inspect(connection)
        tables = set(inspector.get_table_names())
        constraints: set[str] = set()
        indexes: set[str] = set()
        for table in {"users", "device_sessions", "auth_login_transactions"}:
            constraints.update(
                item["name"] for item in inspector.get_unique_constraints(table)
            )
            constraints.update(
                item["name"] for item in inspector.get_check_constraints(table)
            )
            indexes.update(item["name"] for item in inspector.get_indexes(table))
        return tables, constraints, indexes

    async with engine.connect() as connection:
        tables, constraints, indexes = await connection.run_sync(schema_snapshot)
    await engine.dispose()

    assert {"users", "device_sessions", "auth_login_transactions"} <= tables
    assert {
        "uq_users_social_identity",
        "uq_device_sessions_user_device",
        "uq_auth_login_transactions_state_hash",
        "ck_users_social_provider",
        "ck_device_sessions_platform",
        "ck_auth_login_transactions_platform",
        "ck_auth_login_transactions_status",
    } <= constraints
    assert {
        "uq_auth_login_transactions_ticket_hash",
        "ix_auth_login_transactions_cleanup",
        "ix_device_sessions_cleanup",
    } <= indexes
