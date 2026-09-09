package com.gilpick.progress

import com.gilpick.itinerary.ItemStatus
import com.gilpick.itinerary.TransportMode

/** progress package test가 공유하는 계약 fixture. 값은 `contracts/progress.openapi.yaml`을 따른다. */

internal const val PROGRESS_TRIP_ID = "3f1d2c4b-5a6e-4f70-8a91-b2c3d4e5f607"
internal const val PROGRESS_DATE = "2026-09-08"
internal const val P_ITEM_A = "9c8b7a6f-5e4d-4c3b-8a29-18f7e6d5c4b3"
internal const val P_ITEM_B = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
internal const val P_ITEM_C = "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e"
internal const val PROGRESS_REQUEST_ID = "11111111-2222-4333-8444-555555555555"

/** 시작 전(NOT_STARTED, version 0): 모든 항목 PLANNED, ETA·inboundTravel null. */
internal fun notStartedJson() = envelope(
    """
    {
      "tripId": "$PROGRESS_TRIP_ID", "date": "$PROGRESS_DATE", "dayStatus": "NOT_STARTED",
      "progressVersion": 0, "scheduleVersion": 3,
      "actualStartedAt": null, "completedAt": null, "startLocation": null,
      "currentItemId": null, "nextItemId": "$P_ITEM_A",
      "items": [
        {"itemId": "$P_ITEM_A", "sequence": 1, "status": "PLANNED", "estimatedArrivalAt": null, "estimatedDepartureAt": null, "actualArrivedAt": null, "completedAt": null, "inboundTravel": null},
        {"itemId": "$P_ITEM_B", "sequence": 2, "status": "PLANNED", "estimatedArrivalAt": null, "estimatedDepartureAt": null, "actualArrivedAt": null, "completedAt": null, "inboundTravel": null}
      ]
    }
    """.trimIndent(),
)

/** 진행 중(IN_PROGRESS): A 완료(시작 위치에서 계산한 구간), B 이동 중(계획 경로 구간), C 예정인데 이동시간 불명(ETA·inboundTravel null). */
internal fun inProgressJson(progressVersion: Int = 2) = envelope(
    """
    {
      "tripId": "$PROGRESS_TRIP_ID", "date": "$PROGRESS_DATE", "dayStatus": "IN_PROGRESS",
      "progressVersion": $progressVersion, "scheduleVersion": 3,
      "actualStartedAt": "2026-09-08T01:00:00Z", "completedAt": null,
      "startLocation": {"latitude": 37.57, "longitude": 126.97},
      "currentItemId": null, "nextItemId": "$P_ITEM_B",
      "items": [
        {"itemId": "$P_ITEM_A", "sequence": 1, "status": "COMPLETED", "estimatedArrivalAt": "2026-09-08T01:10:00Z", "estimatedDepartureAt": "2026-09-08T02:10:00Z", "actualArrivedAt": "2026-09-08T01:12:00Z", "completedAt": "2026-09-08T02:00:00Z",
         "inboundTravel": {"fromItemId": null, "transportMode": "WALK", "durationSeconds": 600, "distanceMeters": 800, "source": "COMPUTED"}},
        {"itemId": "$P_ITEM_B", "sequence": 2, "status": "EN_ROUTE", "estimatedArrivalAt": "2026-09-08T02:20:00Z", "estimatedDepartureAt": "2026-09-08T03:20:00Z", "actualArrivedAt": null, "completedAt": null,
         "inboundTravel": {"fromItemId": "$P_ITEM_A", "transportMode": "TRANSIT", "durationSeconds": 1200, "distanceMeters": 3400, "source": "PLANNED_ROUTE"}},
        {"itemId": "$P_ITEM_C", "sequence": 3, "status": "PLANNED", "estimatedArrivalAt": null, "estimatedDepartureAt": null, "actualArrivedAt": null, "completedAt": null, "inboundTravel": null}
      ]
    }
    """.trimIndent(),
)

/** 항목마다 처리 출처와 거절 이유가 다른 응답(#312 계약). */
internal fun itemMetadataJson() = envelope(
    """
    {
      "tripId": "$PROGRESS_TRIP_ID", "date": "$PROGRESS_DATE", "dayStatus": "IN_PROGRESS",
      "progressVersion": 4, "scheduleVersion": 3,
      "actualStartedAt": "2026-09-08T01:00:00Z", "completedAt": null,
      "startLocation": {"latitude": 37.57, "longitude": 126.97},
      "currentItemId": null, "nextItemId": "$P_ITEM_B",
      "items": [
        {"itemId": "$P_ITEM_A", "sequence": 1, "status": "COMPLETED", "estimatedArrivalAt": null, "estimatedDepartureAt": null, "actualArrivedAt": null, "completedAt": null, "inboundTravel": null,
         "processingSource": "AUTO", "eventRejectionReason": "LOW_ACCURACY"},
        {"itemId": "$P_ITEM_B", "sequence": 2, "status": "ARRIVED", "estimatedArrivalAt": null, "estimatedDepartureAt": null, "actualArrivedAt": null, "completedAt": null, "inboundTravel": null,
         "processingSource": "MANUAL", "eventRejectionReason": null},
        {"itemId": "$P_ITEM_C", "sequence": 3, "status": "PLANNED", "estimatedArrivalAt": null, "estimatedDepartureAt": null, "actualArrivedAt": null, "completedAt": null, "inboundTravel": null,
         "processingSource": null, "eventRejectionReason": null}
      ]
    }
    """.trimIndent(),
)

private fun envelope(data: String) =
    """{"success": true, "data": $data, "meta": {"requestId": "$PROGRESS_REQUEST_ID"}}"""

/** 계약이 정한 error envelope. */
internal fun progressErrorJson(code: String, retryable: Boolean = false) =
    """{"success":false,"error":{"code":"$code","message":"진단용 설명","retryable":$retryable,"details":{}},""" +
        """"meta":{"requestId":"$PROGRESS_REQUEST_ID"}}"""

/** [inProgressJson]과 같은 값의 DTO. */
internal fun inProgress(progressVersion: Int = 2): ProgressData = ProgressData(
    tripId = PROGRESS_TRIP_ID,
    date = PROGRESS_DATE,
    dayStatus = DayStatus.IN_PROGRESS,
    progressVersion = progressVersion,
    scheduleVersion = 3,
    actualStartedAt = "2026-09-08T01:00:00Z",
    completedAt = null,
    startLocation = StartLocationDto(latitude = 37.57, longitude = 126.97),
    currentItemId = null,
    nextItemId = P_ITEM_B,
    items = listOf(
        ProgressItemDto(
            itemId = P_ITEM_A, sequence = 1, status = ItemStatus.COMPLETED,
            estimatedArrivalAt = "2026-09-08T01:10:00Z", estimatedDepartureAt = "2026-09-08T02:10:00Z",
            actualArrivedAt = "2026-09-08T01:12:00Z", completedAt = "2026-09-08T02:00:00Z",
            inboundTravel = InboundTravelDto(fromItemId = null, transportMode = TransportMode.WALK, durationSeconds = 600, distanceMeters = 800, source = TravelSource.COMPUTED),
        ),
        ProgressItemDto(
            itemId = P_ITEM_B, sequence = 2, status = ItemStatus.EN_ROUTE,
            estimatedArrivalAt = "2026-09-08T02:20:00Z", estimatedDepartureAt = "2026-09-08T03:20:00Z",
            actualArrivedAt = null, completedAt = null,
            inboundTravel = InboundTravelDto(fromItemId = P_ITEM_A, transportMode = TransportMode.TRANSIT, durationSeconds = 1200, distanceMeters = 3400, source = TravelSource.PLANNED_ROUTE),
        ),
        ProgressItemDto(
            itemId = P_ITEM_C, sequence = 3, status = ItemStatus.PLANNED,
            estimatedArrivalAt = null, estimatedDepartureAt = null, actualArrivedAt = null, completedAt = null,
            inboundTravel = null,
        ),
    ),
)
