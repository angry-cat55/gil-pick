package com.gilpick.notification

import android.app.NotificationManager
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T025·T030: 실제 채널 알림 표시와 탭 extras 왕복, 포그라운드 판정.
 *
 * FCM 종단 수신은 실 Firebase가 필요해 통합 Issue에서 본다. 여기서는 `onMessageReceived`가 부르는
 * [GilpickMessagingService.show]·[GilpickMessagingService.isAppInForeground]를 기기에서 확인한다.
 */
@RunWith(AndroidJUnit4::class)
class GilpickMessagingServiceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun grantNotificationPermission() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun 백그라운드_수신은_채널_알림을_띄우고_탭_extras가_route로_돌아온다() {
        manager.cancelAll()
        MainActivityChannel.ensure(context)
        val push = PushNotification.fromData(
            mapOf(
                "type" to "PLACE_CHANGE_SUGGESTION",
                "notificationId" to "n-1",
                "tripId" to "trip-1",
                "detectionId" to "det-1",
                "title" to "다음 장소 변경을 추천해요",
                "body" to "도착 시각에 영업이 어렵거나 곧 문을 닫아요",
            ),
        )!!

        assertFalse("화면 없는 instrumentation 프로세스는 백그라운드", GilpickMessagingService.isAppInForeground())
        GilpickMessagingService.show(context, push)

        // notify는 비동기라 잠시 기다린다.
        val posted = generateSequence { manager.activeNotifications.firstOrNull { it.id == push.id } ?: Thread.sleep(200).let { null } }
            .take(25).filterNotNull().first().notification
        assertEquals(GilpickMessagingService.CHANNEL_ID, posted.channelId)
        assertEquals("다음 장소 변경을 추천해요", posted.extras.getCharSequence(android.app.Notification.EXTRA_TITLE))

        // PendingIntent 안 Intent는 꺼낼 수 없어 같은 extras로 만든 Intent로 MainActivity 해석을 확인한다.
        val tap = Intent().apply { push.extras.forEach { (k, v) -> putExtra(k, v) } }
        assertEquals(NotificationTarget.Alternative("det-1", "trip-1"), PendingNotificationTarget.fromIntent(tap)!!.route)
        manager.cancelAll()
    }

    @Test
    fun 화면이_보이는_동안은_포그라운드로_판정한다() {
        ActivityScenario.launch(ComponentActivity::class.java).use {
            instrumentation.waitForIdleSync()
            assertTrue(GilpickMessagingService.isAppInForeground())
        }
    }
}

/** `MainActivity.createNotificationChannel`과 같은 채널. 알림 표시에 채널이 먼저 있어야 한다. */
private object MainActivityChannel {
    fun ensure(context: android.content.Context) {
        val channel = android.app.NotificationChannel(
            GilpickMessagingService.CHANNEL_ID,
            "test",
            NotificationManager.IMPORTANCE_HIGH,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
