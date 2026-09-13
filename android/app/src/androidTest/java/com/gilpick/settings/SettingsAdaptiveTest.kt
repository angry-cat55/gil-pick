package com.gilpick.settings

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gilpick.GilpickApp
import com.gilpick.TAG_NAV_BAR
import com.gilpick.TAG_NAV_SETTINGS
import com.gilpick.auth.AuthUiState
import com.gilpick.ui.theme.GilpickTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T027: 설정 화면 adaptive·접근성 검증과 Figma 대조용 screenshot(spec UI-005·UI-006·UI-007).
 *
 * 검증(assert)과 기록(capture)을 함께 둔다. 기록은 각 상태를 그려 기기 저장소에 PNG로 남기고
 * `adb pull`로 꺼내 사람이 Figma `SettingsScreen`과 대조한다. phone/tablet 가로 방향은 compose
 * window 안에서 만들 수 없는 폭이라 quickstart 5절의 `wm size` 절차로 따로 찍는다.
 *
 * 저장 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/`.
 */
@RunWith(AndroidJUnit4::class)
class SettingsAdaptiveTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // --- UI-005 터치 대상 ---

    @Test
    fun 모든_터치_대상은_48dp_이상이다() {
        setDestination()

        listOf(TAG_TOGGLE, TAG_PRIVACY_POLICY, TAG_TERMS_OF_SERVICE, TAG_LOGOUT).forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().assertHeightIsAtLeast(48.dp)
        }
    }

    // --- UI-006 360dp·글자 확대 ---

    @Test
    fun 좁은_화면_최대_글자에서도_가로로_넘치지_않고_모든_항목에_닿는다() {
        setDestination(narrow = true, fontScale = 2f)

        listOf(TAG_ACCOUNT_SECTION, TAG_PREFERENCE_SECTION, TAG_POLICY_SECTION, TAG_LOGOUT).forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag).performScrollTo().getBoundsInRoot()
            assertTrue("$tag right=${bounds.right}", bounds.right <= 360.dp)
        }
        // 핵심 문구가 확대에서도 남는다. 잘림 여부는 screenshot으로 대조한다.
        composeRule.onNodeWithTag(TAG_PREFERENCE_SECTION).performScrollTo()
        composeRule.onNodeWithText("장소 변경 제안 알림").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_LOGOUT).performScrollTo()
        composeRule.onNodeWithText("로그아웃").assertIsDisplayed()
    }

    @Test
    fun 낮은_높이에서도_로그아웃까지_스크롤로_닿는다() {
        // phone 가로 방향의 content 높이(약 320dp)를 흉내 낸다.
        setDestination(height = 320.dp)

        composeRule.onNodeWithTag(TAG_LOGOUT).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_ACCOUNT_SECTION).performScrollTo().assertIsDisplayed()
    }

    // --- UI-006 고정 하단 탭 ---

    @Test
    fun 하단_탭이_로그아웃을_가리지_않는다() {
        setApp()
        composeRule.onNodeWithTag(TAG_NAV_SETTINGS).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasTestTag(TAG_TOGGLE) and isEnabled()).fetchSemanticsNodes().size == 1
        }

        val logout = composeRule.onNodeWithTag(TAG_LOGOUT).performScrollTo().getBoundsInRoot()
        val bar = composeRule.onNodeWithTag(TAG_NAV_BAR).getBoundsInRoot()

        assertTrue("logout.bottom=${logout.bottom} bar.top=${bar.top}", logout.bottom <= bar.top)
        save("settings_tab_bar_logout")
    }

    // --- UI-007 Figma 대조 screenshot ---

    @Test
    fun content_켜짐() = capture("settings_content_on") { Screen(state()) }

    @Test
    fun content_꺼짐() = capture("settings_content_off") { Screen(state(PreferencePhase.Content(value = false))) }

    @Test
    fun 저장_중() = capture("settings_saving") { Screen(state(PreferencePhase.Content(value = false, isSaving = true))) }

    @Test
    fun loading_1초_초과() {
        // 대기 표시는 1초 뒤에만 나타난다(UI-003). test 시계를 직접 넘겨 그 뒤 모습을 찍는다.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { GilpickTheme { Screen(state(PreferencePhase.Loading)) } }
        composeRule.mainClock.advanceTimeBy(1_100)
        composeRule.onNodeWithContentDescription("설정을 불러오는 중").assertExists()
        composeRule.mainClock.autoAdvance = true
        save("settings_loading")
    }

    @Test
    fun 조회_error() = capture("settings_error_load") {
        Screen(state(PreferencePhase.Error(lastConfirmedValue = null, error = SettingsError.Network)))
    }

    @Test
    fun 저장_error_마지막_성공값_유지() = capture("settings_error_save") {
        Screen(state(PreferencePhase.Error(lastConfirmedValue = true, error = SettingsError.Unexpected)))
    }

    @Test
    fun 정책_열기_실패() = capture("settings_policy_error", scrollTo = TAG_POLICY_ERROR) {
        Screen(state(policyOpenError = PolicyOpenFailure.LauncherUnavailable))
    }

    @Test
    fun 계정_정보_없음() = capture("settings_account_unknown") { Screen(state(nickname = null)) }

    @Test
    fun content_360dp_최대_글자배율() = capture("settings_content_360dp_fontscale2") { Narrow { Screen(state()) } }

    @Test
    fun content_360dp_최대_글자배율_하단() = capture("settings_content_360dp_fontscale2_bottom", scrollTo = TAG_LOGOUT) {
        Narrow { Screen(state()) }
    }

    @Test
    fun 조회_error_360dp_최대_글자배율() = capture("settings_error_load_360dp_fontscale2") {
        Narrow { Screen(state(PreferencePhase.Error(lastConfirmedValue = null, error = SettingsError.Network))) }
    }

    @Test
    fun 가로_높이_로그아웃() = capture("settings_landscape_height_logout", scrollTo = TAG_LOGOUT) {
        Box(modifier = Modifier.height(320.dp)) { Screen(state()) }
    }

    private fun state(
        preference: PreferencePhase = PreferencePhase.Content(value = true),
        policyOpenError: PolicyOpenFailure? = null,
        nickname: String? = "길픽",
    ) = SettingsUiState(
        nickname = nickname,
        profileImageUrl = null,
        versionName = "1.0.0",
        preference = preference,
        policyOpenError = policyOpenError,
    )

    @Composable
    private fun Screen(state: SettingsUiState) {
        SettingsDestination(
            state = state,
            onToggle = {},
            onRetryLoad = {},
            onRetrySave = {},
            onReauthenticate = {},
            onOpenPolicy = {},
            onRetryPolicy = {},
            onDismissPolicyError = {},
            onLogout = {},
        )
    }

    /** 360dp 너비 + 시스템 글자 확대 최대 배율(2.0). */
    @Composable
    private fun Narrow(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        Box(modifier = Modifier.width(360.dp)) {
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) { content() }
        }
    }

    private fun setDestination(narrow: Boolean = false, fontScale: Float = 1f, height: androidx.compose.ui.unit.Dp? = null) {
        composeRule.setContent {
            GilpickTheme {
                val density = LocalDensity.current
                val modifier = Modifier
                    .then(if (narrow) Modifier.width(360.dp) else Modifier.fillMaxSize())
                    .then(if (height != null) Modifier.height(height) else Modifier)
                Box(modifier = modifier) {
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                        Screen(state())
                    }
                }
            }
        }
    }

    private fun setApp() {
        val repository = runBlocking { signedInSettingsRepository(context, FakeSettingsService()) }
        composeRule.setContent {
            GilpickApp(
                state = AuthUiState.Authenticated("user-1", nickname = "길픽", profileImageUrl = null),
                onKakaoLogin = {},
                onRetry = {},
                settingsRepository = { repository },
            )
        }
    }

    private fun capture(name: String, scrollTo: String? = null, content: @Composable () -> Unit) {
        composeRule.setContent { GilpickTheme { content() } }
        composeRule.waitForIdle()
        scrollTo?.let { composeRule.onNodeWithTag(it).performScrollTo() }
        save(name)
    }

    private fun save(name: String) {
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
