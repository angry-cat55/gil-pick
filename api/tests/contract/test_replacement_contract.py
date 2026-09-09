"""F010 일정 변경 runtime 계약 검증."""

import re

from app.main import create_app


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
