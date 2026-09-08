import uuid
from decimal import Decimal
from sqlalchemy.orm import Mapped
from typing import get_type_hints

from app.models.detection import Detection


def test_detection_contract_and_fingerprint() -> None:
    table = Detection.__table__
    assert set(table.columns.keys()) == {
        "detection_id", "trip_day_id", "item_id", "primary_type", "status",
        "eta", "score", "reason", "evaluation_snapshot", "fingerprint",
        "detected_at", "last_evaluated_at", "read_at", "resolved_at",
    }
    day_id, item_id = uuid.uuid4(), uuid.uuid4()
    assert Detection.make_fingerprint(day_id, item_id) == f"{day_id}:{item_id}"
    assert table.c.evaluation_snapshot.type.compile().upper() == "JSON"
    assert get_type_hints(Detection)["score"] == Mapped[Decimal | None]
