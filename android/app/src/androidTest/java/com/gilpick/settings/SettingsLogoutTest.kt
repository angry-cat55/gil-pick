package com.gilpick.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.GilpickApp
import com.gilpick.auth.AuthUiState
import com.gilpick.ui.theme.GilpickTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T021: 설정 화면 로그아웃과 최상위 인증 전환 검증(spec US3, FR-010, UI-004·UI-005).
 *
 * 로그아웃 규칙 자체(즉시 local 종료, 서버 폐기 재시도, 다른 기기 session 유지)는 F001이
 * `AuthLogoutIntegrationTest`로 이미 검증한다. 이 test는 **설정 화면이 그 흐름에 올바르게
 * 연결되는지**만 본다. 설정 화면은 [SettingsDestination]의 `onLogout` 하나로만 로그아웃을
 * 요청하고, token 삭제·서버 호출은 그 뒤 F001 몫이다.
 */
@RunWith(AndroidJUnit4::class)
class SettingsLogoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 로그아웃을_연속으로_골라도_한_번만_실행된다() {
        // 연속 요청이 다른 기기 session이나 설정을 건드리지 않는 첫 관문이다(Edge Cases).
        var requests = 0
        setDestination(onLogout = { requests++ })

        composeRule.onNodeWithTag(TAG_LOGOUT).performClick()
        composeRule.onNodeWithTag(TAG_LOGOUT).performClick()
        composeRule.onNodeWithTag(TAG_LOGOUT).performClick()

        composeRule.runOnIdle { assertEquals(1, requests) }
    }

    @Test
    fun 설정_조회가_실패해도_로그아웃은_쓸_수_있다() {
        // offline이면 설정 조회는 실패하지만 로그아웃은 독립 행동이라 막히지 않는다(UI-004, FR-010).
        var requests = 0
        setDestination(
            preference = PreferencePhase.Error(lastConfirmedValue = null, error = SettingsError.Network),
            onLogout = { requests++ },
        )

        composeRule.onNodeWithTag(TAG_LOGOUT).assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag(TAG_LOGOUT).performClick()

        composeRule.runOnIdle { assertEquals(1, requests) }
    }

    @Test
    fun 설정을_불러오는_중에도_로그아웃은_쓸_수_있다() {
        var requests = 0
        setDestination(preference = PreferencePhase.Loading, onLogout = { requests++ })

        composeRule.onNodeWithTag(TAG_LOGOUT).performClick()

        composeRule.runOnIdle { assertEquals(1, requests) }
    }

    @Test
    fun 로그아웃_버튼은_48dp_이상이다() {
        setDestination()

        composeRule.onNodeWithTag(TAG_LOGOUT).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun 로그아웃하면_최상위가_즉시_로그인_화면으로_바뀐다() {
        // 여행 목록 → 설정 → 로그아웃. 인증 상태가 SignedOut이 되는 순간 NavHost 전체가 내려가고
        // 로그인 화면이 올라와야 한다(US3 시나리오 1). 서버를 두지 않으므로 설정 조회는 실패하는
        // offline 경로다(시나리오 3). 여행 목록의 임시 로그아웃은 사라졌어야 한다(T022).
        var state by mutableStateOf<AuthUiState>(AuthUiState.Authenticated("user-1", nickname = null, profileImageUrl = null))
        composeRule.setContent {
            GilpickApp(
                state = state,
                onKakaoLogin = {},
                onRetry = {},
                onLogout = { state = AuthUiState.SignedOut },
            )
        }

        composeRule.onNodeWithText("내 여행").assertIsDisplayed()
        composeRule.onNodeWithText("로그아웃").assertDoesNotExist()
        composeRule.onNodeWithText("설정").performClick()
        composeRule.onNodeWithTag(TAG_LOGOUT).performClick()

        composeRule.onNodeWithText("카카오로 시작하기").assertIsDisplayed()
        composeRule.onNodeWithText("내 여행").assertDoesNotExist()
        composeRule.onNodeWithTag(TAG_LOGOUT).assertDoesNotExist()
    }

    private fun setDestination(
        preference: PreferencePhase = PreferencePhase.Content(value = true),
        onLogout: () -> Unit = {},
    ) {
        composeRule.setContent {
            GilpickTheme {
                SettingsDestination(
                    state = SettingsUiState(preference = preference),
                    onToggle = {},
                    onRetryLoad = {},
                    onRetrySave = {},
                    onReauthenticate = {},
                    onOpenPolicy = {},
                    onRetryPolicy = {},
                    onDismissPolicyError = {},
                    onLogout = onLogout,
                )
            }
        }
    }
}
