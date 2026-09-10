package com.gilpick.notification

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.gilpick.ui.theme.GilpickTheme
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * T034: 알림 목록 `content`(읽음·안 읽음·2그룹 혼재)·`empty`·`error` × (기본, 360dp fontScale 2.0) screenshot 증빙(UI-007, SC-010).
 *
 * 검증이 아니라 기록이다. F009 `AlternativeScreenshotTest`와 같은 방식으로 기기 저장소에 PNG로 남기고
 * `adb pull`로 꺼내 사람이 Figma `NotificationsScreen`과 대조한다.
 *
 * 저장 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/`.
 */
class NotificationsScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 알림_목록() = capture("notifications_content") { Screen(mixedContent()) }

    @Test
    fun 알림_목록_360dp_최대_글자배율() = capture("notifications_content_360dp_fontscale2") { Narrow { Screen(mixedContent()) } }

    @Test
    fun 알림_없음() = capture("notifications_empty") { Screen(NotificationUiState.Empty) }

    @Test
    fun 알림_없음_360dp_최대_글자배율() = capture("notifications_empty_360dp_fontscale2") { Narrow { Screen(NotificationUiState.Empty) } }

    @Test
    fun 조회_실패() = capture("notifications_error") { Screen(networkError()) }

    @Test
    fun 조회_실패_360dp_최대_글자배율() = capture("notifications_error_360dp_fontscale2") { Narrow { Screen(networkError()) } }

    private fun networkError() = NotificationUiState.Error(NotificationError.Network, retryable = true)

    @Composable
    private fun Screen(state: NotificationUiState) {
        NotificationListScreen(
            state = state,
            onBack = {},
            onRetry = {},
            onOpen = {},
            onMarkAllRead = {},
            onReauthenticate = {},
            now = NOW,
        )
    }

    /** 360dp 너비 + 최대 글자 배율(2.0). */
    @Composable
    private fun Narrow(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        Box(modifier = Modifier.width(360.dp)) {
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) { content() }
        }
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent { GilpickTheme { content() } }
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
