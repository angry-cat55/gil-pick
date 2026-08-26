package com.gilpick.auth

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response

/**
 * T028: single-flight refresh, 원 요청 최대 1회 replay, 통신 실패 시 session 보존 검증.
 *
 * 확정된 무효(`INVALID_REFRESH_TOKEN`·`TOKEN_EXPIRED`·`DEVICE_MISMATCH`)만 로그아웃으로
 * 이어지고, 통신 실패는 `RefreshOffline`으로 남아야 한다.
 */
class AuthRefreshTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val api = ProgrammableAuthService()
    private lateinit var refreshScope: CoroutineScope
    private lateinit var store: AuthSessionStore
    private lateinit var repository: AuthRepository

    @Before
    fun setUp() {
        store = AuthSessionStore(
            AuthSessionStore.createDataStore(File(tempFolder.root, AuthSessionStore.FILE_NAME)),
            FakeSessionCipher(),
        )
        // single-flight 합류를 실제 동시성으로 확인하므로 virtual time이 아닌 scope를 쓴다.
        refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repository = AuthRepository(
            store = store,
            api = api,
            appLinkHandler = AuthAppLinkHandler(APP_LINK_HOST),
            scope = refreshScope,
        )
    }

    @After
    fun tearDown() {
        refreshScope.cancel()
    }

    // --- single-flight ---

    @Test
    fun `동시 갱신 요청은 refresh 한 건으로 합쳐지고 모든 waiter가 같은 결과를 받는다`() = runBlocking {
        signIn()
        val gate = CompletableDeferred<Unit>()
        api.onRefresh = {
            gate.await()
            refreshOk(access = SECOND_ACCESS, refresh = SECOND_REFRESH)
        }

        val waiters = List(3) { async(Dispatchers.Default) { repository.refresh() } }
        // 첫 waiter가 서버 호출에 들어간 뒤 나머지가 같은 작업에 합류할 시간을 준다.
        while (api.refreshCount == 0) delay(5)
        delay(100)
        gate.complete(Unit)
        val outcomes = waiters.awaitAll()

        assertEquals("동시 요청은 refresh 한 건으로 합쳐져야 한다", 1, api.refreshCount)
        assertEquals("모든 waiter가 같은 결과를 받아야 한다", 1, outcomes.distinct().size)
        assertEquals(RefreshOutcome.Refreshed(SECOND_ACCESS), outcomes.first())
    }

    @Test
    fun `갱신에 성공하면 Token pair가 교체되고 사용자 정보는 유지된다`() = runTest {
        signIn()
        api.onRefresh = { refreshOk(access = SECOND_ACCESS, refresh = SECOND_REFRESH) }

        val outcome = repository.refresh()

        assertEquals(RefreshOutcome.Refreshed(SECOND_ACCESS), outcome)
        val session = repository.currentSession()
        assertNotNull(session)
        assertEquals(SECOND_ACCESS, session?.accessToken)
        assertEquals(SECOND_REFRESH, session?.refreshToken)
        assertEquals("session-2", session?.sessionId)
        assertEquals("길픽", session?.nickname)
        assertEquals(AuthUiState.Authenticated(USER_ID, "길픽", null), repository.state.value)
        assertEquals(
            "이전 Refresh Token으로 요청해야 한다",
            FIRST_REFRESH,
            api.lastRefreshRequest?.refreshToken,
        )
    }

    // --- 통신 실패 보존 ---

    @Test
    fun `통신 실패는 session을 보존한 채 RefreshOffline이 된다`() = runTest {
        signIn()
        api.onRefresh = { throw IOException("연결 실패") }

        val outcome = repository.refresh()

        assertEquals(RefreshOutcome.Offline, outcome)
        assertNotNull("통신 실패만으로 로그아웃해서는 안 된다", repository.currentSession())
        assertEquals(AuthUiState.RefreshOffline(USER_ID), repository.state.value)
    }

    @Test
    fun `확정된 무효가 아닌 서버 오류도 session을 보존한다`() = runTest {
        signIn()
        api.onRefresh = { errorResponse(500, "INTERNAL_ERROR", retryable = true) }

        assertEquals(RefreshOutcome.Offline, repository.refresh())
        assertNotNull(repository.currentSession())
    }

    // --- 확정된 무효 ---

    @Test
    fun `만료된 Refresh Token은 SignedOut으로 확정된다`() = runTest {
        signIn()
        api.onRefresh = { errorResponse(401, AuthErrorCodes.TOKEN_EXPIRED) }

        assertEquals(RefreshOutcome.SignedOut, repository.refresh())
        assertNull(repository.currentSession())
        assertEquals(AuthUiState.SignedOut, repository.state.value)
    }

    @Test
    fun `무효한 Refresh Token은 SignedOut으로 확정된다`() = runTest {
        signIn()
        api.onRefresh = { errorResponse(401, AuthErrorCodes.INVALID_REFRESH_TOKEN) }

        assertEquals(RefreshOutcome.SignedOut, repository.refresh())
        assertNull(repository.currentSession())
    }

    @Test
    fun `기기 불일치는 SignedOut으로 확정된다`() = runTest {
        signIn()
        api.onRefresh = { errorResponse(403, AuthErrorCodes.DEVICE_MISMATCH) }

        assertEquals(RefreshOutcome.SignedOut, repository.refresh())
        assertNull(repository.currentSession())
    }

    // --- 원 요청 replay ---

    @Test
    fun `401을 받은 원 요청은 갱신 후 한 번만 replay된다`() = runTest {
        signIn()
        api.onRefresh = { refreshOk(access = SECOND_ACCESS, refresh = SECOND_REFRESH) }
        val tokens = mutableListOf<String>()

        val result = repository.withAccessToken { token ->
            tokens += token
            if (token == FIRST_ACCESS) unauthorized() else ok("보호 자원")
        }

        assertEquals(listOf(FIRST_ACCESS, SECOND_ACCESS), tokens)
        assertEquals("보호 자원", (result as AuthResult.Success).value)
    }

    @Test
    fun `replay도 401이면 refresh를 반복하지 않고 SignedOut이 된다`() = runTest {
        signIn()
        api.onRefresh = { refreshOk(access = SECOND_ACCESS, refresh = SECOND_REFRESH) }
        val calls = AtomicInteger()

        val result = repository.withAccessToken {
            calls.incrementAndGet()
            unauthorized()
        }

        assertEquals("원 요청은 최초 1회와 replay 1회만 수행한다", 2, calls.get())
        assertEquals("refresh loop가 생기면 안 된다", 1, api.refreshCount)
        assertTrue(result is AuthResult.Failure)
        assertEquals(AuthUiState.SignedOut, repository.state.value)
        assertNull(repository.currentSession())
    }

    @Test
    fun `갱신이 통신 실패로 끝나면 원 요청을 replay하지 않는다`() = runTest {
        signIn()
        api.onRefresh = { throw IOException("연결 실패") }
        val calls = AtomicInteger()

        val result = repository.withAccessToken {
            calls.incrementAndGet()
            unauthorized()
        }

        assertEquals(1, calls.get())
        assertTrue((result as AuthResult.Failure).error is AuthError.Offline)
        assertNotNull(repository.currentSession())
        assertEquals(AuthUiState.RefreshOffline(USER_ID), repository.state.value)
    }

    @Test
    fun `401이 아닌 응답은 갱신하지 않고 그대로 전달한다`() = runTest {
        signIn()

        val result = repository.withAccessToken { ok("보호 자원") }

        assertEquals("보호 자원", (result as AuthResult.Success).value)
        assertEquals(0, api.refreshCount)
    }

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

    private companion object {
        const val APP_LINK_HOST = "app.gilpick.example"
        const val USER_ID = "33333333-4444-4555-8666-777777777777"
        const val FIRST_ACCESS = "access-1"
        const val SECOND_ACCESS = "access-2"
        const val SECRET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"
        const val FIRST_REFRESH = "session-1.$SECRET"
        const val SECOND_REFRESH = "session-2.$SECRET"
    }
}

/** 계약 형식을 지킨 refresh 성공 응답. */
internal fun refreshOk(access: String, refresh: String): Response<SuccessEnvelope<RefreshTokenData>> =
    Response.success(
        SuccessEnvelope(
            success = true,
            data = RefreshTokenData(
                accessToken = access,
                expiresIn = 3_600,
                refreshToken = refresh,
                refreshExpiresIn = 2_592_000,
            ),
            meta = ResponseMeta(requestId = TEST_REQUEST_ID),
        ),
    )

/** 계약 형식을 지킨 오류 응답. */
internal fun <T> errorResponse(status: Int, code: String, retryable: Boolean = false): Response<T> =
    Response.error(
        status,
        ("""{"success":false,"error":{"code":"$code","message":"","retryable":$retryable},""" +
            """"meta":{"requestId":"$TEST_REQUEST_ID"}}""")
            .toResponseBody("application/json".toMediaType()),
    )

/** 만료된 Access Token으로 보호 자원을 요청했을 때의 응답. */
internal fun unauthorized(): Response<SuccessEnvelope<String>> =
    errorResponse(401, AuthErrorCodes.TOKEN_EXPIRED)

/** 보호 자원 요청의 성공 응답. */
internal fun ok(value: String): Response<SuccessEnvelope<String>> =
    Response.success(SuccessEnvelope(success = true, data = value, meta = ResponseMeta(TEST_REQUEST_ID)))

internal const val TEST_REQUEST_ID = "99999999-8888-4777-8666-555555555555"
