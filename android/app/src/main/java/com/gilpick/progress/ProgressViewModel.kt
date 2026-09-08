package com.gilpick.progress

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gilpick.BuildConfig
import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthResult
import com.gilpick.auth.AuthService
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.SessionRevocationWorker
import com.gilpick.auth.createAuthRetrofit
import com.gilpick.itinerary.ItemStatus
import com.gilpick.itinerary.ItineraryRepository
import com.gilpick.itinerary.toItineraryError
import com.gilpick.trip.KST
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 진행 화면의 상태 보유자(T019).
 *
 * F004 개요(ITIN-003)와 오늘 날짜의 진행 현황(PROG-001)을 병렬로 조회해 [ProgressUiState]로 합친다.
 * 화면이 다시 보일 때마다([load]) 같은 조회를 반복하되, 이미 내용이 있으면 대기 표시로 돌아가지
 * 않고 조용히 갱신한다. 전환([arrive]·[depart]·[skip]·[updateStatus])은 PROG-006 한 번이며 응답의
 * 진행 현황으로 내용을 통째로 바꾼다. 남은·지난 시간은 저장된 ETA와 기기 시각으로 계산하므로 [ProgressUiState.Content.now]를
 * 매분 정각에 갱신한다(`plan.md` State & Interaction).
 *
 * @param tripId 여행.
 * @param clock 오늘 날짜와 현재 시각의 출처. test가 고정한다.
 */
class ProgressViewModel(
    private val progressRepository: ProgressRepository,
    private val itineraryRepository: ItineraryRepository,
    private val tripId: String,
    private val clock: Clock = Clock.system(KST),
    private val detectionRepository: DetectionRepository? = null,
    private val geofenceManager: GeofenceManager? = null,
) : ViewModel() {

    /** 진행 현황을 조회하는 오늘 날짜(KST). `empty`의 `장소 추가`가 이 날짜의 편집으로 간다. */
    val today: LocalDate = LocalDate.now(clock)

    private val _state = MutableStateFlow<ProgressUiState>(ProgressUiState.Loading)

    /** 화면이 관찰하는 현재 상태. */
    val state: StateFlow<ProgressUiState> = _state.asStateFlow()

    /** 진행 중인 조회. 재진입·`다시 시도` 연타로 겹치는 조회를 막는다. */
    private var job: Job? = null

    init {
        viewModelScope.launch { tickEveryMinute() }
        viewModelScope.launch { scheduleDeadlineReload() }
    }

    /**
     * 개요와 오늘 진행 현황을 함께 조회한다. 화면 진입·재개와 `다시 시도`가 부른다.
     *
     * 내용이 이미 있으면 그대로 둔 채 갱신하고, 갱신 조회가 실패해도 보던 내용을 지우지 않는다.
     * 처음 조회이거나 `error`·`empty`에서 다시 시도하면 `loading`을 거친다.
     */
    fun load() {
        if (job?.isActive == true) return
        val previous = _state.value as? ProgressUiState.Content
        if (previous == null) _state.value = ProgressUiState.Loading
        job = viewModelScope.launch {
            val next = fetch()
            _state.update { current ->
                val kept = current as? ProgressUiState.Content
                when {
                    kept == null -> next
                    next !is ProgressUiState.Content -> if (next is ProgressUiState.Error) kept else next
                    // 전환 응답이 먼저 도착해 더 새 version을 보고 있으면 늦게 온 조회로 되돌리지 않는다.
                    else -> next.copy(
                        progress = if (next.progress.progressVersion < kept.progress.progressVersion) kept.progress else next.progress,
                        pendingAction = kept.pendingAction,
                        actionError = kept.actionError,
                        viewingDate = kept.viewingDate,
                        decisionPending = kept.decisionPending,
                        decisionFailure = kept.decisionFailure,
                        undoPending = kept.undoPending,
                        // 되돌릴 대상이 바뀌면 지난 실패 안내를 지운다.
                        undoError = kept.undoError.takeIf {
                            kept.progress.undoable?.transitionId == next.progress.undoable?.transitionId
                        },
                        // 후보가 바뀌면 닫아 둔 시트를 다시 띄운다. 새 질문이기 때문이다.
                        candidateDismissed = kept.candidateDismissed &&
                            kept.progress.pendingCandidate?.transitionId == next.progress.pendingCandidate?.transitionId,
                    )
                }
            }
            syncGeofences()
        }
    }

    /**
     * 서버가 준 감지 대상과 실제 등록 상태를 맞춘다(FR-023·research 4절).
     *
     * 앱은 무엇을 감지할지 계산하지 않고 받은 목록만 반영한다. 등록에 실패해도 자동 감지만
     * 꺼지고 수동 진행은 그대로다(FR-024).
     */
    private suspend fun syncGeofences() {
        val manager = geofenceManager ?: return
        val content = _state.value as? ProgressUiState.Content
        if (content == null) {
            manager.clear()
            return
        }
        manager.sync(tripId, content.progress.date, content.progress.detectionTargets)
    }

    /** 이동 중 카드의 `도착했어요`: 다음 장소를 `ARRIVED`로(US2 시나리오 1·4). */
    fun arrive() = nextItem()?.let { updateStatus(it, ItemStatus.ARRIVED) }

    /** 이동 중 카드의 `건너뛰기`: 다음 장소를 `SKIPPED`로(US2 시나리오 3). */
    fun skip() = nextItem()?.let { updateStatus(it, ItemStatus.SKIPPED) }

    /** 도착 카드의 `다음 장소로 출발`: 현재 장소를 `COMPLETED`로(US2 시나리오 2). */
    fun depart() = (_state.value as? ProgressUiState.Content)?.progress?.currentItemId?.let { updateStatus(it, ItemStatus.COMPLETED) }

    /**
     * 장소의 상태를 목표 상태로 바꾼다(PROG-006). 카드 행동과 상태 수정 시트(US3)가 모두 여기로 온다.
     *
     * 요청 중에는 내용을 그대로 두고 [ProgressUiState.Content.pendingAction]만 표시한다. 성공하면 응답으로
     * 내용을 바꾸고, 실패하면 내용은 요청 전 그대로 두고 원인을 남긴다. `VERSION_CONFLICT`·
     * `INVALID_STATUS_TRANSITION`은 다른 곳에서 바뀐 것이므로 최신 현황을 다시 조회한다(FR-017).
     * 이미 요청 중이면 무시한다.
     */
    fun updateStatus(itemId: String, status: ItemStatus) {
        val content = _state.value as? ProgressUiState.Content ?: return
        if (content.pendingAction != null) return
        val action = ProgressAction(itemId, status)
        _state.value = content.copy(pendingAction = action, actionError = null)
        viewModelScope.launch {
            val result = progressRepository.updateStatus(itemId, status, content.progress.progressVersion)
            val error = (result as? AuthResult.Failure)?.error?.toProgressError()
            _state.update { current ->
                val kept = current as? ProgressUiState.Content ?: return@update current
                when (result) {
                    is AuthResult.Success -> kept.copy(progress = result.value, now = clock.instant(), pendingAction = null)
                    is AuthResult.Failure -> kept.copy(pendingAction = null, actionError = ProgressActionFailure(action, error!!))
                }
            }
            if (error == ProgressError.VersionConflict || error == ProgressError.InvalidTransition) load()
        }
    }

    /**
     * 확인 시트의 응답을 보낸다(PROG-004).
     *
     * 보내는 동안 후보는 그대로 두고 두 행동만 잠근다. 성공하면 최신 진행 현황을 다시 조회해
     * 후보·상태·감지 대상을 한 번에 맞춘다. 실패하면 후보를 임의로 취소하지 않고 원인만 남긴다(UI-006).
     */
    fun decide(decision: TransitionDecision) {
        val content = _state.value as? ProgressUiState.Content ?: return
        val candidate = content.progress.pendingCandidate ?: return
        val repository = detectionRepository ?: return
        if (content.decisionPending != null) return

        _state.value = content.copy(decisionPending = decision, decisionFailure = null)
        viewModelScope.launch {
            when (val result = repository.decide(candidate.transitionId, decision)) {
                is AuthResult.Success -> {
                    _state.update { current ->
                        (current as? ProgressUiState.Content)?.copy(decisionPending = null, decisionFailure = null) ?: current
                    }
                    // 응답은 전환 결과만 준다. 후보·상태·감지 대상은 진행 조회로 한 번에 맞춘다.
                    load()
                }

                is AuthResult.Failure -> {
                    val error = result.error.toDetectionError()
                    _state.update { current ->
                        (current as? ProgressUiState.Content)
                            ?.copy(decisionPending = null, decisionFailure = DecisionFailure(decision, error)) ?: current
                    }
                    // 이미 처리된 후보는 최신 상태를 다시 받아야 화면이 맞는다.
                    if (error == DetectionError.TransitionNotPending || error == DetectionError.InvalidDecision) load()
                }
            }
        }
    }

    /**
     * 실패한 확인 응답을 **같은 답으로** 다시 보낸다.
     *
     * 보낼 답은 [ProgressUiState.Content.decisionFailure]에 기억해 둔 것을 쓴다. 후보가 허용하는
     * 답 목록에서 고르면 사용자가 거절한 전환을 확정해 버릴 수 있다.
     */
    fun retryDecision() {
        val pending = (_state.value as? ProgressUiState.Content) ?: return
        val decision = pending.decisionFailure?.decision ?: return
        _state.value = pending.copy(decisionFailure = null)
        decide(decision)
    }

    /**
     * 자동으로 확정된 전환을 되돌린다(PROG-005).
     *
     * 만료 판정은 서버가 한다(FR-018). 앱은 남은 시간을 표시만 하므로, 눌린 시점에 이미
     * 지났으면 서버가 `409`로 거절하고 그 원인을 토스트에 보인다.
     */
    fun undo() {
        val content = _state.value as? ProgressUiState.Content ?: return
        val undoable = content.progress.undoable ?: return
        val repository = detectionRepository ?: return
        if (content.undoPending) return

        _state.value = content.copy(undoPending = true, undoError = null)
        viewModelScope.launch {
            when (val result = repository.undo(undoable.transitionId)) {
                is AuthResult.Success -> {
                    _state.update { current ->
                        (current as? ProgressUiState.Content)?.copy(undoPending = false, undoError = null) ?: current
                    }
                    // 복원 결과는 진행 조회로 받는다. 상태·ETA·감지 대상이 함께 되돌아간다.
                    load()
                }

                is AuthResult.Failure -> {
                    val error = result.error.toDetectionError()
                    _state.update { current ->
                        (current as? ProgressUiState.Content)?.copy(undoPending = false, undoError = error) ?: current
                    }
                    // 만료·대상 아님은 최신 상태를 받아야 토스트가 사라진다.
                    if (error == DetectionError.UndoWindowExpired || error == DetectionError.TransitionNotUndoable) load()
                }
            }
        }
    }

    /** 확인 시트를 닫는다. 후보는 살아 있고 수동 진행을 계속할 수 있다(UI-007). */
    fun dismissCandidate() {
        _state.update { state ->
            if (state is ProgressUiState.Content) state.copy(candidateDismissed = true, decisionFailure = null) else state
        }
    }

    /** 실패한 전환을 같은 내용으로 다시 보낸다. 같은 `Idempotency-Key`가 나간다. */
    fun retryAction() {
        val failure = (_state.value as? ProgressUiState.Content)?.actionError ?: return
        updateStatus(failure.action.itemId, failure.action.status)
    }

    /** 날짜 진행 표시의 점을 눌러 그 날짜의 일정을 본다(US4). 오늘을 고르면 [returnToToday]와 같다. */
    fun selectDate(date: LocalDate) {
        _state.update { state ->
            if (state is ProgressUiState.Content) state.copy(viewingDate = date.takeIf { it != state.today }) else state
        }
    }

    /** `오늘로 돌아가기`(UI-005). */
    fun returnToToday() {
        _state.update { state -> if (state is ProgressUiState.Content) state.copy(viewingDate = null) else state }
    }

    /** 전환 실패 안내를 닫는다. */
    fun dismissActionError() {
        _state.update { state -> if (state is ProgressUiState.Content) state.copy(actionError = null) else state }
    }

    private fun nextItem(): String? = (_state.value as? ProgressUiState.Content)?.progress?.nextItemId

    private suspend fun fetch(): ProgressUiState = coroutineScope {
        val overview = async { itineraryRepository.getOverview(tripId) }
        val progress = async { progressRepository.getDayProgress(tripId, today) }
        val days = when (val result = overview.await()) {
            is AuthResult.Success -> result.value.days
            is AuthResult.Failure -> return@coroutineScope ProgressUiState.Error(result.error.toItineraryError().toProgressError())
        }
        when (val result = progress.await()) {
            is AuthResult.Success -> if (result.value.items.isEmpty()) {
                ProgressUiState.Empty
            } else {
                ProgressUiState.Content(days = days, progress = result.value, now = clock.instant())
            }
            is AuthResult.Failure -> ProgressUiState.Error(result.error.toProgressError())
        }
    }

    /**
     * 자동 확정·되돌리기 만료 시각에 맞춰 진행을 다시 조회한다(T025).
     *
     * 서버는 요청이 올 때 만료된 후보를 확정하므로(`research.md` 2절), 그 시각에 아무도
     * 조회하지 않으면 화면이 옛 상태로 남는다. 앱이 해당 시각을 지나면 한 번 더 조회해
     * 확정 결과와 되돌리기 만료를 화면에 반영한다.
     */
    private suspend fun scheduleDeadlineReload() {
        var lastTarget: String? = null
        while (true) {
            val content = _state.value as? ProgressUiState.Content
            val target = content?.progress?.pendingCandidate?.autoFinalizeAt
                ?: content?.progress?.undoable?.undoDeadline
            if (target != null && target != lastTarget) {
                lastTarget = target
                val millis = millisUntil(target)
                if (millis > 0) delay(millis)
                load()
            }
            delay(DEADLINE_POLL_MILLIS)
        }
    }

    /** 지정 시각까지 남은 밀리초. 파싱할 수 없거나 이미 지났으면 0이다. */
    private fun millisUntil(isoInstant: String): Long {
        val target = runCatching { java.time.Instant.parse(isoInstant) }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(isoInstant).toInstant() }.getOrNull()
            ?: return 0
        return (target.toEpochMilli() - clock.millis()).coerceAtLeast(0)
    }

    /** 매분 정각에 [ProgressUiState.Content.now]를 갱신해 `N분 남았어요`가 분 단위로 맞게 한다. */
    private suspend fun tickEveryMinute() {
        while (true) {
            delay(MINUTE_MILLIS - clock.millis() % MINUTE_MILLIS)
            _state.update { state ->
                if (state is ProgressUiState.Content) state.copy(now = clock.instant()) else state
            }
        }
    }

    companion object {
        private const val MINUTE_MILLIS = 60_000L

        /** 새 만료 시각이 생겼는지 확인하는 주기. 토스트 남은 초 표시와 같은 정도면 충분하다. */
        private const val DEADLINE_POLL_MILLIS = 1_000L

        /** 화면이 사용할 의존성을 조립한다. DI 도구를 두지 않는 F001 방식이다. */
        fun factory(
            tripId: String,
            progressRepository: ProgressRepository,
            itineraryRepository: ItineraryRepository,
            detectionRepository: DetectionRepository? = null,
            geofenceManager: GeofenceManager? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ProgressViewModel(
                    progressRepository = progressRepository,
                    itineraryRepository = itineraryRepository,
                    tripId = tripId,
                    detectionRepository = detectionRepository,
                    geofenceManager = geofenceManager,
                )
            }
        }

        /** 실제 서버를 향한 repository. F005 `RouteViewModel.defaultRepository`와 같은 조립이다. */
        fun defaultRepository(context: Context): ProgressRepository {
            val appContext = context.applicationContext
            val auth = AuthRepository(
                store = AuthSessionStore.create(appContext),
                api = createAuthRetrofit(BuildConfig.API_BASE_URL).create(AuthService::class.java),
                appLinkHandler = AuthAppLinkHandler(BuildConfig.APP_LINK_HOST),
                scheduleRevocation = SessionRevocationWorker.scheduler(appContext),
            )
            return ProgressRepository(
                api = createProgressRetrofit(BuildConfig.API_BASE_URL).create(ProgressService::class.java),
                auth = auth,
            )
        }
    }
}
