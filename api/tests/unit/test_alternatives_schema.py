"""F009 대체 장소 공개 스키마 계약 검증."""

from pathlib import Path

import yaml

from app.schemas.alternatives import (
    AlternativeCandidate,
    AlternativeErrorCode,
    AlternativeListData,
    AlternativeSearchItem,
    DetectionDismissData,
    OperatingStatus,
    ScoreBreakdown,
)


def test_public_schemas_match_source_contract_fields_and_enums() -> None:
    source = yaml.safe_load(
        (Path(__file__).parents[3] / "specs/009-alternative-places/contracts/alternatives.openapi.yaml")
        .read_text(encoding="utf-8")
    )
    contract = source["components"]["schemas"]

    runtime = {
        "AlternativeCandidate": AlternativeCandidate.model_json_schema(),
        "AlternativeListData": AlternativeListData.model_json_schema(),
        "AlternativeSearchItem": AlternativeSearchItem.model_json_schema(),
        "ScoreBreakdown": ScoreBreakdown.model_json_schema(),
    }
    for name, schema in runtime.items():
        assert set(schema.get("required", [])) == set(contract[name].get("required", []))
        assert set(schema["properties"]) == set(contract[name]["properties"])
    dismiss_contract = source["paths"]["/detections/{detectionId}/dismiss"]["post"]["responses"]["200"]["content"]["application/json"]["schema"]["properties"]["data"]
    dismiss_schema = DetectionDismissData.model_json_schema()
    assert set(dismiss_schema["required"]) == set(dismiss_contract["required"])
    assert set(dismiss_schema["properties"]) == set(dismiss_contract["properties"])
    assert {value.value for value in OperatingStatus} == set(contract["OperatingStatus"]["enum"])


def test_alternative_error_codes_include_contract_and_f010_handoff() -> None:
    assert {
        "DETECTION_NOT_ACTIVE",
        "INVALID_CANDIDATE",
        "TOUR_API_FAILED",
        "TOUR_API_TIMEOUT",
    } <= {value.value for value in AlternativeErrorCode}
