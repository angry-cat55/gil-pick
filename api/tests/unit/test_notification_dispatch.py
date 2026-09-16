"""알림 발송의 기기 격리·재시도·큐 처리 단위 테스트."""

import logging
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
from app.clients.fcm import FcmSendResult
from app.models.notification import Notification
from app.services.notification.dispatch import NotificationDispatchService

NOW = datetime(2026, 9, 10, 3, 0, tzinfo=UTC)


def _notification() -> Notification:
    return Notification(
        notification_id=uuid4(),
        user_id=uuid4(),
        trip_id=uuid4(),
        trip_day_id=uuid4(),
        item_id=uuid4(),
        detection_id=uuid4(),
        type="PLACE_CHANGE_SUGGESTION",
        title="다음 장소 변경을 추천해요",
        body="광화문 방문을 다시 확인해요.",
        dedup_key="detection:test",
        created_at=NOW,
    )


def _dispatch(
    sessions: list[object], results: list[FcmSendResult]
) -> tuple[NotificationDispatchService, AsyncMock, AsyncMock]:
    scalar_result = SimpleNamespace(all=lambda: sessions)
    session = SimpleNamespace(scalars=AsyncMock(return_value=scalar_result))
    client = SimpleNamespace(send=AsyncMock(side_effect=results))
    sleep = AsyncMock()
    return (
        NotificationDispatchService(
            session, client=client, now=lambda: NOW, sleep=sleep
        ),
        client.send,
        sleep,
    )


@pytest.mark.asyncio
async def test_send_one_sends_minimum_data_to_every_active_device() -> None:
    notification = _notification()
    service, send, _ = _dispatch(
        [SimpleNamespace(fcm_token="token-1"), SimpleNamespace(fcm_token="token-2")],
        [FcmSendResult.OK, FcmSendResult.OK],
    )

    await service.send_one(notification)

    assert [call.args[0] for call in send.await_args_list] == ["token-1", "token-2"]
    payload = send.await_args_list[0].args[1]
    assert payload["notificationId"] == str(notification.notification_id)
    assert payload["detectionId"] == str(notification.detection_id)
    assert (
        not {"latitude", "longitude", "rating", "token", "candidates"} & payload.keys()
    )
    assert notification.sent_at == NOW
    assert notification.delivery_status == "SENT"
    assert notification.delivery_attempts == 1


@pytest.mark.asyncio
async def test_retryable_failure_uses_two_backoffs_then_succeeds() -> None:
    notification = _notification()
    service, send, sleep = _dispatch(
        [SimpleNamespace(fcm_token="token-1")],
        [FcmSendResult.RETRYABLE, FcmSendResult.RETRYABLE, FcmSendResult.OK],
    )

    await service.send_one(notification)

    assert send.await_count == 3
    assert [call.args[0] for call in sleep.await_args_list] == [0.5, 1.5]
    assert notification.sent_at == NOW
    assert notification.delivery_status == "SENT"


@pytest.mark.asyncio
async def test_invalid_token_is_cleared_without_stopping_other_device() -> None:
    first = SimpleNamespace(fcm_token="invalid-token")
    second = SimpleNamespace(fcm_token="valid-token")
    notification = _notification()
    service, send, sleep = _dispatch(
        [first, second], [FcmSendResult.INVALID_TOKEN, FcmSendResult.OK]
    )

    await service.send_one(notification)

    assert first.fcm_token is None
    assert second.fcm_token == "valid-token"
    assert send.await_count == 2
    sleep.assert_not_awaited()


@pytest.mark.asyncio
async def test_zero_devices_finishes_as_no_device_without_sent_at() -> None:
    notification = _notification()
    service, send, _ = _dispatch([], [])

    await service.send_one(notification)

    send.assert_not_awaited()
    assert notification.sent_at is None
    assert notification.delivery_status == "NO_DEVICE"


@pytest.mark.asyncio
async def test_retryable_failure_is_scheduled_for_next_tick_without_sent_at(
    caplog: pytest.LogCaptureFixture,
) -> None:
    notification = _notification()
    service, _, _ = _dispatch(
        [SimpleNamespace(fcm_token="secret-device-token")],
        [FcmSendResult.RETRYABLE] * 3,
    )

    with caplog.at_level(logging.INFO, logger="gilpick.notification.dispatch"):
        await service.send_one(notification)

    assert "notification_delivery_retry_scheduled" in caplog.text
    assert "secret-device-token" not in caplog.text
    assert notification.body not in caplog.text
    assert notification.sent_at is None
    assert notification.delivery_status == "PENDING"
    assert notification.delivery_attempts == 1
    assert notification.next_attempt_at == NOW + timedelta(seconds=30)


@pytest.mark.asyncio
async def test_retryable_failure_stops_after_three_dispatch_attempts(
    caplog: pytest.LogCaptureFixture,
) -> None:
    notification = _notification()
    service, send, _ = _dispatch(
        [SimpleNamespace(fcm_token="secret-device-token")],
        [FcmSendResult.RETRYABLE] * 9,
    )

    with caplog.at_level(logging.INFO, logger="gilpick.notification.dispatch"):
        await service.send_one(notification)
        await service.send_one(notification)
        await service.send_one(notification)

    assert send.await_count == 9
    assert notification.sent_at is None
    assert notification.delivery_status == "FAILED"
    assert notification.delivery_attempts == 3
    assert notification.next_attempt_at is None
    assert "notification_delivery_failed" in caplog.text
    assert "secret-device-token" not in caplog.text


@pytest.mark.asyncio
async def test_fatal_failure_is_not_retried() -> None:
    notification = _notification()
    service, send, sleep = _dispatch(
        [SimpleNamespace(fcm_token="token-1")], [FcmSendResult.FATAL]
    )

    await service.send_one(notification)

    assert send.await_count == 1
    sleep.assert_not_awaited()
    assert notification.sent_at is None
    assert notification.delivery_status == "FAILED"


@pytest.mark.asyncio
async def test_partial_success_finishes_without_resending_successful_device() -> None:
    notification = _notification()
    service, send, _ = _dispatch(
        [SimpleNamespace(fcm_token="token-1"), SimpleNamespace(fcm_token="token-2")],
        [FcmSendResult.OK, *([FcmSendResult.RETRYABLE] * 3)],
    )

    await service.send_one(notification)

    assert send.await_count == 4
    assert notification.delivery_status == "SENT"
    assert notification.sent_at == NOW
    assert notification.next_attempt_at is None


@pytest.mark.asyncio
async def test_tick_processes_only_rows_selected_from_pending_queue() -> None:
    first, second = _notification(), _notification()
    empty = SimpleNamespace(all=list)
    pending = SimpleNamespace(all=lambda: [first, second])
    session = SimpleNamespace(scalars=AsyncMock(side_effect=[empty, empty, pending]))
    service = NotificationDispatchService(
        session, client=SimpleNamespace(send=AsyncMock()), now=lambda: NOW
    )
    service.send_one = AsyncMock()

    processed = await service.tick()

    assert processed == 2
    assert [call.args[0] for call in service.send_one.await_args_list] == [
        first,
        second,
    ]


@pytest.mark.asyncio
async def test_tick_finalizes_active_days_and_creates_due_reprompt(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    day = SimpleNamespace(trip_day_id=uuid4())
    transition = SimpleNamespace(transition_id=uuid4())
    pending_days = SimpleNamespace(all=lambda: [day])
    due_reprompts = SimpleNamespace(all=lambda: [transition])
    no_notifications = SimpleNamespace(all=list)
    session = SimpleNamespace(
        scalars=AsyncMock(side_effect=[pending_days, due_reprompts, no_notifications])
    )
    finalize = AsyncMock()
    create_reprompt = AsyncMock()
    monkeypatch.setattr(
        "app.services.notification.dispatch.DetectionService",
        lambda _: SimpleNamespace(finalize_due_candidates=finalize),
    )
    monkeypatch.setattr(
        "app.services.notification.dispatch.NotificationService",
        lambda *_args, **_kwargs: SimpleNamespace(
            create_transition_check=create_reprompt
        ),
    )
    service = NotificationDispatchService(
        session, client=SimpleNamespace(send=AsyncMock()), now=lambda: NOW
    )

    assert await service.tick() == 0
    finalize.assert_awaited_once_with(day, now=NOW)
    create_reprompt.assert_awaited_once_with(transition, prompt_seq=2)
