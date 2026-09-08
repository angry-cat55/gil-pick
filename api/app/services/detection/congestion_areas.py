"""서울시 혼잡 지원 지점 설정을 검증하고 가까운 지점을 찾는다."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Sequence

from pydantic import BaseModel, ConfigDict, Field, field_validator
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession


class CongestionArea(BaseModel):
    """서울시 실시간 도시데이터가 제공되는 한 지점."""

    model_config = ConfigDict(frozen=True, populate_by_name=True)

    name: str = Field(min_length=1)
    area_code: str = Field(alias="areaCode", min_length=1)
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)


class _CongestionAreaConfig(BaseModel):
    version: str = Field(min_length=1)
    source: str = Field(min_length=1)
    areas: tuple[CongestionArea, ...]

    @field_validator("areas")
    @classmethod
    def validate_unique_codes(
        cls, areas: tuple[CongestionArea, ...]
    ) -> tuple[CongestionArea, ...]:
        if len({area.area_code for area in areas}) != len(areas):
            raise ValueError("areaCode는 중복될 수 없습니다.")
        return areas


_DEFAULT_PATH = Path(__file__).with_name("congestion_areas.json")


def load_congestion_areas(path: Path = _DEFAULT_PATH) -> tuple[CongestionArea, ...]:
    """설정 파일을 읽고 지원 지점 목록을 반환한다.

    Args:
        path: `{version, source, areas}` 형식의 JSON 파일 경로.

    Returns:
        검증된 불변 지원 지점 목록.

    Raises:
        ValueError: 설정 구조나 지점 값이 올바르지 않은 경우.
    """
    return _CongestionAreaConfig.model_validate_json(
        path.read_text(encoding="utf-8")
    ).areas


async def find_nearest_congestion_area(
    session: AsyncSession,
    *,
    latitude: float,
    longitude: float,
    areas: Sequence[CongestionArea] | None = None,
    radius_meters: int = 500,
) -> CongestionArea | None:
    """장소에서 반경 안에 있는 가장 가까운 지원 지점을 찾는다.

    PostGIS geography의 `ST_DWithin`으로 500m 경계를 판정하고
    `ST_Distance`가 가장 작은 지점 하나만 반환한다.

    Args:
        session: SQL을 실행할 비동기 DB session.
        latitude: 장소 위도.
        longitude: 장소 경도.
        areas: 검색할 지원 지점. 생략하면 기본 설정을 사용한다.
        radius_meters: 검색 반경(m).

    Returns:
        가장 가까운 지원 지점 또는 반경 내 지점이 없을 때 `None`.
    """
    candidates = tuple(areas) if areas is not None else load_congestion_areas()
    if not candidates:
        return None

    payload = json.dumps(
        [
            {
                "area_code": area.area_code,
                "latitude": area.latitude,
                "longitude": area.longitude,
            }
            for area in candidates
        ]
    )
    statement = text(
        """
        WITH areas AS (
            SELECT *
            FROM jsonb_to_recordset(CAST(:areas_json AS jsonb))
                AS area(area_code text, latitude double precision, longitude double precision)
        ), distances AS (
            SELECT
                area_code,
                ST_Distance(
                    CAST(ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326) AS geography),
                    CAST(ST_SetSRID(ST_MakePoint(longitude, latitude), 4326) AS geography)
                ) AS distance_meters
            FROM areas
            WHERE ST_DWithin(
                CAST(ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326) AS geography),
                CAST(ST_SetSRID(ST_MakePoint(longitude, latitude), 4326) AS geography),
                :radius_meters
            )
        )
        SELECT area_code, distance_meters
        FROM distances
        ORDER BY distance_meters, area_code
        LIMIT 1
        """
    )
    result = await session.execute(
        statement,
        {
            "areas_json": payload,
            "latitude": latitude,
            "longitude": longitude,
            "radius_meters": radius_meters,
        },
    )
    row = result.mappings().first()
    if row is None:
        return None
    area_code = row["area_code"]  # type: ignore[index]
    return next(area for area in candidates if area.area_code == area_code)
