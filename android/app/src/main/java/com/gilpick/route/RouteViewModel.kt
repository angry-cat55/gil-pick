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
import java.time.LocalDate
import kotlinx.coroutines.Job
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
 * @param tripId 여행.
 * @param date 경로를 볼 날짜.
 */
class RouteViewModel(
    private val repository: RouteRepository,
    private val tripId: String,
    private val date: LocalDate,
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
     * 현재 경로를 다시 조회한다. `error`의 `다시 시도`와 화면 재진입이 쓴다.
     *
     * 이미 조회 중이면 아무 것도 하지 않는다. 같은 화면에서 `content`로 전환하므로(UI-003)
     * 이전 내용을 지우고 `loading`으로 돌아간다.
     */
    fun load() {
        if (job?.isActive == true) return
        _state.value = RouteUiState.Loading
        job = viewModelScope.launch {
            _state.value = when (val result = repository.getDayRoute(tripId, date)) {
                is AuthResult.Success -> result.value.toUiState()
                is AuthResult.Failure -> RouteUiState.Error(RouteProblem.Request(result.error.toRouteError()))
            }
        }
    }

    companion object {

        /** 조회 응답을 화면 상태로 바꾼다. version이 다른 경로는 현재 경로로 보이지 않는다(FR-014). */
        internal fun DayRouteDto.toUiState(): RouteUiState = when (routeStatus) {
            RouteStatus.NOT_CALCULATED -> RouteUiState.Empty
            RouteStatus.FAILED -> failure
                ?.let { RouteUiState.Error(RouteProblem.Calculation(it, scheduleVersion)) }
                ?: RouteUiState.Error(RouteProblem.Request(RouteError.Unexpected))

            RouteStatus.READY -> route?.let {
                if (it.scheduleVersion == scheduleVersion) RouteUiState.Content(it) else RouteUiState.Error(RouteProblem.Stale)
            } ?: RouteUiState.Error(RouteProblem.Request(RouteError.Unexpected))
        }

        /** 화면이 사용할 의존성을 조립한다. DI 도구를 두지 않는 F001 방식이다. */
        fun factory(
            tripId: String,
            date: LocalDate,
            repository: RouteRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { RouteViewModel(repository = repository, tripId = tripId, date = date) }
        }

        /** 실제 서버를 향한 repository. F004 `ItineraryEditViewModel.defaultRepository`와 같은 조립이다. */
        fun defaultRepository(context: Context): RouteRepository {
            val appContext = context.applicationContext
            val auth = AuthRepository(
                store = AuthSessionStore.create(appContext),
                api = createAuthRetrofit(BuildConfig.API_BASE_URL).create(AuthService::class.java),
                appLinkHandler = AuthAppLinkHandler(BuildConfig.APP_LINK_HOST),
                scheduleRevocation = SessionRevocationWorker.scheduler(appContext),
            )
            return RouteRepository(
                api = createRouteRetrofit(BuildConfig.API_BASE_URL).create(RouteService::class.java),
                auth = auth,
            )
        }
    }
}
