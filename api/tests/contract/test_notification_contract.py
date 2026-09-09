"""F011 NOTI·DEV HTTP 계약을 검증한다."""

from __future__ import annotations

import uuid
from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace

import pytest
import yaml
from fastapi.testclient import TestClient

from app.api.dependencies import get_current_principal
from app.api.errors import AppError
from app.api.v1.notifications import _decode_cursor, get_notification_service
from app.core.security import AuthPrincipal
from app.main import app, create_app

USER_ID = uuid.uuid4()
NOTIFICATION_ID = uuid.uuid4()
NOW = datetime(2026, 9, 10, 3, 0, tzinfo=UTC)


class StubNotificationService:
    """NOTI·DEV route가 사용하는 최소 service 대역."""

    async def list_notifications(self, user_id, *, cursor, limit, read):
        return [
            SimpleNamespace(
                notification_id=NOTIFICATION_ID,
                user_id=user_id,
                type="PLACE_CHANGE_SUGGESTION",
                trip_id=uuid.uuid4(),
                trip_day_id=None,
                item_id=None,
                detection_id=uuid.uuid4(),
                transition_id=None,
                title="일정 확인이 필요해요",
                body="대체 장소를 확인해 주세요.",
                read_at=None,
                created_at=NOW,
            )
        ]

    async def mark_read(self, user_id, notification_id):
        return SimpleNamespace(notification_id=notification_id)

    async def mark_all_read(self, user_id):
        return 2

    async def register_fcm_token(self, user_id, device_id, fcm_token, platform):
        return SimpleNamespace(client_device_id=device_id, fcm_token=fcm_token)

    async def unregister_fcm_token(self, user_id, device_id):
        return SimpleNamespace(client_device_id=device_id, fcm_token=None)


@pytest.fixture
def client() -> TestClient:
    """인증과 DB를 분리한 알림 계약 client를 제공한다."""
    app.dependency_overrides[get_current_principal] = lambda: AuthPrincipal(
        user_id=USER_ID, session_id=uuid.uuid4(), token_id=uuid.uuid4()
    )
    app.dependency_overrides[get_notification_service] = StubNotificationService
    try:
        yield TestClient(app)
    finally:
        app.dependency_overrides.clear()


def test_runtime_routes_and_schemas_match_source_contract() -> None:
    source = yaml.safe_load(
        (Path(__file__).parents[3] / "specs/011-notification/contracts/notifications.openapi.yaml")
        .read_text(encoding="utf-8")
    )
    runtime = create_app().openapi()
    paths = runtime["paths"]

    for source_path, method in (
        ("/notifications", "get"),
        ("/notifications/{notificationId}/read", "patch"),
        ("/notifications/read-all", "patch"),
        ("/devices/fcm-token", "put"),
        ("/devices/{deviceId}/fcm-token", "delete"),
    ):
        assert set(paths[f"/api/v1{source_path}"][method]["responses"]) == set(
            source["paths"][source_path][method]["responses"]
        )

    schemas = runtime["components"]["schemas"]
    item = schemas["NotificationItem"]
    assert set(item["required"]) == set(source["components"]["schemas"]["NotificationItem"]["required"])
    assert set(item["properties"]) == set(source["components"]["schemas"]["NotificationItem"]["properties"])
    assert set(schemas["NotificationType"]["enum"]) == set(source["components"]["schemas"]["NotificationType"]["enum"])


def test_notification_list_and_read_routes(client: TestClient) -> None:
    listing = client.get("/api/v1/notifications", params={"read": "false", "limit": 20})
    marked = client.patch(f"/api/v1/notifications/{NOTIFICATION_ID}/read")
    marked_all = client.patch("/api/v1/notifications/read-all")

    assert listing.status_code == 200
    assert listing.json()["data"]["items"][0]["notificationId"] == str(NOTIFICATION_ID)
    assert listing.json()["data"]["items"][0]["read"] is False
    assert marked.json()["data"] == {"notificationId": str(NOTIFICATION_ID), "read": True}
    assert marked_all.json()["data"] == {"updated": 2}


def test_notification_cursor_rejects_invalid_or_naive_values() -> None:
    """변조되거나 timezone이 없는 cursor를 공개 오류로 거절한다."""
    for cursor in ("not-base64", "WyIyMDI2LTA5LTEwVDAzOjAwOjAwIiwgImJhZCJd"):
        with pytest.raises(AppError) as error:
            _decode_cursor(cursor)
        assert error.value.status_code == 400


def test_device_token_register_and_unregister(client: TestClient) -> None:
    registered = client.put(
        "/api/v1/devices/fcm-token",
        json={"deviceId": "device-1", "fcmToken": "token-1", "platform": "ANDROID"},
    )
    unregistered = client.delete("/api/v1/devices/device-1/fcm-token")

    assert registered.status_code == 200
    assert registered.json()["data"] == {"deviceId": "device-1", "registered": True}
    assert unregistered.status_code == 204
    assert unregistered.content == b""


def test_protected_routes_return_401_without_access_token() -> None:
    with TestClient(create_app()) as unauthenticated:
        for method, path in (
            ("get", "/api/v1/notifications"),
            ("patch", f"/api/v1/notifications/{NOTIFICATION_ID}/read"),
            ("patch", "/api/v1/notifications/read-all"),
            ("put", "/api/v1/devices/fcm-token"),
            ("delete", "/api/v1/devices/device-1/fcm-token"),
        ):
            response = unauthenticated.request(method, path)
            assert response.status_code == 401


@pytest.mark.parametrize("status_code", [403, 404])
def test_ownership_errors_keep_public_status(client: TestClient, status_code: int) -> None:
    class RejectingService(StubNotificationService):
        async def mark_read(self, user_id, notification_id):
            raise AppError(status_code, "NOTIFICATION_FORBIDDEN" if status_code == 403 else "NOTIFICATION_NOT_FOUND", "알림을 처리할 수 없습니다.")

    app.dependency_overrides[get_notification_service] = RejectingService
    response = client.patch(f"/api/v1/notifications/{NOTIFICATION_ID}/read")
    assert response.status_code == status_code
