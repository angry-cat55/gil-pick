"""알림 유형별 사용자 문구 생성."""

from datetime import UTC, datetime


def place_change_message(place_name: str, reason: str) -> tuple[str, str]:
    return "다음 장소 변경을 추천해요", f"{place_name}: {reason}"


def transition_check_message(kind: str, place_name: str, prompt_seq: int = 1) -> tuple[str, str]:
    if kind == "ARRIVAL" and prompt_seq > 1:
        return "아직 도착 안 하셨나요?", f"{place_name} 도착을 다시 확인해요."
    if kind == "ARRIVAL":
        return "도착하셨나요?", f"{place_name} 근처에 머물고 있어요."
    return "다음 장소로 이동하셨나요?", f"{place_name}에서 나온 것 같아요."


def transition_auto_message(kind: str, undo_deadline: datetime | None, *, now: datetime | None = None) -> tuple[str, str]:
    title = "도착으로 자동 처리했어요" if kind == "ARRIVAL" else "출발로 자동 처리했어요"
    clock = now or datetime.now(UTC)
    if undo_deadline is None or undo_deadline <= clock:
        return title, "되돌리기 시간이 지났어요."
    minutes = max(1, int((undo_deadline - clock).total_seconds() / 60))
    return title, f"{minutes}분 안에 되돌릴 수 있어요."
