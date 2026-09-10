package com.gilpick.progress

import androidx.lifecycle.ViewModelStore
import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeAuthService
import com.gilpick.auth.FakeSessionCipher
import com.gilpick.alternative.AlternativeRepository
import com.gilpick.alternative.DETECTION_ID
import com.gilpick.alternative.DetectionStatus
import com.gilpick.alternative.FakeAlternativeService
import com.gilpick.alternative.activeDetectionsJson
import com.gilpick.alternative.list
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
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import com.gilpick.replacement.ReplacementError
import com.gilpick.replacement.ReplacementErrorCodes
import com.gilpick.replacement.undoFailure
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T019·T024·T036: 진행 화면 ViewModel 상태 전이 검증.
 *
 * `spec.md` US1, UI-008(네 상태), `plan.md` State & Interaction(개요+진행 병렬 조회, `onResume` 재조회,
 * 1분 갱신)과 US2 전환(행동→목표 상태, `pendingAction`, 응답 교체, 실패 유지, `VERSION_CONFLICT` 재조회)이 대상이다. HTTP 왕복은 `ProgressRepositoryTest`가 보므로 여기서는 fake service로 응답만 정한다.
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
    private val detectionService = FakeDetectionService()
    private val replacementService = com.gilpick.replacement.FakeReplacementService()
    private val alternativeService = FakeAlternativeService()

    /** 대체 장소 repository 주입 여부. F009 배너 조회는 주입됐을 때만 한다(T020). */
    private var withAlternative = true
    private val geofenceClient = RecordingGeofenceClient()
    private val geofenceSession = FakeDetectionSessionStore()

    /** 백그라운드 위치 권한 보유 여부. test가 기기 상태 대신 바꾼다. */
    private var backgroundPermission = true

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

    // ---- T024: 전환 ----

    @Test
    fun `도착했어요는 다음 장소를 ARRIVED로, 건너뛰기는 SKIPPED로, 출발은 현재 장소를 COMPLETED로 보낸다`() = viewModelTest { viewModel ->
        viewModel.load()
        runCurrent()

        viewModel.arrive()
        runCurrent()
        viewModel.skip()
        runCurrent()
        progressService.onGet = { progressOk(inProgress(progressVersion = 4).copy(currentItemId = P_ITEM_B, nextItemId = P_ITEM_C)) }
        viewModel.load()
        runCurrent()
        viewModel.depart()
        runCurrent()

        assertEquals(listOf(P_ITEM_B, P_ITEM_B, P_ITEM_B), progressService.updateCalls.map { it.second })
        assertEquals(listOf(ItemStatus.ARRIVED, ItemStatus.SKIPPED, ItemStatus.COMPLETED), progressService.updateCalls.map { it.third.status })
        // 화면이 보고 있던 version을 그대로 보낸다.
        assertEquals(listOf(2, 3, 4), progressService.updateCalls.map { it.third.progressVersion })
    }

    @Test
    fun `요청 중에는 pendingAction만 표시하고 내용은 그대로이며 겹치는 요청은 무시한다`() = viewModelTest { viewModel ->
        val gate = CompletableDeferred<Unit>()
        progressService.onUpdate = { _, _ ->
            gate.await()
            progressOk(inProgress(progressVersion = 3))
        }
        viewModel.load()
        runCurrent()
        val before = viewModel.state.value as ProgressUiState.Content

        viewModel.arrive()
        viewModel.skip()
        runCurrent()

        val pending = viewModel.state.value as ProgressUiState.Content
        assertEquals(ProgressAction(P_ITEM_B, ItemStatus.ARRIVED), pending.pendingAction)
        assertEquals(before.progress, pending.progress)
        assertEquals(1, progressService.updateCalls.size)

        gate.complete(Unit)
        runCurrent()
        val after = viewModel.state.value as ProgressUiState.Content
        assertNull(after.pendingAction)
        assertEquals(3, after.progress.progressVersion)
        assertEquals(now, after.now)
    }

    @Test
    fun `실패하면 내용은 요청 전 그대로이고 원인과 다시 시도가 남는다`() = viewModelTest { viewModel ->
        var fail = true
        progressService.onUpdate = { _, _ -> if (fail) throw IOException("끊김") else progressOk(inProgress(progressVersion = 3)) }
        viewModel.load()
        runCurrent()
        val before = viewModel.state.value as ProgressUiState.Content

        viewModel.arrive()
        runCurrent()

        val failed = viewModel.state.value as ProgressUiState.Content
        assertEquals(before.progress, failed.progress)
        assertNull(failed.pendingAction)
        assertEquals(ProgressActionFailure(ProgressAction(P_ITEM_B, ItemStatus.ARRIVED), ProgressError.Network), failed.actionError)
        assertTrue(failed.actionError!!.retryable)
        assertEquals(1, progressService.getCalls.size)

        fail = false
        viewModel.retryAction()
        runCurrent()
        val retried = viewModel.state.value as ProgressUiState.Content
        assertNull(retried.actionError)
        assertEquals(3, retried.progress.progressVersion)
        // 같은 요청은 같은 Idempotency-Key로 나간다(FR-017).
        assertEquals(progressService.updateCalls[0].first, progressService.updateCalls[1].first)
    }

    // --- #313: 처리 출처와 이벤트 거절 이유 ---

    @Test
    fun `자동 처리 표시는 항목별 처리 출처로 판단한다`() = viewModelTest { viewModel ->
        progressService.onGet = { progressOk(withSources(auto = P_ITEM_A, manual = P_ITEM_B)) }

        viewModel.load()
        runCurrent()

        val content = viewModel.state.value as ProgressUiState.Content
        // 사용자가 확인 시트에 답해 확정된 것(MANUAL)과 이력이 없는 것(null)은 표시하지 않는다.
        assertEquals(setOf(P_ITEM_A), content.autoProcessedItemIds)
    }

    @Test
    fun `되돌릴 수 없게 된 뒤에도 자동 처리 표시가 남는다`() = viewModelTest { viewModel ->
        // undoable이 사라져도 표시는 처리 출처에서 오므로 유지된다(#313 완료 조건).
        progressService.onGet = { progressOk(withSources(auto = P_ITEM_A, manual = null).copy(undoable = null)) }

        viewModel.load()
        runCurrent()

        val content = viewModel.state.value as ProgressUiState.Content
        assertNull(content.progress.undoable)
        assertEquals(setOf(P_ITEM_A), content.autoProcessedItemIds)
    }

    @Test
    fun `정확도 미달로 거절되면 자동 감지 꺼짐을 안내한다`() = viewModelTest { viewModel ->
        progressService.onGet = { progressOk(withRejection(EventRejectionReason.LOW_ACCURACY)) }

        viewModel.load()
        runCurrent()

        val content = viewModel.state.value as ProgressUiState.Content
        assertEquals(DetectionOffReason.AccuracyLow, content.visibleDetectionNotice)
    }

    @Test
    fun `정확도 밖의 거절 이유는 안내하지 않는다`() = viewModelTest { viewModel ->
        // 감지 일시 중지 같은 정상 상태까지 문제처럼 알리지 않는다(UI-005는 두 원인만 정한다).
        progressService.onGet = { progressOk(withRejection(EventRejectionReason.DETECTION_PAUSED)) }

        viewModel.load()
        runCurrent()

        assertNull((viewModel.state.value as ProgressUiState.Content).visibleDetectionNotice)
    }

    @Test
    fun `권한 없음이 정확도 부족보다 먼저다`() = viewModelTest { viewModel ->
        // 권한이 없으면 이벤트 자체가 올라가지 않는다. 사용자가 먼저 풀 수 있는 원인을 보인다.
        backgroundPermission = false
        progressService.onGet = { progressOk(withRejection(EventRejectionReason.LOW_ACCURACY)) }

        viewModel.load()
        runCurrent()

        assertEquals(
            DetectionOffReason.PermissionMissing,
            (viewModel.state.value as ProgressUiState.Content).visibleDetectionNotice,
        )
    }

    // --- T033: 백그라운드 권한과 자동 감지 ---

    @Test
    fun `권한이 없으면 감지를 걸지 않고 원인을 남긴다`() = viewModelTest { viewModel ->
        backgroundPermission = false
        progressService.onGet = { progressOk(inProgress().copy(detectionTargets = listOf(detectionTarget()))) }

        viewModel.load()
        runCurrent()

        val content = viewModel.state.value as ProgressUiState.Content
        assertEquals(DetectionOffReason.PermissionMissing, content.detectionOff)
        assertEquals(emptyList<List<String>>(), geofenceClient.added)
        // 수동 진행은 그대로다(FR-024). 다음 장소와 행동이 남아 있다.
        assertEquals(P_ITEM_B, content.progress.nextItemId)
    }

    @Test
    fun `권한이 있으면 받은 대상을 걸고 안내를 지운다`() = viewModelTest { viewModel ->
        progressService.onGet = { progressOk(inProgress().copy(detectionTargets = listOf(detectionTarget()))) }

        viewModel.load()
        runCurrent()

        val content = viewModel.state.value as ProgressUiState.Content
        assertNull(content.detectionOff)
        assertEquals(listOf(listOf(detectionTarget().geofenceId)), geofenceClient.added)
    }

    @Test
    fun `진행 중 권한을 회수하면 걸어 둔 것을 풀고 안내한다`() = viewModelTest { viewModel ->
        progressService.onGet = { progressOk(inProgress().copy(detectionTargets = listOf(detectionTarget()))) }
        viewModel.load()
        runCurrent()
        val before = viewModel.state.value as ProgressUiState.Content
        geofenceClient.clearLog()

        backgroundPermission = false
        viewModel.load()
        runCurrent()

        val after = viewModel.state.value as ProgressUiState.Content
        assertEquals(DetectionOffReason.PermissionMissing, after.detectionOff)
        assertEquals(listOf(listOf(detectionTarget().geofenceId)), geofenceClient.removed)
        // 이미 확정된 상태는 그대로다. 자동 감지만 멈춘다.
        assertEquals(before.progress.items, after.progress.items)
    }

    @Test
    fun `안내를 닫으면 다시 조회해도 뜨지 않는다`() = viewModelTest { viewModel ->
        backgroundPermission = false
        viewModel.load()
        runCurrent()

        viewModel.dismissDetectionNotice()
        viewModel.load()
        runCurrent()

        val content = viewModel.state.value as ProgressUiState.Content
        // 원인은 그대로 남지만 안내는 보이지 않는다(FR-025).
        assertEquals(DetectionOffReason.PermissionMissing, content.detectionOff)
        assertNull(content.visibleDetectionNotice)
    }

    @Test
    fun `권한을 허용하고 돌아오면 그 자리에서 감지를 건다`() = viewModelTest { viewModel ->
        backgroundPermission = false
        progressService.onGet = { progressOk(inProgress().copy(detectionTargets = listOf(detectionTarget()))) }
        viewModel.load()
        runCurrent()

        backgroundPermission = true
        viewModel.onBackgroundPermissionResult()
        runCurrent()

        val content = viewModel.state.value as ProgressUiState.Content
        assertNull(content.detectionOff)
        assertEquals(listOf(listOf(detectionTarget().geofenceId)), geofenceClient.added)
    }

    @Test
    fun `확인 응답이 실패하면 보낸 답과 원인을 함께 남긴다`() = viewModelTest { viewModel ->
        progressService.onGet = { progressOk(inProgress().copy(pendingCandidate = departureCandidate())) }
        detectionService.onDecide = { decideServerError() }
        viewModel.load()
        runCurrent()

        viewModel.decide(TransitionDecision.STILL_HERE)
        runCurrent()

        val failed = viewModel.state.value as ProgressUiState.Content
        assertNull(failed.decisionPending)
        assertEquals(DecisionFailure(TransitionDecision.STILL_HERE, DetectionError.Unexpected), failed.decisionFailure)
        // 후보는 그대로 둔다. 사용자가 다시 답할 수 있어야 한다(UI-006).
        assertNotNull(failed.visibleCandidate)
    }

    @Test
    fun `다시 시도는 실패한 답을 그대로 다시 보낸다`() = viewModelTest { viewModel ->
        // `allowedDecisions`의 첫 값(CONFIRM)을 보내면 사용자가 거절한 출발을 확정하게 된다.
        progressService.onGet = { progressOk(inProgress().copy(pendingCandidate = departureCandidate())) }
        var fail = true
        detectionService.onDecide = { if (fail) decideServerError() else decideOk() }
        viewModel.load()
        runCurrent()
        viewModel.decide(TransitionDecision.STILL_HERE)
        runCurrent()

        fail = false
        viewModel.retryDecision()
        runCurrent()

        assertEquals(
            listOf(TransitionDecision.STILL_HERE, TransitionDecision.STILL_HERE),
            detectionService.decideCalls.map { it.second },
        )
        assertNull((viewModel.state.value as ProgressUiState.Content).decisionFailure)
    }

    @Test
    fun `실패한 답이 없으면 다시 시도는 아무것도 보내지 않는다`() = viewModelTest { viewModel ->
        progressService.onGet = { progressOk(inProgress().copy(pendingCandidate = departureCandidate())) }
        viewModel.load()
        runCurrent()

        viewModel.retryDecision()
        runCurrent()

        assertEquals(emptyList<Pair<String, TransitionDecision>>(), detectionService.decideCalls)
    }

    @Test
    fun `VERSION_CONFLICT면 오류를 남기고 최신 현황을 다시 조회한다`() = viewModelTest { viewModel ->
        progressService.onUpdate = { _, _ -> progressError(409, ProgressErrorCodes.VERSION_CONFLICT) }
        viewModel.load()
        runCurrent()
        progressService.onGet = { progressOk(inProgress(progressVersion = 5)) }

        viewModel.arrive()
        runCurrent()

        val content = viewModel.state.value as ProgressUiState.Content
        assertEquals(ProgressError.VersionConflict, content.actionError?.error)
        assertTrue(!content.actionError!!.retryable)
        assertEquals(5, content.progress.progressVersion)
        assertEquals(2, progressService.getCalls.size)

        viewModel.dismissActionError()
        assertNull((viewModel.state.value as ProgressUiState.Content).actionError)
    }

    @Test
    fun `전환 응답보다 늦게 온 조회는 더 새 version을 되돌리지 않는다`() = viewModelTest { viewModel ->
        val gate = CompletableDeferred<Unit>()
        viewModel.load()
        runCurrent()
        progressService.onGet = {
            gate.await()
            progressOk(inProgress(progressVersion = 2))
        }
        viewModel.load()
        viewModel.arrive()
        runCurrent()
        assertEquals(3, (viewModel.state.value as ProgressUiState.Content).progress.progressVersion)

        gate.complete(Unit)
        runCurrent()
        assertEquals(3, (viewModel.state.value as ProgressUiState.Content).progress.progressVersion)
    }

    // ---- T036: 다른 날짜 조회 ----

    @Test
    fun `다른 날짜를 고르면 그 날짜의 저장된 상태만 있는 행이고 오늘로 돌아가면 진행 행으로 돌아온다`() = viewModelTest { viewModel ->
        itineraryService.onOverview = { ok(threeDayOverview()) }
        viewModel.load()
        runCurrent()

        viewModel.selectDate(LocalDate.parse("2026-09-07"))
        val past = viewModel.state.value as ProgressUiState.Content
        assertEquals(LocalDate.parse("2026-09-07"), past.viewing)
        assertTrue(!past.isToday)
        assertEquals(listOf("창덕궁"), past.rows.map { it.item.place.name })
        assertEquals(listOf(ItemStatus.COMPLETED), past.rows.map { it.progress.status })
        assertTrue(past.viewingStarted)
        assertNull(past.rows.single().progress.actualArrivedAt)
        // 오늘의 다음 장소·완료 수는 그대로다.
        assertEquals("북촌한옥마을", past.nextRow?.item?.place?.name)
        assertEquals(3, past.todayRows.size)

        viewModel.selectDate(LocalDate.parse("2026-09-09"))
        val future = viewModel.state.value as ProgressUiState.Content
        assertTrue(!future.viewingStarted)
        assertEquals(listOf(ItemStatus.PLANNED), future.rows.map { it.progress.status })

        viewModel.returnToToday()
        val today = viewModel.state.value as ProgressUiState.Content
        assertTrue(today.isToday)
        assertNull(today.viewingDate)
        assertEquals(ItemStatus.EN_ROUTE, today.rows[1].progress.status)
    }

    @Test
    fun `오늘을 고르면 viewingDate가 비고 재조회해도 보던 날짜를 유지한다`() = viewModelTest { viewModel ->
        itineraryService.onOverview = { ok(threeDayOverview()) }
        viewModel.load()
        runCurrent()

        viewModel.selectDate(LocalDate.parse(PROGRESS_DATE))
        assertNull((viewModel.state.value as ProgressUiState.Content).viewingDate)

        viewModel.selectDate(LocalDate.parse("2026-09-09"))
        viewModel.load()
        runCurrent()
        assertEquals(LocalDate.parse("2026-09-09"), (viewModel.state.value as ProgressUiState.Content).viewing)
    }

    // ---- T020: 진행 화면 배너(F009 US2, UI-001) ----

    @Test
    fun `ACTIVE 감지 2건이면 status ACTIVE limit 50으로 조회하고 배너는 ETA가 이른 하나다`() = viewModelTest { viewModel ->
        alternativeService.onListDetections = { list(activeDetectionsJson()) }

        viewModel.load()
        runCurrent()

        assertEquals(listOf(DetectionStatus.ACTIVE to 50), alternativeService.listCalls)
        val content = viewModel.state.value as ProgressUiState.Content
        assertEquals(2, content.activeDetections.size)
        assertEquals(DETECTION_ID, content.bannerDetection?.detectionId)
        assertEquals("경복궁", content.bannerDetection?.placeName)
    }

    @Test
    fun `당일이 완료됐으면 감지가 있어도 배너가 없다`() = viewModelTest { viewModel ->
        alternativeService.onListDetections = { list(activeDetectionsJson()) }
        progressService.onGet = { progressOk(inProgress().copy(dayStatus = DayStatus.COMPLETED)) }

        viewModel.load()
        runCurrent()

        val content = viewModel.state.value as ProgressUiState.Content
        assertEquals(2, content.activeDetections.size)
        assertNull(content.bannerDetection)
    }

    @Test
    fun `다른 날짜를 보는 동안에는 배너가 없고 오늘로 돌아오면 다시 있다`() = viewModelTest { viewModel ->
        alternativeService.onListDetections = { list(activeDetectionsJson()) }
        itineraryService.onOverview = { ok(threeDayOverview()) }
        viewModel.load()
        runCurrent()
        assertNotNull((viewModel.state.value as ProgressUiState.Content).bannerDetection)

        viewModel.selectDate(LocalDate.parse("2026-09-09"))
        assertNull((viewModel.state.value as ProgressUiState.Content).bannerDetection)

        viewModel.returnToToday()
        assertNotNull((viewModel.state.value as ProgressUiState.Content).bannerDetection)
    }

    @Test
    fun `감지 조회 실패는 배너만 숨기고 나머지 내용은 정상이다`() = viewModelTest { viewModel ->
        alternativeService.onListDetections = { throw IOException("offline") }

        viewModel.load()
        runCurrent()

        val content = viewModel.state.value as ProgressUiState.Content
        assertEquals(emptyList<Any>(), content.activeDetections)
        assertNull(content.bannerDetection)
        assertEquals(3, content.rows.size)
    }

    @Test
    fun `대체 장소 repository가 없으면 감지를 조회하지 않는다`() = viewModelTest(withAlternative = false) { viewModel ->
        viewModel.load()
        runCurrent()

        assertTrue(alternativeService.listCalls.isEmpty())
        assertTrue(viewModel.state.value is ProgressUiState.Content)
        assertNull((viewModel.state.value as ProgressUiState.Content).bannerDetection)
    }

    /** 9/7~9/9 사흘 여행 개요. 어제는 완료된 창덕궁 한 곳, 내일은 예정 한 곳이다. */
    private fun threeDayOverview() = overview(
        day("2026-09-07", dayNumber = 1, version = 1, items = listOf(savedItem("item-past", 1, name = "창덕궁", status = ItemStatus.COMPLETED))),
        todayOverview().days.single().copy(dayNumber = 2),
        day("2026-09-09", dayNumber = 3, version = 1, items = listOf(savedItem("item-future", 1, name = "남산타워"))),
    )

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

    /** 항목별 처리 출처를 지정한 진행 현황. */
    private fun withSources(auto: String?, manual: String?) = inProgress().let { data ->
        data.copy(
            items = data.items.map { item ->
                when (item.itemId) {
                    auto -> item.copy(processingSource = ProgressProcessingSource.AUTO)
                    manual -> item.copy(processingSource = ProgressProcessingSource.MANUAL)
                    else -> item
                }
            },
        )
    }

    /** 첫 장소의 최신 위치 이벤트가 거절된 진행 현황. */
    private fun withRejection(reason: EventRejectionReason) = inProgress().let { data ->
        data.copy(items = data.items.map { if (it.itemId == P_ITEM_A) it.copy(eventRejectionReason = reason) else it })
    }

    /** 서버가 준 감지 대상 하나. 앱은 목록을 그대로 등록만 한다(research 4절). */
    private fun detectionTarget() = DetectionTargetDto(
        itemId = P_ITEM_B,
        kind = DetectionKind.ARRIVAL,
        geofenceId = "$P_ITEM_B:ARRIVAL",
        latitude = 37.5825,
        longitude = 126.9830,
        radiusMeters = 300,
        dwellMinutes = 5,
    )

    /**
     * 출발 확인을 기다리는 후보. `아직 머무는 중`과 `출발 확정`을 함께 받는다.
     *
     * 두 답의 뜻이 반대여서, 실패한 답을 기억하지 않으면 `다시 시도`가 무엇을 보내는지가 문제가 된다.
     */
    private fun departureCandidate() = TransitionCandidateDto(
        transitionId = TRANSITION_ID,
        itemId = P_ITEM_B,
        type = DetectionKind.DEPARTURE,
        status = TransitionStatus.PENDING_CONFIRMATION,
        detectedAt = "2026-09-08T02:33:00Z",
        autoFinalizeAt = "2026-09-08T02:38:00Z",
        allowedDecisions = listOf(TransitionDecision.CONFIRM, TransitionDecision.STILL_HERE),
        evidence = CandidateEvidenceDto(occurredAt = "2026-09-08T02:33:00Z", accuracyMeters = 22.0, dwellMinutes = null),
    )

    /** ViewModel을 만들어 test에 넘기고, 끝나면 `onCleared`로 매분 갱신 loop를 멈춘다. */
    private fun viewModelTest(withAlternative: Boolean = true, block: suspend TestScope.(ProgressViewModel) -> Unit) = runTest {
        this@ProgressViewModelTest.withAlternative = withAlternative
        val store = ViewModelStore()
        val viewModel = newViewModel()
        store.put("progress", viewModel)
        try {
            block(viewModel)
        } finally {
            store.clear()
        }
    }

    // --- F010 장소 변경 되돌리기(T031) ---

    @Test
    fun 장소_변경_되돌리기는_REPL_004를_보내고_성공하면_진행을_다시_조회한다() = viewModelTest { viewModel ->
        // 되돌리기는 장소·경로·감지 대상을 한 transaction으로 바꾼다. 응답만 반영하면 화면이 어긋난다.
        progressService.onGet = { progressOk(inProgress().copy(undoableReplacement = replacementUndo())) }
        viewModel.load()
        runCurrent()
        val before = progressService.getCalls.size

        viewModel.undoReplacement()
        runCurrent()

        assertEquals(listOf(REPLACEMENT_ID), replacementService.undoCalls)
        assertTrue(progressService.getCalls.size > before)
        assertNull((viewModel.state.value as ProgressUiState.Content).replacementUndoError)
    }

    @Test
    fun 되돌리는_중에는_잠기고_연타로_요청이_겹치지_않는다() = viewModelTest { viewModel ->
        progressService.onGet = { progressOk(inProgress().copy(undoableReplacement = replacementUndo())) }
        viewModel.load()
        runCurrent()

        viewModel.undoReplacement()
        assertTrue((viewModel.state.value as ProgressUiState.Content).replacementUndoPending)
        viewModel.undoReplacement()
        runCurrent()

        assertEquals(1, replacementService.undoCalls.size)
    }

    @Test
    fun 되돌리기_실패는_토스트를_유지하고_원인만_남긴다() = viewModelTest { viewModel ->
        // UI-007. 사용자가 원인과 일정 편집 안내를 볼 수 있어야 한다.
        progressService.onGet = { progressOk(inProgress().copy(undoableReplacement = replacementUndo())) }
        replacementService.onUndo = { undoFailure(ReplacementErrorCodes.UNDO_EXPIRED, httpStatus = 409) }
        viewModel.load()
        runCurrent()

        viewModel.undoReplacement()
        runCurrent()

        val content = viewModel.state.value as ProgressUiState.Content
        assertEquals(false, content.replacementUndoPending)
        assertEquals(ReplacementError.UndoExpired, content.replacementUndoError)
    }

    @Test
    fun 되돌릴_수_없게_된_두_원인은_안내가_남도록_바로_재조회하지_않는다() = viewModelTest { viewModel ->
        // FR-019·UI-007. 재조회하면 Content가 새로 만들어지며 원인이 지워져 안내가 사라진다.
        // 토스트는 만료 시각 재조회와 매분 갱신이 걷어 간다.
        progressService.onGet = { progressOk(inProgress().copy(undoableReplacement = replacementUndo())) }
        replacementService.onUndo = { undoFailure(ReplacementErrorCodes.FOLLOW_UP_CHANGE_EXISTS, httpStatus = 409) }
        viewModel.load()
        runCurrent()
        val before = progressService.getCalls.size

        viewModel.undoReplacement()
        runCurrent()

        val content = viewModel.state.value as ProgressUiState.Content
        assertEquals(ReplacementError.FollowUpChangeExists, content.replacementUndoError)
        assertEquals(before, progressService.getCalls.size)
        // 안내가 보일 자리가 남아 있어야 원인을 읽을 수 있다.
        assertNotNull(content.visibleReplacementUndo)
    }

    @Test
    fun 되돌릴_수_있는_장소_변경이_없으면_요청을_보내지_않는다() = viewModelTest { viewModel ->
        viewModel.load()
        runCurrent()

        viewModel.undoReplacement()
        runCurrent()

        assertTrue(replacementService.undoCalls.isEmpty())
    }

    @Test
    fun 두_되돌리기가_동시에_가능하면_장소_변경을_먼저_보인다() = viewModelTest { viewModel ->
        // UI-006a·SC-008. 표시 지점이 하나라 남은 시간이 짧은 쪽을 먼저 보여야 둘 다 쓸 수 있다.
        progressService.onGet = {
            progressOk(inProgress().copy(undoable = autoUndoable(), undoableReplacement = replacementUndo()))
        }
        viewModel.load()
        runCurrent()

        val content = viewModel.state.value as ProgressUiState.Content
        assertEquals(REPLACEMENT_ID, content.visibleReplacementUndo!!.replacementId)
        assertNull(content.visibleUndoable)
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
            detectionRepository = DetectionRepository(api = detectionService, auth = auth),
            geofenceManager = GeofenceManager(client = geofenceClient, session = geofenceSession),
            hasBackgroundPermission = { backgroundPermission },
            alternativeRepository = AlternativeRepository(api = alternativeService, auth = auth).takeIf { withAlternative },
            replacementRepository = com.gilpick.replacement.ReplacementRepository(api = replacementService, auth = auth),
        )
    }
}
