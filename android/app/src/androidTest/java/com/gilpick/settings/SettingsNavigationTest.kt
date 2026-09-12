package com.gilpick.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gilpick.GilpickApp
import com.gilpick.TAG_NAV_BAR
import com.gilpick.TAG_NAV_SETTINGS
import com.gilpick.TAG_NAV_TRIPS
import com.gilpick.auth.AuthUiState
import com.gilpick.trip.TAG_NOTIFICATIONS
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T026: 최상위 탭과 설정 destination 연결 검증(spec UI-002, plan Navigation).
 *
 * 설정 화면 자체는 `SettingsPreferenceTest` 등이 본다. 여기서는 **탭을 다녀와도 마지막으로 저장에
 * 성공한 값이 남는지**와 탭이 보여야 할 곳·숨어야 할 곳만 본다. 여행 목록은 서버가 없어 실패
 * 상태로 뜨지만 탭 왕복에는 영향이 없다.
 */
@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 설정_탭을_다녀와도_마지막_저장값이_남는다() {
        val service = FakeSettingsService(initial = true)
        setApp(service)

        composeRule.onNodeWithTag(TAG_NAV_SETTINGS).performClick()
        awaitToggle()
        composeRule.onNodeWithTag(TAG_TOGGLE).assertIsOn().performClick()
        awaitToggle()
        composeRule.onNodeWithTag(TAG_TOGGLE).assertIsOff()
        composeRule.runOnIdle { assertEquals(listOf(false), service.updateCalls) }

        // 다른 최상위 화면으로 갔다가 돌아온다(UI-002).
        composeRule.onNodeWithTag(TAG_NAV_TRIPS).performClick()
        composeRule.onNodeWithTag(TAG_TOGGLE).assertDoesNotExist()
        composeRule.onNodeWithTag(TAG_NAV_SETTINGS).performClick()
        awaitToggle()

        composeRule.onNodeWithTag(TAG_TOGGLE).assertIsOff()
        // 돌아올 때 화면이 다시 조회해 서버가 마지막으로 성공 처리한 값을 보인다(FR-003).
        composeRule.runOnIdle { assertTrue("getCalls=${service.getCalls}", service.getCalls >= 2) }
    }

    @Test
    fun 탭_선택_상태는_현재_화면을_따른다() {
        setApp(FakeSettingsService())

        composeRule.onNodeWithTag(TAG_NAV_TRIPS).assertIsSelected()
        composeRule.onNodeWithTag(TAG_NAV_SETTINGS).assertIsNotSelected()

        composeRule.onNodeWithTag(TAG_NAV_SETTINGS).performClick()

        composeRule.onNodeWithTag(TAG_NAV_SETTINGS).assertIsSelected()
        composeRule.onNodeWithTag(TAG_NAV_TRIPS).assertIsNotSelected()
    }

    @Test
    fun 설정_탭에서_뒤로_가면_여행_목록_탭으로_돌아간다() {
        setApp(FakeSettingsService())
        composeRule.onNodeWithTag(TAG_NAV_SETTINGS).performClick()
        awaitToggle()

        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        composeRule.onNodeWithTag(TAG_TOGGLE).assertDoesNotExist()
        composeRule.onNodeWithTag(TAG_NAV_TRIPS).assertIsSelected()
    }

    @Test
    fun 하위_화면에서는_하단_탭이_내려간다() {
        // 기존 여행·알림 route의 배치를 바꾸지 않는다(회귀). 알림 목록은 최상위가 아니다.
        setApp(FakeSettingsService())
        composeRule.onNodeWithTag(TAG_NAV_BAR).assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_NOTIFICATIONS).performClick()

        composeRule.onNodeWithTag(TAG_NAV_BAR).assertDoesNotExist()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag(TAG_NAV_BAR).assertIsDisplayed()
    }

    private fun setApp(service: SettingsService) {
        val repository = runBlocking { signedInSettingsRepository(context, service) }
        composeRule.setContent {
            GilpickApp(
                state = AuthUiState.Authenticated("user-1", nickname = "길픽", profileImageUrl = null),
                onKakaoLogin = {},
                onRetry = {},
                settingsRepository = { repository },
            )
        }
    }

    /** 조회·저장은 IO에서 끝나므로 토글이 다시 눌릴 수 있을 때까지 기다린다. */
    private fun awaitToggle() {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasTestTag(TAG_TOGGLE) and isEnabled()).fetchSemanticsNodes().size == 1
        }
    }
}
