package com.gilpick.auth

import java.io.File
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T016: login transaction 생성, App Link ticket 일회 수신, ticket 교환과 암호화 저장 검증.
 *
 * 오류·취소 경로에서 부분 session이 남지 않는다는 조건을 함께 확인한다.
 */
class AuthLoginFlowTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: AuthSessionStore
    private lateinit var repository: AuthRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = AuthSessionStore(
            AuthSessionStore.createDataStore(File(tempFolder.root, AuthSessionStore.FILE_NAME)),
            FakeSessionCipher(),
        )
        repository = AuthRepository(
            store = store,
            api = createAuthRetrofit(server.url("/api/v1/").toString(), OkHttpClient())
                .create(AuthService::class.java),
            appLinkHandler = AuthAppLinkHandler(APP_LINK_HOST),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    // --- transaction 생성 ---

    @Test
    fun `transaction 생성은 201의 authorizationUrl을 전달한다`() = runTest {
        server.enqueue(MockResponse(code = 201, body = transactionJson()))

        val start = repository.startLogin()

        assertEquals(AUTHORIZATION_URL, (start as LoginStart.Ready).authorizationUrl)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/auth/kakao/transactions", request.url.encodedPath)
        val body = request.body?.utf8().orEmpty()
        assertTrue("계약상 platform은 ANDROID다", body.contains("\"platform\":\"ANDROID\""))
        assertTrue("기기 session 결합을 위해 deviceId를 보낸다", body.contains(store.deviceId()))
    }

    @Test
    fun `transaction 생성 실패는 로그인 화면에 오류로 남고 session을 만들지 않는다`() = runTest {
        server.enqueue(MockResponse(code = 500, body = errorJson("INTERNAL_ERROR", retryable = true)))

        val start = repository.startLogin()

        assertTrue(start is LoginStart.Failed)
        val failed = repository.state.value as AuthUiState.LoginFailed
        assertTrue("서버가 재시도 가능하다고 알리면 그대로 보존한다", failed.retryable)
        assertNull("부분 session이 남으면 안 된다", store.loadSession())
    }

    // --- App Link ticket 수신 ---

    @Test
    fun `fragment ticket은 한 번만 수락하고 이후 같은 intent는 무시한다`() {
        val handler = AuthAppLinkHandler(APP_LINK_HOST)

        assertEquals(TICKET, (handler.consume(successLink()) as AppLinkResult.Ticket).loginTicket)
        assertEquals(AppLinkResult.Ignored, handler.consume(successLink()))
    }

    @Test
    fun `query로 전달된 ticket은 수락하지 않는다`() {
        val handler = AuthAppLinkHandler(APP_LINK_HOST)

        val result = handler.consume("https://$APP_LINK_HOST/auth/kakao/complete?loginTicket=$TICKET")

        assertEquals(AppLinkResult.Ignored, result)
    }

    @Test
    fun `허용하지 않은 host와 path는 거절한다`() {
        val handler = AuthAppLinkHandler(APP_LINK_HOST)

        assertEquals(
            AppLinkResult.Ignored,
            handler.consume("https://evil.example/auth/kakao/complete#loginTicket=$TICKET"),
        )
        assertEquals(
            AppLinkResult.Ignored,
            handler.consume("https://$APP_LINK_HOST/api/v1/auth/kakao/callback#loginTicket=$TICKET"),
        )
        assertEquals(
            AppLinkResult.Ignored,
            handler.consume("http://$APP_LINK_HOST/auth/kakao/complete#loginTicket=$TICKET"),
        )
    }

    @Test
    fun `계약 형식이 아닌 ticket과 잘못된 URI는 거절한다`() {
        val handler = AuthAppLinkHandler(APP_LINK_HOST)

        assertEquals(
            AppLinkResult.Ignored,
            handler.consume("https://$APP_LINK_HOST/auth/kakao/complete#loginTicket=test"),
        )
        assertEquals(AppLinkResult.Ignored, handler.consume("not a uri"))
        assertEquals(AppLinkResult.Ignored, handler.consume(null))
    }

    @Test
    fun `callback error는 code와 재시도 가능 여부로 전달된다`() {
        val timeout = AuthAppLinkHandler(APP_LINK_HOST)
            .consume(failureLink(AuthErrorCodes.KAKAO_API_TIMEOUT)) as AppLinkResult.Failed
        assertTrue("provider 일시 장애는 잠시 후 재시도할 수 있다", timeout.error.retryable)

        val denied = AuthAppLinkHandler(APP_LINK_HOST)
            .consume(failureLink(AuthErrorCodes.ACCESS_DENIED)) as AppLinkResult.Failed
        assertEquals(AuthErrorCodes.ACCESS_DENIED, denied.error.code)
        assertFalse("동의 거절은 자동 재시도 대상이 아니다", denied.error.retryable)
    }

    // --- ticket 교환과 저장 ---

    @Test
    fun `신규 사용자 201 교환은 Token pair를 저장하고 Authenticated가 된다`() = runTest {
        server.enqueue(MockResponse(code = 201, body = tokenJson(nickname = null)))

        val state = repository.completeLogin(successLink())

        val authenticated = state as AuthUiState.Authenticated
        assertEquals(USER_ID, authenticated.userId)
        assertNull("profile 미동의 사용자도 로그인한다", authenticated.nickname)

        val session = store.loadSession()!!
        assertEquals(ACCESS_TOKEN, session.accessToken)
        assertEquals(REFRESH_TOKEN, session.refreshToken)
        assertTrue(
            "Refresh Token 만료가 Access Token 만료보다 뒤다",
            session.refreshExpiresAtEpochSeconds > session.accessExpiresAtEpochSeconds,
        )
    }

    @Test
    fun `기존 사용자 200 교환도 같은 경로로 로그인한다`() = runTest {
        server.enqueue(MockResponse(code = 200, body = tokenJson(nickname = "길픽")))

        val authenticated = repository.completeLogin(successLink()) as AuthUiState.Authenticated

        assertEquals("길픽", authenticated.nickname)
        assertNotNull(store.loadSession())
    }

    @Test
    fun `교환 요청은 fragment에서 꺼낸 ticket과 deviceId를 보낸다`() = runTest {
        server.enqueue(MockResponse(code = 201, body = tokenJson(nickname = null)))

        repository.completeLogin(successLink())

        val request = server.takeRequest()
        assertEquals("/api/v1/auth/kakao/exchange", request.url.encodedPath)
        val body = request.body?.utf8().orEmpty()
        assertTrue(body.contains("\"loginTicket\":\"$TICKET\""))
        assertTrue(body.contains(store.deviceId()))
    }

    @Test
    fun `만료되거나 재사용된 ticket은 session을 만들지 않는다`() = runTest {
        server.enqueue(
            MockResponse(code = 401, body = errorJson(AuthErrorCodes.LOGIN_TICKET_EXPIRED, retryable = false)),
        )

        val failed = repository.completeLogin(successLink()) as AuthUiState.LoginFailed

        assertEquals(AuthErrorCodes.LOGIN_TICKET_EXPIRED, failed.code)
        assertNull(store.loadSession())
    }

    @Test
    fun `기기 불일치 403은 session을 만들지 않는다`() = runTest {
        server.enqueue(
            MockResponse(code = 403, body = errorJson(AuthErrorCodes.DEVICE_MISMATCH, retryable = false)),
        )

        val failed = repository.completeLogin(successLink()) as AuthUiState.LoginFailed

        assertEquals(AuthErrorCodes.DEVICE_MISMATCH, failed.code)
        assertNull(store.loadSession())
    }

    @Test
    fun `통신 실패로 교환이 끊겨도 부분 session이 남지 않는다`() = runTest {
        server.close()

        val state = repository.completeLogin(successLink())

        assertTrue(state is AuthUiState.LoginFailed)
        assertNull(store.loadSession())
    }

    @Test
    fun `인증 App Link가 아니면 상태를 바꾸지 않는다`() = runTest {
        repository.restore()

        val state = repository.completeLogin("https://evil.example/auth/kakao/complete#loginTicket=$TICKET")

        assertEquals(AuthUiState.SignedOut, state)
    }

    @Test
    fun `로그인 취소는 SignedOut으로 돌아가고 session을 남기지 않는다`() = runTest {
        server.enqueue(MockResponse(code = 201, body = transactionJson()))
        repository.startLogin()

        assertEquals(AuthUiState.SignedOut, repository.cancelLogin())
        assertNull(store.loadSession())
    }

    // --- fixtures ---

    private fun successLink() = "https://$APP_LINK_HOST/auth/kakao/complete#loginTicket=$TICKET"

    private fun failureLink(code: String) = "https://$APP_LINK_HOST/auth/kakao/complete?error=$code"

    private fun transactionJson() = """
        {"success":true,
         "data":{"transactionId":"$TRANSACTION_ID","authorizationUrl":"$AUTHORIZATION_URL","expiresIn":600},
         "meta":{"requestId":"$REQUEST_ID"}}
    """.trimIndent()

    private fun tokenJson(nickname: String?) = """
        {"success":true,
         "data":{"accessToken":"$ACCESS_TOKEN","expiresIn":3600,
                 "refreshToken":"$REFRESH_TOKEN","refreshExpiresIn":2592000,
                 "user":{"userId":"$USER_ID",
                         "nickname":${if (nickname == null) "null" else "\"$nickname\""},
                         "profileImageUrl":null,"provider":"KAKAO"}},
         "meta":{"requestId":"$REQUEST_ID"}}
    """.trimIndent()

    private fun errorJson(code: String, retryable: Boolean) = """
        {"success":false,
         "error":{"code":"$code","message":"진단용 설명","details":{},"retryable":$retryable},
         "meta":{"requestId":"$REQUEST_ID"}}
    """.trimIndent()

    private companion object {
        const val APP_LINK_HOST = "app.gilpick.example"
        const val TRANSACTION_ID = "11111111-2222-4333-8444-555555555555"
        const val REQUEST_ID = "22222222-3333-4444-8555-666666666666"
        const val USER_ID = "33333333-4444-4555-8666-777777777777"
        const val AUTHORIZATION_URL = "https://kauth.kakao.com/oauth/authorize?client_id=test&state=abc"
        const val ACCESS_TOKEN = "access-token-plaintext-value"
        const val TICKET = "11111111-2222-4333-8444-555555555555.abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"
        const val REFRESH_TOKEN = "44444444-5555-4666-8777-888888888888.abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"
    }
}
