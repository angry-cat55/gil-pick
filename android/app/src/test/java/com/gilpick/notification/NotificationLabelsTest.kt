package com.gilpick.notification

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T015: KST 날짜 구간, 유형→목적지, 푸시 extras → 대상 파싱 검증. */
class NotificationLabelsTest {

    // KST 2026-09-10 14:30
    private val now: Instant = Instant.parse("2026-09-10T05:30:00Z")

    @Test
    fun `날짜 구간은 KST 자정 기준으로 오늘·어제·그 이전을 가른다`() {
        // UTC로는 9일 밤이지만 KST로는 10일 00:10이라 오늘이다.
        assertEquals(DateBucket.TODAY, dateBucket("2026-09-09T15:10:00Z", now))
        assertEquals(DateBucket.YESTERDAY, dateBucket("2026-09-09T23:59:00+09:00", now))
        assertEquals(DateBucket.EARLIER, dateBucket("2026-09-08T23:59:00+09:00", now))
    }

    @Test
    fun `목록은 구간 순서로 묶이고 빈 구간은 만들지 않는다`() {
        val groups = listOf(
            suggestionItem(createdAt = "2026-09-10T14:20:00+09:00"),
            arrivalCheckItem(createdAt = "2026-09-01T10:00:00+09:00"),
        ).toGroups(now)

        assertEquals(listOf(DateBucket.TODAY, DateBucket.EARLIER), groups.map { it.bucket })
        assertEquals(true, groups[0].items.single().unread)
        assertEquals(NotificationTarget.Alternative(DETECTION_ID, TRIP_ID), groups[0].items.single().target)
        assertEquals(NotificationTarget.Progress(TRIP_ID), groups[1].items.single().target)
    }

    @Test
    fun `detectionId 없는 제안 알림은 진행 화면으로 보낸다`() {
        assertEquals(NotificationTarget.Progress(TRIP_ID), suggestionItem().copy(detectionId = null).target)
    }

    @Test
    fun `푸시 extras는 유형별 route로 옮기고 없는 값은 목록으로 보낸다`() {
        val extras = mapOf(
            PendingNotificationTarget.EXTRA_TYPE to "PLACE_CHANGE_SUGGESTION",
            PendingNotificationTarget.EXTRA_TRIP_ID to TRIP_ID,
            PendingNotificationTarget.EXTRA_DETECTION_ID to DETECTION_ID,
        )
        val suggestion = PendingNotificationTarget.fromExtras(extras::get)!!
        assertEquals(NotificationTarget.Alternative(DETECTION_ID, TRIP_ID), suggestion.route)

        val arrival = PendingNotificationTarget.fromExtras(
            mapOf(PendingNotificationTarget.EXTRA_TYPE to "ARRIVAL_CHECK", PendingNotificationTarget.EXTRA_TRIP_ID to TRIP_ID)::get,
        )!!
        assertEquals(NotificationTarget.Progress(TRIP_ID), arrival.route)

        // 계약 밖 유형·식별자 누락은 알림 목록으로.
        assertNull(PendingNotificationTarget.fromExtras(mapOf(PendingNotificationTarget.EXTRA_TYPE to "UNKNOWN")::get)!!.route)
        assertNull(PendingNotificationTarget.fromExtras(mapOf(PendingNotificationTarget.EXTRA_TYPE to "PLACE_CHANGE_SUGGESTION")::get)!!.route)
        // notif_type 자체가 없으면 알림 탭이 아니다.
        assertNull(PendingNotificationTarget.fromExtras { null })
    }
}
