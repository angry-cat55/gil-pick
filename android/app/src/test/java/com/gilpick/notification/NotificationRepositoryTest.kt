package com.gilpick.notification

import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthErrorCodes
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthResult
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T014: repository의 401 갱신·replay와 401/403/404/IO → [NotificationError] 매핑, DEV-001 body·DEV-002 204 검증.
 *
 * F009 `AlternativeRepositoryTest`와 같은 구조다.
 */
class NotificationRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val server = MockWebServer()
    private val authApi = ProgrammableAuthService()
    private lateinit var auth: AuthRepository
    private lateinit var repository: NotificationRepository

    @Before
    fun setUp() = runTest {
        server.start()
        val store = AuthSessionStore(
            AuthSessionStore.createDataStore(File(tempFolder.root, AuthSessionStore.FILE_NAME)),
            FakeSessionCipher(),
        )
        auth = AuthRepository(
            store = store,
            api = authApi,
            appLinkHandler = AuthAppLinkHandler("app.gilpick.example"),
        )
        auth.onSignedIn(
            sessionId = "session-1",
            userId = "user-1",
            nickname = null,
            profileImageUrl = null,
            accessToken = FIRST_ACCESS,
            refreshToken = "session-1.refresh-token",
            accessExpiresAtEpochSeconds = 3_600,
            refreshExpiresAtEpochSeconds = 2_592_000,
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

    // --- 요청과 응답 ---

    @Test
    fun `목록은 read·cursor를 보내고 pagination을 페이지로 옮긴다`() = runTest {
        server.enqueue(MockResponse(code = 200, body = notificationListJson(nextCursor = "c2Vjb25k", hasNext = true)))

        val page = (repository.listNotifications(cursor = "Zmlyc3Q=", read = false) as AuthResult.Success).value

        val request = server.takeRequest()
        assertEquals("/api/v1/notifications", request.url.encodedPath)
        assertEquals("false", request.url.queryParameter("read"))
        assertEquals("Zmlyc3Q=", request.url.queryParameter("cursor"))
        assertEquals("Bearer $FIRST_ACCESS", request.headers["Authorization"])
        assertEquals(listOf(NotificationType.PLACE_CHANGE_SUGGESTION, NotificationType.ARRIVAL_CHECK), page.items.map { it.type })
        assertEquals(DETECTION_ID, page.items[0].detectionId)
        assertNull(page.items[1].detectionId)
        assertEquals("c2Vjb25k", page.nextCursor)
        assertTrue(page.hasNext)
    }

    @Test
    fun `읽음 처리는 PATCH이고 모두 읽음은 updated를 돌려준다`() = runTest {
        server.enqueue(MockResponse(code = 200, body = envelope("""{"notificationId": "$NOTIFICATION_ID", "read": true}""")))
        server.enqueue(MockResponse(code = 200, body = envelope("""{"updated": 3}""")))

        val one = (repository.markRead(NOTIFICATION_ID) as AuthResult.Success).value
        val all = (repository.markAllRead() as AuthResult.Success).value

        val first = server.takeRequest()
        assertEquals("PATCH", first.method)
        assertEquals("/api/v1/notifications/$NOTIFICATION_ID/read", first.url.encodedPath)
        assertEquals("/api/v1/notifications/read-all", server.takeRequest().url.encodedPath)
        assertTrue(one.read)
        assertEquals(3, all.updated)
    }

    @Test
    fun `토큰 등록은 기기 ID·platform을 담아 PUT하고 해제는 204를 성공으로 본다`() = runTest {
        server.enqueue(MockResponse(code = 200, body = envelope("""{"deviceId": "d", "registered": true}""")))
        server.enqueue(MockResponse(code = 204))
        val deviceId = auth.deviceId()

        val registered = repository.registerFcmToken("fcm-token-1")
        val cleared = repository.unregisterFcmToken()

        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/api/v1/devices/fcm-token", put.url.encodedPath)
        val body = put.body!!.utf8()
        assertTrue(body, body.contains("\"deviceId\":\"$deviceId\""))
        assertTrue(body, body.contains("\"fcmToken\":\"fcm-token-1\""))
        assertTrue(body, body.contains("\"platform\":\"ANDROID\""))
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/api/v1/devices/$deviceId/fcm-token", delete.url.encodedPath)
        assertTrue((registered as AuthResult.Success).value.registered)
        assertTrue(cleared is AuthResult.Success)
    }

    // --- 401 갱신과 replay ---

    @Test
    fun `401이면 갱신 후 새 Token으로 한 번 replay한다`() = runTest {
        authApi.onRefresh = { refreshOk(access = SECOND_ACCESS, refresh = "session-2.refresh-token") }
        server.enqueue(MockResponse(code = 401, body = errorJson(NotificationErrorCodes.INVALID_ACCESS_TOKEN)))
        server.enqueue(MockResponse(code = 200, body = notificationListJson()))

        val result = repository.listNotifications()

        assertEquals("Bearer $FIRST_ACCESS", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer $SECOND_ACCESS", server.takeRequest().headers["Authorization"])
        assertEquals(1, authApi.refreshCount)
        assertEquals(2, (result as AuthResult.Success).value.items.size)
    }

    @Test
    fun `replay도 401이면 SessionExpired로 분류한다`() = runTest {
        authApi.onRefresh = { refreshOk(access = SECOND_ACCESS, refresh = "session-2.refresh-token") }
        server.enqueue(MockResponse(code = 401, body = errorJson(NotificationErrorCodes.INVALID_ACCESS_TOKEN)))
        server.enqueue(MockResponse(code = 401, body = errorJson(NotificationErrorCodes.INVALID_ACCESS_TOKEN)))

        assertEquals(NotificationError.SessionExpired, failure(repository.markAllRead()))
        assertEquals(1, authApi.refreshCount)
    }

    // --- 오류 분류 ---

    @Test
    fun `404와 403을 알림·기기 code 모두에서 구분한다`() = runTest {
        server.enqueue(MockResponse(code = 404, body = errorJson(NotificationErrorCodes.NOTIFICATION_NOT_FOUND)))
        server.enqueue(MockResponse(code = 403, body = errorJson(NotificationErrorCodes.NOTIFICATION_FORBIDDEN)))
        server.enqueue(MockResponse(code = 404, body = errorJson(NotificationErrorCodes.DEVICE_SESSION_NOT_FOUND)))
        server.enqueue(MockResponse(code = 403, body = errorJson(NotificationErrorCodes.DEVICE_FORBIDDEN)))

        assertEquals(NotificationError.NotFound, failure(repository.markRead(NOTIFICATION_ID)))
        assertEquals(NotificationError.Forbidden, failure(repository.markRead(NOTIFICATION_ID)))
        assertEquals(NotificationError.NotFound, failure(repository.registerFcmToken("t")))
        assertEquals(NotificationError.Forbidden, failure(repository.unregisterFcmToken()))
    }

    @Test
    fun `통신 실패는 Network로 분류한다`() = runTest {
        server.close()

        assertEquals(NotificationError.Network, failure(repository.listNotifications()))
    }

    @Test
    fun `계약과 다른 오류 body와 400·5xx는 Unexpected로 구분된다`() {
        assertEquals(NotificationError.Unexpected, AuthError.Malformed(IOException("x")).toNotificationError())
        assertEquals(NotificationError.Unexpected, AuthError.Server("INTERNAL_ERROR", true, 500).toNotificationError())
        assertEquals(NotificationError.Unexpected, AuthError.Server(NotificationErrorCodes.INVALID_REQUEST, false, 400).toNotificationError())
        assertEquals(NotificationError.SessionExpired, AuthError.Server(AuthErrorCodes.INVALID_REFRESH_TOKEN, false, 401).toNotificationError())
        // 계약 밖 code라도 상태 코드로 소유권·존재 여부는 구분한다.
        assertEquals(NotificationError.NotFound, AuthError.Server("GONE", false, 404).toNotificationError())
        assertEquals(NotificationError.Forbidden, AuthError.Server("NOPE", false, 403).toNotificationError())
        assertTrue(NotificationError.Network.retryable)
        assertTrue(!NotificationError.SessionExpired.retryable)
    }

    private fun failure(result: AuthResult<*>): NotificationError =
        (result as AuthResult.Failure).error.toNotificationError()

    private companion object {
        const val FIRST_ACCESS = "access-token-1"
        const val SECOND_ACCESS = "access-token-2"
    }
}
