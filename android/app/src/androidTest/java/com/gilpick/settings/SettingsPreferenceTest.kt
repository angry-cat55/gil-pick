package com.gilpick.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.ui.theme.GilpickTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T016: 알림 설정 영역의 네 상태와 변경 성공·실패·연속 선택 검증
 * (quickstart, spec UI-003·UI-004·UI-005).
 *
 * 상태는 ViewModel 없이 [PreferencePhase]를 직접 넣는다. 실제 요청과 상태 전이는
 * `SettingsViewModelTest`가 본다. `empty` 상태 test가 없는 이유는 이 화면에 `empty`가 없기
 * 때문이다 — 인증된 사용자에게는 항상 저장된 값이 있다(UI-003).
 */
@RunWith(AndroidJUnit4::class)
class SettingsPreferenceTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- UI-003 상태 ---

    @Test
    fun loading은_1초_전에는_표시하지_않고_이후_대기_표시를_띄운다() {
        composeRule.mainClock.autoAdvance = false
        setSection(PreferencePhase.Loading)

        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithContentDescription("설정을 불러오는 중").assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(600)
        composeRule.onNodeWithContentDescription("설정을 불러오는 중").assertIsDisplayed()
    }

    @Test
    fun content는_제목과_설명과_저장된_값을_보여준다() {
        setSection(PreferencePhase.Content(value = true))

        composeRule.onNodeWithText("장소 변경 제안 알림").assertIsDisplayed()
        // FR-005. 이 토글이 도착·출발 확인 알림까지 끄는 것으로 오해하면 안 된다.
        composeRule.onNodeWithText("일정에 변수가 생기면 대체 장소를 제안합니다. 도착·출발 확인 알림은 계속 받습니다.").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_TOGGLE).assertIsOn().assertIsEnabled()
    }

    @Test
    fun 꺼진_설정은_토글이_꺼진_상태로_보인다() {
        setSection(PreferencePhase.Content(value = false))

        composeRule.onNodeWithTag(TAG_TOGGLE).assertIsOff()
    }

    @Test
    fun 토글을_누르면_원하는_절대값이_전달된다() {
        val selected = mutableListOf<Boolean>()
        setSection(PreferencePhase.Content(value = true), onToggle = { selected += it })

        composeRule.onNodeWithTag(TAG_TOGGLE).performClick()

        composeRule.runOnIdle { assertEquals(listOf(false), selected) }
    }

    // --- UI-004 저장 중 ---

    @Test
    fun 저장_중에는_토글이_잠기고_저장_중임을_문구로_알린다() {
        // UI-005. 색만으로 상태를 전달하지 않는다.
        val selected = mutableListOf<Boolean>()
        setSection(PreferencePhase.Content(value = false, isSaving = true), onToggle = { selected += it })

        composeRule.onNodeWithTag(TAG_SAVING_BADGE).assertIsDisplayed()
        composeRule.onNodeWithText("저장 중").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_TOGGLE).assertIsNotEnabled().performClick()
        composeRule.runOnIdle { assertEquals(emptyList<Boolean>(), selected) }
    }

    @Test
    fun 저장_중에도_고른_값이_토글에_보인다() {
        // 토글이 반응하지 않으면 눌리지 않은 것처럼 보인다.
        setSection(PreferencePhase.Content(value = true, isSaving = true))

        composeRule.onNodeWithTag(TAG_TOGGLE).assertIsOn()
    }

    // --- UI-003 실패와 재시도 ---

    @Test
    fun 조회_실패는_불러오지_못했다는_안내와_다시_조회_행동을_준다() {
        var loads = 0
        var saves = 0
        setSection(
            PreferencePhase.Error(lastConfirmedValue = null, error = SettingsError.Network),
            onRetryLoad = { loads++ },
            onRetrySave = { saves++ },
        )

        composeRule.onNodeWithTag(TAG_ERROR_BAR).assertIsDisplayed()
        composeRule.onNodeWithText("설정을 불러오지 못했어요").assertIsDisplayed()
        composeRule.onNodeWithText("연결을 확인한 뒤 다시 시도해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_RETRY).performClick()
        composeRule.runOnIdle {
            assertEquals(1, loads)
            assertEquals(0, saves)
        }
    }

    @Test
    fun 저장_실패는_저장하지_못했다는_안내와_다시_저장_행동을_준다() {
        // 아는 값이 있으면 저장 실패다. 다음 행동이 조회 실패와 다르다.
        var loads = 0
        var saves = 0
        setSection(
            PreferencePhase.Error(lastConfirmedValue = true, error = SettingsError.Unexpected),
            onRetryLoad = { loads++ },
            onRetrySave = { saves++ },
        )

        composeRule.onNodeWithText("설정을 저장하지 못했어요").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_RETRY).performClick()
        composeRule.runOnIdle {
            assertEquals(0, loads)
            assertEquals(1, saves)
        }
    }

    @Test
    fun 저장에_실패하면_마지막으로_저장에_성공한_값을_보인다() {
        // FR-006. 저장되지 않은 값을 성공처럼 남기지 않는다.
        setSection(PreferencePhase.Error(lastConfirmedValue = true, error = SettingsError.Unexpected))

        composeRule.onNodeWithTag(TAG_TOGGLE).assertIsOn().assertIsNotEnabled()
    }

    @Test
    fun 세션이_만료되면_재인증_행동을_대신_준다() {
        var reauth = 0
        var loads = 0
        setSection(
            PreferencePhase.Error(lastConfirmedValue = null, error = SettingsError.SessionExpired),
            onRetryLoad = { loads++ },
            onReauthenticate = { reauth++ },
        )

        composeRule.onNodeWithText("다시 로그인").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_RETRY).performClick()
        composeRule.runOnIdle {
            assertEquals(1, reauth)
            assertEquals(0, loads)
        }
    }

    @Test
    fun 실패가_아니면_안내를_보이지_않는다() {
        setSection(PreferencePhase.Content(value = true))

        composeRule.onNodeWithTag(TAG_ERROR_BAR).assertDoesNotExist()
    }

    // --- UI-005 접근성 ---

    @Test
    fun 토글과_다시_시도는_48dp_이상이다() {
        setSection(PreferencePhase.Content(value = true))
        composeRule.onNodeWithTag(TAG_TOGGLE).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun 실패_안내의_다시_시도도_48dp_이상이다() {
        // Figma의 `다시 시도`는 32dp지만 터치 영역은 48dp를 지킨다(AGENTS.md 6절).
        setSection(PreferencePhase.Error(lastConfirmedValue = true, error = SettingsError.Network))

        composeRule.onNodeWithTag(TAG_RETRY).assertHeightIsAtLeast(48.dp)
    }

    // --- 연속 선택 ---

    @Test
    fun 빠르게_연속으로_고르면_고른_값이_순서대로_전달된다() {
        // ViewModel이 단일 in-flight와 마지막 희망값을 처리한다. 화면은 선택을 빠뜨리지 않으면 된다.
        val selected = mutableListOf<Boolean>()
        val phase = mutableStateOf<PreferencePhase>(PreferencePhase.Content(value = true))
        composeRule.setContent {
            GilpickTheme {
                NotificationPreferenceSection(
                    phase = phase.value,
                    onToggle = { selected += it },
                    onRetryLoad = {},
                    onRetrySave = {},
                    onReauthenticate = {},
                )
            }
        }

        composeRule.onNodeWithTag(TAG_TOGGLE).performClick()
        composeRule.runOnIdle { phase.value = PreferencePhase.Content(value = false) }
        composeRule.onNodeWithTag(TAG_TOGGLE).performClick()
        composeRule.runOnIdle { phase.value = PreferencePhase.Content(value = true) }
        composeRule.onNodeWithTag(TAG_TOGGLE).performClick()

        composeRule.runOnIdle { assertEquals(listOf(false, true, false), selected) }
    }

    private fun setSection(
        phase: PreferencePhase,
        onToggle: (Boolean) -> Unit = {},
        onRetryLoad: () -> Unit = {},
        onRetrySave: () -> Unit = {},
        onReauthenticate: () -> Unit = {},
    ) {
        composeRule.setContent {
            GilpickTheme {
                NotificationPreferenceSection(
                    phase = phase,
                    onToggle = onToggle,
                    onRetryLoad = onRetryLoad,
                    onRetrySave = onRetrySave,
                    onReauthenticate = onReauthenticate,
                )
            }
        }
    }
}
