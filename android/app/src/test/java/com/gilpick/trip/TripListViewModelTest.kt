package com.gilpick.trip

import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeAuthService
import com.gilpick.auth.FakeSessionCipher
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T017: 여행 목록 화면의 검색·필터 조합과 4상태 전이 검증.
 *
 * `spec.md` US2 Acceptance Scenario 2·3과 `docs/design/ui-guidelines.md` 9절의
 * `loading`·`empty`·`error`·`content` 표현이 대상이다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripListViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val service = FakeTripService()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `첫 조회 전에는 loading이다`() = runTest {
        val viewModel = newViewModel()

        assertEquals(TripListPhase.Loading, viewModel.state.value.phase)
    }

    @Test
    fun `여행을 받으면 content가 된다`() = runTest {
        service.onList = { page(listOf(trip("t1"), trip("t2"))) }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(TripListPhase.Content, state.phase)
        assertEquals(listOf("t1", "t2"), state.trips.map { it.tripId })
    }

    @Test
    fun `여행이 하나도 없으면 empty가 된다`() = runTest {
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        assertEquals(TripListPhase.Empty, viewModel.state.value.phase)
        // 필터를 걸지 않은 empty다. 화면은 "여행 만들기"를 안내해야 한다.
        assertFalse(viewModel.state.value.filtered)
    }

    // --- US2 Acceptance Scenario 2: 여행명 검색 ---

    @Test
    fun `검색어를 서버 요청에 전달한다`() = runTest {
        val viewModel = loaded()

        viewModel.onQueryChange("서울")
        advanceUntilIdle()

        assertEquals("서울", service.listCalls.last().query)
    }

    @Test
    fun `검색어를 지우면 전체 목록을 다시 조회한다`() = runTest {
        val viewModel = loaded()
        viewModel.onQueryChange("서울")
        advanceUntilIdle()

        viewModel.onQueryChange("")
        advanceUntilIdle()

        assertEquals(null, service.listCalls.last().query)
    }

    @Test
    fun `연속 입력은 마지막 검색어 한 번만 조회한다`() = runTest {
        val viewModel = loaded()
        val before = service.listCalls.size

        viewModel.onQueryChange("서")
        viewModel.onQueryChange("서울")
        viewModel.onQueryChange("서울 여")
        advanceUntilIdle()

        // 글자마다 요청하면 목록이 깜빡이고 서버 부하도 커진다.
        assertEquals(1, service.listCalls.size - before)
        assertEquals("서울 여", service.listCalls.last().query)
    }

    // --- US2 Acceptance Scenario 3: 상태 필터 ---

    @Test
    fun `상태 필터를 서버 요청에 전달한다`() = runTest {
        val viewModel = loaded()

        viewModel.onStatusFilterChange(TripStatus.IN_PROGRESS)
        advanceUntilIdle()

        assertEquals(TripStatus.IN_PROGRESS, service.listCalls.last().status)
    }

    @Test
    fun `검색어와 상태 필터를 함께 적용한다`() = runTest {
        val viewModel = loaded()

        viewModel.onQueryChange("제주")
        advanceUntilIdle()
        viewModel.onStatusFilterChange(TripStatus.COMPLETED)
        advanceUntilIdle()

        val call = service.listCalls.last()
        assertEquals("제주", call.query)
        assertEquals(TripStatus.COMPLETED, call.status)
    }

    @Test
    fun `검색어와 필터를 함께 걸어 결과가 없으면 걸러진 empty가 된다`() = runTest {
        val viewModel = loaded(trips = listOf(trip("t1")))
        service.onList = { emptyPage() }

        viewModel.onQueryChange("없는 여행")
        advanceUntilIdle()
        viewModel.onStatusFilterChange(TripStatus.COMPLETED)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(TripListPhase.Empty, state.phase)
        // 여행이 없는 것과 조건에 맞는 결과가 없는 것은 다른 안내가 필요하다.
        assertTrue(state.filtered)
        assertTrue(state.trips.isEmpty())
    }

    @Test
    fun `조건을 바꾸면 첫 페이지부터 다시 조회한다`() = runTest {
        val viewModel = loaded(trips = listOf(trip("t1")), nextCursor = "cursor-1")

        viewModel.onQueryChange("서울")
        advanceUntilIdle()

        // cursor는 최초 요청의 검색 조건에 묶여 있어 그대로 쓰면 서버가 거절한다.
        assertEquals(null, service.listCalls.last().cursor)
    }

    // --- 무한 스크롤 ---

    @Test
    fun `다음 페이지를 이어서 붙인다`() = runTest {
        val viewModel = loaded(trips = listOf(trip("t1")), nextCursor = "cursor-1")
        service.onList = { page(listOf(trip("t2"))) }

        viewModel.loadMore()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("cursor-1", service.listCalls.last().cursor)
        assertEquals(listOf("t1", "t2"), state.trips.map { it.tripId })
        assertFalse(state.hasNext)
    }

    @Test
    fun `마지막 페이지에서는 추가 조회하지 않는다`() = runTest {
        val viewModel = loaded(trips = listOf(trip("t1")))
        val before = service.listCalls.size

        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(before, service.listCalls.size)
    }

    // --- 오류와 재시도 ---

    @Test
    fun `통신 실패는 error가 된다`() = runTest {
        service.onList = { throw java.io.IOException("연결 실패") }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        assertEquals(TripListPhase.Failed(TripListError.NETWORK), viewModel.state.value.phase)
    }

    @Test
    fun `재시도하면 다시 조회한다`() = runTest {
        service.onList = { throw java.io.IOException("연결 실패") }
        val viewModel = newViewModel()
        viewModel.load()
        advanceUntilIdle()

        service.onList = { page(listOf(trip("t1"))) }
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(TripListPhase.Content, viewModel.state.value.phase)
    }

    /** 첫 페이지를 이미 받아 둔 view model을 만든다. */
    private suspend fun TestScope.loaded(
        trips: List<TripDto> = listOf(trip("t1")),
        nextCursor: String? = null,
    ): TripListViewModel {
        service.onList = { page(trips, nextCursor) }
        val viewModel = newViewModel()
        viewModel.load()
        advanceUntilIdle()
        return viewModel
    }

    /** 로그인된 session을 가진 repository 위에 view model을 만든다. */
    private suspend fun newViewModel(): TripListViewModel {
        val store = AuthSessionStore(
            // DataStore 기본 scope는 Dispatchers.IO다. 그대로 두면 저장소 작업이 test
            // scheduler 밖에서 돌아 advanceUntilIdle()이 기다려 주지 않는다.
            AuthSessionStore.createDataStore(
                File(tempFolder.root, AuthSessionStore.FILE_NAME),
                scope = CoroutineScope(dispatcher + SupervisorJob()),
            ),
            FakeSessionCipher(),
        )
        val auth = AuthRepository(
            store = store,
            api = FakeAuthService,
            appLinkHandler = AuthAppLinkHandler("app.gilpick.example"),
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
        return TripListViewModel(TripRepository(api = service, auth = auth))
    }
}
