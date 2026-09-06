package com.gilpick.itinerary

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
import java.time.LocalDate
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
 * T008: repository의 401 갱신·replay와 오류 분류 검증.
 *
 * HTTP 왕복은 MockWebServer로 만들어 갱신 후 replay가 실제 요청으로 나가는지와 오류 body의
 * `details`가 [ItineraryError]까지 전달되는지를 함께 본다. refresh endpoint만
 * [ProgrammableAuthService]로 대신한다.
 */
class ItineraryRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val server = MockWebServer()
    private val authApi = ProgrammableAuthService()
    private lateinit var repository: ItineraryRepository
    private lateinit var auth: AuthRepository

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
        repository = ItineraryRepository(
            api = createItineraryRetrofit(server.url("/api/v1/").toString()).create(ItineraryService::class.java),
            auth = auth,
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `날짜별 조회는 ISO 날짜 경로로 요청하고 일정을 돌려준다`() = runTest {
        server.enqueue(MockResponse(code = 200, body = dayEnvelopeJson(dayJson())))

        val result = repository.getDayItinerary(TRIP_ID, LocalDate.of(2026, 9, 8))

        assertEquals("/api/v1/trips/$TRIP_ID/days/2026-09-08/itinerary", server.takeRequest().url.encodedPath)
        val day = (result as AuthResult.Success).value
        assertEquals(3, day.version)
        assertEquals(2, day.items.size)
    }

    @Test
    fun `401이면 갱신 후 새 Token으로 한 번 replay하고 그 결과를 돌려준다`() = runTest {
        authApi.onRefresh = { refreshOk(access = SECOND_ACCESS, refresh = "session-2.refresh-token") }
        server.enqueue(MockResponse(code = 401, body = errorJson(ItineraryErrorCodes.INVALID_ACCESS_TOKEN)))
        server.enqueue(MockResponse(code = 201, body = dayEnvelopeJson(dayJson(version = 1))))

        val result = repository.saveDayItinerary(
            tripId = TRIP_ID,
            date = LocalDate.of(2026, 9, 8),
            version = 0,
            items = saveRequest().items,
            idempotencyKey = IDEMPOTENCY_KEY,
        )

        val first = server.takeRequest()
        val replayed = server.takeRequest()
        assertEquals("Bearer $FIRST_ACCESS", first.headers["Authorization"])
        assertEquals("Bearer $SECOND_ACCESS", replayed.headers["Authorization"])
        // replay는 같은 저장 시도이므로 Idempotency-Key와 body가 그대로여야 한다.
        assertEquals(IDEMPOTENCY_KEY, replayed.headers["Idempotency-Key"])
        assertEquals(first.body!!.utf8(), replayed.body!!.utf8())
        assertEquals(1, authApi.refreshCount)
        val success = result as AuthResult.Success
        assertEquals(201, success.httpStatus)
        assertEquals(1, success.value.version)
    }

    @Test
    fun `replay도 401이면 갱신을 반복하지 않고 SessionExpired로 분류한다`() = runTest {
        authApi.onRefresh = { refreshOk(access = SECOND_ACCESS, refresh = "session-2.refresh-token") }
        server.enqueue(MockResponse(code = 401, body = errorJson(ItineraryErrorCodes.INVALID_ACCESS_TOKEN)))
        server.enqueue(MockResponse(code = 401, body = errorJson(ItineraryErrorCodes.INVALID_ACCESS_TOKEN)))

        val result = repository.getOverview(TRIP_ID)

        assertEquals(2, server.requestCount)
        assertEquals(1, authApi.refreshCount)
        assertEquals(ItineraryError.SessionExpired, failure(result))
        assertNull(auth.currentSession())
    }

    @Test
    fun `VERSION_CONFLICT는 자동 재저장 대상으로 구분된다`() = runTest {
        server.enqueue(MockResponse(code = 409, body = errorJson(ItineraryErrorCodes.VERSION_CONFLICT)))

        assertEquals(ItineraryError.VersionConflict, failure(save()))
        assertEquals(0, authApi.refreshCount)
    }

    @Test
    fun `INVALID_ITINERARY는 violations를 담아 구분된다`() = runTest {
        server.enqueue(
            MockResponse(
                code = 422,
                body = errorJson(
                    ItineraryErrorCodes.INVALID_ITINERARY,
                    details = """{"violations":[{"field":"plannedStayMinutes","itemIndex":0,"reason":"multiple of 30"}]}""",
                ),
            ),
        )

        val error = failure(save()) as ItineraryError.InvalidItinerary

        assertEquals(1, error.violations.size)
        assertEquals("plannedStayMinutes", error.violations[0].field)
        assertEquals(0, error.violations[0].itemIndex)
    }

    @Test
    fun `ITINERARY_ITEM_LOCKED는 잠긴 itemId를 담아 VERSION_CONFLICT와 구분된다`() = runTest {
        server.enqueue(
            MockResponse(
                code = 409,
                body = errorJson(ItineraryErrorCodes.ITINERARY_ITEM_LOCKED, details = """{"itemId":"$ITEM_ID"}"""),
            ),
        )

        assertEquals(ItineraryError.ItemLocked(ITEM_ID), failure(save()))
    }

    @Test
    fun `TRIP_FORBIDDEN과 TRIP_NOT_FOUND는 각각 구분된다`() = runTest {
        server.enqueue(MockResponse(code = 403, body = errorJson(ItineraryErrorCodes.TRIP_FORBIDDEN)))
        server.enqueue(MockResponse(code = 404, body = errorJson(ItineraryErrorCodes.TRIP_NOT_FOUND)))

        assertEquals(ItineraryError.Forbidden, failure(repository.getOverview(TRIP_ID)))
        assertEquals(ItineraryError.NotFound, failure(repository.getOverview(TRIP_ID)))
    }

    @Test
    fun `통신 실패는 network 오류로 구분되고 갱신을 시도하지 않는다`() = runTest {
        // 응답 없이 연결을 끊어 IOException을 만든다.
        server.close()

        val result = save()

        assertTrue((result as AuthResult.Failure).error is AuthError.Offline)
        assertEquals(ItineraryError.Network, result.error.toItineraryError())
        assertEquals(0, authApi.refreshCount)
    }

    @Test
    fun `계약과 다른 오류 body와 5xx는 Unexpected로 구분된다`() {
        assertEquals(ItineraryError.Unexpected, AuthError.Malformed(IOException("x")).toItineraryError())
        assertEquals(ItineraryError.Unexpected, AuthError.Server("INTERNAL_ERROR", true, 500).toItineraryError())
        assertEquals(ItineraryError.Unexpected, AuthError.Server(ItineraryErrorCodes.INVALID_REQUEST, false, 400).toItineraryError())
        assertEquals(
            ItineraryError.SessionExpired,
            AuthError.Server(AuthErrorCodes.INVALID_REFRESH_TOKEN, false, 401).toItineraryError(),
        )
    }

    private suspend fun save(): AuthResult<DayItineraryDto> = repository.saveDayItinerary(
        tripId = TRIP_ID,
        date = LocalDate.of(2026, 9, 8),
        version = 3,
        items = saveRequest().items,
        idempotencyKey = IDEMPOTENCY_KEY,
    )

    private fun failure(result: AuthResult<*>): ItineraryError =
        (result as AuthResult.Failure).error.toItineraryError()

    private companion object {
        const val FIRST_ACCESS = "access-token-1"
        const val SECOND_ACCESS = "access-token-2"
    }
}
