package com.gilpick.replacement

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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T008: repository의 401 갱신·replay, `Idempotency-Key` 생성과 재시도 시 재사용, 오류 분류 검증.
 *
 * F007 [com.gilpick.progress.DetectionRepositoryTest]와 같은 구조다. 폐기(REPL-003)와
 * 되돌리기(REPL-004)는 계약상 `Idempotency-Key`를 받지 않는다는 점이 다르다.
 */
class ReplacementRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val server = MockWebServer()
    private val authApi = ProgrammableAuthService()
    private lateinit var repository: ReplacementRepository

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
        repository = ReplacementRepository(
            api = createReplacementRetrofit(server.url("/api/v1/").toString())
                .create(ReplacementService::class.java),
            auth = auth,
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    // --- 요청 경로와 멱등 ---

    @Test
    fun `미리보기 생성은 감지 결과 경로로 요청하고 비교를 돌려준다`() = runTest {
        server.enqueue(MockResponse(code = 200, body = routePreviewJson()))

        val result = createPreview()

        val request = server.takeRequest()
        assertEquals("/api/v1/detections/$REPL_DETECTION_ID/route-previews", request.url.encodedPath)
        assertEquals(REPL_PREVIEW_ID, (result as AuthResult.Success).value.previewId)
        assertEquals(1500, result.value.comparison.totalDurationSeconds.after)
    }

    @Test
    fun `같은 미리보기 요청은 같은 Idempotency-Key로 나간다`() = runTest {
        // FR-011. 통신 실패 후 `다시 시도`가 중복 미리보기를 만들지 않는다.
        server.enqueue(MockResponse(code = 200, body = routePreviewJson()))
        server.enqueue(MockResponse(code = 200, body = routePreviewJson()))

        createPreview()
        createPreview()

        assertEquals(server.takeRequest().headers["Idempotency-Key"], server.takeRequest().headers["Idempotency-Key"])
    }

    @Test
    fun `같은 승인 요청은 같은 Idempotency-Key로 나간다`() = runTest {
        server.enqueue(MockResponse(code = 200, body = replacementJson()))
        server.enqueue(MockResponse(code = 200, body = replacementJson()))

        repository.approvePreview(REPL_PREVIEW_ID)
        repository.approvePreview(REPL_PREVIEW_ID)

        assertEquals(server.takeRequest().headers["Idempotency-Key"], server.takeRequest().headers["Idempotency-Key"])
    }

    @Test
    fun `후보 식별자나 일정 version이 다르면 다른 Idempotency-Key를 쓴다`() = runTest {
        // 같은 장소라도 추천 후보와 직접 검색은 서버가 다르게 검증한다(FR-004).
        repeat(3) { server.enqueue(MockResponse(code = 200, body = routePreviewJson())) }

        createPreview(candidateId = REPL_CANDIDATE_ID)
        createPreview(candidateId = null)
        createPreview(candidateId = REPL_CANDIDATE_ID, scheduleVersion = REPL_SCHEDULE_VERSION + 1)

        val withCandidate = server.takeRequest().headers["Idempotency-Key"]
        val withoutCandidate = server.takeRequest().headers["Idempotency-Key"]
        val otherVersion = server.takeRequest().headers["Idempotency-Key"]
        assertNotEquals(withCandidate, withoutCandidate)
        assertNotEquals(withCandidate, otherVersion)
    }

    @Test
    fun `폐기와 되돌리기는 Idempotency-Key 없이 나간다`() = runTest {
        // 대상 상태로 결과가 정해지는 자연 멱등이라 계약에 header가 없다.
        server.enqueue(MockResponse(code = 204))
        server.enqueue(MockResponse(code = 200, body = undoResultJson()))

        repository.rejectPreview(REPL_PREVIEW_ID)
        repository.undoReplacement(REPL_REPLACEMENT_ID)

        assertNull(server.takeRequest().headers["Idempotency-Key"])
        assertNull(server.takeRequest().headers["Idempotency-Key"])
    }

    @Test
    fun `폐기는 body 없는 204를 성공으로 읽는다`() = runTest {
        server.enqueue(MockResponse(code = 204))

        assertTrue(repository.rejectPreview(REPL_PREVIEW_ID) is AuthResult.Success)
    }

    // --- 401 갱신과 replay ---

    @Test
    fun `401이면 갱신 후 새 Token으로 한 번 replay하고 같은 body·멱등 키를 다시 보낸다`() = runTest {
        authApi.onRefresh = { refreshOk(access = SECOND_ACCESS, refresh = "session-2.refresh-token") }
        server.enqueue(MockResponse(code = 401, body = replacementErrorJson(ReplacementErrorCodes.INVALID_ACCESS_TOKEN)))
        server.enqueue(MockResponse(code = 200, body = routePreviewJson()))

        val result = createPreview()

        val first = server.takeRequest()
        val replayed = server.takeRequest()
        assertEquals("Bearer $FIRST_ACCESS", first.headers["Authorization"])
        assertEquals("Bearer $SECOND_ACCESS", replayed.headers["Authorization"])
        assertEquals(first.body!!.utf8(), replayed.body!!.utf8())
        assertEquals(first.headers["Idempotency-Key"], replayed.headers["Idempotency-Key"])
        assertEquals(1, authApi.refreshCount)
        assertEquals(REPL_PREVIEW_ID, (result as AuthResult.Success).value.previewId)
    }

    @Test
    fun `승인도 replay 시 같은 멱등 키를 다시 보낸다`() = runTest {
        // 중복 승인은 일정을 두 번 바꾸므로 replay에서 key가 바뀌면 안 된다(FR-011, SC-003).
        authApi.onRefresh = { refreshOk(access = SECOND_ACCESS, refresh = "session-2.refresh-token") }
        server.enqueue(MockResponse(code = 401, body = replacementErrorJson(ReplacementErrorCodes.INVALID_ACCESS_TOKEN)))
        server.enqueue(MockResponse(code = 200, body = replacementJson()))

        repository.approvePreview(REPL_PREVIEW_ID)

        assertEquals(server.takeRequest().headers["Idempotency-Key"], server.takeRequest().headers["Idempotency-Key"])
    }

    @Test
    fun `replay도 401이면 SessionExpired로 분류한다`() = runTest {
        authApi.onRefresh = { refreshOk(access = SECOND_ACCESS, refresh = "session-2.refresh-token") }
        repeat(2) {
            server.enqueue(MockResponse(code = 401, body = replacementErrorJson(ReplacementErrorCodes.INVALID_ACCESS_TOKEN)))
        }

        assertEquals(ReplacementError.SessionExpired, failure(repository.approvePreview(REPL_PREVIEW_ID)))
        assertEquals(1, authApi.refreshCount)
    }

    // --- 오류 분류 ---

    @Test
    fun `승인 실패 원인 네 가지를 서로 다르게 분류한다`() = runTest {
        // SC-007. 같은 409라도 다음 행동이 다르다(다시 만들기·후보 목록으로).
        server.enqueue(MockResponse(code = 409, body = replacementErrorJson(ReplacementErrorCodes.VERSION_CONFLICT)))
        server.enqueue(MockResponse(code = 409, body = replacementErrorJson(ReplacementErrorCodes.PREVIEW_EXPIRED)))
        server.enqueue(MockResponse(code = 409, body = replacementErrorJson(ReplacementErrorCodes.ITEM_ALREADY_VISITED)))
        server.enqueue(MockResponse(code = 409, body = replacementErrorJson(ReplacementErrorCodes.ALTERNATIVE_UNAVAILABLE)))

        assertEquals(ReplacementError.ScheduleChanged, failure(repository.approvePreview(REPL_PREVIEW_ID)))
        assertEquals(ReplacementError.PreviewExpired, failure(repository.approvePreview(REPL_PREVIEW_ID)))
        assertEquals(ReplacementError.AlreadyVisited, failure(repository.approvePreview(REPL_PREVIEW_ID)))
        assertEquals(ReplacementError.AlternativeUnavailable, failure(repository.approvePreview(REPL_PREVIEW_ID)))
    }

    @Test
    fun `밀려난 미리보기와 이미 승인한 미리보기와 끝난 감지를 구분한다`() = runTest {
        server.enqueue(MockResponse(code = 409, body = replacementErrorJson(ReplacementErrorCodes.PREVIEW_SUPERSEDED)))
        server.enqueue(MockResponse(code = 409, body = replacementErrorJson(ReplacementErrorCodes.ALREADY_APPROVED)))
        server.enqueue(MockResponse(code = 409, body = replacementErrorJson(ReplacementErrorCodes.DETECTION_NOT_ACTIVE)))

        assertEquals(ReplacementError.PreviewSuperseded, failure(repository.approvePreview(REPL_PREVIEW_ID)))
        assertEquals(ReplacementError.AlreadyApproved, failure(repository.rejectPreview(REPL_PREVIEW_ID)))
        assertEquals(ReplacementError.DetectionNotActive, failure(createPreview()))
    }

    @Test
    fun `되돌리기 거절 두 가지를 서로 다르게 분류한다`() = runTest {
        // 두 경우 모두 일정 편집으로 안내해야 하지만 문구가 다르다(FR-019·UI-007).
        server.enqueue(MockResponse(code = 409, body = replacementErrorJson(ReplacementErrorCodes.UNDO_EXPIRED)))
        server.enqueue(MockResponse(code = 409, body = replacementErrorJson(ReplacementErrorCodes.FOLLOW_UP_CHANGE_EXISTS)))

        assertEquals(ReplacementError.UndoExpired, failure(repository.undoReplacement(REPL_REPLACEMENT_ID)))
        assertEquals(ReplacementError.FollowUpChangeExists, failure(repository.undoReplacement(REPL_REPLACEMENT_ID)))
    }

    @Test
    fun `경로 계산 실패는 재시도 가능 여부를 보존한 RouteUnavailable이다`() = runTest {
        // FR-007. 미리보기 생성에서만 일어나고 일정은 그대로다.
        server.enqueue(
            MockResponse(code = 502, body = replacementErrorJson(ReplacementErrorCodes.ROUTE_PROVIDER_ERROR, retryable = true)),
        )
        server.enqueue(
            MockResponse(code = 504, body = replacementErrorJson(ReplacementErrorCodes.ROUTE_PROVIDER_TIMEOUT, retryable = false)),
        )

        assertEquals(ReplacementError.RouteUnavailable(retryable = true), failure(createPreview()))
        assertEquals(ReplacementError.RouteUnavailable(retryable = false), failure(createPreview()))
    }

    @Test
    fun `통신 실패는 Network로 분류한다`() = runTest {
        server.close()

        assertEquals(ReplacementError.Network, failure(createPreview()))
    }

    @Test
    fun `안내가 갈리지 않는 실패는 Unexpected로 모은다`() {
        // 400·403·404와 남은 409는 화면이 같은 안내와 같은 다음 행동을 준다.
        assertEquals(ReplacementError.Unexpected, serverError(ReplacementErrorCodes.INVALID_CANDIDATE, 400))
        assertEquals(ReplacementError.Unexpected, serverError(ReplacementErrorCodes.TRIP_FORBIDDEN, 403))
        assertEquals(ReplacementError.Unexpected, serverError(ReplacementErrorCodes.PREVIEW_NOT_FOUND, 404))
        assertEquals(ReplacementError.Unexpected, serverError(ReplacementErrorCodes.PLACE_ALREADY_IN_SCHEDULE, 409))
        assertEquals(ReplacementError.Unexpected, serverError("INTERNAL_ERROR", 500))
        assertEquals(ReplacementError.Unexpected, AuthError.Malformed(IOException("x")).toReplacementError())
    }

    @Test
    fun `계약에 없는 401과 refresh 오류도 SessionExpired로 확정한다`() {
        assertEquals(ReplacementError.SessionExpired, serverError(AuthErrorCodes.INVALID_REFRESH_TOKEN, 401))
        assertEquals(ReplacementError.SessionExpired, serverError("SOMETHING_ELSE", 401))
    }

    private suspend fun createPreview(
        candidateId: String? = REPL_CANDIDATE_ID,
        scheduleVersion: Int = REPL_SCHEDULE_VERSION,
    ) = repository.createPreview(
        detectionId = REPL_DETECTION_ID,
        placeId = REPL_PLACE_ID,
        candidateId = candidateId,
        scheduleVersion = scheduleVersion,
    )

    private fun failure(result: AuthResult<*>): ReplacementError =
        (result as AuthResult.Failure).error.toReplacementError()

    private fun serverError(code: String, httpStatus: Int, retryable: Boolean = false): ReplacementError =
        AuthError.Server(code, retryable, httpStatus).toReplacementError()

    private companion object {
        const val FIRST_ACCESS = "access-token-1"
        const val SECOND_ACCESS = "access-token-2"
    }
}
