package com.gilpick.route

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
import com.gilpick.itinerary.RouteStatus
import com.gilpick.notification.FcmTokenClearWorker
import com.gilpick.notification.FcmTokenSyncWorker
import com.gilpick.progress.ProgressRepository
import com.gilpick.progress.toRouteMarks
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 날짜별 경로 화면의 상태 보유자.
 *
 * 한 날짜의 현재 경로(ROUTE-001)만 다룬다. 지도 SDK 객체는 들지 않고 [RouteUiState]만 노출한다
 * (`research.md` 결정 6). 화면 이동은 화면 콜백이 직접 하므로 일회성 event flow가 따로 없다.
 *
 * 시작된 날짜는 진행 현황(PROG-001)을 함께 조회해 marker·구간 목록에 상태를 겹친다(F006 UI-011, T031).
 * 진행 조회가 실패하면 계획만 보인다.
 *
 * @param tripId 여행.
 * @param date 경로를 볼 날짜.
 * @param progressRepository 진행 현황 접근 지점. `null`이면 진행 표시를 겹치지 않는다.
 */
class RouteViewModel(
    private val repository: RouteRepository,
    private val tripId: String,
    private val date: LocalDate,
    private val progressRepository: ProgressRepository? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<RouteUiState>(RouteUiState.Loading)

    /** 화면이 관찰하는 현재 상태. */
    val state: StateFlow<RouteUiState> = _state.asStateFlow()

    /** 진행 중인 조회. `다시 시도` 연타로 겹치는 조회를 막는다. */
    private var job: Job? = null

    init {
        load()
    }

    /**
     * 현재 경로를 다시 조회한다. 화면 재진입과 조회 실패 뒤 `다시 시도`가 쓴다.
     *
     * 이미 조회·재계산 중이면 아무 것도 하지 않는다. 같은 화면에서 `content`로 전환하므로(UI-003)
     * 이전 내용을 지우고 `loading`으로 돌아간다.
     */
    fun load() {
        if (job?.isActive == true) return
        _state.value = RouteUiState.Loading
        job = viewModelScope.launch {
            coroutineScope {
                val marks = async { fetchMarks() }
                _state.value = when (val result = repository.getDayRoute(tripId, date)) {
                    is AuthResult.Success -> result.value.toUiState(marks.await())
                    is AuthResult.Failure -> RouteUiState.Error(RouteProblem.Request(result.error.toRouteError()))
                }
            }
        }
    }

    /** 진행 현황을 진행 표시로 바꾼다. 조회 지점이 없거나 어떤 이유로든 실패하면 계획만 그린다. 경로 조회를 막지 않는다. */
    private suspend fun fetchMarks(): RouteMarks {
        val repository = progressRepository ?: return RouteMarks.NONE
        return runCatching { (repository.getDayProgress(tripId, date) as? AuthResult.Success)?.value?.toRouteMarks() }
            .getOrNull() ?: RouteMarks.NONE
    }

    /**
     * `error`의 `다시 시도`(FR-010, US3).
     *
     * 경로 계산이 최종 실패한 상태([RouteProblem.Calculation])에서만 같은 일정 version으로 재계산을
     * 요청한다(ROUTE-003). 조회 자체가 실패했거나 version이 어긋난 상태에서는 조회만 다시 한다.
     * 정상 경로에서는 부를 곳이 없다(FR-019). 진행 중 연타는 [load]와 같은 job으로 막는다.
     *
     * 재계산 결과는 성공(`READY`)·재실패(`FAILED`, 새 원인) 모두 `200`으로 오고, `409 VERSION_CONFLICT`면
     * 일정이 바뀐 것이라 최신 일정을 다시 확인하도록 안내한다. `409 ROUTE_NOT_FAILED`는 이미 경로가
     * 생긴 것이므로 조회로 대신한다.
     */
    fun retry() {
        val problem = (_state.value as? RouteUiState.Error)?.problem
        if (problem !is RouteProblem.Calculation) {
            load()
            return
        }
        if (job?.isActive == true) return
        _state.value = RouteUiState.Loading
        job = viewModelScope.launch {
            when (val result = repository.retryDayRoute(tripId, date, problem.scheduleVersion)) {
                is AuthResult.Success -> _state.value = result.value.toUiState(fetchMarks())
                is AuthResult.Failure -> when (val error = result.error.toRouteError()) {
                    RouteError.NotFailed -> {
                        job = null
                        load()
                    }

                    else -> _state.value = RouteUiState.Error(RouteProblem.Request(error))
                }
            }
        }
    }

    companion object {

        /** 조회 응답을 화면 상태로 바꾼다. version이 다른 경로는 현재 경로로 보이지 않는다(FR-014). */
        internal fun DayRouteDto.toUiState(marks: RouteMarks = RouteMarks.NONE): RouteUiState = when (routeStatus) {
            RouteStatus.NOT_CALCULATED -> RouteUiState.Empty
            RouteStatus.FAILED -> failure
                ?.let { RouteUiState.Error(RouteProblem.Calculation(it, scheduleVersion)) }
                ?: RouteUiState.Error(RouteProblem.Request(RouteError.Unexpected))

            RouteStatus.READY -> route?.let {
                if (it.scheduleVersion == scheduleVersion) RouteUiState.Content(it, marks) else RouteUiState.Error(RouteProblem.Stale)
            } ?: RouteUiState.Error(RouteProblem.Request(RouteError.Unexpected))
        }

        /** 화면이 사용할 의존성을 조립한다. DI 도구를 두지 않는 F001 방식이다. */
        fun factory(
            tripId: String,
            date: LocalDate,
            repository: RouteRepository,
            progressRepository: ProgressRepository? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { RouteViewModel(repository = repository, tripId = tripId, date = date, progressRepository = progressRepository) }
        }

        /** 실제 서버를 향한 repository. F004 `ItineraryEditViewModel.defaultRepository`와 같은 조립이다. */
        fun defaultRepository(context: Context): RouteRepository {
            val appContext = context.applicationContext
            val auth = AuthRepository(
                store = AuthSessionStore.create(appContext),
                api = createAuthRetrofit(BuildConfig.API_BASE_URL).create(AuthService::class.java),
                appLinkHandler = AuthAppLinkHandler(BuildConfig.APP_LINK_HOST),
                scheduleRevocation = SessionRevocationWorker.scheduler(appContext),
                syncPushToken = { FcmTokenSyncWorker.enqueue(appContext) },
                clearPushToken = { FcmTokenClearWorker.enqueue(appContext) },
            )
            return RouteRepository(
                api = createRouteRetrofit(BuildConfig.API_BASE_URL).create(RouteService::class.java),
                auth = auth,
            )
        }
    }
}
