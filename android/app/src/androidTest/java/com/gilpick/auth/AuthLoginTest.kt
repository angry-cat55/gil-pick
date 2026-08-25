package com.gilpick.auth

import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.BuildConfig
import com.gilpick.GilpickApp
import com.gilpick.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T017: verified App Link 수신과 로그인 화면 상태 전이 검증.
 *
 * 실제 host/path의 intent를 앱이 claim하는지, 로그인에 성공하면 빈 여행 목록 shell로
 * 이동하는지 확인한다.
 */
@RunWith(AndroidJUnit4::class)
class AuthLoginTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun string(id: Int) = context.getString(id)

    // --- verified App Link 수신 ---

    /**
     * 앱이 인증 완료 host를 App Link domain으로 선언하는지 확인한다.
     *
     * 선언까지가 앱의 몫이고, `VERIFIED`로 바뀌려면 실제 host의
     * `/.well-known/assetlinks.json` 배포가 필요하다(T010). API 31 이상은 검증되지 않은
     * domain을 암시적 intent 해석에서 제외하므로 `queryIntentActivities`로는 선언 여부를
     * 확인할 수 없다. path 단위 claim 범위는 `AuthAppLinkHandler` test와 T045의
     * `pm get-app-links`로 확인한다.
     */
    @Test
    fun 앱이_인증_완료_host를_App_Link_domain으로_선언한다() {
        assumeTrue("domain verification 조회는 API 31 이상이다", Build.VERSION.SDK_INT >= 31)
        val manager = context.getSystemService(DomainVerificationManager::class.java)

        val hostStates = manager.getDomainVerificationUserState(context.packageName)?.hostToStateMap

        assertTrue(
            "선언한 App Link domain: $hostStates",
            hostStates?.containsKey(BuildConfig.APP_LINK_HOST) == true,
        )
        assertEquals(
            "assetlinks.json 배포 전에는 검증되지 않은 상태가 정상이다",
            DomainVerificationUserState.DOMAIN_STATE_NONE,
            hostStates?.get(BuildConfig.APP_LINK_HOST),
        )
    }

    @Test
    fun 수신한_App_Link에서_ticket을_한_번만_꺼낸다() {
        val handler = AuthAppLinkHandler(BuildConfig.APP_LINK_HOST)

        val first = handler.consume(completeLink())
        val second = handler.consume(completeLink())

        assertEquals(TICKET, (first as AppLinkResult.Ticket).loginTicket)
        assertEquals(AppLinkResult.Ignored, second)
    }

    // --- 로그인 화면 ---

    @Test
    fun 로그아웃_상태는_카카오_로그인_버튼을_보여준다() {
        var started = 0
        composeRule.setContent {
            GilpickApp(state = AuthUiState.SignedOut, onKakaoLogin = { started++ }, onRetry = {})
        }

        composeRule.onNodeWithText(string(R.string.login_kakao)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.login_kakao)).performClick()

        assertEquals(1, started)
    }

    @Test
    fun 진행_중에는_진행_상태를_보여준다() {
        composeRule.setContent {
            GilpickApp(state = AuthUiState.LoggingIn, onKakaoLogin = {}, onRetry = {})
        }

        composeRule.onNodeWithText(string(R.string.login_in_progress)).assertIsDisplayed()
    }

    @Test
    fun 일시적_실패는_잠시_후_재시도를_안내한다() {
        var retried = 0
        composeRule.setContent {
            GilpickApp(
                state = AuthUiState.LoginFailed(AuthErrorCodes.KAKAO_API_TIMEOUT, retryable = true),
                onKakaoLogin = {},
                onRetry = { retried++ },
            )
        }

        composeRule.onNodeWithText(string(R.string.login_error_retryable)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.login_retry)).performClick()

        assertEquals(1, retried)
    }

    @Test
    fun 확정된_실패는_새_카카오_인증을_안내한다() {
        composeRule.setContent {
            GilpickApp(
                state = AuthUiState.LoginFailed(AuthErrorCodes.LOGIN_TICKET_EXPIRED, retryable = false),
                onKakaoLogin = {},
                onRetry = {},
            )
        }

        composeRule.onNodeWithText(string(R.string.login_error_restart)).assertIsDisplayed()
    }

    // --- 로그인 성공 후 이동 ---

    @Test
    fun 로그인에_성공하면_빈_여행_목록_shell로_이동한다() {
        composeRule.setContent {
            GilpickApp(
                state = AuthUiState.Authenticated(USER_ID, nickname = "길픽", profileImageUrl = null),
                onKakaoLogin = {},
                onRetry = {},
            )
        }

        composeRule.onNodeWithText(string(R.string.trips_empty)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.login_kakao)).assertDoesNotExist()
    }

    @Test
    fun profile이_없는_사용자도_빈_여행_목록_shell로_들어간다() {
        composeRule.setContent {
            GilpickApp(
                state = AuthUiState.Authenticated(USER_ID, nickname = null, profileImageUrl = null),
                onKakaoLogin = {},
                onRetry = {},
            )
        }

        composeRule.onNodeWithText(string(R.string.trips_empty)).assertIsDisplayed()
    }

    private fun completeLink() =
        "https://${BuildConfig.APP_LINK_HOST}/auth/kakao/complete#loginTicket=$TICKET"

    private companion object {
        const val USER_ID = "33333333-4444-4555-8666-777777777777"
        const val TICKET =
            "11111111-2222-4333-8444-555555555555.abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"
    }
}
