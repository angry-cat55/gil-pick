"""진행 API의 commit 이후 재평가·알림 발송 연결을 검증한다."""

import uuid
from datetime import date
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.api.v1 import progress


@pytest.mark.asyncio
async def test_reevaluation_hook_passes_resolved_day_id(monkeypatch) -> None:
    day_id = uuid.uuid4()
    session = AsyncMock()
    session.scalar.return_value = day_id

    class Factory:
        def __call__(self):
            return self

        async def __aenter__(self):
            return session

        async def __aexit__(self, *args):
            return None

    factory = Factory()
    reevaluate = AsyncMock()
    monkeypatch.setattr(progress, "reevaluate_day", reevaluate)

    await progress._reevaluate_progress_day(
        factory, trip_id=uuid.uuid4(), visit_date=date(2026, 9, 9)
    )

    reevaluate.assert_awaited_once_with(factory, day_id)


@pytest.mark.asyncio
async def test_reevaluation_hook_does_not_break_progress_response(monkeypatch) -> None:
    class Factory:
        def __call__(self):
            return self

        async def __aenter__(self):
            raise RuntimeError("database unavailable")

        async def __aexit__(self, *args):
            return None

    monkeypatch.setattr(progress.logger, "exception", lambda *args, **kwargs: None)

    await progress._reevaluate_progress_day(
        Factory(), trip_id=uuid.uuid4(), visit_date=date(2026, 9, 9)
    )


class _Session:
    def __init__(self, day_id, notifications):
        self._day_id = day_id
        self._notifications = notifications

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return None

    def begin(self):
        return self

    async def scalar(self, *_a, **_k):
        return self._day_id

    async def scalars(self, *_a, **_k):
        return SimpleNamespace(all=lambda: self._notifications)


@pytest.mark.asyncio
async def test_progress_dispatch_sends_unsent_progress_notifications(monkeypatch) -> None:
    day_id = uuid.uuid4()
    notifications = [SimpleNamespace(notification_id=uuid.uuid4()) for _ in range(2)]
    session = _Session(day_id, notifications)
    closed = []
    monkeypatch.setattr(
        progress, "FcmClient", lambda *_a, **_k: SimpleNamespace(aclose=AsyncMock(side_effect=lambda: closed.append(True)))
    )
    send_one = AsyncMock()
    monkeypatch.setattr(
        progress, "NotificationDispatchService", lambda *_a, **_k: SimpleNamespace(send_one=send_one)
    )

    await progress._dispatch_progress_notifications(
        lambda: session, trip_id=uuid.uuid4(), visit_date=date(2026, 9, 9)
    )

    assert [call.args[0] for call in send_one.await_args_list] == notifications
    assert closed == [True]


@pytest.mark.asyncio
async def test_progress_dispatch_failure_does_not_raise(monkeypatch) -> None:
    class Factory:
        def __call__(self):
            return self

        async def __aenter__(self):
            raise RuntimeError("database unavailable")

        async def __aexit__(self, *args):
            return None

    monkeypatch.setattr(progress, "FcmClient", lambda *_a, **_k: SimpleNamespace(aclose=AsyncMock()))
    monkeypatch.setattr(progress.logger, "exception", lambda *args, **kwargs: None)

    await progress._dispatch_progress_notifications(
        Factory(), trip_id=uuid.uuid4(), visit_date=date(2026, 9, 9)
    )
