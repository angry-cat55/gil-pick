package com.gilpick.auth

import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.os.Build
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.BuildConfig
import com.gilpick.GilpickApp
import com.gilpick.R
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T017: verified App Link 수신과 로그인 화면 상태 전이 검증.
 *
 * 실제 host/path의 intent를 앱이 claim하는지, 로그인에 성공하면 여행 목록 화면으로
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
     * 앱이 인증 완료 host를 App Link domain으로 선언하고 검증까지 통과하는지 확인한다.
     *
     * T010의 검증 기준을 자동화한 test다. `VERIFIED`가 되려면 앱의 선언과 host의
     * `/.well-known/assetlinks.json`이 모두 있어야 하고, 그 파일의 fingerprint 목록에
     * 이 빌드의 서명 인증서가 들어 있어야 한다. 셋 중 하나라도 어긋나면 실패한다.
     *
     * API 31 이상은 검증되지 않은 domain을 암시적 intent 해석에서 제외하므로
     * `queryIntentActivities`로는 선언 여부를 확인할 수 없다. path 단위 claim 범위는
     * `AuthAppLinkHandler` test로 확인한다.
     */
    @Test
    fun 앱이_인증_완료_host를_App_Link_domain으로_검증한다() {
        assumeTrue("domain verification 조회는 API 31 이상이다", Build.VERSION.SDK_INT >= 31)
        val manager = context.getSystemService(DomainVerificationManager::class.java)

        val hostStates = manager.getDomainVerificationUserState(context.packageName)?.hostToStateMap

        assertTrue(
            "선언한 App Link domain: $hostStates",
            hostStates?.containsKey(BuildConfig.APP_LINK_HOST) == true,
        )
        assertEquals(
            "${BuildConfig.APP_LINK_HOST}의 assetlinks.json에 이 빌드의 서명 fingerprint가 " +
                "있어야 한다. 상태가 바뀌지 않으면 pm verify-app-links --re-verify로 재검증한다.",
            DomainVerificationUserState.DOMAIN_STATE_VERIFIED,
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
    fun 로그인에_성공하면_여행_목록_화면으로_이동한다() {
        composeRule.setContent {
            GilpickApp(
                state = AuthUiState.Authenticated(USER_ID, nickname = "길픽", profileImageUrl = null),
                onKakaoLogin = {},
                onRetry = {},
            )
        }

        composeRule.onNodeWithText(string(R.string.trips_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.login_kakao)).assertDoesNotExist()
    }

    @Test
    fun profile이_없는_사용자도_여행_목록_화면으로_들어간다() {
        composeRule.setContent {
            GilpickApp(
                state = AuthUiState.Authenticated(USER_ID, nickname = null, profileImageUrl = null),
                onKakaoLogin = {},
                onRetry = {},
            )
        }

        composeRule.onNodeWithText(string(R.string.trips_title)).assertIsDisplayed()
    }

    // --- T045: 반복 시간 측정 ---

    /**
     * mock 환경에서 App Link 수신부터 여행 목록 화면 표시까지의 시간을 20회 측정한다.
     *
     * SC-001의 "20회 중 19회 이상 10초 이내"를 확인한다. Kakao와 Backend는 mock이므로
     * 외부 대기시간은 빠지고 ticket 교환, 암호화 저장, 화면 전환 경로만 측정한다.
     * 느린 회차도 기록해야 분포를 볼 수 있으므로 대기 한도는 기준치보다 넉넉하게 둔다.
     */
    @Test
    fun mock_App_Link부터_여행_목록_화면까지_20회_중_19회_이상_10초_이내다() {
        val server = MockWebServer()
        server.start()
        val storeFile = File(context.cacheDir, "auth-perf-${System.nanoTime()}.pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repository = AuthRepository(
            store = AuthSessionStore(
                AuthSessionStore.createDataStore(storeFile, scope),
                KeystoreSessionCipher(PERF_KEY_ALIAS),
            ),
            api = createAuthRetrofit(server.url("/api/v1/").toString())
                .create(AuthService::class.java),
            appLinkHandler = AuthAppLinkHandler(BuildConfig.APP_LINK_HOST),
            scope = scope,
        )

        try {
            composeRule.setContent {
                val state by repository.state.collectAsState()
                GilpickApp(state = state, onKakaoLogin = {}, onRetry = {})
            }

            val durationsMs = (1..ITERATIONS).map { iteration ->
                server.enqueue(MockResponse(code = 201, body = tokenJson(iteration)))
                runBlocking { repository.onSignedOut() }
                composeRule.waitUntil(WAIT_LIMIT_MS) { isDisplayed(R.string.login_kakao) }

                val startedAt = System.nanoTime()
                runBlocking { repository.completeLogin(completeLink(iteration)) }
                composeRule.waitUntil(WAIT_LIMIT_MS) { isDisplayed(R.string.trips_title) }
                (System.nanoTime() - startedAt) / 1_000_000
            }

            val withinLimit = durationsMs.count { it <= LIMIT_MS }
            assertTrue(
                "10초 이내 $withinLimit/$ITERATIONS 회. 회차별 ms=$durationsMs",
                withinLimit >= REQUIRED_WITHIN_LIMIT,
            )
        } finally {
            server.close()
            scope.cancel()
            storeFile.delete()
        }
    }

    /** 해당 문자열을 가진 node가 화면에 있는지 확인한다. */
    private fun isDisplayed(id: Int) =
        composeRule.onAllNodesWithText(string(id)).fetchSemanticsNodes().isNotEmpty()

    /** 회차마다 다른 ticket을 만든다. 같은 link는 한 번만 소비되기 때문이다. */
    private fun completeLink(iteration: Int) =
        "https://${BuildConfig.APP_LINK_HOST}/auth/kakao/complete" +
            "#loginTicket=${ticketFor(iteration)}"

    private fun ticketFor(iteration: Int) =
        "11111111-2222-4333-8444-%012d.%s".format(iteration, TICKET_SECRET)

    private fun tokenJson(iteration: Int) = """
        {"success":true,
         "data":{"accessToken":"access-$iteration","expiresIn":3600,
                 "refreshToken":"${ticketFor(iteration)}","refreshExpiresIn":2592000,
                 "user":{"userId":"$USER_ID","provider":"KAKAO"}},
         "meta":{"requestId":"$REQUEST_ID"}}
    """.trimIndent()

    private fun completeLink() =
        "https://${BuildConfig.APP_LINK_HOST}/auth/kakao/complete#loginTicket=$TICKET"

    private companion object {
        const val USER_ID = "33333333-4444-4555-8666-777777777777"
        const val REQUEST_ID = "99999999-8888-4777-8666-555555555555"
        const val PERF_KEY_ALIAS = "gilpick.auth.perf.test"
        const val ITERATIONS = 20
        const val REQUIRED_WITHIN_LIMIT = 19
        const val LIMIT_MS = 10_000L
        const val WAIT_LIMIT_MS = 30_000L
        const val TICKET_SECRET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"
        const val TICKET =
            "11111111-2222-4333-8444-555555555555.abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"
    }
}
