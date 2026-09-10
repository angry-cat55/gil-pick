package com.gilpick.replacement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gilpick.alternative.AlternativeRepository
import com.gilpick.auth.AuthResult
import com.gilpick.route.RouteRepository
import com.gilpick.itinerary.RouteStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 변경 경로 미리보기 화면의 상태 보유자(T015).
 *
 * [load]는 REPL-001 하나로 끝나지 않는다. 계약이 요구하는 `scheduleVersion`과 지도에 함께 그릴
 * 기존 경로가 F009가 넘긴 값에 없어 앞에 조회 둘이 붙는다.
 *
 * ```
 * DETECT-002 getDetection(detectionId)   → tripId, eta(날짜), reason
 *   └ ROUTE-001 getDayRoute(tripId, 날짜) → scheduleVersion + 기존 경로
 *      └ REPL-001 createPreview(...)      → 비교와 변경 경로
 * ```
 *
 * ROUTE-001 하나로 `scheduleVersion`과 기존 경로를 함께 받는다. PROG-001에도 같은
 * `scheduleVersion`이 있지만 따로 부르면 호출이 하나 늘고 두 값이 서로 다른 시점의 것이 될 수 있다.
 *
 * `scheduleVersion`을 route로 나르지 않고 여기서 다시 조회하는 이유는 F004 `TripEditRoute`와 같다.
 * 앞 화면을 열어 둔 사이 일정이 바뀌었을 수 있고, 낡은 version으로 요청하면 서버가 거절한다.
 *
 * 경로가 아직 계산되지 않았거나 실패한 날짜여도 계속 진행한다. `scheduleVersion`은 그대로 오고
 * 비교 표는 REPL-001의 값만 쓰므로 지도에 기존 경로가 빠질 뿐 화면은 성립한다(constitution IV).
 *
 * @param detectionId 기준 감지. F009가 넘긴 [com.gilpick.alternative.SelectedAlternative]의 값이다.
 * @param placeId 사용자가 고른 대체 장소.
 * @param candidateId F009 추천 후보면 그 토큰, 직접 검색이면 `null`. 요청에 그대로 반영된다(FR-004).
 * @param clock 남은 시간 표시의 기준 시각을 얻는다. test가 고정 시각으로 바꿔 끼운다.
 */
class PreviewViewModel(
    private val replacements: ReplacementRepository,
    private val detections: AlternativeRepository,
    private val routes: RouteRepository,
    private val detectionId: String,
    private val placeId: String,
    private val candidateId: String?,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

    private val _state = MutableStateFlow<PreviewUiState>(PreviewUiState.Loading)

    /** 화면이 관찰하는 현재 상태. */
    val state: StateFlow<PreviewUiState> = _state.asStateFlow()

    private val _approved = MutableStateFlow<ReplacementDto?>(null)

    /**
     * 승인이 끝나 화면을 닫아야 한다. 끝나지 않았으면 `null`이다.
     *
     * 값을 되돌리지 않으므로 재구성돼도 이동이 두 번 일어나지 않는다. F009 `AlternativeViewModel`의
     * `dismissed`와 같은 방식이다.
     */
    val approved: StateFlow<ReplacementDto?> = _approved.asStateFlow()

    /** 진행 중인 조회. `다시 시도` 연타로 요청이 겹치지 않게 한다. */
    private var job: Job? = null

    /** 진행 중인 승인. 승인 연타로 요청이 겹치지 않게 한다. */
    private var approveJob: Job? = null

    init {
        load()
    }

    /**
     * 미리보기를 만든다. 화면 진입과 `다시 시도`가 부른다.
     *
     * `다시 시도`는 같은 인자로 이 함수를 다시 부르므로 repository가 같은 `Idempotency-Key`를
     * 만들어 낸다. 서버는 첫 결과를 그대로 돌려주고 미리보기가 중복 생성되지 않는다(FR-011).
     */
    fun load() {
        if (job?.isActive == true) return
        approveJob?.cancel()
        _state.value = PreviewUiState.Loading
        job = viewModelScope.launch {
            _state.value = build()
        }
    }

    /**
     * 미리보기를 승인해 일정·경로·이력을 함께 바꾼다(REPL-002).
     *
     * 처리 중에는 [PreviewUiState.Content.approving]이 켜져 화면이 행동을 잠근다(UI-005). 실패는
     * 보던 비교를 지우지 않고 [PreviewUiState.Content.approveFailure]로만 남는다 — 원인에 따라
     * 사용자가 이 화면에서 바로 다음 행동을 고르기 때문이다.
     *
     * 승인 전에는 아무것도 바뀌지 않고, 재검증에서 하나라도 어긋나면 서버가 전체를 되돌린다(FR-010).
     */
    fun approve() {
        val content = _state.value as? PreviewUiState.Content ?: return
        if (approveJob?.isActive == true) return
        _state.value = content.copy(approving = true, approveFailure = null)
        approveJob = viewModelScope.launch {
            when (val result = replacements.approvePreview(content.preview.previewId)) {
                is AuthResult.Success -> _approved.value = result.value
                is AuthResult.Failure -> _state.update { current ->
                    (current as? PreviewUiState.Content)
                        ?.copy(approving = false, approveFailure = result.error.toReplacementError())
                        ?: current
                }
            }
        }
    }

    private suspend fun build(): PreviewUiState {
        val detection = when (val result = detections.getDetection(detectionId)) {
            is AuthResult.Success -> result.value
            is AuthResult.Failure -> return PreviewUiState.Error(result.error.toReplacementError())
        }
        // 감지 대상 항목의 도착 예정 시각이 속한 날짜가 그 항목이 놓인 여행 날짜다.
        val date = runCatching { LocalDate.parse(detection.eta.take(ISO_DATE_LENGTH)) }.getOrNull()
            ?: return PreviewUiState.Error(ReplacementError.Unexpected)

        val dayRoute = when (val result = routes.getDayRoute(detection.tripId, date)) {
            is AuthResult.Success -> result.value
            is AuthResult.Failure -> return PreviewUiState.Error(result.error.toReplacementError())
        }
        // 경로가 없는 날짜여도 `scheduleVersion`은 온다. 지도만 변경 경로 하나로 그린다.
        val originalRoute = dayRoute.route?.takeIf { dayRoute.routeStatus == RouteStatus.READY }

        val preview = when (
            val result = replacements.createPreview(
                detectionId = detectionId,
                placeId = placeId,
                candidateId = candidateId,
                scheduleVersion = dayRoute.scheduleVersion,
            )
        ) {
            is AuthResult.Success -> result.value
            is AuthResult.Failure -> return PreviewUiState.Error(result.error.toReplacementError())
        }

        return PreviewUiState.Content(
            preview = preview,
            originalRoute = originalRoute,
            now = Instant.now(clock),
        )
    }

    companion object {
        /** ISO-8601 `2026-09-08T16:00:00+09:00`에서 날짜 부분의 길이. */
        private const val ISO_DATE_LENGTH = 10

        /** 화면이 사용할 의존성을 조립한다. DI 도구를 두지 않는 F001 방식이다. */
        fun factory(
            detectionId: String,
            placeId: String,
            candidateId: String?,
            replacements: ReplacementRepository,
            detections: AlternativeRepository,
            routes: RouteRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PreviewViewModel(
                    replacements = replacements,
                    detections = detections,
                    routes = routes,
                    detectionId = detectionId,
                    placeId = placeId,
                    candidateId = candidateId,
                )
            }
        }
    }
}
