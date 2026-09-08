"""F008 주기 평가 작업의 반복·예외 격리를 검증한다."""

import asyncio

import pytest

from app.jobs import variable_detection


@pytest.mark.asyncio
async def test_job_runs_one_tick_and_sleeps(monkeypatch: pytest.MonkeyPatch) -> None:
    calls = []

    class Session:
        async def __aenter__(self): return self
        async def __aexit__(self, *args): return None
        def begin(self): return self

    async def evaluate(session): calls.append(session)
    async def stop(_): raise asyncio.CancelledError

    monkeypatch.setattr(variable_detection, "evaluate_all_active", evaluate)
    monkeypatch.setattr(variable_detection.asyncio, "sleep", stop)

    with pytest.raises(asyncio.CancelledError):
        await variable_detection.run_variable_detection(lambda: Session(), interval_seconds=1)
    assert len(calls) == 1


@pytest.mark.asyncio
async def test_job_subtracts_evaluation_time_from_interval(monkeypatch: pytest.MonkeyPatch) -> None:
    sleeps = []

    class Session:
        async def __aenter__(self): return self
        async def __aexit__(self, *args): return None
        def begin(self): return self

    class Loop:
        values = iter((100.0, 102.5))
        def time(self): return next(self.values)

    async def evaluate(session): pass
    async def stop(delay):
        sleeps.append(delay)
        raise asyncio.CancelledError

    monkeypatch.setattr(variable_detection, "evaluate_all_active", evaluate)
    monkeypatch.setattr(variable_detection.asyncio, "get_running_loop", lambda: Loop())
    monkeypatch.setattr(variable_detection.asyncio, "sleep", stop)

    with pytest.raises(asyncio.CancelledError):
        await variable_detection.run_variable_detection(lambda: Session(), interval_seconds=10)
    assert sleeps == [7.5]


@pytest.mark.asyncio
async def test_job_continues_after_failed_tick(monkeypatch: pytest.MonkeyPatch) -> None:
    calls = 0

    class Session:
        async def __aenter__(self): return self
        async def __aexit__(self, *args): return None
        def begin(self): return self

    async def evaluate(session):
        nonlocal calls
        calls += 1
        if calls == 1:
            raise RuntimeError("tick failed")

    async def sleep(_):
        if calls == 2:
            raise asyncio.CancelledError

    monkeypatch.setattr(variable_detection, "evaluate_all_active", evaluate)
    monkeypatch.setattr(variable_detection.asyncio, "sleep", sleep)

    with pytest.raises(asyncio.CancelledError):
        await variable_detection.run_variable_detection(lambda: Session(), interval_seconds=1)
    assert calls == 2
