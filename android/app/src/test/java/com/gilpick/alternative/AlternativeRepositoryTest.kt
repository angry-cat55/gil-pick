package com.gilpick.alternative

import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthErrorCodes
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthResult
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.ErrorDetails
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T011: repository의 401 갱신·replay와 401/403/404/409/502/504/IO → [AlternativeError] 매핑 검증.
 *
 * F007 `DetectionRepositoryTest`와 같은 구조다. F009 endpoint는 모두 body가 없고
 * 거절(DETECT-004)은 상태 기반 멱등이라 `Idempotency-Key` 검증이 없다.
 */
class AlternativeRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val server = MockWebServer()
    private val authApi = ProgrammableAuthService()
    private lateinit var repository: AlternativeRepository

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
        repository = AlternativeRepository(
            api = createAlternativeRetrofit(server.url("/api/v1/").toString()).create(AlternativeService::class.java),
            auth = auth,
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    // --- 요청과 페이지 ---

    @Test
    fun `감지 목록은 status 필터를 보내고 pagination을 페이지로 옮긴다`() = runTest {
        server.enqueue(MockResponse(code = 200, body = detectionListJson()))

        val page = (repository.listDetections(TRIP_ID, status = DetectionStatus.ACTIVE) as AuthResult.Success).value

        val request = server.takeRequest()
        assertEquals("/api/v1/trips/$TRIP_ID/detections", request.url.encodedPath)
        assertEquals("ACTIVE", request.url.queryParameter("status"))
        assertEquals("Bearer $FIRST_ACCESS", request.headers["Authorization"])
        assertEquals(listOf("경복궁", "인사동거리"), page.items.map { it.placeName })
        assertEquals(false, page.hasNext)
        assertNull(page.nextCursor)
    }

    @Test
    fun `직접 검색은 query·cursor를 보내고 다음 cursor를 돌려준다`() = runTest {
        server.enqueue(MockResponse(code = 200, body = searchJson()))

        val page = (repository.searchAlternatives(DETECTION_ID, query = "궁궐", cursor = "Zmlyc3Q=") as AuthResult.Success).value

        val request = server.takeRequest()
        assertEquals("/api/v1/detections/$DETECTION_ID/alternatives/search", request.url.encodedPath)
        assertEquals("궁궐", request.url.queryParameter("query"))
        assertEquals("Zmlyc3Q=", request.url.queryParameter("cursor"))
        assertEquals(3, page.items.size)
        assertEquals("c2Vjb25k", page.nextCursor)
        assertEquals(true, page.hasNext)
    }

    @Test
    fun `거절은 Idempotency-Key 없이 POST하고 결과 상태를 돌려준다`() = runTest {
        server.enqueue(MockResponse(code = 200, body = dismissJson()))

        val result = repository.dismissDetection(DETECTION_ID)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertNull(request.headers["Idempotency-Key"])
        assertEquals(DetectionStatus.DISMISSED, (result as AuthResult.Success).value.status)
    }

    // --- 401 갱신과 replay ---

    @Test
    fun `401이면 갱신 후 새 Token으로 한 번 replay한다`() = runTest {
        authApi.onRefresh = { refreshOk(access = SECOND_ACCESS, refresh = "session-2.refresh-token") }
        server.enqueue(MockResponse(code = 401, body = alternativeErrorJson(AlternativeErrorCodes.INVALID_ACCESS_TOKEN)))
        server.enqueue(MockResponse(code = 200, body = alternativesJson()))

        val result = repository.listAlternatives(DETECTION_ID)

        assertEquals("Bearer $FIRST_ACCESS", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer $SECOND_ACCESS", server.takeRequest().headers["Authorization"])
        assertEquals(1, authApi.refreshCount)
        assertEquals(2, (result as AuthResult.Success).value.items.size)
    }

    @Test
    fun `replay도 401이면 SessionExpired로 분류한다`() = runTest {
        authApi.onRefresh = { refreshOk(access = SECOND_ACCESS, refresh = "session-2.refresh-token") }
        server.enqueue(MockResponse(code = 401, body = alternativeErrorJson(AlternativeErrorCodes.INVALID_ACCESS_TOKEN)))
        server.enqueue(MockResponse(code = 401, body = alternativeErrorJson(AlternativeErrorCodes.INVALID_ACCESS_TOKEN)))

        assertEquals(AlternativeError.SessionExpired, failure(repository.getDetection(DETECTION_ID)))
        assertEquals(1, authApi.refreshCount)
    }

    // --- 오류 분류 ---

    @Test
    fun `409 DETECTION_NOT_ACTIVE는 details의 현재 상태를 담아 NotActive로 분류한다`() = runTest {
        server.enqueue(
            MockResponse(
                code = 409,
                body = alternativeErrorJson(AlternativeErrorCodes.DETECTION_NOT_ACTIVE, details = """{"status": "DISMISSED"}"""),
            ),
        )
        server.enqueue(MockResponse(code = 409, body = alternativeErrorJson(AlternativeErrorCodes.DETECTION_NOT_ACTIVE)))

        assertEquals(AlternativeError.NotActive(DetectionStatus.DISMISSED), failure(repository.listAlternatives(DETECTION_ID)))
        // details가 없거나 모르는 값이면 상태 없이 NotActive다.
        assertEquals(AlternativeError.NotActive(null), failure(repository.searchAlternatives(DETECTION_ID, "궁궐")))
    }

    @Test
    fun `404와 403을 구분한다`() = runTest {
        server.enqueue(MockResponse(code = 404, body = alternativeErrorJson(AlternativeErrorCodes.DETECTION_NOT_FOUND)))
        server.enqueue(MockResponse(code = 403, body = alternativeErrorJson(AlternativeErrorCodes.TRIP_FORBIDDEN)))

        assertEquals(AlternativeError.NotFound, failure(repository.getDetection(DETECTION_ID)))
        assertEquals(AlternativeError.Forbidden, failure(repository.dismissDetection(DETECTION_ID)))
    }

    @Test
    fun `502·504 TourAPI 실패는 retryable을 담아 ProviderFailed로 분류한다`() = runTest {
        // FR-021: TourAPI 최종 실패는 빈 목록으로 위장하지 않고 추천 실패로 드러난다.
        server.enqueue(MockResponse(code = 502, body = alternativeErrorJson(AlternativeErrorCodes.TOUR_API_FAILED, retryable = true)))
        server.enqueue(MockResponse(code = 504, body = alternativeErrorJson(AlternativeErrorCodes.TOUR_API_TIMEOUT, retryable = true)))
        server.enqueue(MockResponse(code = 429, body = alternativeErrorJson(AlternativeErrorCodes.TOUR_API_RATE_LIMITED, retryable = false)))

        assertEquals(AlternativeError.ProviderFailed(retryable = true), failure(repository.listAlternatives(DETECTION_ID)))
        assertEquals(AlternativeError.ProviderFailed(retryable = true), failure(repository.listAlternatives(DETECTION_ID)))
        assertEquals(AlternativeError.ProviderFailed(retryable = false), failure(repository.searchAlternatives(DETECTION_ID, "궁궐")))
    }

    @Test
    fun `통신 실패는 Network로 분류한다`() = runTest {
        server.close()

        assertEquals(AlternativeError.Network, failure(repository.listAlternatives(DETECTION_ID)))
    }

    @Test
    fun `계약과 다른 오류 body와 400·5xx는 Unexpected로 구분된다`() {
        assertEquals(AlternativeError.Unexpected, AuthError.Malformed(IOException("x")).toAlternativeError())
        assertEquals(AlternativeError.Unexpected, AuthError.Server("INTERNAL_ERROR", true, 500).toAlternativeError())
        assertEquals(AlternativeError.Unexpected, AuthError.Server(AlternativeErrorCodes.INVALID_REQUEST, false, 400).toAlternativeError())
        assertEquals(AlternativeError.Unexpected, AuthError.Server(AlternativeErrorCodes.INVALID_CURSOR, false, 400).toAlternativeError())
        // 계약 밖 code라도 502·504면 추천 실패다.
        assertEquals(AlternativeError.ProviderFailed(true), AuthError.Server("BAD_GATEWAY", true, 502).toAlternativeError())
        assertEquals(AlternativeError.SessionExpired, AuthError.Server(AuthErrorCodes.INVALID_REFRESH_TOKEN, false, 401).toAlternativeError())
        assertEquals(
            AlternativeError.NotActive(DetectionStatus.RESOLVED),
            AuthError.Server(AlternativeErrorCodes.DETECTION_NOT_ACTIVE, false, 409, ErrorDetails(status = "RESOLVED")).toAlternativeError(),
        )
    }

    private fun failure(result: AuthResult<*>): AlternativeError =
        (result as AuthResult.Failure).error.toAlternativeError()

    private companion object {
        const val FIRST_ACCESS = "access-token-1"
        const val SECOND_ACCESS = "access-token-2"
    }
}
