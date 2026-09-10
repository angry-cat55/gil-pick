"""F012 사용자 설정 조회·원자 갱신 service 테스트."""

import logging
from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest

from app.core.logging import request_id_context
from app.services.preferences import PreferencesService


@pytest.mark.asyncio
async def test_get_returns_stored_value() -> None:
    result = SimpleNamespace(scalar_one=lambda: False)
    session = SimpleNamespace(
        execute=AsyncMock(return_value=result),
    )

    assert await PreferencesService(session).get(uuid4()) is False
    session.execute.assert_awaited_once()


@pytest.mark.asyncio
async def test_update_uses_one_returning_statement_and_is_idempotent() -> None:
    result = SimpleNamespace(scalar_one=lambda: False)
    session = SimpleNamespace(execute=AsyncMock(return_value=result))
    user_id = uuid4()

    assert await PreferencesService(session).update(user_id, False) is False
    assert await PreferencesService(session).update(user_id, False) is False

    for call in session.execute.await_args_list:
        sql = str(call.args[0])
        assert "UPDATE users" in sql
        assert "users.user_id" in sql
        assert "RETURNING users.replacement_suggestion_enabled" in sql


@pytest.mark.asyncio
async def test_log_contains_request_id_without_user_or_setting_value(caplog) -> None:
    result = SimpleNamespace(scalar_one=lambda: True)
    session = SimpleNamespace(execute=AsyncMock(return_value=result))
    user_id = uuid4()
    request_id = str(uuid4())
    token = request_id_context.set(request_id)
    try:
        with caplog.at_level(logging.INFO, logger="gilpick.preferences"):
            await PreferencesService(session).update(user_id, True)
    finally:
        request_id_context.reset(token)

    text = caplog.text
    assert request_id in text
    assert str(user_id) not in text
    assert "True" not in text
