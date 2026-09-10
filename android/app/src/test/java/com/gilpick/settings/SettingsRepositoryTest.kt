package com.gilpick.settings

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T009(repository): 401 갱신·replay와 오류 분류 검증.
 *
 * F001 `withAuthorizedCall`을 그대로 재사용하는지, 계약이 정한 두 실패(`400`·`401`)를 화면이
 * 쓸 수 있는 원인으로 좁히는지 본다. 다른 feature의 `*RepositoryTest`와 같은 구조다.
 */
class SettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val server = MockWebServer()
    private val authApi = ProgrammableAuthService()
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() = runTest {
        server.start()
        val store = AuthSessionStore(
            AuthSessionStore.createDataStore(File(tempFolder.root, AuthSessionStore.FILE_NAME)),
            FakeSessionCipher(),
        )
        val auth = AuthRepository(
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
        repository = SettingsRepository(
            api = createSettingsRetrofit(server.url("/api/v1/").toString()).create(SettingsService::class.java),
            auth = auth,
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `조회는 저장된 값을 돌려준다`() = runTest {
        server.enqueue(MockResponse(code = 200, body = preferenceJson(enabled = false)))

        val result = repository.getPreferences()

        assertEquals("/api/v1/users/me/preferences", server.takeRequest().url.encodedPath)
        assertFalse((result as AuthResult.Success).value.placeChangeSuggestionNotificationEnabled)
    }

    @Test
    fun `변경은 원하는 절대값을 보내고 서버가 저장한 값을 돌려준다`() = runTest {
        // FR-003. 다른 기기의 나중 변경 때문에 보낸 값과 저장된 값이 다를 수 있다.
        server.enqueue(MockResponse(code = 200, body = preferenceJson(enabled = true)))

        val result = repository.updatePreferences(enabled = false)

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertTrue(request.body!!.utf8().contains("\"placeChangeSuggestionNotificationEnabled\":false"))
        assertTrue((result as AuthResult.Success).value.placeChangeSuggestionNotificationEnabled)
    }

    @Test
    fun `401이면 갱신 후 새 Token으로 한 번 replay하고 같은 body를 다시 보낸다`() = runTest {
        authApi.onRefresh = { refreshOk(access = SECOND_ACCESS, refresh = "session-2.refresh-token") }
        server.enqueue(MockResponse(code = 401, body = settingsErrorJson(SettingsErrorCodes.INVALID_ACCESS_TOKEN)))
        server.enqueue(MockResponse(code = 200, body = preferenceJson(enabled = false)))

        val result = repository.updatePreferences(enabled = false)

        val first = server.takeRequest()
        val replayed = server.takeRequest()
        assertEquals("Bearer $FIRST_ACCESS", first.headers["Authorization"])
        assertEquals("Bearer $SECOND_ACCESS", replayed.headers["Authorization"])
        assertEquals(first.body!!.utf8(), replayed.body!!.utf8())
        assertEquals(1, authApi.refreshCount)
        assertFalse((result as AuthResult.Success).value.placeChangeSuggestionNotificationEnabled)
    }

    @Test
    fun `replay도 401이면 SessionExpired로 분류한다`() = runTest {
        authApi.onRefresh = { refreshOk(access = SECOND_ACCESS, refresh = "session-2.refresh-token") }
        repeat(2) {
            server.enqueue(MockResponse(code = 401, body = settingsErrorJson(SettingsErrorCodes.INVALID_ACCESS_TOKEN)))
        }

        assertEquals(SettingsError.SessionExpired, failure(repository.getPreferences()))
        assertEquals(1, authApi.refreshCount)
    }

    @Test
    fun `통신 실패는 Network로 분류한다`() = runTest {
        server.close()

        assertEquals(SettingsError.Network, failure(repository.getPreferences()))
    }

    @Test
    fun `잘못된 요청과 서버 오류와 계약과 다른 응답은 Unexpected로 모은다`() {
        assertEquals(SettingsError.Unexpected, AuthError.Server(SettingsErrorCodes.INVALID_REQUEST, false, 400).toSettingsError())
        assertEquals(SettingsError.Unexpected, AuthError.Server("INTERNAL_ERROR", true, 500).toSettingsError())
        assertEquals(SettingsError.Unexpected, AuthError.Malformed(IOException("x")).toSettingsError())
    }

    @Test
    fun `계약에 없는 401과 refresh 오류도 SessionExpired로 확정한다`() {
        assertEquals(SettingsError.SessionExpired, AuthError.Server(AuthErrorCodes.INVALID_REFRESH_TOKEN, false, 401).toSettingsError())
        assertEquals(SettingsError.SessionExpired, AuthError.Server("SOMETHING_ELSE", false, 401).toSettingsError())
    }

    private fun failure(result: AuthResult<*>): SettingsError =
        (result as AuthResult.Failure).error.toSettingsError()

    private companion object {
        const val FIRST_ACCESS = "access-token-1"
        const val SECOND_ACCESS = "access-token-2"
    }
}
