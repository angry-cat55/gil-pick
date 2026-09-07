package com.gilpick.progress

import androidx.lifecycle.ViewModelStore
import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeAuthService
import com.gilpick.auth.FakeSessionCipher
import com.gilpick.itinerary.FakeItineraryService
import com.gilpick.itinerary.ItemStatus
import com.gilpick.itinerary.ItineraryError
import com.gilpick.itinerary.ItineraryErrorCodes
import com.gilpick.itinerary.ItineraryRepository
import com.gilpick.itinerary.TransportMode
import com.gilpick.itinerary.day
import com.gilpick.itinerary.itineraryError
import com.gilpick.itinerary.ok
import com.gilpick.itinerary.overview
import com.gilpick.itinerary.savedItem
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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

/**
 * T019: 진행 화면 ViewModel 상태 전이 검증.
 *
 * `spec.md` US1, UI-008(네 상태), `plan.md` State & Interaction(개요+진행 병렬 조회, `onResume` 재조회,
 * 1분 갱신)이 대상이다. HTTP 왕복은 `ProgressRepositoryTest`가 보므로 여기서는 fake service로 응답만 정한다.
 *
 * 1초 대기 표시 지연은 다른 화면과 같이 composable(`ActiveTravelScreen`)이 맡는다. 여기서는 응답 전
 * 상태가 [ProgressUiState.Loading]임을, 지연 규칙은 `ActiveTravelScreenTest`가 확인한다.
 *
 * ViewModel이 매분 갱신 loop를 돌리므로 `advanceUntilIdle()`과 `runTest`의 종료 대기는 끝나지 않는다.
 * fake 응답에는 지연이 없어 `runCurrent()`로 충분하고, 시간이 필요한 곳만 `advanceTimeBy`로 옮기며,
 * 각 test 끝에 [ViewModelStore.clear]로 ViewModel을 정리해 loop를 멈춘다([viewModelTest]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val progressService = FakeProgressService()
    private val itineraryService = FakeItineraryService()

    /** 2026-09-08 11:10:30 KST. [inProgress]의 B ETA(02:20Z = 11:20 KST)까지 9분 30초 남았다. */
    private var now: Instant = Instant.parse("2026-09-08T02:10:30Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("Asia/Seoul")
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        itineraryService.onOverview = { ok(todayOverview()) }
        progressService.onGet = { progressOk(inProgress()) }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `응답 전에는 loading이고 load는 개요와 오늘 진행 현황을 함께 조회한다`() = viewModelTest { viewModel ->

        viewModel.load()
        assertEquals(ProgressUiState.Loading, viewModel.state.value)
        runCurrent()

        assertEquals(listOf(PROGRESS_DATE), progressService.getCalls)
        assertTrue(viewModel.state.value is ProgressUiState.Content)
    }

    @Test
    fun `두 조회는 병렬이라 개요 응답을 기다리지 않고 진행 현황을 요청한다`() = viewModelTest { viewModel ->
        val overviewGate = CompletableDeferred<Unit>()
        itineraryService.onOverview = {
            overviewGate.await()
            ok(todayOverview())
        }

        viewModel.load()
        runCurrent()
        assertEquals(listOf(PROGRESS_DATE), progressService.getCalls)
        assertEquals(ProgressUiState.Loading, viewModel.state.value)

        overviewGate.complete(Unit)
        runCurrent()
        assertTrue(viewModel.state.value is ProgressUiState.Content)
    }

    @Test
    fun `content는 개요와 진행 현황을 itemId로 잇고 다음·현재 장소와 완료 수를 낸다`() = viewModelTest { viewModel ->
        viewModel.load()
        runCurrent()

        val content = viewModel.state.value as ProgressUiState.Content
        assertEquals(PROGRESS_DATE, content.today.toString())
        assertEquals(now, content.now)
        assertEquals(listOf("경복궁", "북촌한옥마을", "인사동거리"), content.rows.map { it.item.place.name })
        assertEquals(listOf(ItemStatus.COMPLETED, ItemStatus.EN_ROUTE, ItemStatus.PLANNED), content.rows.map { it.progress.status })
        assertEquals("북촌한옥마을", content.nextRow?.item?.place?.name)
        assertNull(content.currentRow)
        assertEquals(1, content.visitedCount)
        assertEquals(1, content.todayItinerary?.dayNumber)
    }

    @Test
    fun `개요에는 있지만 진행 현황에 없는 장소는 PLANNED로 본다`() = viewModelTest { viewModel ->
        progressService.onGet = { progressOk(inProgress().copy(items = inProgress().items.take(2))) }
        viewModel.load()
        runCurrent()

        val content = viewModel.state.value as ProgressUiState.Content
        val last = content.rows.last()
        assertEquals(P_ITEM_C, last.item.itemId)
        assertEquals(ItemStatus.PLANNED, last.progress.status)
        assertNull(last.progress.estimatedArrivalAt)
    }

    @Test
    fun `오늘 날짜에 장소가 없으면 empty다`() = viewModelTest { viewModel ->
        progressService.onGet = { progressOk(notStarted(items = emptyList())) }
        viewModel.load()
        runCurrent()

        assertEquals(ProgressUiState.Empty, viewModel.state.value)
    }

    @Test
    fun `진행 현황 조회가 통신 실패하면 network error다`() = viewModelTest { viewModel ->
        progressService.onGet = { throw IOException("끊김") }
        viewModel.load()
        runCurrent()

        assertEquals(ProgressUiState.Error(ProgressError.Network), viewModel.state.value)
    }

    @Test
    fun `개요 조회가 실패하면 원인을 진행 오류로 옮긴 error다`() = viewModelTest { viewModel ->
        itineraryService.onOverview = { itineraryError(404, ItineraryErrorCodes.TRIP_NOT_FOUND) }
        viewModel.load()
        runCurrent()

        assertEquals(ProgressUiState.Error(ProgressError.NotFound), viewModel.state.value)
        assertEquals(ProgressError.Unexpected, ItineraryError.InvalidItinerary(emptyList()).toProgressError())
        assertEquals(ProgressError.SessionExpired, ItineraryError.SessionExpired.toProgressError())
    }

    @Test
    fun `다시 시도하면 loading을 거쳐 content가 된다`() = viewModelTest { viewModel ->
        var fail = true
        progressService.onGet = { if (fail) throw IOException("끊김") else progressOk(inProgress()) }
        viewModel.load()
        runCurrent()
        assertTrue(viewModel.state.value is ProgressUiState.Error)

        fail = false
        viewModel.load()
        assertEquals(ProgressUiState.Loading, viewModel.state.value)
        runCurrent()

        assertTrue(viewModel.state.value is ProgressUiState.Content)
        assertEquals(2, progressService.getCalls.size)
    }

    @Test
    fun `onResume 재조회는 내용을 유지한 채 최신 진행 현황으로 바꾼다`() = viewModelTest { viewModel ->
        viewModel.load()
        runCurrent()
        val before = viewModel.state.value as ProgressUiState.Content

        progressService.onGet = { progressOk(inProgress(progressVersion = 3).copy(currentItemId = P_ITEM_B, nextItemId = P_ITEM_C)) }
        viewModel.load()
        // 갱신 중에도 loading으로 돌아가지 않는다.
        assertEquals(before, viewModel.state.value)
        runCurrent()

        val after = viewModel.state.value as ProgressUiState.Content
        assertEquals(3, after.progress.progressVersion)
        assertEquals("북촌한옥마을", after.currentRow?.item?.place?.name)
        assertEquals(2, progressService.getCalls.size)
    }

    @Test
    fun `재조회가 실패해도 보던 내용을 지우지 않는다`() = viewModelTest { viewModel ->
        viewModel.load()
        runCurrent()
        val before = viewModel.state.value

        progressService.onGet = { throw IOException("끊김") }
        viewModel.load()
        runCurrent()

        assertEquals(before, viewModel.state.value)
    }

    @Test
    fun `조회 중에 다시 부르면 조회를 겹치지 않는다`() = viewModelTest { viewModel ->

        viewModel.load()
        viewModel.load()
        runCurrent()

        assertEquals(1, progressService.getCalls.size)
    }

    @Test
    fun `now는 매분 정각에 갱신돼 남은 시간이 1분 단위로 바뀐다`() = viewModelTest { viewModel ->
        viewModel.load()
        runCurrent()
        assertEquals(Instant.parse("2026-09-08T02:10:30Z"), (viewModel.state.value as ProgressUiState.Content).now)

        // 11:10:30에서 29초 뒤(11:10:59)는 아직 같은 분이다.
        now = Instant.parse("2026-09-08T02:10:59Z")
        advanceTimeBy(29_000)
        assertEquals(Instant.parse("2026-09-08T02:10:30Z"), (viewModel.state.value as ProgressUiState.Content).now)

        // 정각(11:11:00)이 되면 갱신된다.
        now = Instant.parse("2026-09-08T02:11:00Z")
        advanceTimeBy(1_001)
        assertEquals(Instant.parse("2026-09-08T02:11:00Z"), (viewModel.state.value as ProgressUiState.Content).now)

        // 그 다음 갱신은 다시 60초 뒤다.
        now = Instant.parse("2026-09-08T02:12:00Z")
        advanceTimeBy(60_000)
        assertEquals(Instant.parse("2026-09-08T02:12:00Z"), (viewModel.state.value as ProgressUiState.Content).now)
    }

    /** 9/8 하루 여행 개요. [inProgress]의 세 항목과 같은 ID다. */
    private fun todayOverview() = overview(
        day(
            PROGRESS_DATE,
            dayNumber = 1,
            version = 3,
            items = listOf(
                savedItem(P_ITEM_A, 1, name = "경복궁", transportToNext = TransportMode.TRANSIT),
                savedItem(P_ITEM_B, 2, name = "북촌한옥마을", transportToNext = TransportMode.WALK),
                savedItem(P_ITEM_C, 3, name = "인사동거리"),
            ),
        ),
    )

    /** ViewModel을 만들어 test에 넘기고, 끝나면 `onCleared`로 매분 갱신 loop를 멈춘다. */
    private fun viewModelTest(block: suspend TestScope.(ProgressViewModel) -> Unit) = runTest {
        val store = ViewModelStore()
        val viewModel = newViewModel()
        store.put("progress", viewModel)
        try {
            block(viewModel)
        } finally {
            store.clear()
        }
    }

    private suspend fun newViewModel(): ProgressViewModel {
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
        return ProgressViewModel(
            progressRepository = ProgressRepository(api = progressService, auth = auth),
            itineraryRepository = ItineraryRepository(api = itineraryService, auth = auth),
            tripId = PROGRESS_TRIP_ID,
            clock = clock,
        )
    }
}
