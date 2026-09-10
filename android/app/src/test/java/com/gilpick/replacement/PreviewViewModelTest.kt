package com.gilpick.replacement

import com.gilpick.alternative.AlternativeRepository
import com.gilpick.alternative.FakeAlternativeService
import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeAuthService
import com.gilpick.auth.FakeSessionCipher
import com.gilpick.auth.SuccessEnvelope
import com.gilpick.itinerary.RouteStatus
import com.gilpick.route.DayRouteDto
import com.gilpick.route.FakeRouteService
import com.gilpick.route.RouteRepository
import com.gilpick.route.readyRoute
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * T015: 미리보기 화면 상태와 조회 흐름 검증.
 *
 * `spec.md` US1, FR-002·FR-007·FR-011과 data-model.md 4.1이 대상이다. HTTP 왕복과
 * `Idempotency-Key` 생성은 [ReplacementRepositoryTest]가 보므로 여기서는 Fake service로 응답만
 * 정한다. 1초 대기 표시 지연은 composable(`RoutePreviewScreen`)이 맡는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val replacementService = FakeReplacementService()
    private val detectionService = FakeAlternativeService()
    private val routeService = FakeRouteService()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        routeService.onGet = { readyDayRoute() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `조회가 끝나면 비교와 기존·변경 두 경로를 담은 Content가 된다`() = runTest {
        val viewModel = newViewModel()

        advanceUntilIdle()

        val content = viewModel.state.value as PreviewUiState.Content
        assertEquals(REPL_PREVIEW_ID, content.preview.previewId)
        assertEquals(1500, content.preview.comparison.totalDurationSeconds.after)
        // 기존 경로는 ROUTE-001에서 따로 받아 지도에 함께 그린다(UI-001).
        assertEquals(3, content.originalRoute!!.markers.size)
    }

    @Test
    fun `감지의 도착 예정 날짜로 경로를 조회하고 그 일정 version으로 미리보기를 만든다`() = runTest {
        routeService.onGet = { readyDayRoute(scheduleVersion = 7) }

        newViewModel()
        advanceUntilIdle()

        // 감지 상세의 eta 2026-09-09T14:00:00+09:00 → 그 항목이 놓인 여행 날짜.
        assertEquals(listOf("2026-09-09"), routeService.getCalls)
        assertEquals(7, replacementService.previewCalls.single().second.scheduleVersion)
    }

    @Test
    fun `추천 후보로 고른 장소는 candidateId를 그대로 보낸다`() = runTest {
        newViewModel()
        advanceUntilIdle()

        val body = replacementService.previewCalls.single().second
        assertEquals(REPL_CANDIDATE_ID, body.candidateId)
        assertEquals(REPL_PLACE_ID, body.placeId)
    }

    @Test
    fun `직접 검색으로 고른 장소는 candidateId 없이 보낸다`() = runTest {
        // FR-004. 같은 화면이 열리고 candidateId 유무만 요청에 반영된다.
        newViewModel(candidateId = null)
        advanceUntilIdle()

        assertNull(replacementService.previewCalls.single().second.candidateId)
    }

    @Test
    fun `경로가 계산되지 않은 날짜여도 일정 version으로 미리보기를 만들고 기존 경로만 비운다`() = runTest {
        // constitution IV. 지도에 기존 경로가 빠질 뿐 비교는 그대로 보인다.
        routeService.onGet = { dayRoute(RouteStatus.NOT_CALCULATED, route = null) }

        val viewModel = newViewModel()
        advanceUntilIdle()

        val content = viewModel.state.value as PreviewUiState.Content
        assertNull(content.originalRoute)
        assertEquals(REPL_PREVIEW_ID, content.preview.previewId)
    }

    @Test
    fun `경로 계산이 실패한 날짜도 기존 경로만 비우고 계속 진행한다`() = runTest {
        routeService.onGet = { dayRoute(RouteStatus.FAILED, route = null) }

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertNull((viewModel.state.value as PreviewUiState.Content).originalRoute)
        assertEquals(1, replacementService.previewCalls.size)
    }

    @Test
    fun `경로 계산 실패는 다른 원인과 구분되는 Error가 된다`() = runTest {
        // FR-007·UI-004. `다시 시도`와 `다른 후보 보기`를 함께 안내해야 해서 따로 구분한다.
        replacementService.onCreatePreview = {
            failure(ReplacementErrorCodes.ROUTE_PROVIDER_TIMEOUT, httpStatus = 504, retryable = true)
        }

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(
            PreviewUiState.Error(ReplacementError.RouteUnavailable(retryable = true)),
            viewModel.state.value,
        )
    }

    @Test
    fun `이미 처리된 감지와 이미 방문한 장소는 서로 다른 원인으로 온다`() = runTest {
        replacementService.onCreatePreview = { failure(ReplacementErrorCodes.DETECTION_NOT_ACTIVE, httpStatus = 409) }
        val closed = newViewModel()
        advanceUntilIdle()
        assertEquals(PreviewUiState.Error(ReplacementError.DetectionNotActive), closed.state.value)

        replacementService.onCreatePreview = { failure(ReplacementErrorCodes.ITEM_ALREADY_VISITED, httpStatus = 409) }
        val visited = newViewModel()
        advanceUntilIdle()
        assertEquals(PreviewUiState.Error(ReplacementError.AlreadyVisited), visited.state.value)
    }

    @Test
    fun `미리보기 앞의 준비 조회가 실패해도 같은 Error 분류를 쓴다`() = runTest {
        // 감지 상세·경로 조회는 미리보기를 만들기 위한 준비다. 화면은 원인을 똑같이 다룬다.
        routeService.onGet = { Response.error(500, errorBody()) }

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(PreviewUiState.Error(ReplacementError.Unexpected), viewModel.state.value)
        // 준비가 끝나지 않았으므로 미리보기를 만들지 않는다. 일정은 그대로다(FR-001).
        assertTrue(replacementService.previewCalls.isEmpty())
    }

    @Test
    fun `다시 시도는 Loading으로 돌아간 뒤 같은 요청을 다시 보낸다`() = runTest {
        // FR-011. 같은 인자로 다시 부르므로 repository가 같은 Idempotency-Key를 만든다.
        replacementService.onCreatePreview = { failure("INTERNAL_ERROR", httpStatus = 500) }
        val viewModel = newViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.state.value is PreviewUiState.Error)

        replacementService.onCreatePreview = { ok(routePreviewJson()) }
        viewModel.load()
        assertEquals(PreviewUiState.Loading, viewModel.state.value)

        advanceUntilIdle()
        assertTrue(viewModel.state.value is PreviewUiState.Content)
        val (firstKey, firstBody) = replacementService.previewCalls[0]
        val (secondKey, secondBody) = replacementService.previewCalls[1]
        assertEquals(firstKey, secondKey)
        assertEquals(firstBody, secondBody)
    }

    @Test
    fun `조회가 끝나기 전에는 Loading이고 다시 시도 연타로 요청이 겹치지 않는다`() = runTest {
        val viewModel = newViewModel()
        assertEquals(PreviewUiState.Loading, viewModel.state.value)

        viewModel.load()
        viewModel.load()
        advanceUntilIdle()

        assertEquals(1, replacementService.previewCalls.size)
    }

    private fun readyDayRoute(scheduleVersion: Int = REPL_SCHEDULE_VERSION) =
        dayRoute(RouteStatus.READY, route = readyRoute(scheduleVersion = scheduleVersion), scheduleVersion = scheduleVersion)

    private fun dayRoute(
        status: RouteStatus,
        route: com.gilpick.route.RouteDto?,
        scheduleVersion: Int = REPL_SCHEDULE_VERSION,
    ): Response<SuccessEnvelope<DayRouteDto>> = Response.success(
        SuccessEnvelope(
            success = true,
            data = DayRouteDto(
                tripId = REPL_TRIP_ID,
                date = "2026-09-09",
                scheduleVersion = scheduleVersion,
                routeStatus = status,
                route = route,
                failure = null,
            ),
            meta = com.gilpick.auth.ResponseMeta(requestId = REPL_REQUEST_ID),
        ),
    )

    private fun errorBody() = replacementErrorJson("INTERNAL_ERROR")
        .toResponseBody("application/json".toMediaType())

    private suspend fun newViewModel(candidateId: String? = REPL_CANDIDATE_ID) = PreviewViewModel(
        replacements = ReplacementRepository(api = replacementService, auth = auth()),
        detections = AlternativeRepository(api = detectionService, auth = auth()),
        routes = RouteRepository(api = routeService, auth = auth()),
        detectionId = REPL_DETECTION_ID,
        placeId = REPL_PLACE_ID,
        candidateId = candidateId,
    )

    /** 로그인된 session을 가진 인증 계층. F009 `AlternativeViewModelTest`와 같다. */
    private suspend fun auth(): AuthRepository {
        val store = AuthSessionStore(
            AuthSessionStore.createDataStore(
                File(tempFolder.newFolder(), AuthSessionStore.FILE_NAME),
                scope = CoroutineScope(dispatcher + SupervisorJob()),
            ),
            FakeSessionCipher(),
        )
        val auth = AuthRepository(
            store = store,
            api = FakeAuthService,
            appLinkHandler = AuthAppLinkHandler("app.gilpick.example"),
            scope = CoroutineScope(dispatcher + SupervisorJob()),
        )
        auth.onSignedIn(
            sessionId = "session-1",
            userId = "user-1",
            nickname = null,
            profileImageUrl = null,
            accessToken = "access-token-1",
            refreshToken = "session-1.refresh-token",
            accessExpiresAtEpochSeconds = 3_600,
            refreshExpiresAtEpochSeconds = 2_592_000,
        )
        return auth
    }
}
