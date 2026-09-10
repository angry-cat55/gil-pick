package com.gilpick.notification

import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeSessionCipher
import com.gilpick.auth.ProgrammableAuthService
import com.gilpick.auth.refreshOk
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T019·T020: 기기 토큰 worker의 결과 정책과 인증 흐름 hook 검증.
 *
 * WorkManager 실행 자체는 다루지 않고, Context·Firebase 없이 검증할 수 있는 결과 분류와
 * `AuthRepository`가 로그인·갱신·로그아웃에서 예약 함수를 부르는지만 본다(F001
 * `SessionRevocationWorkerTest`와 같은 범위).
 */
class FcmTokenSyncWorkerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val server = MockWebServer()
    private val authApi = ProgrammableAuthService()
    private val syncCalls = mutableListOf<Unit>()
    private val clearCalls = mutableListOf<Unit>()
    private lateinit var auth: AuthRepository
    private lateinit var repository: NotificationRepository

    @Before
    fun setUp() {
        server.start()
        val store = AuthSessionStore(
            AuthSessionStore.createDataStore(File(tempFolder.root, AuthSessionStore.FILE_NAME)),
            FakeSessionCipher(),
        )
        auth = AuthRepository(
            store = store,
            api = authApi,
            appLinkHandler = AuthAppLinkHandler("app.gilpick.example"),
            syncPushToken = { syncCalls += Unit },
            clearPushToken = { clearCalls += Unit },
        )
        repository = NotificationRepository(
            api = createNotificationRetrofit(server.url("/api/v1/").toString()).create(NotificationService::class.java),
            auth = auth,
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    // --- 등록(DEV-001) ---

    @Test
    fun `토큰을 얻으면 DEV-001로 등록하고 완료한다`() = runTest {
        signIn()
        server.enqueue(MockResponse(code = 200, body = envelope("""{"deviceId": "d", "registered": true}""")))

        assertEquals(FcmTokenOutcome.COMPLETED, syncFcmToken(repository) { "fcm-token-1" })

        val body = server.takeRequest().body!!.utf8()
        assertTrue(body, body.contains("\"fcmToken\":\"fcm-token-1\""))
    }

    @Test
    fun `토큰 조회의 통신 실패는 재시도하고 그 밖의 실패는 끝낸다`() = runTest {
        signIn()

        assertEquals(FcmTokenOutcome.RETRY, syncFcmToken(repository) { throw IOException("SERVICE_NOT_AVAILABLE") })
        assertEquals(FcmTokenOutcome.TERMINAL, syncFcmToken(repository) { throw IllegalStateException("FirebaseApp 없음") })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `5xx는 재시도하고 403은 끝낸다`() = runTest {
        signIn()
        server.enqueue(MockResponse(code = 503, body = errorJson("INTERNAL_ERROR", retryable = true)))
        server.enqueue(MockResponse(code = 403, body = errorJson(NotificationErrorCodes.DEVICE_FORBIDDEN)))

        assertEquals(FcmTokenOutcome.RETRY, syncFcmToken(repository) { "t" })
        assertEquals(FcmTokenOutcome.TERMINAL, syncFcmToken(repository) { "t" })
    }

    @Test
    fun `로그인 전이면 요청 없이 끝낸다`() = runTest {
        assertEquals(FcmTokenOutcome.TERMINAL, syncFcmToken(repository) { "t" })
        assertEquals(0, server.requestCount)
    }

    // --- 해제(DEV-002) ---

    @Test
    fun `기기 토큰 삭제가 실패해도 DEV-002는 보낸다`() = runTest {
        signIn()
        server.enqueue(MockResponse(code = 204))
        var deleted = false

        val outcome = clearFcmToken(repository) {
            deleted = true
            throw IllegalStateException("FirebaseApp 없음")
        }

        assertEquals(FcmTokenOutcome.COMPLETED, outcome)
        assertTrue(deleted)
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `로그아웃 뒤 session이 없으면 DEV-002 없이 끝낸다`() = runTest {
        signIn()
        auth.logout()

        assertEquals(FcmTokenOutcome.TERMINAL, clearFcmToken(repository) {})
        assertEquals(0, server.requestCount)
    }

    // --- 인증 흐름 hook(T020) ---

    @Test
    fun `로그인·갱신은 등록을, 로그아웃은 해제를 예약한다`() = runTest {
        signIn()
        assertEquals(1, syncCalls.size)

        authApi.onRefresh = { _ -> refreshOk("access-2", "session-2.refresh-2") }
        auth.refresh()
        assertEquals(2, syncCalls.size)
        assertEquals(0, clearCalls.size)

        auth.logout()
        assertEquals(1, clearCalls.size)
    }

    private suspend fun signIn() {
        auth.onSignedIn(
            sessionId = "session-1",
            userId = "user-1",
            nickname = null,
            profileImageUrl = null,
            accessToken = "access-1",
            refreshToken = "session-1.refresh-1",
            accessExpiresAtEpochSeconds = 3_600,
            refreshExpiresAtEpochSeconds = 2_592_000,
        )
    }
}
