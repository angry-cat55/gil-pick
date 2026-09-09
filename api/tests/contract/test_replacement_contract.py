"""F010 일정 변경 runtime 계약 검증."""

import re
import uuid

import pytest
from fastapi.testclient import TestClient

from app.api.dependencies import get_current_principal
from app.api.errors import AppError
from app.api.v1.replacements import get_replacement_service
from app.core.security import AuthPrincipal
from app.main import create_app
from app.schemas.replacement import ReplacementUndoResult


def test_replacement_preview_contract_matches_repl_001() -> None:
    operation = create_app().openapi()["paths"][
        "/api/v1/detections/{detectionId}/route-previews"
    ]["post"]

    assert operation["operationId"] == "createRoutePreview"
    assert {parameter["name"] for parameter in operation["parameters"]} == {
        "detectionId",
        "Idempotency-Key",
    }
    assert set(operation["responses"]) == {"200", "400", "401", "403", "404", "409", "502", "504"}

    schemas = create_app().openapi()["components"]["schemas"]
    request = schemas["CreatePreviewRequest"]
    assert set(request["required"]) == {"placeId", "scheduleVersion"}
    assert request["properties"]["scheduleVersion"]["minimum"] == 1

    preview = schemas["RoutePreview"]
    assert set(preview["required"]) == {
        "previewId", "detectionId", "tripId", "date", "itemId",
        "originalPlace", "alternativePlace", "detectionReason",
        "comparison", "route", "scheduleVersion", "expiresAt",
    }
    comparison = schemas["PreviewComparison"]
    assert set(comparison["required"]) == {
        "totalDurationSeconds", "totalDistanceMeters", "estimatedArrivalAt", "closesAt",
    }
    assert all(
        schemas["ComparisonValue"]["required"] == ["before", "after"]
        for _ in comparison["properties"]
    )


def test_replacement_preview_reject_contract_matches_repl_003() -> None:
    operation = create_app().openapi()["paths"][
        "/api/v1/route-previews/{previewId}/reject"
    ]["post"]

    assert operation["operationId"] == "rejectRoutePreview"
    assert [parameter["name"] for parameter in operation["parameters"]] == ["previewId"]
    assert set(operation["responses"]) == {"204", "401", "403", "404", "409"}
    assert "requestBody" not in operation


def test_replacement_approval_contract_matches_repl_002() -> None:
    operation = create_app().openapi()["paths"][
        "/api/v1/route-previews/{previewId}/approve"
    ]["post"]

    assert operation["operationId"] == "approveRoutePreview"
    assert {parameter["name"] for parameter in operation["parameters"]} == {
        "previewId",
        "Idempotency-Key",
    }
    assert set(operation["responses"]) == {"200", "400", "401", "403", "404", "409"}
    assert set(re.findall(r"`([A-Z_]+)`", operation["responses"]["409"]["description"])) == {
        "PREVIEW_EXPIRED",
        "PREVIEW_SUPERSEDED",
        "PREVIEW_REJECTED",
        "ALREADY_APPROVED",
        "VERSION_CONFLICT",
        "ITEM_ALREADY_VISITED",
        "ALTERNATIVE_UNAVAILABLE",
        "DETECTION_NOT_ACTIVE",
    }

    replacement = create_app().openapi()["components"]["schemas"]["Replacement"]
    assert set(replacement["required"]) == {
        "replacementId",
        "tripId",
        "date",
        "itemId",
        "originalPlaceId",
        "newPlaceId",
        "originalPlaceName",
        "newPlaceName",
        "scheduleVersion",
        "routeStatus",
        "undoExpiresAt",
    }
    # 승인은 미리보기에서 계산이 완료된 경로만 확정하므로 READY만 반환한다.
    assert replacement["properties"]["routeStatus"]["const"] == "READY"
    assert replacement["properties"]["originalPlaceName"]["type"] == "string"
    assert replacement["properties"]["newPlaceName"]["type"] == "string"


def test_replacement_undo_contract_matches_repl_004_and_prog_001() -> None:
    schema = create_app().openapi()
    operation = schema["paths"]["/api/v1/replacements/{replacementId}/undo"]["post"]

    assert operation["operationId"] == "undoReplacement"
    assert [parameter["name"] for parameter in operation["parameters"]] == ["replacementId"]
    assert set(operation["responses"]) == {"200", "401", "403", "404", "409"}
    assert set(re.findall(r"`([A-Z_]+)`", operation["responses"]["409"]["description"])) == {
        "UNDO_EXPIRED",
        "FOLLOW_UP_CHANGE_EXISTS",
    }

    schemas = schema["components"]["schemas"]
    assert set(schemas["ReplacementUndoResult"]["required"]) == {
        "replacementId", "restored", "scheduleVersion", "routeStatus", "detectionRestored",
    }
    assert schemas["UndoEnvelope"]["properties"]["data"]["$ref"].endswith(
        "/ReplacementUndoResult"
    )
    assert set(schemas["UndoableReplacement"]["required"]) == {
        "replacementId", "itemId", "originalPlaceName", "newPlaceName", "undoExpiresAt",
    }
    assert "UndoableReplacement" in str(
        schemas["ProgressData"]["properties"]["undoableReplacement"]
    )


class _UndoService:
    def __init__(self, error: AppError | None = None) -> None:
        self.error = error

    async def undo_replacement(self, **_kwargs) -> ReplacementUndoResult:
        if self.error is not None:
            raise self.error
        return ReplacementUndoResult(
            replacement_id=uuid.UUID("00000000-0000-0000-0000-000000000123"),
            restored=True,
            schedule_version=3,
            route_status="READY",
            detection_restored=True,
        )


def _undo_client(service: _UndoService) -> TestClient:
    app = create_app()
    app.dependency_overrides[get_current_principal] = lambda: AuthPrincipal(
        user_id=uuid.uuid4(), session_id=uuid.uuid4(), token_id=uuid.uuid4()
    )
    app.dependency_overrides[get_replacement_service] = lambda: service
    return TestClient(app)


def test_replacement_undo_router_returns_camel_case_envelope() -> None:
    response = _undo_client(_UndoService()).post(
        "/api/v1/replacements/00000000-0000-0000-0000-000000000123/undo"
    )

    assert response.status_code == 200
    assert response.json()["data"] == {
        "replacementId": "00000000-0000-0000-0000-000000000123",
        "restored": True,
        "scheduleVersion": 3,
        "routeStatus": "READY",
        "detectionRestored": True,
    }


@pytest.mark.parametrize(
    ("status", "code"),
    [
        (403, "TRIP_FORBIDDEN"),
        (404, "REPLACEMENT_NOT_FOUND"),
        (409, "UNDO_EXPIRED"),
        (409, "FOLLOW_UP_CHANGE_EXISTS"),
    ],
)
def test_replacement_undo_router_preserves_domain_errors(status: int, code: str) -> None:
    response = _undo_client(_UndoService(AppError(status, code, "되돌리기 실패"))).post(
        f"/api/v1/replacements/{uuid.uuid4()}/undo"
    )

    assert response.status_code == status
    assert response.json()["error"]["code"] == code
