"""PostGIS에 생성된 일정 테이블과 제약을 확인하는 통합 테스트."""

import os
from collections.abc import AsyncIterator

import pytest
from sqlalchemy import inspect, text
from sqlalchemy.ext.asyncio import AsyncEngine, create_async_engine


@pytest.fixture
async def engine() -> AsyncIterator[AsyncEngine]:
    database_url = os.getenv("TEST_DATABASE_URL") or os.getenv("DATABASE_URL")
    if not database_url:
        pytest.fail("TEST_DATABASE_URL 또는 DATABASE_URL이 필요합니다.")

    database_engine = create_async_engine(database_url)
    yield database_engine
    await database_engine.dispose()


@pytest.mark.asyncio
async def test_itinerary_migration_creates_tables_and_constraints(
    engine: AsyncEngine,
) -> None:
    """ERD 5.2~5.4의 테이블, 제약, 공간·부분 인덱스를 검증한다."""

    def inspect_schema(connection):
        inspector = inspect(connection)
        return {
            "tables": set(inspector.get_table_names()),
            "trip_day_unique": {
                constraint["name"]
                for constraint in inspector.get_unique_constraints("trip_days")
            },
            "item_unique": {
                constraint["name"]
                for constraint in inspector.get_unique_constraints("itinerary_items")
            },
            "item_checks": {
                constraint["name"]
                for constraint in inspector.get_check_constraints("itinerary_items")
            },
            "place_indexes": {
                index["name"]: index for index in inspector.get_indexes("places")
            },
            "location_type": connection.execute(
                text(
                    "SELECT format_type(a.atttypid, a.atttypmod) "
                    "FROM pg_attribute a "
                    "WHERE a.attrelid = 'places'::regclass AND a.attname = 'location'"
                )
            ).scalar_one(),
        }

    async with engine.connect() as connection:
        schema = await connection.run_sync(inspect_schema)

    assert {"trip_days", "places", "itinerary_items"} <= schema["tables"]
    assert {"uq_trip_days_trip_visit_date", "uq_trip_days_trip_day_number"} <= schema[
        "trip_day_unique"
    ]
    assert "uq_itinerary_items_day_sequence" in schema["item_unique"]
    assert "ck_itinerary_items_stay_minutes" in schema["item_checks"]
    assert {
        "uq_places_tour_content_id",
        "uq_places_google_place_id",
        "ix_places_location",
    } <= set(schema["place_indexes"])
    assert schema["place_indexes"]["uq_places_tour_content_id"]["unique"]
    assert schema["place_indexes"]["uq_places_google_place_id"]["unique"]
    assert schema["location_type"].lower() == "geography(point,4326)"
