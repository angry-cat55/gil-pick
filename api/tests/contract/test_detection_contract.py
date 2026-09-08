"""F007 위치 감지 API의 공개 경로와 멱등 계약을 검증한다."""

from pathlib import Path

import yaml

from app.main import app


def test_detection_source_contract_declares_user_story_one_endpoints() -> None:
    contract_path = (
        Path(__file__).parents[3]
        / "specs"
        / "007-location-detection"
        / "contracts"
        / "detection.openapi.yaml"
    )
    contract = yaml.safe_load(contract_path.read_text(encoding="utf-8"))
    paths = contract["paths"]

    event = paths["/trips/{tripId}/days/{date}/progress/events"]["post"]
    decision = paths["/progress/transitions/{transitionId}/decisions"]["post"]

    assert set(event["responses"]) == {"200", "400", "401", "403", "404"}
    assert {parameter["$ref"].split("/")[-1] for parameter in event["parameters"]} == {
        "TripId",
        "VisitDate",
    }
    assert event["requestBody"]["content"]["application/json"]["schema"][
        "$ref"
    ].endswith("/ProgressEventRequest")

    assert {"200", "400", "401", "403", "404", "409"} == set(
        decision["responses"]
    )
    assert "IdempotencyKey" in {
        parameter["$ref"].split("/")[-1] for parameter in decision["parameters"]
    }


def test_runtime_openapi_exposes_detection_endpoints_and_response_models() -> None:
    schema = app.openapi()
    paths = schema["paths"]

    event = paths["/api/v1/trips/{tripId}/days/{date}/progress/events"]["post"]
    decision = paths["/api/v1/progress/transitions/{transitionId}/decisions"]["post"]

    assert {"200", "400", "401", "403", "404"} <= set(event["responses"])
    assert {"200", "400", "401", "403", "404", "409"} <= set(
        decision["responses"]
    )
    assert "ProgressEventEnvelope" in str(event["responses"]["200"])
    assert "TransitionResultEnvelope" in str(decision["responses"]["200"])

    event_parameters = {item["name"] for item in event.get("parameters", [])}
    decision_parameters = {
        item["name"] for item in decision.get("parameters", [])
    }
    assert "Idempotency-Key" not in event_parameters
    assert "Idempotency-Key" in decision_parameters


def test_detection_contract_declares_candidate_and_decision_rules() -> None:
    schemas = app.openapi()["components"]["schemas"]

    candidate = schemas["TransitionCandidate"]
    result = schemas["TransitionResult"]
    request = schemas["DecisionRequest"]

    assert {
        "transitionId",
        "itemId",
        "type",
        "status",
        "detectedAt",
        "autoFinalizeAt",
        "allowedDecisions",
        "evidence",
    } <= set(candidate["required"])
    assert set(request["properties"]["decision"]["$ref"].split("/")[-1:]) == {
        "TransitionDecision"
    }
    assert {
        "transitionId",
        "status",
        "affectedItems",
        "dayStatus",
        "undoDeadline",
        "nextPromptAt",
        "progressVersion",
    } <= set(result["required"])
