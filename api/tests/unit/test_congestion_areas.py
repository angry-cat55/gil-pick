"""서울시 혼잡 지원 지점 설정과 공간 매핑을 검증한다."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.services.detection.congestion_areas import (
    CongestionArea,
    find_nearest_congestion_area,
    load_congestion_areas,
)


class _MappingResult:
    def __init__(self, row: dict[str, object] | None) -> None:
        self.row = row

    def mappings(self) -> _MappingResult:
        return self

    def first(self) -> dict[str, object] | None:
        return self.row


class _Session:
    def __init__(self, row: dict[str, object] | None) -> None:
        self.row = row
        self.statement = ""
        self.params: dict[str, object] = {}

    async def execute(
        self, statement: object, params: dict[str, object]
    ) -> _MappingResult:
        self.statement = str(statement)
        self.params = params
        return _MappingResult(self.row)


def test_load_congestion_areas_validates_and_returns_typed_entries() -> None:
    areas = load_congestion_areas()

    assert areas
    assert all(isinstance(area, CongestionArea) for area in areas)
    assert len({area.area_code for area in areas}) == len(areas)
    assert all(-90 <= area.latitude <= 90 for area in areas)
    assert all(-180 <= area.longitude <= 180 for area in areas)


def test_load_congestion_areas_rejects_invalid_schema(tmp_path: Path) -> None:
    path = tmp_path / "areas.json"
    path.write_text(
        json.dumps(
            {
                "version": "test",
                "source": "fixture",
                "areas": [
                    {
                        "name": "좌표 누락",
                        "areaCode": "BROKEN",
                        "latitude": 37.5,
                    }
                ],
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="longitude"):
        load_congestion_areas(path)


@pytest.mark.asyncio
async def test_find_nearest_area_uses_postgis_500m_boundary() -> None:
    area = CongestionArea(
        name="광화문·덕수궁",
        area_code="POI009",
        latitude=37.5752,
        longitude=126.9768,
    )
    session = _Session(
        {
            "area_code": area.area_code,
            "distance_meters": 125.0,
        }
    )

    result = await find_nearest_congestion_area(
        session, latitude=37.5759, longitude=126.9769, areas=(area,)
    )

    assert result == area
    assert "ST_DWithin" in session.statement
    assert "ST_Distance" in session.statement
    assert session.params["radius_meters"] == 500


@pytest.mark.asyncio
async def test_find_nearest_area_returns_none_outside_support_area() -> None:
    session = _Session(None)
    area = CongestionArea(
        name="광화문·덕수궁",
        area_code="POI009",
        latitude=37.5752,
        longitude=126.9768,
    )

    result = await find_nearest_congestion_area(
        session, latitude=37.0, longitude=127.0, areas=(area,)
    )

    assert result is None
