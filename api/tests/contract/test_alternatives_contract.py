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
from app.clients.tour_api import TourApiClientError
from app.core.security import AuthPrincipal
from app.main import app
from app.models.itinerary import Place
from app.schemas.alternatives import AlternativeListData
from app.schemas.place import BusinessStatus, PlaceCategory, PlaceSource, PlaceSummary
from app.services.alternatives import AlternativeService
from app.services.place import PlaceService

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


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("tour_error", "status", "code"),
    [
        (TourApiClientError("TOUR_API_TIMEOUT", retryable=True), 504, "TOUR_API_TIMEOUT"),
        (TourApiClientError("TOUR_API_FAILED", retryable=True), 502, "TOUR_API_FAILED"),
        (TourApiClientError("TOUR_API_RATE_LIMITED", status_code=429), 502, "TOUR_API_FAILED"),
    ],
)
async def test_list_candidates_surfaces_tour_failure_instead_of_empty_list(
    monkeypatch: pytest.MonkeyPatch, tour_error: TourApiClientError, status: int, code: str
) -> None:
    place = Place(
        place_id=uuid.uuid4(), tour_content_id="origin", google_place_id=None,
        name="기준 장소", category="CAFE", tour_category_1="FD",
        tour_category_2="FD05", tour_category_3="FD050100",
        address="서울", location="POINT(126.978 37.5665)", image_url=None,
    )

    class Result:
        def __init__(self, value): self.value = value
        def one(self): return self.value
        def scalars(self): return iter(self.value)

    class ReadOnlySession:
        def __init__(self) -> None:
            self.results = [Result((place, 37.5665, 126.978)), Result([place])]

        async def execute(self, statement):
            return self.results.pop(0)

    detection = SimpleNamespace(
        status="ACTIVE", item_id=uuid.uuid4(), trip_day_id=uuid.uuid4(), eta=NOW
    )
    monkeypatch.setattr(
        "app.services.alternatives.owned_detection",
        lambda *args: _async_value((detection, uuid.uuid4(), place.name)),
    )

    async def build(**kwargs):
        raise tour_error

    monkeypatch.setattr("app.services.alternatives.build_candidates", build)
    service = AlternativeService(
        ReadOnlySession(), tour_client=object(), google_client=object(),
        operating_hours_source=object(), kma_client=object(), seoul_client=object(),
        candidate_secret="x" * 32,
    )

    with pytest.raises(AppError) as raised:
        await service.list_candidates(DETECTION_ID, USER_ID)

    assert raised.value.status_code == status
    assert raised.value.code == code
    assert raised.value.retryable is tour_error.retryable


async def _async_value(value):
    return value


# --- ALT-002 대체 장소 직접 검색 (US3, T028) ---


def _summary(
    place_id: str,
    *,
    business_status: BusinessStatus | None = None,
    latitude: float | None = 37.5700,
    longitude: float | None = 126.9800,
) -> PlaceSummary:
    source, source_id = place_id.split(":", 1)
    return PlaceSummary(
        place_id=place_id,
        source=PlaceSource.TOUR_API if source == "tourapi" else PlaceSource.GOOGLE_PLACES,
        source_place_id=source_id,
        name=f"장소 {source_id}",
        category=PlaceCategory.CAFE,
        tour_api_category=None,
        address="서울 중구",
        latitude=latitude,
        longitude=longitude,
        image_url=None,
        recommended_stay_minutes=60,
        rating=None,
        user_rating_count=None,
        business_status=business_status,
        regular_opening_hours=None,
        current_opening_hours=None,
        google_attributions=None,
    )


class StubSearchService:
    def __init__(self, *, items=None, error: AppError | None = None) -> None:
        self.items = items if items is not None else []
        self.error = error
        self.received: dict = {}

    async def search(
        self, detection_id, user_id, *, query: str, cursor: str | None, limit: int
    ):
        self.received = {
            "detection_id": detection_id, "user_id": user_id,
            "query": query, "cursor": cursor, "limit": limit,
        }
        if self.error is not None:
            raise self.error
        return self.items, "CURSOR2", True


@pytest.fixture
def search_stub():
    stub = StubSearchService()
    app.dependency_overrides[get_current_principal] = lambda: AuthPrincipal(
        user_id=USER_ID, session_id=uuid.uuid4(), token_id=uuid.uuid4()
    )
    app.dependency_overrides[get_alternative_service] = lambda: stub
    try:
        yield stub
    finally:
        app.dependency_overrides.clear()


def test_alt_002_exposes_documented_contract_and_pagination(search_stub) -> None:
    document = app.openapi()
    operation = document["paths"][
        "/api/v1/detections/{detectionId}/alternatives/search"
    ]["get"]
    assert set(operation["responses"]) == {
        "200", "400", "401", "403", "404", "409", "429", "502", "504"
    }
    assert {item["name"] for item in operation["parameters"]} == {
        "detectionId", "query", "cursor", "limit"
    }
    item_schema = document["components"]["schemas"]["AlternativeSearchItem"]
    assert set(item_schema["required"]) == {
        "place", "distanceMeters", "operatingStatus", "visitable", "inSchedule"
    }

    client = TestClient(app)
    response = client.get(
        f"/api/v1/detections/{DETECTION_ID}/alternatives/search",
        params={"query": " 카페 ", "limit": 15},
    )

    assert response.status_code == 200
    assert response.json()["data"] == {"items": []}
    assert response.json()["meta"]["pagination"] == {
        "nextCursor": "CURSOR2", "hasNext": True
    }
    assert search_stub.received["query"] == "카페"  # trim 적용
    assert search_stub.received["limit"] == 15


@pytest.mark.parametrize("raw_query", ["카", "  카  ", " "])
def test_alt_002_rejects_query_shorter_than_two_characters(
    search_stub, raw_query: str
) -> None:
    client = TestClient(app)

    response = client.get(
        f"/api/v1/detections/{DETECTION_ID}/alternatives/search",
        params={"query": raw_query},
    )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_REQUEST"
    assert search_stub.received == {}  # service 호출 전에 거절


@pytest.mark.parametrize(
    ("error", "status", "code"),
    [
        (
            AppError(409, "DETECTION_NOT_ACTIVE", "처리됨", details={"status": "DISMISSED"}),
            409, "DETECTION_NOT_ACTIVE",
        ),
        (AppError(403, "DETECTION_FORBIDDEN", "권한 없음"), 403, "TRIP_FORBIDDEN"),
        (AppError(404, "DETECTION_NOT_FOUND", "없음"), 404, "DETECTION_NOT_FOUND"),
        (AppError(504, "TOUR_API_TIMEOUT", "시간 초과", retryable=True), 504, "TOUR_API_TIMEOUT"),
        (AppError(502, "TOUR_API_FAILED", "실패", retryable=True), 502, "TOUR_API_FAILED"),
    ],
)
def test_alt_002_preserves_public_error_contract(
    search_stub, error: AppError, status: int, code: str
) -> None:
    search_stub.error = error
    client = TestClient(app)

    response = client.get(
        f"/api/v1/detections/{DETECTION_ID}/alternatives/search",
        params={"query": "카페"},
    )

    assert response.status_code == status
    assert response.json()["error"]["code"] == code
    if status == 409:
        assert response.json()["error"]["details"] == {"status": "DISMISSED"}


@pytest.mark.asyncio
async def test_alternative_service_search_annotates_places(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    origin_place = Place(
        place_id=uuid.uuid4(), tour_content_id="origin", google_place_id=None,
        name="기준 장소", category="CAFE", tour_category_1="FD",
        tour_category_2="FD05", tour_category_3="FD050100",
        address="서울", location="POINT(126.978 37.5665)", image_url=None,
    )
    scheduled_place = Place(
        place_id=uuid.uuid4(), tour_content_id="sched1", google_place_id=None,
        name="같은 날짜 장소", category="CAFE", tour_category_1="FD",
        tour_category_2="FD05", tour_category_3="FD050100",
        address="서울", location="POINT(126.979 37.5665)", image_url=None,
    )

    class Result:
        def __init__(self, value): self.value = value
        def one(self): return self.value
        def scalars(self): return iter(self.value)

    class ReadOnlySession:
        def __init__(self) -> None:
            self.results = [
                Result((origin_place, 37.5665, 126.9780)),
                Result([origin_place, scheduled_place]),
            ]

        async def execute(self, statement):
            return self.results.pop(0)

    detection = SimpleNamespace(
        status="ACTIVE", item_id=uuid.uuid4(), trip_day_id=uuid.uuid4(), eta=NOW
    )
    monkeypatch.setattr(
        "app.services.alternatives.owned_detection",
        lambda *args: _async_value((detection, uuid.uuid4(), "기준 장소")),
    )

    found = [
        _summary("tourapi:origin"),
        _summary("tourapi:sched1"),
        _summary("google:closed", business_status=BusinessStatus.CLOSED_TEMPORARILY),
        _summary("google:open", business_status=BusinessStatus.OPERATIONAL),
        _summary("tourapi:nocoord", latitude=None, longitude=None),
    ]

    async def fake_search_places(self, *, query, category, area_code, cursor, limit):
        assert query == "카페"
        assert category is None and area_code is None
        return found, "NEXT", True

    monkeypatch.setattr(PlaceService, "search_places", fake_search_places)
    service = AlternativeService(
        ReadOnlySession(), tour_client=object(), google_client=object(),
        operating_hours_source=object(), kma_client=object(), seoul_client=object(),
        candidate_secret="x" * 32,
    )

    items, next_cursor, has_next = await service.search(
        DETECTION_ID, USER_ID, query="카페", cursor=None, limit=20
    )

    assert (next_cursor, has_next) == ("NEXT", True)
    by_id = {item.place.place_id: item for item in items}
    assert by_id["tourapi:origin"].in_schedule is True
    assert by_id["tourapi:origin"].visitable is False
    assert by_id["tourapi:sched1"].in_schedule is True
    assert by_id["tourapi:sched1"].visitable is False
    assert by_id["google:closed"].operating_status == "CLOSED"
    assert by_id["google:closed"].visitable is False
    assert by_id["google:closed"] in items  # 결과에서 빠지지 않음
    assert by_id["google:open"].operating_status == "OPEN"
    assert by_id["google:open"].visitable is True
    assert by_id["google:open"].in_schedule is False
    assert by_id["tourapi:nocoord"].distance_meters is None
    assert by_id["tourapi:nocoord"].operating_status == "UNKNOWN"
    assert isinstance(by_id["google:open"].distance_meters, int)
    assert by_id["google:open"].distance_meters > 0


@pytest.mark.asyncio
async def test_alternative_service_search_rejects_non_active_detection(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    detection = SimpleNamespace(
        status="DISMISSED", item_id=uuid.uuid4(), trip_day_id=uuid.uuid4(), eta=NOW
    )
    monkeypatch.setattr(
        "app.services.alternatives.owned_detection",
        lambda *args: _async_value((detection, uuid.uuid4(), "장소")),
    )
    called = False

    async def fake_search_places(self, **kwargs):
        nonlocal called
        called = True
        return [], None, False

    monkeypatch.setattr(PlaceService, "search_places", fake_search_places)
    service = AlternativeService(
        object(), tour_client=object(), google_client=object(),
        operating_hours_source=object(), kma_client=object(), seoul_client=object(),
        candidate_secret="x" * 32,
    )

    with pytest.raises(AppError) as raised:
        await service.search(DETECTION_ID, USER_ID, query="카페", cursor=None, limit=20)

    assert raised.value.status_code == 409
    assert raised.value.code == "DETECTION_NOT_ACTIVE"
    assert raised.value.details == {"status": "DISMISSED"}
    assert called is False
