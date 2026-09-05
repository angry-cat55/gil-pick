"""Provider 공통 결과가 외부 응답 형식과 분리되는지 검증한다."""

import pytest
from pydantic import ValidationError

from app.clients.route_provider import Coordinate, NormalizedRoute, Provider, TransportMode


def _route(**changes: object) -> dict[str, object]:
    values: dict[str, object] = {
        "provider": "TMAP", "transport_mode": "WALK", "duration_seconds": 60,
        "distance_meters": 120, "coordinates": [
            {"longitude": 126.97, "latitude": 37.57},
            {"longitude": 126.98, "latitude": 37.58},
        ],
        "attribution": "TMAP",
    }
    values.update(changes)
    return values


def test_normalized_route_accepts_one_candidate_with_valid_geometry() -> None:
    route = NormalizedRoute.model_validate(_route())
    assert route.provider is Provider.TMAP
    assert route.transport_mode is TransportMode.WALK
    assert route.coordinates[0] == Coordinate(longitude=126.97, latitude=37.57)


@pytest.mark.parametrize("field", ["duration_seconds", "distance_meters"])
def test_normalized_route_rejects_negative_metrics(field: str) -> None:
    with pytest.raises(ValidationError):
        NormalizedRoute.model_validate(_route(**{field: -1}))


def test_normalized_route_rejects_short_geometry_and_empty_attribution() -> None:
    with pytest.raises(ValidationError):
        NormalizedRoute.model_validate(_route(
            coordinates=[{"longitude": 126.97, "latitude": 37.57}], attribution=""
        ))
