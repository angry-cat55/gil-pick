"""F008 DETECT-001·002·003 라우트 계약을 검증한다."""

from pathlib import Path

import pytest
import yaml

from app.api.errors import AppError
from app.api.v1.detections import _decode_cursor
from app.main import create_app


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
    paths = create_app().openapi()["paths"]
    listing = paths["/api/v1/trips/{tripId}/detections"]["get"]
    detail = paths["/api/v1/detections/{detectionId}"]["get"]
    read = paths["/api/v1/detections/{detectionId}/read"]["patch"]

    assert listing["operationId"].startswith("list_detections")
    assert {item["name"] for item in listing["parameters"]} == {"tripId", "cursor", "limit"}
    assert listing["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith("DetectionListEnvelope")
    assert detail["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith("DetectionDetailEnvelope")
    assert read["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith("DetectionReadEnvelope")
    assert {"400", "401", "403", "404"} <= set(listing["responses"])
    assert {"401", "403", "404"} <= set(detail["responses"])
    assert {"401", "403", "404"} <= set(read["responses"])
