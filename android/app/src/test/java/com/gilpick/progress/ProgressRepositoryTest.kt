package com.gilpick.progress

import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthErrorCodes
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthResult
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeSessionCipher
import com.gilpick.auth.ProgrammableAuthService
import com.gilpick.auth.refreshOk
import com.gilpick.itinerary.ItemStatus
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T009: repository의 401 갱신·replay, 오류 분류, 요청별 `Idempotency-Key` 생성과 재시도 시
 * 재사용 검증.
 */
class ProgressRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val server = MockWebServer()
    private val authApi = ProgrammableAuthService()
    private lateinit var repository: ProgressRepository
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
        repository = ProgressRepository(
            api = createProgressRetrofit(server.url("/api/v1/").toString()).create(ProgressService::class.java),
            auth = auth,
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `조회는 ISO 날짜 경로로 요청하고 진행 현황을 돌려준다`() = runTest {
        server.enqueue(MockResponse(code = 200, body = inProgressJson()))

        val result = repository.getDayProgress(PROGRESS_TRIP_ID, date)

        assertEquals("/api/v1/trips/$PROGRESS_TRIP_ID/days/2026-09-08/progress", server.takeRequest().url.encodedPath)
        assertEquals(inProgress(), (result as AuthResult.Success).value)
    }

    @Test
    fun `401이면 갱신 후 새 Token으로 한 번 replay하고 같은 body·멱등 키를 다시 보낸다`() = runTest {
        authApi.onRefresh = { refreshOk(access = SECOND_ACCESS, refresh = "session-2.refresh-token") }
        server.enqueue(MockResponse(code = 401, body = progressErrorJson(ProgressErrorCodes.INVALID_ACCESS_TOKEN)))
        server.enqueue(MockResponse(code = 200, body = inProgressJson(progressVersion = 3)))

        val result = repository.updateStatus(P_ITEM_B, ItemStatus.ARRIVED, progressVersion = 2)

        val first = server.takeRequest()
        val replayed = server.takeRequest()
        assertEquals("Bearer $FIRST_ACCESS", first.headers["Authorization"])
        assertEquals("Bearer $SECOND_ACCESS", replayed.headers["Authorization"])
        assertEquals(first.body!!.utf8(), replayed.body!!.utf8())
        assertEquals(first.headers["Idempotency-Key"], replayed.headers["Idempotency-Key"])
        assertEquals(1, authApi.refreshCount)
        assertEquals(3, (result as AuthResult.Success).value.progressVersion)
    }

    @Test
    fun `replay도 401이면 SessionExpired로 분류한다`() = runTest {
        authApi.onRefresh = { refreshOk(access = SECOND_ACCESS, refresh = "session-2.refresh-token") }
        server.enqueue(MockResponse(code = 401, body = progressErrorJson(ProgressErrorCodes.INVALID_ACCESS_TOKEN)))
        server.enqueue(MockResponse(code = 401, body = progressErrorJson(ProgressErrorCodes.INVALID_ACCESS_TOKEN)))

        assertEquals(ProgressError.SessionExpired, failure(repository.getDayProgress(PROGRESS_TRIP_ID, date)))
        assertEquals(1, authApi.refreshCount)
    }

    @Test
    fun `멱등 키는 UUID이고 같은 요청의 재시도에는 재사용되며 다른 요청에는 새로 만든다`() = runTest {
        repeat(4) { server.enqueue(MockResponse(code = 200, body = inProgressJson(progressVersion = 1))) }
        val location = CurrentLocationDto(37.57, 126.97, 10.0, "2026-09-08T00:59:30Z")

        repository.startDay(PROGRESS_TRIP_ID, date, 0, location)
        repository.startDay(PROGRESS_TRIP_ID, date, 0, null) // 통신 실패 후 재시도. 위치는 다시 얻어 달라질 수 있다.
        repository.updateStatus(P_ITEM_B, ItemStatus.ARRIVED, 1)
        repository.updateStatus(P_ITEM_B, ItemStatus.ARRIVED, 2)

        val keys = List(4) { server.takeRequest().headers["Idempotency-Key"]!! }
        keys.forEach { UUID.fromString(it) }
        // 같은 대상·version의 재시도는 같은 key라 서버가 최초 결과를 돌려준다.
        assertEquals(keys[0], keys[1])
        // 대상·목표 상태·version이 다르면 새 key다.
        assertEquals(3, keys.toSet().size)
        assertNotEquals(keys[2], keys[3])
    }

    @Test
    fun `시작 오류 VERSION_CONFLICT·DAY_NOT_TODAY·DAY_EMPTY는 각각 구분된다`() = runTest {
        server.enqueue(MockResponse(code = 409, body = progressErrorJson(ProgressErrorCodes.VERSION_CONFLICT)))
        server.enqueue(MockResponse(code = 409, body = progressErrorJson(ProgressErrorCodes.DAY_NOT_TODAY)))
        server.enqueue(MockResponse(code = 422, body = progressErrorJson(ProgressErrorCodes.DAY_EMPTY)))

        assertEquals(ProgressError.VersionConflict, failure(repository.startDay(PROGRESS_TRIP_ID, date, 0, null)))
        assertEquals(ProgressError.DayNotToday, failure(repository.startDay(PROGRESS_TRIP_ID, date, 0, null)))
        assertEquals(ProgressError.DayEmpty, failure(repository.startDay(PROGRESS_TRIP_ID, date, 0, null)))
    }

    @Test
    fun `전환 오류 DAY_NOT_STARTED·INVALID_STATUS_TRANSITION·ITEM_NOT_FOUND는 각각 구분된다`() = runTest {
        server.enqueue(MockResponse(code = 409, body = progressErrorJson(ProgressErrorCodes.DAY_NOT_STARTED)))
        server.enqueue(MockResponse(code = 422, body = progressErrorJson(ProgressErrorCodes.INVALID_STATUS_TRANSITION)))
        server.enqueue(MockResponse(code = 404, body = progressErrorJson(ProgressErrorCodes.ITINERARY_ITEM_NOT_FOUND)))

        assertEquals(ProgressError.DayNotStarted, failure(repository.updateStatus(P_ITEM_A, ItemStatus.SKIPPED, 0)))
        assertEquals(ProgressError.InvalidTransition, failure(repository.updateStatus(P_ITEM_A, ItemStatus.COMPLETED, 2)))
        assertEquals(ProgressError.NotFound, failure(repository.updateStatus(P_ITEM_A, ItemStatus.PLANNED, 2)))
    }

    @Test
    fun `TRIP_FORBIDDEN과 TRIP_NOT_FOUND는 각각 구분된다`() = runTest {
        server.enqueue(MockResponse(code = 403, body = progressErrorJson(ProgressErrorCodes.TRIP_FORBIDDEN)))
        server.enqueue(MockResponse(code = 404, body = progressErrorJson(ProgressErrorCodes.TRIP_NOT_FOUND)))

        assertEquals(ProgressError.Forbidden, failure(repository.getDayProgress(PROGRESS_TRIP_ID, date)))
        assertEquals(ProgressError.NotFound, failure(repository.getDayProgress(PROGRESS_TRIP_ID, date)))
    }

    @Test
    fun `통신 실패는 network 오류로 구분되고 갱신을 시도하지 않는다`() = runTest {
        server.close()

        val result = repository.updateStatus(P_ITEM_B, ItemStatus.ARRIVED, 2)

        assertTrue((result as AuthResult.Failure).error is AuthError.Offline)
        assertEquals(ProgressError.Network, result.error.toProgressError())
        assertEquals(0, authApi.refreshCount)
    }

    @Test
    fun `계약과 다른 오류 body와 5xx는 Unexpected로 구분된다`() {
        assertEquals(ProgressError.Unexpected, AuthError.Malformed(IOException("x")).toProgressError())
        assertEquals(ProgressError.Unexpected, AuthError.Server("INTERNAL_ERROR", true, 500).toProgressError())
        assertEquals(ProgressError.Unexpected, AuthError.Server(ProgressErrorCodes.INVALID_REQUEST, false, 400).toProgressError())
        assertEquals(ProgressError.SessionExpired, AuthError.Server(AuthErrorCodes.INVALID_REFRESH_TOKEN, false, 401).toProgressError())
    }

    private fun failure(result: AuthResult<*>): ProgressError = (result as AuthResult.Failure).error.toProgressError()

    private companion object {
        const val FIRST_ACCESS = "access-token-1"
        const val SECOND_ACCESS = "access-token-2"
    }
}
