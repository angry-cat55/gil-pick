package com.gilpick.route

import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthErrorCodes
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthResult
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeSessionCipher
import com.gilpick.auth.ProgrammableAuthService
import com.gilpick.auth.refreshOk
import com.gilpick.itinerary.RouteStatus
import java.io.File
import java.io.IOException
import java.time.LocalDate
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
 * T010: repository의 401 갱신·replay와 오류 분류 검증.
 *
 * 네트워크 오류, `VERSION_CONFLICT`, `ROUTE_NOT_FAILED`는 [RouteError]로, provider 최종
 * 실패는 오류가 아닌 [RouteStatus.FAILED] 성공 값으로 구분된다.
 */
class RouteRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val server = MockWebServer()
    private val authApi = ProgrammableAuthService()
    private lateinit var repository: RouteRepository
    private lateinit var auth: AuthRepository
    private val date = LocalDate.of(2026, 9, 8)

    @Before
    fun setUp() = runTest {
        server.start()
        val store = AuthSessionStore(
            AuthSessionStore.createDataStore(File(tempFolder.root, AuthSessionStore.FILE_NAME)),
            FakeSessionCipher(),
        )
        auth = AuthRepository(store = store, api = authApi, appLinkHandler = AuthAppLinkHandler("app.gilpick.example"))
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
        repository = RouteRepository(
            api = createRouteRetrofit(server.url("/api/v1/").toString()).create(RouteService::class.java),
            auth = auth,
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `조회는 ISO 날짜 경로로 요청하고 경로를 돌려준다`() = runTest {
        server.enqueue(MockResponse(code = 200, body = routeEnvelopeJson("READY", route = readyRouteJson())))

        val result = repository.getDayRoute(ROUTE_TRIP_ID, date)

        assertEquals("/api/v1/trips/$ROUTE_TRIP_ID/days/2026-09-08/route", server.takeRequest().url.encodedPath)
        assertEquals(readyRoute(), (result as AuthResult.Success).value.route)
    }

    @Test
    fun `401이면 갱신 후 새 Token으로 한 번 replay하고 같은 body를 다시 보낸다`() = runTest {
        authApi.onRefresh = { refreshOk(access = SECOND_ACCESS, refresh = "session-2.refresh-token") }
        server.enqueue(MockResponse(code = 401, body = routeErrorJson(RouteErrorCodes.INVALID_ACCESS_TOKEN)))
        server.enqueue(MockResponse(code = 200, body = routeEnvelopeJson("READY", route = readyRouteJson())))

        val result = repository.retryDayRoute(ROUTE_TRIP_ID, date, scheduleVersion = 3)

        val first = server.takeRequest()
        val replayed = server.takeRequest()
        assertEquals("Bearer $FIRST_ACCESS", first.headers["Authorization"])
        assertEquals("Bearer $SECOND_ACCESS", replayed.headers["Authorization"])
        assertEquals(first.body!!.utf8(), replayed.body!!.utf8())
        assertEquals(1, authApi.refreshCount)
        assertEquals(RouteStatus.READY, (result as AuthResult.Success).value.routeStatus)
    }

    @Test
    fun `replay도 401이면 SessionExpired로 분류한다`() = runTest {
        authApi.onRefresh = { refreshOk(access = SECOND_ACCESS, refresh = "session-2.refresh-token") }
        server.enqueue(MockResponse(code = 401, body = routeErrorJson(RouteErrorCodes.INVALID_ACCESS_TOKEN)))
        server.enqueue(MockResponse(code = 401, body = routeErrorJson(RouteErrorCodes.INVALID_ACCESS_TOKEN)))

        assertEquals(RouteError.SessionExpired, failure(repository.getDayRoute(ROUTE_TRIP_ID, date)))
        assertEquals(1, authApi.refreshCount)
    }

    @Test
    fun `provider 최종 실패는 오류가 아니라 FAILED 성공 값이다`() = runTest {
        server.enqueue(MockResponse(code = 200, body = routeEnvelopeJson("FAILED", failure = failureJson())))

        val result = repository.retryDayRoute(ROUTE_TRIP_ID, date, scheduleVersion = 3)

        val data = (result as AuthResult.Success).value
        assertEquals(RouteStatus.FAILED, data.routeStatus)
        assertEquals(RouteFailureCodes.PROVIDER_TIMEOUT, data.failure!!.code)
    }

    @Test
    fun `VERSION_CONFLICT와 ROUTE_NOT_FAILED는 각각 구분된다`() = runTest {
        server.enqueue(MockResponse(code = 409, body = routeErrorJson(RouteErrorCodes.VERSION_CONFLICT)))
        server.enqueue(MockResponse(code = 409, body = routeErrorJson(RouteErrorCodes.ROUTE_NOT_FAILED)))

        assertEquals(RouteError.VersionConflict, failure(repository.retryDayRoute(ROUTE_TRIP_ID, date, 2)))
        assertEquals(RouteError.NotFailed, failure(repository.retryDayRoute(ROUTE_TRIP_ID, date, 3)))
    }

    @Test
    fun `TRIP_FORBIDDEN과 TRIP_NOT_FOUND는 각각 구분된다`() = runTest {
        server.enqueue(MockResponse(code = 403, body = routeErrorJson(RouteErrorCodes.TRIP_FORBIDDEN)))
        server.enqueue(MockResponse(code = 404, body = routeErrorJson(RouteErrorCodes.TRIP_NOT_FOUND)))

        assertEquals(RouteError.Forbidden, failure(repository.getDayRoute(ROUTE_TRIP_ID, date)))
        assertEquals(RouteError.NotFound, failure(repository.getDayRoute(ROUTE_TRIP_ID, date)))
    }

    @Test
    fun `통신 실패는 network 오류로 구분되고 갱신을 시도하지 않는다`() = runTest {
        server.close()

        val result = repository.retryDayRoute(ROUTE_TRIP_ID, date, 3)

        assertTrue((result as AuthResult.Failure).error is AuthError.Offline)
        assertEquals(RouteError.Network, result.error.toRouteError())
        assertEquals(0, authApi.refreshCount)
    }

    @Test
    fun `계약과 다른 오류 body와 5xx는 Unexpected로 구분된다`() {
        assertEquals(RouteError.Unexpected, AuthError.Malformed(IOException("x")).toRouteError())
        assertEquals(RouteError.Unexpected, AuthError.Server("INTERNAL_ERROR", true, 500).toRouteError())
        assertEquals(RouteError.Unexpected, AuthError.Server(RouteErrorCodes.INVALID_REQUEST, false, 400).toRouteError())
        assertEquals(RouteError.SessionExpired, AuthError.Server(AuthErrorCodes.INVALID_REFRESH_TOKEN, false, 401).toRouteError())
    }

    private fun failure(result: AuthResult<*>): RouteError = (result as AuthResult.Failure).error.toRouteError()

    private companion object {
        const val FIRST_ACCESS = "access-token-1"
        const val SECOND_ACCESS = "access-token-2"
    }
}
