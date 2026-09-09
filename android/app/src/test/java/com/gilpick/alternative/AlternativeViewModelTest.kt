package com.gilpick.alternative

import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeAuthService
import com.gilpick.auth.FakeSessionCipher
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T019: 대체 장소 화면 ViewModel 상태 전이 검증.
 *
 * `spec.md` US2, FR-015·FR-016, UI-005·UI-006과 data-model.md §3.1·§3.2가 대상이다. HTTP 왕복은
 * `AlternativeRepositoryTest`가 보므로 여기서는 [FakeAlternativeService]로 응답만 정한다.
 * 1초 대기 표시 지연은 composable(`AlternativePlacesScreen`)이 맡는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlternativeViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val service = FakeAlternativeService()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `상세와 후보를 병렬로 조회해 content가 된다`() = runTest {
        val detail = CompletableDeferred<Unit>()
        val candidates = CompletableDeferred<Unit>()
        var detailStarted = false
        var candidatesStarted = false
        service.onGetDetection = { detailStarted = true; detail.await(); ok(detectionDetailJson()) }
        service.onListAlternatives = { candidatesStarted = true; candidates.await(); ok(alternativesJson()) }

        val viewModel = newViewModel()
        assertEquals(AlternativeUiState.Loading, viewModel.state.value)
        runCurrent()

        // 상세 응답을 기다리는 동안 후보 조회가 이미 시작돼 있어야 병렬이다.
        assertTrue(detailStarted && candidatesStarted)
        candidates.complete(Unit)
        detail.complete(Unit)
        advanceUntilIdle()

        val content = viewModel.state.value as AlternativeUiState.Content
        assertEquals("경복궁", content.detection.placeName)
        assertEquals(listOf(1, 2), content.candidates.items.map { it.rank })
        assertFalse(content.refreshing)
        assertFalse(content.isEmpty)
    }

    @Test
    fun `후보가 없으면 빈 content다`() = runTest {
        service.onListAlternatives = { ok(alternativesEmptyJson()) }

        val viewModel = newViewModel()
        advanceUntilIdle()

        val content = viewModel.state.value as AlternativeUiState.Content
        assertTrue(content.isEmpty)
        assertEquals(CategoryMatchLevel.NONE, content.candidates.categoryMatchLevel)
    }

    @Test
    fun `후보 조회 실패는 재시도 가능 여부를 담은 error다`() = runTest {
        service.onListAlternatives = { fail(504, AlternativeErrorCodes.TOUR_API_TIMEOUT, retryable = true) }

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(AlternativeUiState.Error(AlternativeError.ProviderFailed(retryable = true), retryable = true), viewModel.state.value)
    }

    @Test
    fun `상세 조회 실패도 error다`() = runTest {
        service.onGetDetection = { fail(404, AlternativeErrorCodes.DETECTION_NOT_FOUND) }

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(AlternativeUiState.Error(AlternativeError.NotFound, retryable = false), viewModel.state.value)
    }

    @Test
    fun `통신 실패는 network error이고 다시 시도할 수 있다`() = runTest {
        service.onListAlternatives = { throw IOException("offline") }

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(AlternativeUiState.Error(AlternativeError.Network, retryable = true), viewModel.state.value)
    }

    @Test
    fun `409면 이미 처리된 감지 상태를 담은 closed다`() = runTest {
        service.onListAlternatives = {
            fail(409, AlternativeErrorCodes.DETECTION_NOT_ACTIVE, details = """{"status": "RESOLVED"}""")
        }

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(AlternativeUiState.Closed(DetectionStatus.RESOLVED), viewModel.state.value)
    }

    @Test
    fun `재조회 중에는 기존 content를 유지하고 refreshing만 켠다`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()
        val before = viewModel.state.value as AlternativeUiState.Content
        val gate = CompletableDeferred<Unit>()
        service.onListAlternatives = { gate.await(); ok(alternativesEmptyJson()) }

        viewModel.load()
        runCurrent()

        val during = viewModel.state.value as AlternativeUiState.Content
        assertTrue(during.refreshing)
        assertEquals(before.candidates, during.candidates)
        gate.complete(Unit)
        advanceUntilIdle()
        val after = viewModel.state.value as AlternativeUiState.Content
        assertFalse(after.refreshing)
        assertTrue(after.isEmpty)
    }

    @Test
    fun `재조회가 실패해도 보던 content를 지우지 않는다`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()
        val before = viewModel.state.value
        service.onListAlternatives = { throw IOException("offline") }

        viewModel.load()
        advanceUntilIdle()

        assertEquals(before, viewModel.state.value)
    }

    @Test
    fun `조회 중에 다시 부르면 조회를 겹치지 않는다`() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        service.onListAlternatives = { calls++; gate.await(); ok(alternativesJson()) }
        val viewModel = newViewModel()
        runCurrent()

        viewModel.load()
        viewModel.load()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, calls)
    }

    @Test
    fun `거절 성공은 dismissed를 한 번 켜고 요청 중 중복 호출은 무시한다`() = runTest {
        val gate = CompletableDeferred<Unit>()
        service.onDismiss = { gate.await(); ok(dismissJson()) }
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.dismiss()
        runCurrent()
        assertTrue((viewModel.state.value as AlternativeUiState.Content).dismissPending)
        viewModel.dismiss()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(DETECTION_ID), service.dismissCalls)
        assertTrue(viewModel.dismissed.value)
        assertFalse((viewModel.state.value as AlternativeUiState.Content).dismissPending)
    }

    @Test
    fun `거절 실패는 원인을 남기고 화면을 유지한다`() = runTest {
        service.onDismiss = { throw IOException("offline") }
        val viewModel = newViewModel()
        advanceUntilIdle()
        val before = viewModel.state.value as AlternativeUiState.Content

        viewModel.dismiss()
        advanceUntilIdle()

        val after = viewModel.state.value as AlternativeUiState.Content
        assertEquals(AlternativeError.Network, after.dismissError)
        assertFalse(after.dismissPending)
        assertEquals(before.candidates, after.candidates)
        assertFalse(viewModel.dismissed.value)

        viewModel.dismissError()
        assertNull((viewModel.state.value as AlternativeUiState.Content).dismissError)
    }

    @Test
    fun `이미 처리된 감지의 거절 응답은 closed다`() = runTest {
        service.onDismiss = { ok(dismissJson(status = "INVALIDATED", decidedAt = null)) }
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.dismiss()
        advanceUntilIdle()

        assertEquals(AlternativeUiState.Closed(DetectionStatus.INVALIDATED), viewModel.state.value)
        assertFalse(viewModel.dismissed.value)
    }

    @Test
    fun `후보 선택은 candidateId와 점수를 담는다`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()
        val content = viewModel.state.value as AlternativeUiState.Content
        val first = content.candidates.items[0]

        assertEquals(
            SelectedAlternative(
                detectionId = DETECTION_ID,
                placeId = "tourapi:126508",
                candidateId = CANDIDATE_ID,
                name = "창덕궁",
                distanceMeters = 820,
                displayScore = 87,
            ),
            viewModel.select(first),
        )
    }

    private suspend fun newViewModel(): AlternativeViewModel =
        AlternativeViewModel(repository = AlternativeRepository(api = service, auth = auth()), detectionId = DETECTION_ID)

    /** 로그인된 session을 가진 인증 계층. F005 `RouteViewModelTest`와 같다. */
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
            accessToken = "access-token",
            refreshToken = "session-1.refresh-token",
            accessExpiresAtEpochSeconds = 3_600,
            refreshExpiresAtEpochSeconds = 2_592_000,
        )
        return auth
    }
}
