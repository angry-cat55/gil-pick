"""알림 멱등 키와 사용자 문구 계약 테스트."""

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest

from app.services.notification.dedup import (
    detection_key,
    transition_auto_key,
    transition_check_key,
)
from app.services.notification.messages import (
    place_change_message,
    transition_auto_message,
    transition_check_message,
)

NOW = datetime(2026, 9, 10, 3, 0, tzinfo=UTC)


def test_detection_and_transition_dedup_keys_are_stable() -> None:
    detection_id, transition_id = uuid4(), uuid4()

    assert detection_key(detection_id) == f"detection:{detection_id}"
    assert transition_check_key(transition_id, "ARRIVAL", 1) == (
        f"transition:{transition_id}:arrival_check:1"
    )
    assert transition_check_key(transition_id, "ARRIVAL", 2) == (
        f"transition:{transition_id}:arrival_check:2"
    )
    assert transition_check_key(transition_id, "DEPARTURE") == (
        f"transition:{transition_id}:departure_check"
    )
    assert transition_auto_key(transition_id) == f"transition:{transition_id}:auto"


@pytest.mark.parametrize(
    ("kind", "prompt_seq", "expected"),
    [
        ("ARRIVAL", 1, ("도착하셨나요?", "광화문 근처에 머물고 있어요.")),
        ("ARRIVAL", 2, ("아직 도착 안 하셨나요?", "광화문 도착을 다시 확인해요.")),
        ("DEPARTURE", 1, ("다음 장소로 이동하셨나요?", "광화문에서 나온 것 같아요.")),
    ],
)
def test_transition_check_message_matches_type(
    kind: str, prompt_seq: int, expected: tuple[str, str]
) -> None:
    assert transition_check_message(kind, "광화문", prompt_seq) == expected


def test_place_change_message_uses_detection_reason_without_extra_data() -> None:
    title, body = place_change_message(
        "광화문", "도착 시각에 영업이 어렵거나 곧 문을 닫아요"
    )

    assert title == "다음 장소 변경을 추천해요"
    assert body == "광화문: 도착 시각에 영업이 어렵거나 곧 문을 닫아요"


def test_auto_message_reports_remaining_undo_minutes() -> None:
    assert transition_auto_message(
        "ARRIVAL", NOW + timedelta(minutes=4, seconds=59), now=NOW
    ) == ("도착으로 자동 처리했어요", "4분 안에 되돌릴 수 있어요.")


@pytest.mark.parametrize("deadline", [None, NOW, NOW - timedelta(seconds=1)])
def test_auto_message_reports_expired_undo_window(deadline: datetime | None) -> None:
    assert transition_auto_message("DEPARTURE", deadline, now=NOW) == (
        "출발로 자동 처리했어요",
        "되돌리기 시간이 지났어요.",
    )
