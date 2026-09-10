"""F012 사용자 설정 API의 인증·계약·DB 흐름 통합 테스트."""

import asyncio
import os
import uuid
from collections.abc import AsyncIterator
from pathlib import Path

import pytest
import yaml
from fastapi.testclient import TestClient
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.api.dependencies import get_current_principal
from app.api.v1.preferences import get_preferences_service
from app.core.security import AuthPrincipal
from app.db import transaction_session
from app.main import app, create_app
from app.models.auth import User
from app.services.preferences import PreferencesService


class StubPreferencesService:
    """HTTP 계약 검증에 사용하는 메모리 내 설정 service."""

    value = True

    async def get(self, user_id: uuid.UUID) -> bool:
        return self.value

    async def update(self, user_id: uuid.UUID, enabled: bool) -> bool:
        type(self).value = enabled
        return type(self).value


@pytest.fixture
def client() -> AsyncIterator[TestClient]:
    """인증 principal과 설정 service를 고정한 HTTP client를 제공한다."""
    StubPreferencesService.value = True
    app.dependency_overrides[get_current_principal] = lambda: AuthPrincipal(
        user_id=uuid.uuid4(), session_id=uuid.uuid4(), token_id=uuid.uuid4()
    )
    app.dependency_overrides[get_preferences_service] = StubPreferencesService
    try:
        yield TestClient(app)
    finally:
        app.dependency_overrides.clear()


def test_get_patch_and_reread_use_common_envelope(client: TestClient) -> None:
    initial = client.get("/api/v1/users/me/preferences")
    changed = client.patch(
        "/api/v1/users/me/preferences",
        json={"placeChangeSuggestionNotificationEnabled": False},
    )
    reread = client.get("/api/v1/users/me/preferences")

    assert initial.status_code == changed.status_code == reread.status_code == 200
    assert initial.json()["data"] == {"placeChangeSuggestionNotificationEnabled": True}
    assert changed.json()["data"] == reread.json()["data"] == {
        "placeChangeSuggestionNotificationEnabled": False
    }
    for response in (initial, changed, reread):
        assert response.json()["success"] is True
        assert response.json()["meta"]["requestId"] == response.headers["X-Request-ID"]


@pytest.mark.parametrize(
    "body",
    [
        {},
        {"placeChangeSuggestionNotificationEnabled": None},
        {"placeChangeSuggestionNotificationEnabled": "false"},
        {"placeChangeSuggestionNotificationEnabled": 0},
        {"placeChangeSuggestionNotificationEnabled": True, "extra": True},
    ],
)
def test_patch_rejects_invalid_body_with_common_400(
    client: TestClient, body: dict[str, object]
) -> None:
    response = client.patch("/api/v1/users/me/preferences", json=body)

    assert response.status_code == 400
    assert response.json()["success"] is False
    assert response.json()["error"]["code"] == "INVALID_REQUEST"
    assert response.json()["meta"]["requestId"] == response.headers["X-Request-ID"]


def test_preferences_routes_require_authentication() -> None:
    with TestClient(create_app()) as unauthenticated:
        get_response = unauthenticated.get("/api/v1/users/me/preferences")
        patch_response = unauthenticated.patch(
            "/api/v1/users/me/preferences",
            json={"placeChangeSuggestionNotificationEnabled": False},
        )

    assert get_response.status_code == patch_response.status_code == 401
    assert get_response.json()["success"] is False
    assert patch_response.json()["success"] is False


def test_runtime_openapi_matches_feature_contract() -> None:
    source = yaml.safe_load(
        (Path(__file__).parents[3] / "specs/012-user-settings/contracts/preferences.openapi.yaml")
        .read_text(encoding="utf-8")
    )
    runtime = create_app().openapi()
    runtime_path = runtime["paths"]["/api/v1/users/me/preferences"]
    source_path = source["paths"]["/users/me/preferences"]

    for method in ("get", "patch"):
        assert set(runtime_path[method]["responses"]) == set(source_path[method]["responses"])

    schemas = runtime["components"]["schemas"]
    for name in ("PreferenceData", "UpdatePreferenceRequest"):
        assert set(schemas[name]["properties"]) == set(
            source["components"]["schemas"][name]["properties"]
        )
        assert set(schemas[name]["required"]) == set(
            source["components"]["schemas"][name]["required"]
        )


@pytest.fixture
async def session_factory() -> AsyncIterator[async_sessionmaker[AsyncSession]]:
    """migration이 적용된 PostgreSQL session factory를 제공한다."""
    database_url = os.getenv("TEST_DATABASE_URL") or os.getenv("DATABASE_URL")
    if not database_url:
        pytest.fail("TEST_DATABASE_URL 또는 DATABASE_URL이 필요합니다")
    engine = create_async_engine(database_url)
    yield async_sessionmaker(engine, expire_on_commit=False)
    await engine.dispose()


@pytest.mark.asyncio
async def test_update_changes_only_authenticated_user_and_same_value_is_idempotent(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    async with transaction_session(session_factory) as session:
        first = User(social_provider="KAKAO", social_subject=f"pref-{uuid.uuid4()}")
        second = User(social_provider="KAKAO", social_subject=f"pref-{uuid.uuid4()}")
        session.add_all([first, second])
        await session.flush()
        first_id, second_id = first.user_id, second.user_id
    try:
        for _ in range(2):
            async with transaction_session(session_factory) as session:
                assert await PreferencesService(session).update(first_id, False) is False

        async with session_factory() as session:
            values = dict(
                (await session.execute(
                    select(User.user_id, User.replacement_suggestion_enabled).where(
                        User.user_id.in_([first_id, second_id])
                    )
                )).all()
            )
        assert values == {first_id: False, second_id: True}
    finally:
        async with transaction_session(session_factory) as session:
            await session.execute(delete(User).where(User.user_id.in_([first_id, second_id])))


@pytest.mark.asyncio
async def test_concurrent_updates_leave_one_successfully_processed_value(
    session_factory: async_sessionmaker[AsyncSession],
) -> None:
    async with transaction_session(session_factory) as session:
        user = User(social_provider="KAKAO", social_subject=f"pref-{uuid.uuid4()}")
        session.add(user)
        await session.flush()
        user_id = user.user_id

    async def change(enabled: bool) -> bool:
        async with transaction_session(session_factory) as session:
            return await PreferencesService(session).update(user_id, enabled)

    try:
        results = await asyncio.gather(change(False), change(True))
        async with transaction_session(session_factory) as session:
            stored = await PreferencesService(session).get(user_id)
        assert stored in results
    finally:
        async with transaction_session(session_factory) as session:
            await session.execute(delete(User).where(User.user_id == user_id))
