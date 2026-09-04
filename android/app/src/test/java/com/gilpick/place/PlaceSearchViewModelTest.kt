package com.gilpick.place

import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthErrorCodes
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthService
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeAuthService
import com.gilpick.auth.FakeSessionCipher
import com.gilpick.auth.ProgrammableAuthService
import com.gilpick.auth.errorResponse
import java.io.File
import java.io.IOException
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T012·T016·T027: 검색 화면 상태 전이 검증.
 *
 * `spec.md` US1 Acceptance Scenario 1~3·5~8과 FR-003a(명시적 검색), FR-005(dedupe),
 * FR-012(추가 조회 실패 시 기존 결과 유지), UI-003(교체)이 대상이다. US3 Scenario 1~4는
 * 장애를 empty와 구분하고 첫 페이지·추가 조회 재시도를 분리하는지 본다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceSearchViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val service = FakePlaceService()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- 명시적 검색: Scenario 7, FR-003a ---

    @Test
    fun `입력과 칩 변경만으로는 검색하지 않는다`() = runTest {
        val viewModel = newViewModel()

        viewModel.onQueryChange("경복궁")
        viewModel.onCategoryChange(PlaceCategory.HISTORY_CULTURE)
        advanceUntilIdle()

        assertEquals(PlaceSearchPhase.Idle, viewModel.state.value.phase)
        assertTrue(service.searchCalls.isEmpty())
    }

    @Test
    fun `검색을 실행하면 공백을 뗀 draft 조건이 committed가 되어 요청된다`() = runTest {
        service.onSearch = { placePage(listOf(place("tourapi:1", name = "경복궁"))) }
        val viewModel = newViewModel()
        viewModel.onQueryChange("  경복궁 ")
        viewModel.onCategoryChange(PlaceCategory.HISTORY_CULTURE)

        viewModel.search()
        assertEquals(PlaceSearchPhase.Loading, viewModel.state.value.phase)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(PlaceSearchPhase.Content, state.phase)
        assertEquals("경복궁", state.committedQuery)
        assertEquals(PlaceCategory.HISTORY_CULTURE, state.committedCategory)
        assertEquals(listOf(FakePlaceService.SearchCall("경복궁", PlaceCategory.HISTORY_CULTURE, null)), service.searchCalls)
        assertEquals("경복궁", state.results.single().name)
    }

    @Test
    fun `검색 후 draft를 바꿔도 committed 조건과 결과는 그대로다`() = runTest {
        service.onSearch = { placePage(listOf(place("tourapi:1"))) }
        val viewModel = newViewModel()
        viewModel.onQueryChange("경복궁")
        viewModel.search()
        advanceUntilIdle()

        viewModel.onQueryChange("남산")
        viewModel.onCategoryChange(PlaceCategory.NATURE)

        val state = viewModel.state.value
        assertEquals("남산", state.query)
        assertEquals("경복궁", state.committedQuery)
        assertEquals(1, state.results.size)
        assertEquals(1, service.searchCalls.size)
    }

    // --- 조건 검증: Scenario 8, FR-003·FR-003b ---

    @Test
    fun `조건이 없으면 요청하지 않고 안내한다`() = runTest {
        val viewModel = newViewModel()

        viewModel.search()
        advanceUntilIdle()

        assertEquals(PlaceSearchPhase.Invalid(InvalidReason.NO_CONDITION), viewModel.state.value.phase)
        assertTrue(service.searchCalls.isEmpty())
    }

    @Test
    fun `공백 제거 후 1글자 키워드는 category가 있어도 요청하지 않는다`() = runTest {
        val viewModel = newViewModel()
        viewModel.onQueryChange(" 궁 ")
        viewModel.onCategoryChange(PlaceCategory.HISTORY_CULTURE)

        viewModel.search()
        advanceUntilIdle()

        assertEquals(PlaceSearchPhase.Invalid(InvalidReason.TOO_SHORT), viewModel.state.value.phase)
        assertTrue(service.searchCalls.isEmpty())
    }

    @Test
    fun `category만으로는 키워드 없이 검색한다`() = runTest {
        service.onSearch = { placePage(listOf(place("tourapi:1"))) }
        val viewModel = newViewModel()
        viewModel.onCategoryChange(PlaceCategory.CAFE)

        viewModel.search()
        advanceUntilIdle()

        assertEquals(FakePlaceService.SearchCall(null, PlaceCategory.CAFE, null), service.searchCalls.single())
        assertEquals(PlaceSearchPhase.Content, viewModel.state.value.phase)
    }

    // --- 빈 결과: Scenario 6 ---

    @Test
    fun `결과가 없으면 empty가 되고 카테고리로 찾기는 검색 전 상태로 돌린다`() = runTest {
        service.onSearch = { placePage(emptyList()) }
        val viewModel = newViewModel()
        viewModel.onQueryChange("없는곳")
        viewModel.search()
        advanceUntilIdle()
        assertEquals(PlaceSearchPhase.Empty, viewModel.state.value.phase)

        viewModel.onSearchByCategory()

        val state = viewModel.state.value
        assertEquals(PlaceSearchPhase.Idle, state.phase)
        assertEquals("", state.query)
        assertTrue(state.results.isEmpty())
    }

    // --- 교체: UI-003 ---

    @Test
    fun `새 검색은 앞선 결과를 즉시 비우고 새 결과로 교체한다`() = runTest {
        service.onSearch = { placePage(listOf(place("tourapi:1", name = "첫 검색"))) }
        val viewModel = newViewModel()
        viewModel.onQueryChange("첫번째")
        viewModel.search()
        advanceUntilIdle()

        service.onSearch = { placePage(listOf(place("tourapi:2", name = "둘째 검색"))) }
        viewModel.onQueryChange("두번째")
        viewModel.search()
        assertTrue(viewModel.state.value.results.isEmpty())
        advanceUntilIdle()

        assertEquals(listOf("둘째 검색"), viewModel.state.value.results.map { it.name })
    }

    // --- 추가 조회: Scenario 5, FR-005, FR-012 ---

    @Test
    fun `다음 페이지는 기존 결과 뒤에 붙고 placeId가 겹치는 항목은 버린다`() = runTest {
        service.onSearch = { call ->
            if (call.cursor == null) placePage(listOf(place("tourapi:1"), place("tourapi:2")), nextCursor = "c2")
            else placePage(listOf(place("tourapi:2"), place("tourapi:3")))
        }
        val viewModel = newViewModel()
        viewModel.onQueryChange("경복궁")
        viewModel.search()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.hasNext)

        viewModel.loadMore()
        assertTrue(viewModel.state.value.loadingMore)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(listOf("tourapi:1", "tourapi:2", "tourapi:3"), state.results.map { it.placeId })
        assertFalse(state.hasNext)
        assertFalse(state.loadingMore)
        assertEquals("c2", service.searchCalls.last().cursor)
        assertEquals("경복궁", service.searchCalls.last().query)
    }

    @Test
    fun `마지막 페이지에서는 loadMore가 요청하지 않는다`() = runTest {
        service.onSearch = { placePage(listOf(place("tourapi:1"))) }
        val viewModel = newViewModel()
        viewModel.onQueryChange("경복궁")
        viewModel.search()
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(1, service.searchCalls.size)
    }

    @Test
    fun `추가 조회가 실패해도 기존 결과는 남고 재시도로만 다시 요청한다`() = runTest {
        service.onSearch = { call ->
            if (call.cursor == null) placePage(listOf(place("tourapi:1")), nextCursor = "c2")
            else throw IOException("연결 실패")
        }
        val viewModel = newViewModel()
        viewModel.onQueryChange("경복궁")
        viewModel.search()
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()
        var state = viewModel.state.value
        assertEquals(PlaceSearchPhase.Content, state.phase)
        assertEquals(1, state.results.size)
        assertEquals(PlaceError(PlaceErrorKind.NETWORK, retryable = true), state.loadMoreError)

        // 목록 끝에 다시 닿아도 자동으로 재시도하지 않는다.
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(2, service.searchCalls.size)

        service.onSearch = { placePage(listOf(place("tourapi:2"))) }
        viewModel.retryLoadMore()
        advanceUntilIdle()
        state = viewModel.state.value
        assertNull(state.loadMoreError)
        assertEquals(2, state.results.size)
        // 추가 조회 재시도는 첫 페이지가 아니라 실패한 cursor로 다시 요청한다.
        assertEquals("c2", service.searchCalls.last().cursor)
    }

    // --- 실패와 재시도: US3 ---

    @Test
    fun `첫 페이지 실패는 원인과 함께 failed가 되고 재시도하면 같은 조건으로 다시 요청한다`() = runTest {
        service.onSearch = { placeError(503, PlaceErrorCodes.TOUR_API_TIMEOUT, retryable = true) }
        val viewModel = newViewModel()
        viewModel.onQueryChange("경복궁")
        viewModel.search()
        advanceUntilIdle()

        val failed = viewModel.state.value.phase as PlaceSearchPhase.Failed
        assertEquals(PlaceError(PlaceErrorKind.TIMEOUT, retryable = true), failed.error)

        service.onSearch = { placePage(listOf(place("tourapi:1"))) }
        viewModel.retry()
        assertEquals(PlaceSearchPhase.Loading, viewModel.state.value.phase)
        advanceUntilIdle()

        assertEquals(PlaceSearchPhase.Content, viewModel.state.value.phase)
        assertEquals(service.searchCalls[0], service.searchCalls[1])
    }

    @Test
    fun `오류 code별로 원인과 retryable을 보존하고 empty로 표시하지 않는다`() = runTest {
        val cases = listOf(
            Triple(504, PlaceErrorCodes.TOUR_API_TIMEOUT, true) to PlaceError(PlaceErrorKind.TIMEOUT, retryable = true),
            Triple(502, PlaceErrorCodes.TOUR_API_FAILED, true) to PlaceError(PlaceErrorKind.PROVIDER_FAILED, retryable = true),
            Triple(502, PlaceErrorCodes.TOUR_API_FAILED, false) to PlaceError(PlaceErrorKind.PROVIDER_FAILED, retryable = false),
            Triple(429, PlaceErrorCodes.TOUR_API_RATE_LIMITED, false) to PlaceError(PlaceErrorKind.RATE_LIMITED, retryable = false),
            Triple(400, PlaceErrorCodes.INVALID_REQUEST, false) to PlaceError(PlaceErrorKind.INVALID_REQUEST, retryable = false),
        )
        // 같은 temp 파일에 DataStore를 여러 개 열 수 없어 repository는 한 번만 만든다.
        val repository = repository()
        for ((response, expected) in cases) {
            val (status, code, retryable) = response
            service.onSearch = { placeError(status, code, retryable) }
            val viewModel = PlaceSearchViewModel(repository)
            viewModel.onQueryChange("경복궁")
            viewModel.search()
            advanceUntilIdle()

            val phase = viewModel.state.value.phase
            assertEquals(code, PlaceSearchPhase.Failed(expected), phase)
        }
    }

    @Test
    fun `인증 만료는 갱신 거절 뒤 session expired가 된다`() = runTest {
        // 401을 받으면 F001이 갱신을 한 번 시도한다. 갱신도 거절되어야 자격 무효가 확정된다.
        val authService = ProgrammableAuthService().apply {
            onRefresh = { errorResponse(401, AuthErrorCodes.INVALID_REFRESH_TOKEN) }
        }
        service.onSearch = { placeError(401, PlaceErrorCodes.INVALID_ACCESS_TOKEN) }
        val viewModel = PlaceSearchViewModel(repository(authService))
        viewModel.onQueryChange("경복궁")
        viewModel.search()
        advanceUntilIdle()

        val failed = viewModel.state.value.phase as PlaceSearchPhase.Failed
        assertEquals(PlaceError(PlaceErrorKind.SESSION_EXPIRED, retryable = false), failed.error)
        assertEquals(1, authService.refreshCount)
    }

    @Test
    fun `추가 조회 실패는 원인을 담고 첫 페이지 재시도와 분리된다`() = runTest {
        service.onSearch = { call ->
            if (call.cursor == null) placePage(listOf(place("tourapi:1")), nextCursor = "c2")
            else placeError(429, PlaceErrorCodes.TOUR_API_RATE_LIMITED)
        }
        val viewModel = newViewModel()
        viewModel.onQueryChange("경복궁")
        viewModel.search()
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        var state = viewModel.state.value
        assertEquals(PlaceSearchPhase.Content, state.phase)
        assertEquals(listOf("tourapi:1"), state.results.map { it.placeId })
        assertEquals(PlaceError(PlaceErrorKind.RATE_LIMITED, retryable = false), state.loadMoreError)

        // 추가 조회가 실패했다고 첫 페이지 재시도가 되지는 않는다. 사용자가 새로 검색하면 그때 비운다.
        viewModel.search()
        assertNull(viewModel.state.value.loadMoreError)
        assertEquals(PlaceSearchPhase.Loading, viewModel.state.value.phase)
        advanceUntilIdle()
        state = viewModel.state.value
        assertEquals(PlaceSearchPhase.Content, state.phase)
        assertNull(service.searchCalls.last().cursor)
    }

    @Test
    fun `추가 조회 중 인증 만료는 기존 결과를 유지하고 session expired를 알린다`() = runTest {
        val authService = ProgrammableAuthService().apply {
            onRefresh = { errorResponse(401, AuthErrorCodes.INVALID_REFRESH_TOKEN) }
        }
        service.onSearch = { call ->
            if (call.cursor == null) placePage(listOf(place("tourapi:1")), nextCursor = "c2")
            else placeError(401, PlaceErrorCodes.INVALID_ACCESS_TOKEN)
        }
        val viewModel = PlaceSearchViewModel(repository(authService))
        viewModel.onQueryChange("경복궁")
        viewModel.search()
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(PlaceSearchPhase.Content, state.phase)
        assertEquals(1, state.results.size)
        assertEquals(PlaceErrorKind.SESSION_EXPIRED, state.loadMoreError?.kind)
    }

    private suspend fun newViewModel(): PlaceSearchViewModel = PlaceSearchViewModel(repository())

    /** 로그인된 session을 가진 repository를 만든다. `PlaceDetailViewModelTest`와 같다. */
    private suspend fun repository(authService: AuthService = FakeAuthService): PlaceRepository {
        val store = AuthSessionStore(
            AuthSessionStore.createDataStore(
                File(tempFolder.root, AuthSessionStore.FILE_NAME),
                scope = CoroutineScope(dispatcher + SupervisorJob()),
            ),
            FakeSessionCipher(),
        )
        val auth = AuthRepository(
            store = store,
            api = authService,
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
        return PlaceRepository(api = service, auth = auth)
    }
}
