package com.gilpick.trip

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.gilpick.GilpickApp
import com.gilpick.TAG_NAV_ACTIVE
import com.gilpick.TAG_NAV_BAR
import com.gilpick.TAG_NAV_SETTINGS
import com.gilpick.auth.AuthUiState
import com.gilpick.settings.FakeSettingsService
import com.gilpick.settings.signedInSettingsRepository
import com.gilpick.ui.theme.GilpickTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * #502: 하단 탭 3개 선택 상태와 `여행 중` 빈 상태 screenshot 기록.
 *
 * 검증이 아니라 기록이다. 저장 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/`.
 */
class ActiveTripTabScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 진행_중_여행_없음() {
        composeRule.setContent { GilpickTheme { ActiveTripTabScreen(ActiveTripTabPhase.Empty, onRetry = {}, onOpenTrips = {}) } }
        composeRule.waitForIdle()
        save("active_tab_empty", composeRule.onRoot().captureToImage().asAndroidBitmap())
    }

    @Test
    fun 하단_탭_선택_상태() {
        val repository = runBlocking { signedInSettingsRepository(context, FakeSettingsService()) }
        composeRule.setContent {
            GilpickApp(
                state = AuthUiState.Authenticated("user-1", nickname = "길픽", profileImageUrl = null),
                onKakaoLogin = {},
                onRetry = {},
                settingsRepository = { repository },
            )
        }
        composeRule.waitForIdle()
        save("nav_bar_trips", composeRule.onNodeWithTag(TAG_NAV_BAR).captureToImage().asAndroidBitmap())

        composeRule.onNodeWithTag(TAG_NAV_ACTIVE).performClick()
        composeRule.waitForIdle()
        save("nav_bar_active", composeRule.onNodeWithTag(TAG_NAV_BAR).captureToImage().asAndroidBitmap())

        composeRule.onNodeWithTag(TAG_NAV_SETTINGS).performClick()
        composeRule.waitForIdle()
        save("nav_bar_settings", composeRule.onNodeWithTag(TAG_NAV_BAR).captureToImage().asAndroidBitmap())
    }

    private fun save(name: String, bitmap: Bitmap) {
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
