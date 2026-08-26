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
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import com.gilpick.GilpickApp
import com.gilpick.R
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T038: 즉시 local logout, 재시작 후 폐기 대기 보존, 복수 폐기 격리 검증.
 *
 * 서버 폐기가 불가능한 상황에서도 로그아웃이 즉시 끝나고, 요청마다 별도 operation ID의
 * unique work가 등록되어 같은 기기의 재로그인·재로그아웃이 서로를 밀어내지 않는지
 * 확인한다.
 */
@RunWith(AndroidJUnit4::class)
class AuthLogoutIntegrationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private lateinit var storeFile: File
    private lateinit var store: AuthSessionStore
    private lateinit var repository: AuthRepository
    private lateinit var scope: CoroutineScope

    /** 예약된 순서대로의 operation ID. 요청별 격리를 확인한다. */
    private val scheduled = mutableListOf<String>()

    @Before
    fun setUp() {
        storeFile = File(context.cacheDir, "auth-logout-test-${System.nanoTime()}.pb")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = AuthSessionStore(
            AuthSessionStore.createDataStore(storeFile, scope),
            KeystoreSessionCipher(KEY_ALIAS),
        )
        val scheduler = SessionRevocationWorker.scheduler(context)
        repository = AuthRepository(
            store = store,
            api = FailingAuthService,
            appLinkHandler = AuthAppLinkHandler(APP_LINK_HOST),
            scheduleRevocation = { operationId ->
                scheduled += operationId
                scheduler(operationId)
            },
        )
    }

    @After
    fun tearDown() {
        scheduled.forEach {
            WorkManager.getInstance(context).cancelUniqueWork(SessionRevocationWorker.uniqueWorkName(it))
        }
        scope.cancel()
        storeFile.delete()
    }

    @Test
    fun 오프라인_로그아웃은_즉시_보호_화면을_차단한다() {
        runBlocking { signIn(refreshToken = FIRST_REFRESH) }
        setContentWithRepository()
        composeRule.onNodeWithText(string(R.string.trips_empty)).assertIsDisplayed()

        composeRule.onNodeWithText(string(R.string.logout)).performClick()
        composeRule.waitUntil(TIMEOUT_MS) { repository.state.value == AuthUiState.SignedOut }

        composeRule.onNodeWithText(string(R.string.login_kakao)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trips_empty)).assertDoesNotExist()
        assertNull("활성 session이 남으면 안 된다", runBlocking { repository.currentSession() })
    }

    @Test
    fun 폐기_대기_자격은_이_기기의_Token과_deviceId만_담는다() = runBlocking {
        val deviceId = repository.deviceId()
        signIn(refreshToken = FIRST_REFRESH)

        repository.logout()

        val pending = store.loadRevocation(scheduled.single())
        assertNotNull(pending)
        assertEquals(FIRST_REFRESH, pending?.refreshToken)
        assertEquals("다른 기기 session을 건드릴 수 없어야 한다", deviceId, pending?.deviceId)
    }

    @Test
    fun 로그아웃과_재로그인_후_두_번째_로그아웃은_서로_다른_작업으로_격리된다() = runBlocking {
        signIn(refreshToken = FIRST_REFRESH)
        repository.logout()
        signIn(refreshToken = SECOND_REFRESH)
        repository.logout()

        assertEquals(2, scheduled.size)
        assertFalse("요청마다 다른 operation ID여야 한다", scheduled[0] == scheduled[1])
        assertEquals(FIRST_REFRESH, store.loadRevocation(scheduled[0])?.refreshToken)
        assertEquals(SECOND_REFRESH, store.loadRevocation(scheduled[1])?.refreshToken)
        scheduled.forEach { operationId ->
            val name = SessionRevocationWorker.uniqueWorkName(operationId)
            val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()
            assertEquals("operation마다 unique work가 하나씩 있어야 한다", 1, infos.size)
        }
    }

    @Test
    fun 하나의_폐기_완료가_다른_대기_항목을_지우지_않는다() = runBlocking {
        signIn(refreshToken = FIRST_REFRESH)
        repository.logout()
        signIn(refreshToken = SECOND_REFRESH)
        repository.logout()

        store.removeRevocation(scheduled[0])

        assertNull(store.loadRevocation(scheduled[0]))
        assertEquals(SECOND_REFRESH, store.loadRevocation(scheduled[1])?.refreshToken)
    }

    @Test
    fun 재시작해도_폐기_대기_항목이_남는다() = runBlocking {
        signIn(refreshToken = FIRST_REFRESH)
        repository.logout()
        val operationId = scheduled.single()

        // process 재시작을 모사한다. 같은 파일을 새 store 인스턴스로 다시 연다.
        scope.cancel()
        val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val restarted = AuthSessionStore(
            AuthSessionStore.createDataStore(storeFile, restartScope),
            KeystoreSessionCipher(KEY_ALIAS),
        )

        assertNull("활성 session은 복원되면 안 된다", restarted.loadSession())
        assertEquals(FIRST_REFRESH, restarted.loadRevocation(operationId)?.refreshToken)
        restartScope.cancel()
    }

    @Test
    fun 서버에_닿지_못하면_대기_항목을_보존한_채_재시도한다() = runBlocking {
        // worker는 앱의 실제 store를 쓰므로 여기서만 앱 store에 항목을 넣고 정리한다.
        val appStore = AuthSessionStore.create(context)
        val operationId = appStore.enqueueRevocation(FIRST_REFRESH, DEVICE_ID)
        try {
            val worker = TestListenableWorkerBuilder<SessionRevocationWorker>(context)
                .setInputData(SessionRevocationWorker.inputData(operationId))
                .build()

            val result = worker.doWork()

            assertTrue(
                "닿을 수 없는 서버는 재시도 대상이다: $result",
                result is ListenableWorker.Result.Retry,
            )
            assertNotNull("재시도해야 하므로 대기 항목이 남아야 한다", appStore.loadRevocation(operationId))
        } finally {
            appStore.removeRevocation(operationId)
        }
    }

    /** repository 상태를 그대로 구독하는 실제 화면 구성을 띄운다. */
    private fun setContentWithRepository() {
        composeRule.setContent {
            val state by repository.state.collectAsState()
            val uiScope = rememberCoroutineScope()
            GilpickApp(
                state = state,
                onKakaoLogin = {},
                onRetry = {},
                onRetryRefresh = { uiScope.launch { repository.refresh() } },
                onLogout = { uiScope.launch { repository.logout() } },
            )
        }
    }

    private suspend fun signIn(refreshToken: String) {
        repository.onSignedIn(
            sessionId = refreshToken.substringBefore('.'),
            userId = USER_ID,
            nickname = "길픽",
            profileImageUrl = null,
            accessToken = "access-token",
            refreshToken = refreshToken,
            accessExpiresAtEpochSeconds = 3_600,
            refreshExpiresAtEpochSeconds = 2_592_000,
        )
    }

    private fun string(id: Int) = context.getString(id)

    private companion object {
        const val APP_LINK_HOST = "app.gilpick.example"
        const val KEY_ALIAS = "gilpick.auth.logout.test"
        const val TIMEOUT_MS = 5_000L
        const val USER_ID = "33333333-4444-4555-8666-777777777777"
        const val DEVICE_ID = "11111111-2222-4333-8444-555555555555"
        const val SECRET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"
        const val FIRST_REFRESH = "session-1.$SECRET"
        const val SECOND_REFRESH = "session-2.$SECRET"
    }
}

/**
 * 로그아웃이 network와 무관하게 즉시 끝나는지 보기 위한 [AuthService].
 *
 * 이 test의 로그아웃 경로는 서버를 호출하지 않는다. 호출되면 그 자체가 실패다.
 */
private object FailingAuthService : AuthService {

    override suspend fun createLoginTransaction(
        body: CreateLoginTransactionRequest,
    ) = error("로그아웃 경로는 인증 endpoint를 호출하지 않는다")

    override suspend fun exchangeLoginTicket(
        body: LoginTicketExchangeRequest,
    ) = error("로그아웃 경로는 인증 endpoint를 호출하지 않는다")

    override suspend fun refreshTokens(
        body: RefreshTokenRequest,
    ) = error("로그아웃 경로는 인증 endpoint를 호출하지 않는다")

    override suspend fun logout(body: RefreshTokenRequest) =
        error("서버 폐기는 worker가 맡는다")
}
