package com.gilpick.progress

/**
 * F007 위치 감지 test가 함께 쓰는 계약 응답 fixture.
 *
 * 값은 `contracts/detection.openapi.yaml`을 따른다. F006 [ProgressFixtures]와 같은 방식으로
 * JSON 문자열을 두어 계약과 DTO가 어긋나면 파싱에서 드러나게 한다.
 */
internal const val TRIP_ID = "3f1d2c4b-5a6e-4f70-8a91-b2c3d4e5f607"
internal const val DATE = "2026-09-08"
internal const val ITEM_ID = "9c8b7a6f-5e4d-4c3b-8a29-18f7e6d5c4b3"
internal const val EVENT_ID = "5c4b3a29-18f7-4e6d-8c4b-3a2918f7e6d5"
internal const val TRANSITION_ID = "8a2918f7-e6d5-4c4b-8a29-18f7e6d5c4b3"
internal const val GEOFENCE_ID = "$ITEM_ID:ARRIVAL"
internal const val DETECTION_REQUEST_ID = "11111111-2222-4333-8444-555555555555"

/** 도착 조건을 충족한 `DWELL` 이벤트 요청. */
internal fun dwellRequest() = ProgressEventRequest(
    eventId = EVENT_ID,
    eventType = ProgressEventType.DWELL,
    itemId = ITEM_ID,
    geofenceId = GEOFENCE_ID,
    occurredAt = "2026-09-08T12:03:00+09:00",
    location = EventLocationDto(latitude = 37.5825, longitude = 126.9830, accuracyMeters = 18.0),
)

/** 이벤트를 받아 도착 후보를 만든 응답. */
internal fun acceptedWithCandidateJson() = """
    {"success": true,
     "data": {
       "eventId": "$EVENT_ID",
       "accepted": true,
       "rejectionReason": null,
       "candidate": {
         "transitionId": "$TRANSITION_ID",
         "itemId": "$ITEM_ID",
         "type": "ARRIVAL",
         "status": "PENDING_CONFIRMATION",
         "detectedAt": "2026-09-08T12:03:00+09:00",
         "autoFinalizeAt": "2026-09-08T12:08:00+09:00",
         "allowedDecisions": ["CONFIRM", "NOT_ARRIVED"],
         "evidence": {"occurredAt": "2026-09-08T12:03:00+09:00", "accuracyMeters": 18.0, "dwellMinutes": 6}
       },
       "cancelledTransitionId": null
     },
     "meta": {"requestId": "$DETECTION_REQUEST_ID"}}
""".trimIndent()

/** 기준을 넘겨 판정에 쓰지 않은 이벤트 응답. */
internal fun rejectedJson(reason: String) = """
    {"success": true,
     "data": {
       "eventId": "$EVENT_ID",
       "accepted": false,
       "rejectionReason": "$reason",
       "candidate": null,
       "cancelledTransitionId": null
     },
     "meta": {"requestId": "$DETECTION_REQUEST_ID"}}
""".trimIndent()

/** 재진입으로 출발 후보가 취소된 응답. */
internal fun reenterCancelJson() = """
    {"success": true,
     "data": {
       "eventId": "$EVENT_ID",
       "accepted": true,
       "rejectionReason": null,
       "candidate": null,
       "cancelledTransitionId": "$TRANSITION_ID"
     },
     "meta": {"requestId": "$DETECTION_REQUEST_ID"}}
""".trimIndent()

/** 사용자가 직접 확인해 확정된 응답. `undoDeadline`은 계약대로 null이다(FR-020). */
internal fun confirmedJson() = """
    {"success": true,
     "data": {
       "transitionId": "$TRANSITION_ID",
       "status": "CONFIRMED",
       "affectedItems": [{"itemId": "$ITEM_ID", "beforeStatus": "EN_ROUTE", "afterStatus": "ARRIVED"}],
       "dayStatus": null,
       "undoDeadline": null,
       "nextPromptAt": null,
       "progressVersion": 4
     },
     "meta": {"requestId": "$DETECTION_REQUEST_ID"}}
""".trimIndent()

/** `아직이에요`로 후보가 취소된 응답. */
internal fun cancelledJson() = """
    {"success": true,
     "data": {
       "transitionId": "$TRANSITION_ID",
       "status": "CANCELLED",
       "affectedItems": [],
       "dayStatus": null,
       "undoDeadline": null,
       "nextPromptAt": "2026-09-08T12:20:00+09:00",
       "progressVersion": 3
     },
     "meta": {"requestId": "$DETECTION_REQUEST_ID"}}
""".trimIndent()

/** 자동 확정을 되돌려 당일 완료까지 해제된 응답. */
internal fun undoneJson() = """
    {"success": true,
     "data": {
       "transitionId": "$TRANSITION_ID",
       "status": "UNDONE",
       "restoredItems": [{"itemId": "$ITEM_ID", "beforeStatus": "ARRIVED", "afterStatus": "EN_ROUTE"}],
       "dayStatus": "IN_PROGRESS",
       "detectionResumeAt": "2026-09-08T12:20:00+09:00",
       "progressVersion": 6
     },
     "meta": {"requestId": "$DETECTION_REQUEST_ID"}}
""".trimIndent()

/** 계약이 정한 error envelope. repository가 code로 원인을 판정하므로 code를 함께 준다. */
internal fun detectionErrorJson(code: String) = """
    {"success": false,
     "error": {"code": "$code", "message": "진단용 설명", "retryable": false},
     "meta": {"requestId": "$DETECTION_REQUEST_ID"}}
""".trimIndent()
