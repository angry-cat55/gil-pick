package com.gilpick.notification

/** F011 알림 test 고정값. ID는 계약 예시 형태의 UUID다. */

internal const val TRIP_ID = "3f1d2c4b-5a6e-4f70-8a91-b2c3d4e5f607"
internal const val DETECTION_ID = "8a2918f7-e6d5-4c4b-8a29-18f7e6d5c4b3"
internal const val NOTIFICATION_ID = "5b7c9d1e-2f30-4a41-9b52-c63d74e85f96"
internal const val NOTIFICATION_ID_2 = "6c8d0e2f-3041-4b52-ac63-d74e85f96a07"
internal const val TRANSITION_ID = "7d9e1f30-4152-4c63-bd74-e85f96a07b18"
internal const val REQUEST_ID = "9e0f2a41-5263-4d74-ce85-f96a07b18c29"

/** 유형별 계약 예시 항목. 제안은 `detectionId`, 확인은 `tripDayId`·`itemId`·`transitionId`만 있다. */
internal fun suggestionItem(createdAt: String = "2026-09-10T14:20:00+09:00", read: Boolean = false) = NotificationItemDto(
    notificationId = NOTIFICATION_ID,
    type = NotificationType.PLACE_CHANGE_SUGGESTION,
    tripId = TRIP_ID,
    detectionId = DETECTION_ID,
    title = "다음 장소 변경을 추천해요",
    body = "인사동거리가 매우 혼잡해요. 대체 장소를 확인해보세요.",
    read = read,
    createdAt = createdAt,
)

internal fun arrivalCheckItem(createdAt: String = "2026-09-10T14:05:00+09:00", read: Boolean = true) = NotificationItemDto(
    notificationId = NOTIFICATION_ID_2,
    type = NotificationType.ARRIVAL_CHECK,
    tripId = TRIP_ID,
    tripDayId = "day-1",
    itemId = "item-1",
    transitionId = TRANSITION_ID,
    title = "도착하셨나요?",
    body = "북촌한옥마을 근처에서 6분 머물고 있어요.",
    read = read,
    createdAt = createdAt,
)

internal fun notificationListJson(nextCursor: String? = null, hasNext: Boolean = false) = """
    {"success": true,
     "data": {"items": [
       {"notificationId": "$NOTIFICATION_ID", "type": "PLACE_CHANGE_SUGGESTION", "tripId": "$TRIP_ID",
        "detectionId": "$DETECTION_ID", "title": "다음 장소 변경을 추천해요",
        "body": "인사동거리가 매우 혼잡해요.", "read": false, "createdAt": "2026-09-10T14:20:00+09:00"},
       {"notificationId": "$NOTIFICATION_ID_2", "type": "ARRIVAL_CHECK", "tripId": "$TRIP_ID",
        "tripDayId": "day-1", "itemId": "item-1", "transitionId": "$TRANSITION_ID",
        "title": "도착하셨나요?", "body": "북촌한옥마을 근처에서 6분 머물고 있어요.",
        "read": true, "createdAt": "2026-09-10T14:05:00+09:00", "futureField": 1}
     ]},
     "meta": {"requestId": "$REQUEST_ID",
              "pagination": {"nextCursor": ${nextCursor?.let { "\"$it\"" } ?: "null"}, "hasNext": $hasNext}}}
""".trimIndent()

internal fun envelope(data: String) = """
    {"success": true, "data": $data, "meta": {"requestId": "$REQUEST_ID"}}
""".trimIndent()

internal fun errorJson(code: String, retryable: Boolean = false) = """
    {"success": false,
     "error": {"code": "$code", "message": "진단용 설명", "retryable": $retryable},
     "meta": {"requestId": "$REQUEST_ID"}}
""".trimIndent()
