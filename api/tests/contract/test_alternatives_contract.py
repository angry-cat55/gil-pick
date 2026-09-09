"""F009 ALT-001 runtime 계약 검증."""

from __future__ import annotations

import uuid
from datetime import UTC, datetime
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.api.dependencies import get_current_principal
from app.api.errors import AppError
from app.api.v1.alternatives import get_alternative_service
from app.core.security import AuthPrincipal
from app.main import app
from app.models.itinerary import Place
from app.schemas.alternatives import AlternativeListData
from app.services.alternatives import AlternativeService

DETECTION_ID = uuid.UUID("00000000-0000-0000-0000-000000000009")
USER_ID = uuid.UUID("00000000-0000-0000-0000-000000000001")
NOW = datetime(2026, 9, 9, 12, tzinfo=UTC)


class StubService:
    def __init__(self, error: AppError | None = None) -> None:
        self.error = error

    async def list_candidates(
        self, detection_id: uuid.UUID, user_id: uuid.UUID
    ) -> AlternativeListData:
        assert detection_id == DETECTION_ID
        assert user_id == USER_ID
        if self.error:
            raise self.error
        return AlternativeListData(
            detection_id=detection_id,
            origin_place_id="tourapi:origin",
            eta=NOW,
            search_radius_meters=2000,
            category_match_level="NONE",
            evaluated_at=NOW,
            items=[],
        )


@pytest.fixture
def client() -> TestClient:
    app.dependency_overrides[get_current_principal] = lambda: AuthPrincipal(
        user_id=USER_ID, session_id=uuid.uuid4(), token_id=uuid.uuid4()
    )
    app.dependency_overrides[get_alternative_service] = lambda: StubService()
    try:
        yield TestClient(app)
    finally:
        app.dependency_overrides.clear()


def test_alt_001_exposes_documented_contract_and_empty_success(
    client: TestClient,
) -> None:
    operation = app.openapi()["paths"][
        "/api/v1/detections/{detectionId}/alternatives"
    ]["get"]
    assert set(operation["responses"]) == {"200", "401", "403", "404", "409", "502", "504"}

    response = client.get(f"/api/v1/detections/{DETECTION_ID}/alternatives")

    assert response.status_code == 200
    assert response.json()["data"] == {
        "detectionId": str(DETECTION_ID),
        "originPlaceId": "tourapi:origin",
        "eta": NOW.isoformat().replace("+00:00", "Z"),
        "searchRadiusMeters": 2000,
        "categoryMatchLevel": "NONE",
        "evaluatedAt": NOW.isoformat().replace("+00:00", "Z"),
        "items": [],
    }


@pytest.mark.parametrize(
    ("error", "status", "code"),
    [
        (AppError(409, "DETECTION_NOT_ACTIVE", "처리됨", details={"status": "DISMISSED"}), 409, "DETECTION_NOT_ACTIVE"),
        (AppError(403, "DETECTION_FORBIDDEN", "권한 없음"), 403, "TRIP_FORBIDDEN"),
        (AppError(404, "DETECTION_NOT_FOUND", "없음"), 404, "DETECTION_NOT_FOUND"),
        (AppError(502, "TOUR_API_FAILED", "실패"), 502, "TOUR_API_FAILED"),
        (AppError(504, "TOUR_API_TIMEOUT", "시간 초과"), 504, "TOUR_API_TIMEOUT"),
    ],
)
def test_alt_001_preserves_public_error_contract(
    client: TestClient, error: AppError, status: int, code: str
) -> None:
    app.dependency_overrides[get_alternative_service] = lambda: StubService(error)

    response = client.get(f"/api/v1/detections/{DETECTION_ID}/alternatives")

    assert response.status_code == status
    assert response.json()["error"]["code"] == code
    if status == 409:
        assert response.json()["error"]["details"] == {"status": "DISMISSED"}


@pytest.mark.asyncio
async def test_alternative_service_only_reads_schedule_rows(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    origin = Place(
        place_id=uuid.uuid4(), tour_content_id="origin", google_place_id=None,
        name="기준 장소", category="CAFE", tour_category_1="FD",
        tour_category_2="FD05", tour_category_3="FD050100",
        address="서울", location="POINT(126.978 37.5665)", image_url=None,
    )
    scheduled = Place(
        place_id=uuid.uuid4(), tour_content_id="scheduled", google_place_id=None,
        name="일정 장소", category="CAFE", tour_category_1="FD",
        tour_category_2="FD05", tour_category_3="FD050100",
        address="서울", location="POINT(126.979 37.5665)", image_url=None,
    )

    class Result:
        def __init__(self, value): self.value = value
        def one(self): return self.value
        def scalars(self): return iter(self.value)

    class ReadOnlySession:
        def __init__(self) -> None: self.results = [Result((origin, 37.5665, 126.978)), Result([origin, scheduled])]
        async def execute(self, statement):
            assert statement.is_select
            return self.results.pop(0)

    detection = SimpleNamespace(
        status="ACTIVE", item_id=uuid.uuid4(), trip_day_id=uuid.uuid4(), eta=NOW
    )
    monkeypatch.setattr(
        "app.services.alternatives.owned_detection",
        lambda *args: _async_value((detection, uuid.uuid4(), origin.name)),
    )

    async def build(**kwargs):
        assert kwargs["scheduled_place_ids"] == {"tourapi:origin", "tourapi:scheduled"}
        return AlternativeListData(
            detection_id=DETECTION_ID, origin_place_id="tourapi:origin", eta=NOW,
            search_radius_meters=2000, category_match_level="NONE",
            evaluated_at=NOW, items=[],
        )

    monkeypatch.setattr("app.services.alternatives.build_candidates", build)
    session = ReadOnlySession()
    service = AlternativeService(
        session, tour_client=object(), google_client=object(),
        operating_hours_source=object(), kma_client=object(), seoul_client=object(),
        candidate_secret="x" * 32,
    )

    result = await service.list_candidates(DETECTION_ID, USER_ID)

    assert result.items == []
    assert session.results == []


async def _async_value(value):
    return value
