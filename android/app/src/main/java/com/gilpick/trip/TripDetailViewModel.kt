package com.gilpick.trip

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
import com.gilpick.itinerary.DayItineraryDto
import com.gilpick.itinerary.ItineraryError
import com.gilpick.itinerary.ItineraryRepository
import com.gilpick.itinerary.ItineraryService
import com.gilpick.itinerary.RouteStatus
import com.gilpick.itinerary.createItineraryRetrofit
import com.gilpick.itinerary.toItineraryError
import com.gilpick.route.RouteDto
import com.gilpick.route.RouteError
import com.gilpick.route.RouteFailureDto
import com.gilpick.route.RouteRepository
import com.gilpick.route.RouteService
import com.gilpick.route.createRouteRetrofit
import com.gilpick.route.toRouteError
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 상세 화면의 표시 단계.
 *
 * `docs/design/ui-guidelines.md` 9절의 네 상태 가운데 세 가지만 쓴다. `empty`는 상세
 * 조회에 성립하지 않는다. 여행 하나를 특정해 요청하므로 성공하면 반드시 내용이 있고,
 * 없으면 계약상 `404`라 [Failed]가 된다. 빈 성공 응답이 존재하지 않는다.
 */
sealed interface TripDetailPhase {
    /** 조회를 기다리는 중. */
    data object Loading : TripDetailPhase

    /** 여행을 받았다. */
    data class Content(val trip: TripDto) : TripDetailPhase

    /** 조회에 실패했다. */
    data class Failed(val error: TripDetailError) : TripDetailPhase
}

/**
 * 상세 화면 일정 영역의 표시 단계.
 *
 * 여행 정보와 별도로 둔다. 일정 조회가 실패해도 이름·기간·상태는 그대로 보여야 하고
 * `다시 시도`도 일정 영역에만 붙는다(`spec.md` US3 Acceptance Scenario 4).
 *
 * `empty`를 따로 두지 않는다. ITIN-003은 여행 기간의 모든 날짜를 항상 돌려주므로
 * 성공 응답에는 언제나 날짜가 있다. "보여줄 것이 없는" 경우는 날짜가 없는 것이 아니라
 * 장소가 없는 날짜이며, 그것은 [Content] 안에서 날짜별 빈 행으로 표시한다.
 */
sealed interface ItineraryOverviewPhase {
    /** 조회를 기다리는 중. */
    data object Loading : ItineraryOverviewPhase

    /**
     * 날짜별 일정을 받았다.
     *
     * @property days 여행 기간의 모든 날짜. 저장된 적 없는 날짜는 빈 [DayItineraryDto.items]다.
     */
    data class Content(val days: List<DayItineraryDto>) : ItineraryOverviewPhase

    /** 조회에 실패했다. 여행 정보는 영향받지 않는다. */
    data class Failed(val error: ItineraryError) : ItineraryOverviewPhase
}

/**
 * 상세 화면 날짜별 경로 영역의 표시 상태(F005).
 *
 * 일정 내용([ItineraryOverviewPhase])과 분리한다. 저장 뒤 자동 경로 계산만 실패해도 장소·순서·
 * 체류 시간·이동 수단은 그대로 표시하고 경로 영역만 독립된 실패 상태가 된다(FR-009a, UI-002a).
 */
sealed interface DayRoutePhase {
    /** 장소가 없어 경로가 없다. 또는 일정 version과 다른 이전 경로라 현재 경로로 보이지 않는다(FR-014). */
    data object NotCalculated : DayRoutePhase

    /** 현재 일정 version의 계획 경로. 장소가 한 곳이면 구간 없이 합계 0이다(FR-020). */
    data class Ready(val route: RouteDto) : DayRoutePhase

    /** `다시 시도`로 재계산을 요청해 응답을 기다린다. 일정 내용은 그대로 두고 경로 영역만 계산 중을 표시한다(UI-004). */
    data object Calculating : DayRoutePhase

    /**
     * 자동 계산이 최종 실패했다. 일정은 보존됐고 `다시 시도`만 제공한다(FR-010).
     *
     * @property failure 실패 원인. 개요(ITIN-003)에는 원인이 없어 처음엔 `null`이고, 경로 조회나
     *   재시도 응답을 받으면 채운다.
     * @property requestError 마지막 `다시 시도` 요청 자체가 실패한 이유(통신 단절, version 충돌 등).
     *   재계산이 다시 실패한 경우는 여기가 아니라 [failure]가 새 원인으로 바뀐다.
     */
    data class Failed(val failure: RouteFailureDto?, val requestError: RouteError? = null) : DayRoutePhase
}

/**
 * 삭제 요청의 진행 단계.
 *
 * 확인 다이얼로그를 열었는지는 여기 담지 않는다. 그것은 화면 안에서만 쓰이는 표시
 * 상태라 화면이 직접 들고 있는 편이 낫다. 여기에는 요청을 보낸 뒤에야 알 수 있는
 * 결과만 둔다.
 */
sealed interface TripDeletePhase {
    /** 아직 삭제를 요청하지 않았다. */
    data object Idle : TripDeletePhase

    /** 요청을 보냈고 응답을 기다린다. 다이얼로그의 버튼을 잠근다. */
    data object Deleting : TripDeletePhase

    /**
     * 삭제됐다. 화면은 이 값을 보고 목록으로 돌아간다.
     *
     * 돌아간 뒤에는 [TripDetailViewModel.consumeDeleted]로 되돌려, 같은 신호가 다시
     * 소비되지 않게 한다.
     */
    data object Deleted : TripDeletePhase

    /** 삭제에 실패했다. 여행은 그대로 남아 있다. */
    data class Failed(val error: TripDeleteError) : TripDeletePhase
}

/**
 * 여행 상세 화면 상태.
 *
 * @property phase 현재 표시 단계.
 * @property itinerary 일정 영역의 표시 단계. 여행 조회와 별도 요청이라 [phase]에 섞지 않는다.
 * @property deletion 삭제 요청의 진행 단계. 조회 단계와 독립적이라 [phase]에 섞지 않는다.
 */
data class TripDetailUiState(
    val phase: TripDetailPhase = TripDetailPhase.Loading,
    val itinerary: ItineraryOverviewPhase = ItineraryOverviewPhase.Loading,
    val deletion: TripDeletePhase = TripDeletePhase.Idle,
)

/**
 * 여행 상세 화면의 상태 보유자.
 *
 * 여행 상태(`예정`/`여행 중`/`완료`)는 서버가 KST 기준으로 계산해 내려주므로
 * (`spec.md` FR-006) 여기서 다시 계산하지 않고 [TripDto]를 그대로 화면에 넘긴다.
 *
 * 여행 정보와 일정은 서로 다른 endpoint(TRIP-003, ITIN-003)라 각각 조회하고 각각의
 * 실패를 따로 들고 있는다. 한쪽 실패가 다른 쪽 화면을 지우지 않는다.
 *
 * @property repository 여행 데이터 접근 지점.
 * @property itineraryRepository 날짜별 일정 개요 접근 지점.
 * @property routeRepository 날짜별 경로 조회·재시도 접근 지점(F005).
 * @property tripId 이 화면이 보여 줄 여행.
 */
class TripDetailViewModel(
    private val repository: TripRepository,
    private val itineraryRepository: ItineraryRepository,
    private val routeRepository: RouteRepository,
    private val tripId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(TripDetailUiState())

    /** 화면이 관찰하는 현재 상세 상태. */
    val state: StateFlow<TripDetailUiState> = _state.asStateFlow()

    private val _routes = MutableStateFlow<Map<String, DayRoutePhase>>(emptyMap())

    /**
     * 날짜(`yyyy-MM-dd`)별 경로 영역 상태. 일정 개요를 받을 때 채운다.
     *
     * [state]의 일정 내용과 따로 둔다. 경로 재시도는 이 flow만 바꾸고 일정 내용은 건드리지
     * 않아야 하기 때문이다(FR-009). 개요에 없는 날짜는 [DayRoutePhase.NotCalculated]로 본다.
     */
    val routes: StateFlow<Map<String, DayRoutePhase>> = _routes.asStateFlow()

    /** 진행 중인 여행 조회. 재시도하면 앞선 요청을 취소한다. */
    private var loadJob: Job? = null

    /** 진행 중인 일정 조회. 여행 조회와 따로 취소·재시도한다. */
    private var itineraryJob: Job? = null

    /** 여행 상세와 날짜별 일정을 함께 조회한다. */
    fun load() {
        loadTrip()
        loadItinerary()
    }

    /** 여행 상세만 조회한다. */
    private fun loadTrip() {
        loadJob?.cancel()
        // 조회를 시작하기 전에 동기적으로 바꾼다. coroutine 안에서 바꾸면 dispatcher가
        // 돌기 전까지 앞선 실패 화면이 남아 재시도가 눌렸는지 알 수 없다.
        _state.update { it.copy(phase = TripDetailPhase.Loading) }

        loadJob = viewModelScope.launch {
            val phase = when (val result = repository.getTrip(tripId)) {
                is AuthResult.Success -> TripDetailPhase.Content(result.value)
                is AuthResult.Failure -> TripDetailPhase.Failed(result.error.toDetailError())
            }
            _state.update { it.copy(phase = phase) }
        }
    }

    /** 날짜별 일정 개요만 조회한다(ITIN-003). */
    private fun loadItinerary() {
        itineraryJob?.cancel()
        _state.update { it.copy(itinerary = ItineraryOverviewPhase.Loading) }

        itineraryJob = viewModelScope.launch {
            val phase = when (val result = itineraryRepository.getOverview(tripId)) {
                is AuthResult.Success -> {
                    _routes.value = result.value.days.associate { it.date to it.toRoutePhase() }
                    // 개요에는 실패 원인이 없다. 실패한 날짜만 경로를 따로 조회해 원인을 채운다(UI-002a).
                    result.value.days.filter { it.routeStatus == RouteStatus.FAILED }.forEach { loadFailure(it.date) }
                    ItineraryOverviewPhase.Content(result.value.days)
                }
                is AuthResult.Failure ->
                    ItineraryOverviewPhase.Failed(result.error.toItineraryError())
            }
            _state.update { it.copy(itinerary = phase) }
        }
    }

    /** 실패한 날짜의 경로를 조회해 원인만 채운다. 조회가 실패하면 원인 없는 실패로 둔다. */
    private fun loadFailure(date: String) {
        viewModelScope.launch {
            val result = routeRepository.getDayRoute(tripId, LocalDate.parse(date))
            val failure = (result as? AuthResult.Success)?.value?.failure ?: return@launch
            _routes.update { routes ->
                if (routes[date] is DayRoutePhase.Failed) routes + (date to DayRoutePhase.Failed(failure)) else routes
            }
        }
    }

    /**
     * 실패한 날짜의 경로를 같은 일정 입력으로 다시 계산한다(FR-010, ROUTE-003).
     *
     * 실패 상태가 아니거나 이미 계산 중이면 아무 것도 하지 않는다(정상 경로 재계산 금지 FR-019, 중복 클릭
     * 방지). 일정 내용은 건드리지 않고 [routes]만 바꾼다(FR-009). 재계산 결과는 성공·재실패 모두 `200`이고,
     * `409 VERSION_CONFLICT`·`ROUTE_NOT_FAILED`는 일정이나 경로가 이미 바뀐 것이라 개요를 다시 받는다.
     */
    fun retryRoute(date: String) {
        val current = _routes.value[date] as? DayRoutePhase.Failed ?: return
        val version = (_state.value.itinerary as? ItineraryOverviewPhase.Content)
            ?.days?.firstOrNull { it.date == date }?.version ?: return
        _routes.update { it + (date to DayRoutePhase.Calculating) }

        viewModelScope.launch {
            val phase = when (val result = routeRepository.retryDayRoute(tripId, LocalDate.parse(date), version)) {
                is AuthResult.Success -> when (result.value.routeStatus) {
                    RouteStatus.READY -> result.value.route?.takeIf { it.scheduleVersion == version }
                        ?.let { DayRoutePhase.Ready(it) } ?: DayRoutePhase.NotCalculated
                    RouteStatus.FAILED -> DayRoutePhase.Failed(result.value.failure ?: current.failure)
                    RouteStatus.NOT_CALCULATED -> DayRoutePhase.NotCalculated
                }

                is AuthResult.Failure -> when (val error = result.error.toRouteError()) {
                    RouteError.VersionConflict, RouteError.NotFailed -> {
                        loadItinerary()
                        DayRoutePhase.Failed(current.failure, requestError = error)
                    }

                    else -> DayRoutePhase.Failed(current.failure, requestError = error)
                }
            }
            _routes.update { routes ->
                // 개요를 다시 받는 중이면 그쪽 결과가 이긴다.
                if (routes[date] is DayRoutePhase.Calculating) routes + (date to phase) else routes
            }
        }
    }

    /** 여행 조회에 실패한 뒤 다시 조회한다. 일정은 건드리지 않는다. */
    fun retry() {
        loadTrip()
    }

    /** 일정 조회에 실패한 뒤 일정만 다시 조회한다(US3 Acceptance Scenario 4). */
    fun retryItinerary() {
        loadItinerary()
    }

    /**
     * 이 여행을 삭제한다. 확인 다이얼로그에서 `삭제`를 눌렀을 때만 호출한다.
     *
     * 이미 요청을 보냈으면 아무 것도 하지 않는다. 응답을 기다리는 사이 버튼이 두 번
     * 눌리면 같은 삭제가 두 번 나가고, 두 번째 요청은 서버가 `404`로 거절해 성공한
     * 삭제가 실패로 보인다.
     */
    fun delete() {
        if (_state.value.deletion is TripDeletePhase.Deleting) return

        // 조회와 달리 앞선 요청을 취소하지 않는다. 취소해도 서버에 이미 도착한 삭제는
        // 되돌아가지 않으므로, 결과를 못 받는 쪽이 더 나쁘다.
        _state.update { it.copy(deletion = TripDeletePhase.Deleting) }

        viewModelScope.launch {
            val deletion = when (val result = repository.deleteTrip(tripId)) {
                is AuthResult.Success -> TripDeletePhase.Deleted
                is AuthResult.Failure -> TripDeletePhase.Failed(result.error.toDeleteError())
            }
            _state.update { it.copy(deletion = deletion) }
        }
    }

    /** 삭제 완료 신호를 소비한다. 목록으로 돌아간 뒤 화면이 호출한다. */
    fun consumeDeleted() {
        _state.update { it.copy(deletion = TripDeletePhase.Idle) }
    }

    /** 삭제 실패 안내를 지운다. 사용자가 다이얼로그를 닫으면 화면이 호출한다. */
    fun clearDeleteError() {
        if (_state.value.deletion is TripDeletePhase.Failed) {
            _state.update { it.copy(deletion = TripDeletePhase.Idle) }
        }
    }

    /**
     * 개요의 한 날짜를 경로 영역 상태로 바꾼다.
     *
     * `READY`라도 경로의 `scheduleVersion`이 일정 version과 다르면 이전 일정의 결과이므로 현재
     * 경로로 표시하지 않는다(FR-014). 서버가 현재 version의 경로만 주기로 계약했지만, 앱은
     * 그 보장을 다시 확인하고 어긋나면 `정보 없음` 쪽으로 기운다.
     */
    private fun DayItineraryDto.toRoutePhase(): DayRoutePhase = when (routeStatus) {
        RouteStatus.NOT_CALCULATED -> DayRoutePhase.NotCalculated
        RouteStatus.FAILED -> DayRoutePhase.Failed(failure = null)
        RouteStatus.READY -> route?.takeIf { it.scheduleVersion == version }
            ?.let { DayRoutePhase.Ready(it) }
            ?: DayRoutePhase.NotCalculated
    }

    companion object {

        /**
         * 화면이 사용할 의존성을 조립한다.
         *
         * DI 도구를 두지 않는 F001 방식을 그대로 따른다.
         *
         * @param tripId 상세를 볼 여행. navigation route가 넘긴다.
         */
        fun factory(context: Context, tripId: String): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    val auth = AuthRepository(
                        store = AuthSessionStore.create(appContext),
                        api = createAuthRetrofit(BuildConfig.API_BASE_URL)
                            .create(AuthService::class.java),
                        appLinkHandler = AuthAppLinkHandler(BuildConfig.APP_LINK_HOST),
                        scheduleRevocation = SessionRevocationWorker.scheduler(appContext),
                    )
                    TripDetailViewModel(
                        repository = TripRepository(
                            api = createTripRetrofit(BuildConfig.API_BASE_URL)
                                .create(TripService::class.java),
                            auth = auth,
                        ),
                        itineraryRepository = ItineraryRepository(
                            api = createItineraryRetrofit(BuildConfig.API_BASE_URL)
                                .create(ItineraryService::class.java),
                            auth = auth,
                        ),
                        routeRepository = RouteRepository(
                            api = createRouteRetrofit(BuildConfig.API_BASE_URL).create(RouteService::class.java),
                            auth = auth,
                        ),
                        tripId = tripId,
                    )
                }
            }
        }
    }
}
