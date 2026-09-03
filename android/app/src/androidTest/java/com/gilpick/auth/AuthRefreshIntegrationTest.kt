package com.gilpick.auth

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.GilpickApp
import com.gilpick.R
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

/**
 * T029: 실제 기기에서 갱신 성공·재시도·재로그인 UI 전이 검증.
 *
 * AndroidKeyStore 암호화와 Proto DataStore를 그대로 사용해, 통신 실패에서는 로그인
 * 상태가 보존되고 확정된 무효에서만 로그인 화면으로 돌아가는지 확인한다.
 */
@RunWith(AndroidJUnit4::class)
class AuthRefreshIntegrationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private lateinit var server: MockWebServer
    private lateinit var storeFile: File
    private lateinit var repository: AuthRepository

    /** interceptor로 통신 장애를 만든다. 실제 socket을 끊지 않아 재연결도 확인할 수 있다. */
    @Volatile
    private var offline = false

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        storeFile = File(context.cacheDir, "auth-refresh-test-${System.nanoTime()}.pb")
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (offline) throw IOException("연결 실패") else chain.proceed(chain.request())
            }
            .build()
        repository = AuthRepository(
            store = AuthSessionStore(
                AuthSessionStore.createDataStore(storeFile),
                KeystoreSessionCipher(KEY_ALIAS),
            ),
            api = createAuthRetrofit(server.url("/api/v1/").toString(), client)
                .create(AuthService::class.java),
            appLinkHandler = AuthAppLinkHandler(APP_LINK_HOST),
        )
        runBlocking { signIn() }
    }

    @After
    fun tearDown() {
        server.close()
        storeFile.delete()
    }

    @Test
    fun 통신_실패는_로그인_상태를_유지한_채_재시도_화면을_보여준다() {
        offline = true

        val outcome = runBlocking { repository.refresh() }

        assertEquals(RefreshOutcome.Offline, outcome)
        assertNotNull(
            "통신 실패만으로 session을 지우면 안 된다",
            runBlocking { repository.currentSession() },
        )
        setContentWithRepository()
        composeRule.onNodeWithText(string(R.string.refresh_offline_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trips_title)).assertDoesNotExist()
    }

    @Test
    fun 재시도가_성공하면_보호_화면으로_돌아간다() {
        offline = true
        runBlocking { repository.refresh() }
        setContentWithRepository()
        composeRule.onNodeWithText(string(R.string.refresh_offline_title)).assertIsDisplayed()

        offline = false
        server.enqueue(MockResponse(code = 200, body = refreshJson()))
        composeRule.onNodeWithText(string(R.string.refresh_offline_retry)).performClick()
        composeRule.waitUntil(TIMEOUT_MS) { repository.state.value is AuthUiState.Authenticated }

        composeRule.onNodeWithText(string(R.string.trips_title)).assertIsDisplayed()
        assertEquals(SECOND_ACCESS, runBlocking { repository.currentSession() }?.accessToken)
    }

    @Test
    fun replay_두_번째_401은_loop_없이_로그인_화면으로_돌아간다() {
        server.enqueue(MockResponse(code = 200, body = refreshJson()))
        val protectedCalls = AtomicInteger()

        runBlocking {
            repository.withAccessToken<String> {
                protectedCalls.incrementAndGet()
                unauthorizedResponse()
            }
        }

        assertEquals("원 요청은 최초 1회와 replay 1회만 수행한다", 2, protectedCalls.get())
        assertEquals("refresh loop가 생기면 안 된다", 1, server.requestCount)
        assertNull(runBlocking { repository.currentSession() })
        assertEquals(AuthUiState.SignedOut, repository.state.value)
        setContentWithRepository()
        composeRule.onNodeWithText(string(R.string.login_kakao)).assertIsDisplayed()
    }

    @Test
    fun 확정된_무효는_재로그인을_요구한다() {
        server.enqueue(MockResponse(code = 403, body = errorJson(AuthErrorCodes.DEVICE_MISMATCH)))

        val outcome = runBlocking { repository.refresh() }

        assertEquals(RefreshOutcome.SignedOut, outcome)
        assertNull(runBlocking { repository.currentSession() })
        setContentWithRepository()
        composeRule.onNodeWithText(string(R.string.login_kakao)).assertIsDisplayed()
    }

    /** repository 상태를 그대로 구독하는 실제 화면 구성을 띄운다. */
    private fun setContentWithRepository() {
        composeRule.setContent {
            val state by repository.state.collectAsState()
            val scope = rememberCoroutineScope()
            GilpickApp(
                state = state,
                onKakaoLogin = {},
                onRetry = {},
                onRetryRefresh = { scope.launch { repository.refresh() } },
                onLogout = { scope.launch { repository.logout() } },
            )
        }
    }

    /** 만료된 Access Token으로 보호 자원을 요청했을 때 서버가 주는 응답. */
    private fun unauthorizedResponse(): Response<SuccessEnvelope<String>> = Response.error(
        401,
        errorJson(AuthErrorCodes.TOKEN_EXPIRED).toResponseBody("application/json".toMediaType()),
    )

    private suspend fun signIn() {
        repository.onSignedIn(
            sessionId = "session-1",
            userId = USER_ID,
            nickname = "길픽",
            profileImageUrl = null,
            accessToken = FIRST_ACCESS,
            refreshToken = FIRST_REFRESH,
            accessExpiresAtEpochSeconds = 3_600,
            refreshExpiresAtEpochSeconds = 2_592_000,
        )
    }

    private fun string(id: Int) = context.getString(id)

    private fun refreshJson() = """
        {"success":true,
         "data":{"accessToken":"$SECOND_ACCESS","expiresIn":3600,
                 "refreshToken":"$SECOND_REFRESH","refreshExpiresIn":2592000},
         "meta":{"requestId":"$REQUEST_ID"}}
    """.trimIndent()

    private fun errorJson(code: String) = """
        {"success":false,
         "error":{"code":"$code","message":"진단용 설명","retryable":false},
         "meta":{"requestId":"$REQUEST_ID"}}
    """.trimIndent()

    private companion object {
        const val APP_LINK_HOST = "app.gilpick.example"
        const val KEY_ALIAS = "gilpick.auth.refresh.test"
        const val TIMEOUT_MS = 5_000L
        const val USER_ID = "33333333-4444-4555-8666-777777777777"
        const val REQUEST_ID = "99999999-8888-4777-8666-555555555555"
        const val SECRET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"
        const val FIRST_ACCESS = "access-1"
        const val SECOND_ACCESS = "access-2"
        const val FIRST_REFRESH = "session-1.$SECRET"
        const val SECOND_REFRESH = "session-2.$SECRET"
    }
}
