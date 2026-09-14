"""F005 route migration의 PostgreSQL 제약을 검증한다."""

import os

import pytest
from sqlalchemy import text
from sqlalchemy.ext.asyncio import create_async_engine


@pytest.mark.asyncio
async def test_route_migration_creates_expected_constraints_and_index() -> None:
    database_url = os.getenv("TEST_DATABASE_URL") or os.getenv("DATABASE_URL")
    if not database_url:
        pytest.skip("TEST_DATABASE_URL 또는 DATABASE_URL이 필요합니다.")
    engine = create_async_engine(database_url)
    try:
        async with engine.connect() as connection:
            names = set((await connection.execute(text("""
                SELECT conname FROM pg_constraint WHERE conrelid = 'routes'::regclass
                UNION ALL SELECT indexname FROM pg_indexes WHERE tablename = 'routes'
            """))).scalars())
            provider_checks = dict((await connection.execute(text("""
                SELECT conrelid::regclass::text, pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname IN ('ck_routes_provider', 'ck_progress_segments_provider')
            """))).all())
        assert {"uq_routes_day_schedule_version", "uq_routes_active_day", "ck_routes_state_fields"} <= names
        assert "KAKAO" in provider_checks["routes"]
        assert "KAKAO" in provider_checks["progress_segments"]
    finally:
        await engine.dispose()
