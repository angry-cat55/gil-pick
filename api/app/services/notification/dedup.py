"""알림 유형별 멱등 키 생성."""


def detection_key(detection_id: object) -> str:
    return f"detection:{detection_id}"


def transition_check_key(transition_id: object, kind: str, prompt_seq: int = 1) -> str:
    suffix = "arrival_check" if kind == "ARRIVAL" else "departure_check"
    return f"transition:{transition_id}:{suffix}" + (f":{prompt_seq}" if kind == "ARRIVAL" else "")


def transition_auto_key(transition_id: object) -> str:
    return f"transition:{transition_id}:auto"
