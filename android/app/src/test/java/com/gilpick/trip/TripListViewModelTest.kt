package com.gilpick.trip

import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeAuthService
import com.gilpick.auth.FakeSessionCipher
import java.io.File
import okhttp3.ResponseBody.Companion.toResponseBody
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

    // --- #617: 목록 카드 대표 이미지 ---

    @Test
    fun `대표 이미지가 있는 여행만 원본을 받아 covers에 담는다`() = runTest {
        val requested = mutableListOf<String>()
        service.onImageContent = { id ->
            requested += id
            imageResponse(byteArrayOf(1, 2))
        }
        val viewModel = loaded(listOf(trip("t1", imageUrl = IMAGE_URL), trip("t2")))

        assertEquals(listOf("t1"), requested)
        assertEquals(listOf<Byte>(1, 2), viewModel.state.value.covers[coverKey(trip("t1", imageUrl = IMAGE_URL))]?.toList())
    }

    @Test
    fun `이미 받은 이미지는 목록을 다시 조회해도 다시 요청하지 않는다`() = runTest {
        val requested = mutableListOf<String>()
        service.onImageContent = { id ->
            requested += id
            imageResponse(byteArrayOf(1))
        }
        val viewModel = loaded(listOf(trip("t1", imageUrl = IMAGE_URL)))

        // 상세에 다녀오면 목록을 다시 받는다. 같은 이미지를 또 내려받으면 재진입마다 N+1이 된다.
        viewModel.load()
        advanceUntilIdle()

        assertEquals(listOf("t1"), requested)
    }

    @Test
    fun `이미지를 바꾸면 version이 올라 새 이미지를 받는다`() = runTest {
        var bytes = byteArrayOf(1)
        service.onImageContent = { imageResponse(bytes) }
        val viewModel = loaded(listOf(trip("t1", imageUrl = IMAGE_URL, version = 1)))

        bytes = byteArrayOf(9)
        service.onList = { page(listOf(trip("t1", imageUrl = IMAGE_URL, version = 2))) }
        viewModel.load()
        advanceUntilIdle()

        val covers = viewModel.state.value.covers
        assertEquals(listOf<Byte>(9), covers[coverKey(trip("t1", imageUrl = IMAGE_URL, version = 2))]?.toList())
        // 옛 이미지는 남기지 않는다. 남으면 카드가 이전 이미지를 다시 그릴 수 있다.
        assertEquals(1, covers.size)
    }

    @Test
    fun `이미지를 지운 여행은 covers에서 사라진다`() = runTest {
        service.onImageContent = { imageResponse(byteArrayOf(1)) }
        val viewModel = loaded(listOf(trip("t1", imageUrl = IMAGE_URL, version = 1)))

        service.onList = { page(listOf(trip("t1", version = 2))) }
        viewModel.load()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.covers.isEmpty())
    }

    @Test
    fun `이미지를 받지 못해도 목록은 그대로 보인다`() = runTest {
        service.onImageContent = { throw java.io.IOException("offline") }
        val viewModel = loaded(listOf(trip("t1", imageUrl = IMAGE_URL), trip("t2", imageUrl = IMAGE_URL)))

        assertEquals(TripListPhase.Content, viewModel.state.value.phase)
        assertEquals(listOf("t1", "t2"), viewModel.state.value.trips.map { it.tripId })
        assertTrue(viewModel.state.value.covers.isEmpty())
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

    private companion object {

        /** 서버가 내려주는 대표 이미지 주소. 앱은 "이미지 있음" 표시로만 쓴다. */
        const val IMAGE_URL = "http://api.example/trips/t1/image/content"

        /** 이미지 원본 응답. */
        fun imageResponse(bytes: ByteArray) = retrofit2.Response.success(bytes.toResponseBody(null))
    }
}
