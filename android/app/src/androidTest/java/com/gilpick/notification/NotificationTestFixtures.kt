package com.gilpick.notification

import com.gilpick.alternative.CongestionLevel
import com.gilpick.alternative.CongestionSensitivity
import com.gilpick.alternative.CongestionVerdictDto
import com.gilpick.alternative.DetectionListItemDto
import com.gilpick.alternative.DetectionStatus
import com.gilpick.alternative.DetectionType
import com.gilpick.alternative.OperatingHoursVerdictDto
import com.gilpick.alternative.PrecipitationType
import com.gilpick.alternative.UnavailableReason
import com.gilpick.alternative.VariableVerdictsDto
import com.gilpick.alternative.WeatherVerdictDto
import java.time.Instant

/** F011 알림 목록 계측 test 고정값. 시각은 [NOW] 기준으로 `오늘`·`어제`가 갈린다. */

/** 2026-09-10 15:00 KST. */
internal val NOW: Instant = Instant.parse("2026-09-10T06:00:00Z")

internal const val NOTIF_TRIP_ID = "3f1d2c4b-5a6e-4f70-8a91-b2c3d4e5f607"
internal const val NOTIF_DETECTION_ID = "8a2918f7-e6d5-4c4b-8a29-18f7e6d5c4b3"
internal const val NOTIF_SUGGESTION_ID = "5b7c9d1e-2f30-4a41-9b52-c63d74e85f96"
internal const val NOTIF_ARRIVAL_ID = "6c8d0e2f-3041-4b52-ac63-d74e85f96a07"
internal const val NOTIF_AUTO_ID = "7d9e1f30-4152-4c63-bd74-e85f96a07b18"
internal const val NOTIF_REQUEST_ID = "9e0f2a41-5263-4d74-ce85-f96a07b18c29"

/** 오늘 2건(안 읽음 제안 3분 전, 읽은 도착 확인 12분 전) + 어제 1건(읽은 자동 처리). Figma 예시 문구다. */
internal fun mixedItems(tripId: String = NOTIF_TRIP_ID): List<NotifItemUi> = listOf(
    NotifItemUi(
        id = NOTIF_SUGGESTION_ID,
        type = NotificationType.PLACE_CHANGE_SUGGESTION,
        title = "다음 장소 변경을 추천해요",
        body = "인사동거리가 매우 혼잡해요. 대체 장소를 확인해보세요.",
        createdAt = "2026-09-10T14:57:00+09:00",
        unread = true,
        target = NotificationTarget.Alternative(NOTIF_DETECTION_ID, tripId),
    ),
    NotifItemUi(
        id = NOTIF_ARRIVAL_ID,
        type = NotificationType.ARRIVAL_CHECK,
        title = "도착하셨나요?",
        body = "북촌한옥마을 근처에서 6분 머물고 있어요.",
        createdAt = "2026-09-10T14:48:00+09:00",
        unread = false,
        target = NotificationTarget.Progress(tripId),
    ),
    NotifItemUi(
        id = NOTIF_AUTO_ID,
        type = NotificationType.ARRIVAL_AUTO_CONFIRMED,
        title = "도착으로 자동 처리했어요",
        body = "경복궁 도착 · 5분 안에 되돌릴 수 있어요.",
        createdAt = "2026-09-09T18:04:00+09:00",
        unread = false,
        target = NotificationTarget.Progress(tripId),
    ),
)

/** [mixedItems]를 `오늘`/`어제` 두 구간으로 나눈 content. */
internal fun mixedContent(): NotificationUiState.Content = NotificationUiState.Content(
    groups = listOf(
        NotifGroup(DateBucket.TODAY, mixedItems().take(2)),
        NotifGroup(DateBucket.YESTERDAY, mixedItems().drop(2)),
    ),
)

/** [mixedItems]와 같은 내용의 NOTI-001 응답 JSON. 계측 navigation test가 MockWebServer로 준다. */
internal fun notificationListJson(tripId: String = NOTIF_TRIP_ID): String = """
    {"success": true,
     "data": {"items": [
       {"notificationId": "$NOTIF_SUGGESTION_ID", "type": "PLACE_CHANGE_SUGGESTION", "tripId": "$tripId",
        "detectionId": "$NOTIF_DETECTION_ID", "title": "다음 장소 변경을 추천해요",
        "body": "인사동거리가 매우 혼잡해요. 대체 장소를 확인해보세요.", "read": false, "createdAt": "2026-09-10T14:57:00+09:00"},
       {"notificationId": "$NOTIF_ARRIVAL_ID", "type": "ARRIVAL_CHECK", "tripId": "$tripId",
        "tripDayId": "day-1", "itemId": "item-1", "transitionId": "$NOTIF_AUTO_ID",
        "title": "도착하셨나요?", "body": "북촌한옥마을 근처에서 6분 머물고 있어요.",
        "read": false, "createdAt": "2026-09-10T14:48:00+09:00"}
     ]},
     "meta": {"requestId": "$NOTIF_REQUEST_ID", "pagination": {"nextCursor": null, "hasNext": false}}}
""".trimIndent()

internal fun markReadJson(notificationId: String) = """
    {"success": true, "data": {"notificationId": "$notificationId", "read": true}, "meta": {"requestId": "$NOTIF_REQUEST_ID"}}
""".trimIndent()

internal fun notificationErrorJson(code: String) = """
    {"success": false, "error": {"code": "$code", "message": "진단용 설명", "retryable": false},
     "meta": {"requestId": "$NOTIF_REQUEST_ID"}}
""".trimIndent()

/* ---- 감지 목록(US6) ---- */

internal const val MONITOR_DETECTION_1 = "aaaaaaaa-1111-4111-8111-111111111111"
internal const val MONITOR_DETECTION_2 = "aaaaaaaa-2222-4222-8222-222222222222"
internal const val MONITOR_DETECTION_3 = "aaaaaaaa-3333-4333-8333-333333333333"

/**
 * `ACTIVE` 감지 3건(Figma 예시). 경복궁은 혼잡·강수 위험, 창덕궁 후원은 마감 임박, 남산서울타워는
 * 운영시간 제외(`HOURS_UNKNOWN`)다. 시간순 = 창덕궁 후원·경복궁·남산서울타워, 위험순 = 경복궁·남산서울타워·창덕궁 후원.
 */
internal fun monitorDetections(): List<DetectionUi> = listOf(
    DetectionUi(
        item = DetectionListItemDto(
            detectionId = MONITOR_DETECTION_1,
            itemId = "bbbbbbbb-1111-4111-8111-111111111111",
            placeName = "경복궁",
            primaryType = DetectionType.CONGESTION,
            status = DetectionStatus.ACTIVE,
            totalRiskScore = 78,
            eta = "2026-09-10T14:00:00+09:00",
            reason = "오늘 오후 방문이 어려울 수 있어요",
            createdAt = "2026-09-10T14:52:00+09:00",
            read = false,
        ),
        variables = VariableVerdictsDto(
            congestion = CongestionVerdictDto(available = true, level = CongestionLevel.CROWDED, sensitivity = CongestionSensitivity.MEDIUM, crowded = true),
            weather = WeatherVerdictDto(available = true, precipitationProbability = 80, precipitationMmPerHour = 3.5, precipitationType = PrecipitationType.RAIN, atRisk = true),
            operatingHours = OperatingHoursVerdictDto(available = true, closesAt = "2026-09-10T18:00:00+09:00", closingSoon = false),
        ),
    ),
    DetectionUi(
        item = DetectionListItemDto(
            detectionId = MONITOR_DETECTION_2,
            itemId = "bbbbbbbb-2222-4222-8222-222222222222",
            placeName = "창덕궁 후원",
            primaryType = DetectionType.OPERATING_HOURS,
            status = DetectionStatus.ACTIVE,
            totalRiskScore = 40,
            eta = "2026-09-10T11:30:00+09:00",
            reason = "오늘 오전 방문이 어려울 수 있어요",
            createdAt = "2026-09-10T14:39:00+09:00",
            read = false,
        ),
        variables = VariableVerdictsDto(
            congestion = CongestionVerdictDto(available = true, level = CongestionLevel.SLIGHTLY_CROWDED, sensitivity = CongestionSensitivity.MEDIUM, crowded = false),
            weather = WeatherVerdictDto(available = true, precipitationProbability = 10, precipitationMmPerHour = 0.0, precipitationType = PrecipitationType.NONE, atRisk = false),
            operatingHours = OperatingHoursVerdictDto(available = true, closesAt = "2026-09-10T11:45:00+09:00", closingSoon = true),
        ),
    ),
    DetectionUi(
        item = DetectionListItemDto(
            detectionId = MONITOR_DETECTION_3,
            itemId = "bbbbbbbb-3333-4333-8333-333333333333",
            placeName = "남산서울타워",
            primaryType = DetectionType.WEATHER,
            status = DetectionStatus.ACTIVE,
            totalRiskScore = 70,
            eta = "2026-09-10T18:30:00+09:00",
            reason = "오늘 저녁 방문이 어려울 수 있어요",
            createdAt = "2026-09-10T14:26:00+09:00",
            read = true,
        ),
        variables = VariableVerdictsDto(
            congestion = CongestionVerdictDto(available = true, level = CongestionLevel.NORMAL, sensitivity = CongestionSensitivity.MEDIUM, crowded = false),
            weather = WeatherVerdictDto(available = true, precipitationProbability = 70, precipitationMmPerHour = 1.2, precipitationType = PrecipitationType.SHOWER, atRisk = true),
            operatingHours = OperatingHoursVerdictDto(available = false, unavailableReason = UnavailableReason.HOURS_UNKNOWN),
        ),
    ),
)
