"""대체 장소 후보 서명 token 검증."""

import uuid
from datetime import UTC, datetime, timedelta

from app.services.alternatives.candidate_token import (
    issue_candidate_token,
    verify_candidate_token,
)


SECRET = "candidate-test-secret-at-least-32-bytes"
NOW = datetime(2026, 9, 9, 12, tzinfo=UTC)


def test_candidate_token_round_trip() -> None:
    detection_id = uuid.uuid4()
    token = issue_candidate_token(detection_id, "tourapi:123", NOW, SECRET)

    claims = verify_candidate_token(token, SECRET, NOW)

    assert claims is not None
    assert claims.detection_id == detection_id
    assert claims.place_id == "tourapi:123"
    assert claims.evaluated_at == NOW


def test_candidate_token_rejects_tampering_expiry_and_malformed_value() -> None:
    token = issue_candidate_token(uuid.uuid4(), "google:abc", NOW, SECRET)
    payload, signature = token.split(".")
    tampered = f"{payload}.{('A' if signature[0] != 'A' else 'B')}{signature[1:]}"

    assert verify_candidate_token(tampered, SECRET, NOW) is None
    assert verify_candidate_token(token, SECRET, NOW + timedelta(minutes=16)) is None
    assert verify_candidate_token("not-a-token", SECRET, NOW) is None

