"""F008 DETECT-001·002·003 라우트 계약과 F009 DETECT-004(감지 거절)를 검증한다."""

import uuid
from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace

import pytest
import yaml

from app.api.errors import AppError
from app.api.v1.detections import _decode_cursor, owned_detection
from app.main import create_app
from app.schemas.detection import DetectionStatus


def test_source_contract_declares_fields_enums_pagination_and_errors() -> None:
    contract = yaml.safe_load(
        (Path(__file__).parents[3] / "specs/008-variable-detection/contracts/detections.openapi.yaml")
        .read_text(encoding="utf-8")
    )
    schemas = contract["components"]["schemas"]
    assert set(schemas["DetectionListItem"]["required"]) == {
        "detectionId", "itemId", "placeName", "primaryType", "status", "totalRiskScore", "createdAt", "read"
    }
    assert {"nextCursor", "hasNext"} == set(schemas["PaginatedMeta"]["properties"]["pagination"]["required"])
    assert schemas["DetectionStatus"]["enum"] == ["ACTIVE", "RESOLVED", "DISMISSED", "INVALIDATED"]
    assert set(schemas["ErrorEnvelope"]["properties"]["error"]["properties"]["code"]["enum"]) == {
        "INVALID_REQUEST", "INVALID_ACCESS_TOKEN", "TRIP_FORBIDDEN", "TRIP_NOT_FOUND", "DETECTION_NOT_FOUND", "DETECTION_FORBIDDEN"
    }


def test_cursor_requires_timezone_aware_timestamp() -> None:
    with pytest.raises(AppError) as error:
        _decode_cursor("WyIyMDI2LTA5LTA4VDA0OjMwOjAwIiwgIjAwMDAwMDAwLTAwMDAtMDAwMC0wMDAwLTAwMDAwMDAwMDAwMCJd")
    assert error.value.status_code == 400


def test_detection_routes_match_documented_methods_and_models() -> None:
    runtime = create_app().openapi()
    paths = runtime["paths"]
    listing = paths["/api/v1/trips/{tripId}/detections"]["get"]
    detail = paths["/api/v1/detections/{detectionId}"]["get"]
    read = paths["/api/v1/detections/{detectionId}/read"]["patch"]

    assert listing["operationId"].startswith("list_detections")
    assert {item["name"] for item in listing["parameters"]} == {"tripId", "cursor", "limit", "status"}
    assert listing["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith("DetectionListEnvelope")
    assert detail["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith("DetectionDetailEnvelope")
    assert read["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith("DetectionReadEnvelope")
    assert {"400", "401", "403", "404"} <= set(listing["responses"])
    assert {"401", "403", "404"} <= set(detail["responses"])
    assert {"401", "403", "404"} <= set(read["responses"])
    schemas = runtime["components"]["schemas"]
    assert set(schemas["DetectionListItem"]["required"]) == {
        "detectionId", "itemId", "placeName", "primaryType", "status",
        "totalRiskScore", "eta", "reason", "createdAt", "read",
    }
    assert set(schemas["DetectionStatus"]["enum"]) == {
        "ACTIVE", "RESOLVED", "DISMISSED", "INVALIDATED",
    }
    assert {"nextCursor", "hasNext"} == set(
        schemas["app__schemas__detection__Pagination"]["required"]
    )
    assert {
        "INVALID_REQUEST", "INVALID_ACCESS_TOKEN", "TRIP_FORBIDDEN",
        "TRIP_NOT_FOUND", "DETECTION_NOT_FOUND", "DETECTION_FORBIDDEN",
    } == set(schemas["DetectionErrorCode"]["enum"])
    source = yaml.safe_load(
        (Path(__file__).parents[3] / "specs/008-variable-detection/contracts/detections.openapi.yaml")
        .read_text(encoding="utf-8")
    )["components"]["schemas"]
    for name in (
        "DetectionDetail",
        "VariableVerdicts",
        "CongestionVerdict",
        "WeatherVerdict",
        "OperatingHoursVerdict",
    ):
        assert set(schemas[name].get("required", [])) == set(source[name].get("required", []))
        assert set(schemas[name]["properties"]) == set(source[name]["properties"])
    assert set(schemas["DetectionType"]["enum"]) == set(source["DetectionType"]["enum"])
    assert set(schemas["DetectionReadData"]["required"]) == {"detectionId", "read"}


def test_f009_detection_list_extension_matches_contract() -> None:
    source = yaml.safe_load(
        (Path(__file__).parents[3] / "specs/009-alternative-places/contracts/alternatives.openapi.yaml")
        .read_text(encoding="utf-8")
    )["components"]["schemas"]["DetectionListItem"]
    runtime = create_app().openapi()["components"]["schemas"]["DetectionListItem"]

    assert set(runtime["required"]) == set(source["required"])
    assert set(runtime["properties"]) == set(source["properties"])
    assert callable(owned_detection)


# --- F009 DETECT-004 감지 거절 (기존 일정 그대로 진행) ---

_DID = uuid.UUID("00000000-0000-0000-0000-0000000d0004")
_UID = uuid.UUID("00000000-0000-0000-0000-000000000001")
_DECIDED_AT = datetime(2026, 9, 9, 3, 30, tzinfo=UTC)


class _FakeSession:
    """dismiss_detection이 실행하는 UPDATE 문과 refresh 시점 상태를 관찰한다."""

    def __init__(self, on_refresh=None) -> None:
        self.statements: list = []
        self._on_refresh = on_refresh

    async def execute(self, statement):
        self.statements.append(statement)
        return SimpleNamespace(rowcount=1)

    async def refresh(self, obj) -> None:
        if self._on_refresh is not None:
            self._on_refresh(obj)


@pytest.fixture
def patch_owned(monkeypatch):
    def _install(*, result=None, error=None):
        async def _fake(detection_id, user_id, session):
            assert detection_id == _DID
            assert user_id == _UID
            if error is not None:
                raise error
            return result

        monkeypatch.setattr("app.services.alternatives.owned_detection", _fake)

    return _install


def test_detect_004_dismiss_route_matches_contract() -> None:
    runtime = create_app().openapi()
    operation = runtime["paths"]["/api/v1/detections/{detectionId}/dismiss"]["post"]

    assert operation["operationId"].startswith("dismiss_detection")
    assert {item["name"] for item in operation["parameters"]} == {"detectionId"}
    assert set(operation["responses"]) == {"200", "401", "403", "404"}
    assert operation["responses"]["200"]["content"]["application/json"]["schema"][
        "$ref"
    ].endswith("DetectionDismissEnvelope")

    data_schema = runtime["components"]["schemas"]["DetectionDismissData"]
    assert set(data_schema["required"]) == {"detectionId", "status", "decidedAt"}
    assert {"type": "null"} in data_schema["properties"]["decidedAt"]["anyOf"]

    source = yaml.safe_load(
        (Path(__file__).parents[3] / "specs/009-alternative-places/contracts/alternatives.openapi.yaml")
        .read_text(encoding="utf-8")
    )["paths"]["/detections/{detectionId}/dismiss"]["post"]
    assert set(source["responses"]) == {"200", "401", "403", "404"}
    assert set(
        source["responses"]["200"]["content"]["application/json"]["schema"]["properties"]["data"]["required"]
    ) == {"detectionId", "status", "decidedAt"}


@pytest.mark.asyncio
async def test_dismiss_active_detection_transitions_once_and_reports_decided_at(patch_owned) -> None:
    from app.services.alternatives import dismiss_detection

    detection = SimpleNamespace(detection_id=_DID, status="ACTIVE", resolved_at=None)

    def _apply_guarded_update(obj) -> None:  # ACTIVE 행에 UPDATE가 반영된 뒤 상태
        obj.status = "DISMISSED"
        obj.resolved_at = _DECIDED_AT

    session = _FakeSession(on_refresh=_apply_guarded_update)
    patch_owned(result=(detection, uuid.uuid4(), "장소"))

    data = await dismiss_detection(_DID, _UID, session)

    assert data.detection_id == _DID
    assert data.status is DetectionStatus.DISMISSED
    assert data.decided_at == _DECIDED_AT

    assert len(session.statements) == 1
    sql = str(session.statements[0]).lower()
    assert sql.startswith("update detections set")
    assert "status" in sql and "resolved_at" in sql
    assert "where" in sql and "status" in sql.split("where", 1)[1]  # ACTIVE 가드


@pytest.mark.asyncio
async def test_dismiss_is_idempotent_for_non_active_detection(patch_owned) -> None:
    from app.services.alternatives import dismiss_detection

    detection = SimpleNamespace(detection_id=_DID, status="DISMISSED", resolved_at=_DECIDED_AT)
    session = _FakeSession(on_refresh=lambda obj: None)  # 가드에 걸려 어떤 행도 바뀌지 않음
    patch_owned(result=(detection, uuid.uuid4(), "장소"))

    data = await dismiss_detection(_DID, _UID, session)

    assert data.status is DetectionStatus.DISMISSED
    assert data.decided_at == _DECIDED_AT  # resolved_at 불변
    assert len(session.statements) == 1  # 상태 가드가 있는 UPDATE 1회


@pytest.mark.asyncio
async def test_dismiss_reports_null_decided_at_for_invalidated(patch_owned) -> None:
    from app.services.alternatives import dismiss_detection

    detection = SimpleNamespace(detection_id=_DID, status="INVALIDATED", resolved_at=_DECIDED_AT)
    session = _FakeSession(on_refresh=lambda obj: None)
    patch_owned(result=(detection, uuid.uuid4(), "장소"))

    data = await dismiss_detection(_DID, _UID, session)

    assert data.status is DetectionStatus.INVALIDATED
    assert data.decided_at is None


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "error",
    [
        AppError(404, "DETECTION_NOT_FOUND", "감지 결과를 찾을 수 없습니다."),
        AppError(403, "DETECTION_FORBIDDEN", "다른 사용자의 감지 결과입니다."),
    ],
)
async def test_dismiss_propagates_ownership_errors_without_writing(patch_owned, error: AppError) -> None:
    from app.services.alternatives import dismiss_detection

    session = _FakeSession()
    patch_owned(error=error)

    with pytest.raises(AppError) as raised:
        await dismiss_detection(_DID, _UID, session)

    assert raised.value.status_code == error.status_code
    assert raised.value.code == error.code
    assert session.statements == []
