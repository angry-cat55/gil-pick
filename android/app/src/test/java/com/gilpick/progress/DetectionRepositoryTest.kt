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
import java.io.File
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T009: repository의 401 갱신·replay, 오류 분류, `Idempotency-Key` 생성과 재시도 시 재사용 검증.
 *
 * F006 [ProgressRepositoryTest]와 같은 구조다. 이벤트 등록만 `Idempotency-Key` 대신 앱이 만든
 * `eventId`로 중복을 막는다는 점이 다르다.
 */
class DetectionRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val server = MockWebServer()
    private val authApi = ProgrammableAuthService()
    private lateinit var repository: DetectionRepository
    private val date = LocalDate.of(2026, 9, 8)

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
        repository = DetectionRepository(
            api = createDetectionRetrofit(server.url("/api/v1/").toString()).create(DetectionService::class.java),
            auth = auth,
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    // --- 요청 경로와 멱등 ---

    @Test
    fun `이벤트 등록은 ISO 날짜 경로로 요청하고 후보를 돌려준다`() = runTest {
        server.enqueue(MockResponse(code = 200, body = acceptedWithCandidateJson()))

        val result = registerDwell()

        val request = server.takeRequest()
        assertEquals("/api/v1/trips/$TRIP_ID/days/2026-09-08/progress/events", request.url.encodedPath)
        // 이벤트는 eventId로 중복을 막으므로 Idempotency-Key를 보내지 않는다.
        assertNull(request.headers["Idempotency-Key"])
        assertEquals(TRANSITION_ID, (result as AuthResult.Success).value.candidate!!.transitionId)
    }

    @Test
    fun `같은 확인 응답은 같은 Idempotency-Key로 나간다`() = runTest {
        server.enqueue(MockResponse(code = 200, body = confirmedJson()))
        server.enqueue(MockResponse(code = 200, body = confirmedJson()))

        repository.decide(TRANSITION_ID, TransitionDecision.CONFIRM)
        repository.decide(TRANSITION_ID, TransitionDecision.CONFIRM)

        assertEquals(server.takeRequest().headers["Idempotency-Key"], server.takeRequest().headers["Idempotency-Key"])
    }

    @Test
    fun `다른 응답이나 다른 전환은 다른 Idempotency-Key를 쓴다`() = runTest {
        server.enqueue(MockResponse(code = 200, body = confirmedJson()))
        server.enqueue(MockResponse(code = 200, body = cancelledJson()))
        server.enqueue(MockResponse(code = 200, body = undoneJson()))

        repository.decide(TRANSITION_ID, TransitionDecision.CONFIRM)
        repository.decide(TRANSITION_ID, TransitionDecision.NOT_ARRIVED)
        repository.undo(TRANSITION_ID)

        val confirm = server.takeRequest().headers["Idempotency-Key"]
        val notArrived = server.takeRequest().headers["Idempotency-Key"]
        val undo = server.takeRequest().headers["Idempotency-Key"]
        assertNotEquals(confirm, notArrived)
        assertNotEquals(confirm, undo)
    }

    // --- 401 갱신과 replay ---

    @Test
    fun `401이면 갱신 후 새 Token으로 한 번 replay하고 같은 body·멱등 키를 다시 보낸다`() = runTest {
        authApi.onRefresh = { refreshOk(access = SECOND_ACCESS, refresh = "session-2.refresh-token") }
        server.enqueue(MockResponse(code = 401, body = detectionErrorJson(DetectionErrorCodes.INVALID_ACCESS_TOKEN)))
        server.enqueue(MockResponse(code = 200, body = confirmedJson()))

        val result = repository.decide(TRANSITION_ID, TransitionDecision.CONFIRM)

        val first = server.takeRequest()
        val replayed = server.takeRequest()
        assertEquals("Bearer $FIRST_ACCESS", first.headers["Authorization"])
        assertEquals("Bearer $SECOND_ACCESS", replayed.headers["Authorization"])
        assertEquals(first.body!!.utf8(), replayed.body!!.utf8())
        assertEquals(first.headers["Idempotency-Key"], replayed.headers["Idempotency-Key"])
        assertEquals(1, authApi.refreshCount)
        assertEquals(TransitionStatus.CONFIRMED, (result as AuthResult.Success).value.status)
    }

    @Test
    fun `이벤트 등록도 replay 시 같은 eventId를 다시 보낸다`() = runTest {
        authApi.onRefresh = { refreshOk(access = SECOND_ACCESS, refresh = "session-2.refresh-token") }
        server.enqueue(MockResponse(code = 401, body = detectionErrorJson(DetectionErrorCodes.INVALID_ACCESS_TOKEN)))
        server.enqueue(MockResponse(code = 200, body = acceptedWithCandidateJson()))

        registerDwell()

        assertEquals(server.takeRequest().body!!.utf8(), server.takeRequest().body!!.utf8())
    }

    @Test
    fun `replay도 401이면 SessionExpired로 분류한다`() = runTest {
        authApi.onRefresh = { refreshOk(access = SECOND_ACCESS, refresh = "session-2.refresh-token") }
        server.enqueue(MockResponse(code = 401, body = detectionErrorJson(DetectionErrorCodes.INVALID_ACCESS_TOKEN)))
        server.enqueue(MockResponse(code = 401, body = detectionErrorJson(DetectionErrorCodes.INVALID_ACCESS_TOKEN)))

        assertEquals(DetectionError.SessionExpired, failure(repository.undo(TRANSITION_ID)))
        assertEquals(1, authApi.refreshCount)
    }

    // --- 오류 분류 ---

    @Test
    fun `409의 네 원인을 서로 다르게 분류한다`() = runTest {
        // 같은 409라도 다음 행동이 다르다. 재조회·안내·무시가 갈린다.
        server.enqueue(MockResponse(code = 409, body = detectionErrorJson(DetectionErrorCodes.TRANSITION_NOT_PENDING)))
        server.enqueue(MockResponse(code = 409, body = detectionErrorJson(DetectionErrorCodes.INVALID_DECISION)))
        server.enqueue(MockResponse(code = 409, body = detectionErrorJson(DetectionErrorCodes.UNDO_WINDOW_EXPIRED)))
        server.enqueue(MockResponse(code = 409, body = detectionErrorJson(DetectionErrorCodes.TRANSITION_NOT_UNDOABLE)))

        assertEquals(DetectionError.TransitionNotPending, failure(repository.decide(TRANSITION_ID, TransitionDecision.CONFIRM)))
        assertEquals(DetectionError.InvalidDecision, failure(repository.decide(TRANSITION_ID, TransitionDecision.STILL_HERE)))
        assertEquals(DetectionError.UndoWindowExpired, failure(repository.undo(TRANSITION_ID)))
        assertEquals(DetectionError.TransitionNotUndoable, failure(repository.undo(TRANSITION_ID)))
    }

    @Test
    fun `멱등 키 충돌과 소유권·없는 대상을 구분한다`() = runTest {
        server.enqueue(MockResponse(code = 409, body = detectionErrorJson(DetectionErrorCodes.IDEMPOTENCY_KEY_CONFLICT)))
        server.enqueue(MockResponse(code = 403, body = detectionErrorJson(DetectionErrorCodes.TRIP_FORBIDDEN)))
        server.enqueue(MockResponse(code = 404, body = detectionErrorJson(DetectionErrorCodes.TRIP_NOT_FOUND)))

        assertEquals(DetectionError.IdempotencyKeyConflict, failure(repository.decide(TRANSITION_ID, TransitionDecision.CONFIRM)))
        assertEquals(DetectionError.Forbidden, failure(registerDwell()))
        assertEquals(DetectionError.NotFound, failure(registerDwell()))
    }

    @Test
    fun `통신 실패는 Network로 분류한다`() = runTest {
        server.close()

        assertEquals(DetectionError.Network, failure(registerDwell()))
    }

    @Test
    fun `계약과 다른 오류 body와 5xx는 Unexpected로 구분된다`() {
        assertEquals(DetectionError.Unexpected, AuthError.Malformed(IOException("x")).toDetectionError())
        assertEquals(DetectionError.Unexpected, AuthError.Server("INTERNAL_ERROR", true, 500).toDetectionError())
        assertEquals(DetectionError.Unexpected, AuthError.Server(DetectionErrorCodes.INVALID_REQUEST, false, 400).toDetectionError())
        assertEquals(DetectionError.SessionExpired, AuthError.Server(AuthErrorCodes.INVALID_REFRESH_TOKEN, false, 401).toDetectionError())
    }

    private suspend fun registerDwell(): AuthResult<ProgressEventResultDto> {
        val request = dwellRequest()
        return repository.registerEvent(
            tripId = TRIP_ID,
            date = date,
            eventId = request.eventId,
            eventType = request.eventType,
            itemId = request.itemId,
            geofenceId = request.geofenceId,
            occurredAt = request.occurredAt,
            location = request.location,
        )
    }

    private fun failure(result: AuthResult<*>): DetectionError =
        (result as AuthResult.Failure).error.toDetectionError()

    private companion object {
        const val FIRST_ACCESS = "access-token-1"
        const val SECOND_ACCESS = "access-token-2"
    }
}
