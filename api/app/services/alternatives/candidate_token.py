"""DB 저장 없이 대체 장소 후보를 검증하는 HMAC token."""

from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import json
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from app.services.alternatives.policy import CANDIDATE_TTL_MINUTES


@dataclass(frozen=True)
class CandidateClaims:
    """서명된 후보의 최소 식별자."""

    detection_id: uuid.UUID
    place_id: str
    evaluated_at: datetime


def _encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def issue_candidate_token(
    detection_id: uuid.UUID,
    place_id: str,
    evaluated_at: datetime,
    secret: str,
) -> str:
    """감지·장소·평가 시각을 위변 방지 token으로 발급한다.

    Args:
        detection_id: 후보를 생성한 감지 ID.
        place_id: `tourapi:` 또는 `google:` 장소 ID.
        evaluated_at: timezone이 포함된 평가 시각.
        secret: HMAC-SHA256 서명 secret.

    Returns:
        base64url payload와 서명을 `.`으로 연결한 token.

    Raises:
        ValueError: 평가 시각에 timezone이 없는 경우.
    """
    if evaluated_at.tzinfo is None or evaluated_at.utcoffset() is None:
        raise ValueError("evaluated_at must be timezone-aware")
    payload = json.dumps(
        {"d": str(detection_id), "p": place_id, "t": int(evaluated_at.timestamp())},
        separators=(",", ":"),
        sort_keys=True,
    ).encode()
    signature = hmac.new(secret.encode(), payload, hashlib.sha256).digest()
    return f"{_encode(payload)}.{_encode(signature)}"


def verify_candidate_token(
    token: str,
    secret: str,
    now: datetime,
) -> CandidateClaims | None:
    """후보 token의 서명·형식·유효 시간을 검증한다.

    Args:
        token: 검증할 후보 token.
        secret: 발급에 사용한 HMAC secret.
        now: timezone이 포함된 서버 현재 시각.

    Returns:
        정상 claims. 위변·만료·형식 오류면 ``None``.
    """
    try:
        payload_text, signature_text = token.split(".")
        payload = _decode(payload_text)
        signature = _decode(signature_text)
        expected = hmac.new(secret.encode(), payload, hashlib.sha256).digest()
        if not hmac.compare_digest(signature, expected):
            return None
        raw = json.loads(payload)
        if set(raw) != {"d", "p", "t"} or not isinstance(raw["p"], str):
            return None
        evaluated_at = datetime.fromtimestamp(raw["t"], UTC)
        if now - evaluated_at > timedelta(minutes=CANDIDATE_TTL_MINUTES):
            return None
        return CandidateClaims(
            detection_id=uuid.UUID(raw["d"]),
            place_id=raw["p"],
            evaluated_at=evaluated_at,
        )
    except (
        ValueError,
        TypeError,
        KeyError,
        json.JSONDecodeError,
        binascii.Error,
        UnicodeDecodeError,
        OverflowError,
        OSError,
    ):
        return None


__all__ = ["CandidateClaims", "issue_candidate_token", "verify_candidate_token"]
