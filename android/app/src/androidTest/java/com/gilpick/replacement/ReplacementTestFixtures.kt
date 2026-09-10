package com.gilpick.replacement

import com.gilpick.route.readyRouteJson

/**
 * F010 일정 변경 계측 test가 함께 쓰는 계약 응답 fixture.
 *
 * 값은 `specs/010-schedule-replacement/contracts/replacements.openapi.yaml`을 따른다. 단위 test의
 * `ReplacementFixtures`와 같은 값이며, 두 source set이 서로를 보지 못해 각자 둔다. F009
 * `AlternativeTestFixtures`가 같은 이유로 같은 구조다.
 *
 * `route`는 F005 ROUTE-001의 `route`와 같은 형식이라 `com.gilpick.route` 계측 fixture를 그대로 쓴다.
 */
internal const val REPL_TRIP_ID = "3f1d2c4b-5a6e-4f70-8a91-b2c3d4e5f607"
internal const val REPL_DETECTION_ID = "8a2918f7-e6d5-4c4b-8a29-18f7e6d5c4b3"
internal const val REPL_ITEM_ID = "9c8b7a6f-5e4d-4c3b-8a29-18f7e6d5c4b3"
internal const val REPL_PREVIEW_ID = "5d4c3b2a-1f0e-4d9c-8b7a-6f5e4d3c2b1a"
internal const val REPL_CANDIDATE_ID = "eyJkIjoiOGEyOTE4ZjcifQ.c2lnbmF0dXJl"
internal const val REPL_PLACE_ID = "tourapi:126508"
internal const val REPL_REQUEST_ID = "11111111-2222-4333-8444-555555555555"
internal const val REPL_SCHEDULE_VERSION = 3

/**
 * REPL-001 응답. 이동 시간 30분 → 25분, 이동 거리 5.0km → 4.2km로 둘 다 나아진다.
 *
 * @param closesAt 운영 마감 시각 비교 조각. 확보하지 못한 상황을 만들 때 `null` 쌍을 넣는다.
 */
internal fun routePreviewJson(
    closesAt: String = """{"before": "2026-09-08T18:00:00+09:00", "after": "2026-09-08T20:00:00+09:00"}""",
) = """
    {
      "success": true,
      "data": {
        "previewId": "$REPL_PREVIEW_ID",
        "detectionId": "$REPL_DETECTION_ID",
        "tripId": "$REPL_TRIP_ID",
        "date": "2026-09-08",
        "itemId": "$REPL_ITEM_ID",
        "originalPlace": {
          "placeId": "tourapi:264337",
          "name": "경복궁",
          "category": "HISTORY_CULTURE",
          "latitude": 37.5796,
          "longitude": 126.977
        },
        "alternativePlace": {
          "placeId": "$REPL_PLACE_ID",
          "name": "창덕궁",
          "category": "HISTORY_CULTURE",
          "latitude": 37.5794,
          "longitude": 126.991
        },
        "detectionReason": "오후 2시 이후 강한 비 + 매우 높은 혼잡",
        "comparison": {
          "totalDurationSeconds": {"before": 1800, "after": 1500},
          "totalDistanceMeters": {"before": 5000, "after": 4200},
          "estimatedArrivalAt": {"before": "2026-09-08T14:00:00+09:00", "after": "2026-09-08T14:20:00+09:00"},
          "closesAt": $closesAt
        },
        "route": ${readyRouteJson(scheduleVersion = REPL_SCHEDULE_VERSION)},
        "scheduleVersion": $REPL_SCHEDULE_VERSION,
        "expiresAt": "2026-09-08T13:35:00+09:00"
      },
      "meta": {"requestId": "$REPL_REQUEST_ID"}
    }
""".trimIndent()

/** 운영 마감 시각을 확보하지 못한 미리보기. 그 항목만 `null`이고 나머지는 그대로다(FR-002). */
internal fun previewWithoutClosingTimeJson() =
    routePreviewJson(closesAt = """{"before": null, "after": null}""")
