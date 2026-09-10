package com.gilpick.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T025·T030: FCM data payload → 시스템 알림·탭 extras → 유형별 route.
 *
 * 서버 `dispatch.py`가 보내는 키 그대로 넣고, `MainActivity`가 extras에서 읽는
 * [PendingNotificationTarget.fromExtras]까지 한 번에 지난다. 포그라운드 판정·실제 알림 표시는
 * Android 프레임워크라 여기서 다루지 않는다.
 */
class NotificationNavigationTest {

    private fun data(type: String, vararg ids: Pair<String, String>) = mapOf(
        "type" to type,
        "notificationId" to "n-$type",
        "tripId" to TRIP_ID,
        "title" to "제목",
        "body" to "본문",
        *ids,
    )

    private fun routeOf(data: Map<String, String>): NotificationTarget? {
        val push = PushNotification.fromData(data)!!
        return PendingNotificationTarget.fromExtras(push.extras::get)!!.route
    }

    @Test
    fun `장소 변경 제안은 대체 장소 화면으로 간다`() {
        val push = PushNotification.fromData(data("PLACE_CHANGE_SUGGESTION", "detectionId" to DETECTION_ID))!!
        assertEquals(NotificationType.PLACE_CHANGE_SUGGESTION, push.type)
        assertEquals("제목", push.title)
        assertEquals("본문", push.body)
        assertEquals(
            NotificationTarget.Alternative(DETECTION_ID, TRIP_ID),
            PendingNotificationTarget.fromExtras(push.extras::get)!!.route,
        )
    }

    @Test
    fun `도착·출발·자동 처리는 진행 화면으로 간다`() {
        listOf("ARRIVAL_CHECK", "DEPARTURE_CHECK", "ARRIVAL_AUTO_CONFIRMED", "DEPARTURE_AUTO_CONFIRMED").forEach { type ->
            assertEquals(type, NotificationTarget.Progress(TRIP_ID), routeOf(data(type, "transitionId" to TRANSITION_ID)))
        }
    }

    @Test
    fun `계약 밖 유형은 알림은 띄우되 알림 목록으로 보내고, 문구 없는 payload는 버린다`() {
        val unknown = PushNotification.fromData(data("SOMETHING_NEW"))!!
        assertNull(unknown.type)
        assertNull(PendingNotificationTarget.fromExtras(unknown.extras::get)!!.route)

        assertNull(PushNotification.fromData(data("ARRIVAL_CHECK") - "title"))
        assertNull(PushNotification.fromData(data("ARRIVAL_CHECK") - "type"))
    }

    @Test
    fun `알림 식별자가 다르면 시스템 알림 id도 달라 서로 덮어쓰지 않는다`() {
        val first = PushNotification.fromData(data("ARRIVAL_CHECK") + ("notificationId" to "n-1"))!!
        val second = PushNotification.fromData(data("ARRIVAL_CHECK") + ("notificationId" to "n-2"))!!
        assertNotEquals(first.id, second.id)
    }
}
