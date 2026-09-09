"""도메인 상태를 알림 행으로 변환하는 규칙의 단위 테스트."""

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest

from app.services.notification import NotificationService

NOW = datetime(2026, 9, 10, 3, 0, tzinfo=UTC)


def _service_with_context(**overrides: object) -> tuple[NotificationService, AsyncMock]:
    context = {
        "user_id": uuid4(),
        "trip_id": uuid4(),
        "name": "광화문",
        "replacement_suggestion_enabled": True,
        "day_status": "IN_PROGRESS",
    }
    context.update(overrides)
    result = SimpleNamespace(one_or_none=lambda: SimpleNamespace(**context))
    session = SimpleNamespace(execute=AsyncMock(return_value=result))
    service = NotificationService(session, now=lambda: NOW)
    service._insert = AsyncMock(return_value=SimpleNamespace(notification_id=uuid4()))
    return service, service._insert


@pytest.mark.asyncio
async def test_place_change_suggestion_uses_detection_identity_and_reason() -> None:
    service, insert = _service_with_context()
    detection = SimpleNamespace(
        detection_id=uuid4(),
        trip_day_id=uuid4(),
        item_id=uuid4(),
        status="ACTIVE",
        reason="도착 시각에 영업이 어려워요",
    )

    await service.create_place_change_suggestion(detection)

    insert.assert_awaited_once()
    values = insert.await_args.kwargs
    assert values["type"] == "PLACE_CHANGE_SUGGESTION"
    assert values["dedup_key"] == f"detection:{detection.detection_id}"
    assert values["detection_id"] == detection.detection_id
    assert values["body"] == "광화문: 도착 시각에 영업이 어려워요"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("enabled", "status"), [(False, "ACTIVE"), (True, "DISMISSED"), (True, "INVALIDATED")]
)
async def test_place_change_suggestion_skips_disabled_or_inactive_detection(
    enabled: bool, status: str
) -> None:
    service, insert = _service_with_context(replacement_suggestion_enabled=enabled)
    detection = SimpleNamespace(
        detection_id=uuid4(), trip_day_id=uuid4(), item_id=uuid4(), status=status
    )

    assert await service.create_place_change_suggestion(detection) is None
    insert.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("kind", "prompt_seq", "expected_type", "key_suffix"),
    [
        ("ARRIVAL", 1, "ARRIVAL_CHECK", "arrival_check:1"),
        ("ARRIVAL", 2, "ARRIVAL_CHECK", "arrival_check:2"),
        ("DEPARTURE", 1, "DEPARTURE_CHECK", "departure_check"),
    ],
)
async def test_transition_check_has_type_item_and_prompt_specific_dedup_key(
    kind: str, prompt_seq: int, expected_type: str, key_suffix: str
) -> None:
    service, insert = _service_with_context()
    transition = SimpleNamespace(
        transition_id=uuid4(),
        trip_day_id=uuid4(),
        primary_item_id=uuid4(),
        transition_type=kind,
        status="PENDING_CONFIRMATION",
    )

    await service.create_transition_check(transition, prompt_seq)

    values = insert.await_args.kwargs
    assert values["type"] == expected_type
    assert values["item_id"] == transition.primary_item_id
    assert values["transition_id"] == transition.transition_id
    assert values["dedup_key"] == f"transition:{transition.transition_id}:{key_suffix}"


@pytest.mark.asyncio
async def test_transition_check_ignores_replacement_setting() -> None:
    """진행 알림은 장소 변경 제안 설정과 무관하게 만든다(FR-004, SC-003)."""
    service, insert = _service_with_context(replacement_suggestion_enabled=False)
    transition = SimpleNamespace(
        transition_id=uuid4(),
        trip_day_id=uuid4(),
        primary_item_id=uuid4(),
        transition_type="ARRIVAL",
        status="PENDING_CONFIRMATION",
    )

    await service.create_transition_check(transition)

    insert.assert_awaited_once()
    assert insert.await_args.kwargs["type"] == "ARRIVAL_CHECK"


@pytest.mark.asyncio
async def test_place_change_suggestion_resumes_after_reenable_without_backfill() -> None:
    """설정을 끈 동안은 만들지 않고, 다시 켠 뒤 새 감지부터 재개한다(FR-022)."""
    service, insert = _service_with_context(replacement_suggestion_enabled=False)
    skipped = SimpleNamespace(
        detection_id=uuid4(), trip_day_id=uuid4(), item_id=uuid4(), status="ACTIVE"
    )
    assert await service.create_place_change_suggestion(skipped) is None
    insert.assert_not_awaited()

    # 설정을 다시 켜도 건너뛴 감지는 소급 전송하지 않고, 이후 새 감지만 만든다.
    resumed_context = SimpleNamespace(
        one_or_none=lambda: SimpleNamespace(
            user_id=uuid4(), trip_id=uuid4(), name="광화문", replacement_suggestion_enabled=True
        )
    )
    service.session.execute = AsyncMock(return_value=resumed_context)
    fresh = SimpleNamespace(
        detection_id=uuid4(),
        trip_day_id=uuid4(),
        item_id=uuid4(),
        status="ACTIVE",
        reason="도착 시각에 영업이 어려워요",
    )

    await service.create_place_change_suggestion(fresh)

    insert.assert_awaited_once()
    assert insert.await_args.kwargs["detection_id"] == fresh.detection_id


@pytest.mark.asyncio
async def test_transition_check_skips_completed_day() -> None:
    service, insert = _service_with_context(day_status="COMPLETED")
    transition = SimpleNamespace(
        transition_id=uuid4(),
        trip_day_id=uuid4(),
        primary_item_id=uuid4(),
        transition_type="ARRIVAL",
        status="PENDING_CONFIRMATION",
    )

    assert await service.create_transition_check(transition) is None
    insert.assert_not_awaited()


@pytest.mark.asyncio
async def test_auto_confirmed_notification_uses_one_key_and_undo_window() -> None:
    service, insert = _service_with_context(replacement_suggestion_enabled=False)
    transition = SimpleNamespace(
        transition_id=uuid4(),
        trip_day_id=uuid4(),
        primary_item_id=uuid4(),
        transition_type="COMPOSITE",
        status="AUTO_CONFIRMED",
        undo_deadline=NOW + timedelta(minutes=5),
    )

    await service.create_transition_auto_confirmed(transition)

    values = insert.await_args.kwargs
    assert values["type"] == "ARRIVAL_AUTO_CONFIRMED"
    assert values["dedup_key"] == f"transition:{transition.transition_id}:auto"
    assert values["body"] == "5분 안에 되돌릴 수 있어요."


@pytest.mark.asyncio
async def test_mark_read_is_idempotent() -> None:
    user_id = uuid4()
    notification = SimpleNamespace(user_id=user_id, read_at=None)
    session = SimpleNamespace(get=AsyncMock(return_value=notification))
    service = NotificationService(session, now=lambda: NOW)

    assert await service.mark_read(user_id, uuid4()) is notification
    assert notification.read_at == NOW
    assert await service.mark_read(user_id, uuid4()) is notification
    assert notification.read_at == NOW


@pytest.mark.asyncio
async def test_mark_all_read_returns_updated_count() -> None:
    result = SimpleNamespace(rowcount=4)
    session = SimpleNamespace(execute=AsyncMock(return_value=result))

    assert await NotificationService(session, now=lambda: NOW).mark_all_read(uuid4()) == 4


@pytest.mark.asyncio
async def test_list_notifications_returns_database_order() -> None:
    notifications = [SimpleNamespace(notification_id=uuid4()) for _ in range(2)]
    session = SimpleNamespace(
        scalars=AsyncMock(return_value=SimpleNamespace(all=lambda: notifications))
    )

    result = await NotificationService(session, now=lambda: NOW).list_notifications(
        uuid4(), cursor=(NOW, uuid4()), limit=2, read=False
    )

    assert result == notifications
    session.scalars.assert_awaited_once()


@pytest.mark.asyncio
async def test_register_fcm_token_moves_duplicate_token_to_owned_device() -> None:
    user_id = uuid4()
    device = SimpleNamespace(
        session_id=uuid4(), user_id=user_id, fcm_token=None, platform="ANDROID"
    )
    session = SimpleNamespace(
        scalar=AsyncMock(return_value=device),
        execute=AsyncMock(return_value=SimpleNamespace(rowcount=1)),
    )

    result = await NotificationService(session).register_fcm_token(
        user_id, "device-1", "token-1", "ANDROID"
    )

    assert result is device
    assert device.fcm_token == "token-1"
    session.execute.assert_awaited_once()


@pytest.mark.asyncio
async def test_unregister_fcm_token_is_idempotent() -> None:
    user_id = uuid4()
    device = SimpleNamespace(
        session_id=uuid4(), user_id=user_id, fcm_token=None, platform="ANDROID"
    )
    session = SimpleNamespace(scalar=AsyncMock(return_value=device))

    result = await NotificationService(session).unregister_fcm_token(user_id, "device-1")

    assert result is device
    assert device.fcm_token is None
