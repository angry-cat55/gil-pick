package com.gilpick.trip

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gilpick.BuildConfig
import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthResult
import com.gilpick.auth.AuthService
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.SessionRevocationWorker
import com.gilpick.auth.createAuthRetrofit
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 목록을 나누는 세 그룹.
 *
 * 순서는 Figma `MyTripsScreen`의 section 순서이자 `spec.md` FR-005의 정렬 순서다.
 */
enum class TripGroup {
    /** 오늘 진행 중인 여행. */
    IN_PROGRESS,

    /** 아직 시작하지 않은 여행. */
    UPCOMING,

    /** 끝난 여행. */
    COMPLETED,
}

/**
 * 한 그룹과 그 그룹에 속한 여행.
 *
 * @property group 이 구획이 나타내는 그룹.
 * @property trips 화면에 그릴 여행. 서버가 준 순서를 그대로 유지한다.
 */
data class TripGroupSection(
    val group: TripGroup,
    val trips: List<TripDto>,
) {
    /**
     * 그룹의 전체 여행 수.
     *
     * **개수의 출처는 이 프로퍼티 하나다.** 지금은 받아 둔 목록을 직접 세지만, 서버가
     * 그룹별 개수를 `meta`로 내려주면 여기만 그 값을 읽도록 바꾸면 된다. 호출부는
     * 그대로 둘 수 있다.
     *
     * 화면은 아직 이 값을 그리지 않는다. Figma 그룹 헤더에 개수 배지가 없기 때문이다.
     */
    val count: Int get() = trips.size
}

/**
 * 여행 목록을 그룹별 구획으로 나눈다.
 *
 * 빈 그룹은 결과에 넣지 않는다. 화면이 헤더만 있는 구획을 그리지 않도록 여기서 거른다.
 *
 * 서버가 이미 `여행 중 → 예정 → 완료` 순으로 정렬해 주지만(FR-005) 그 순서에 기대지
 * 않고 [TripGroup] 선언 순서로 다시 세운다. 정렬이 바뀌어도 화면 구성은 유지된다.
 */
internal fun groupTrips(trips: List<TripDto>): List<TripGroupSection> {
    val byGroup = trips.groupBy { it.status.group }
    return TripGroup.entries.mapNotNull { group ->
        byGroup[group]?.takeIf { it.isNotEmpty() }?.let { TripGroupSection(group, it) }
    }
}

/** 여행 상태를 목록 그룹에 대응시킨다. */
internal val TripStatus.group: TripGroup
    get() = when (this) {
        TripStatus.IN_PROGRESS -> TripGroup.IN_PROGRESS
        TripStatus.UPCOMING -> TripGroup.UPCOMING
        TripStatus.COMPLETED -> TripGroup.COMPLETED
    }

/** 목록 조회가 실패한 이유. 화면이 원인과 다음 행동을 안내하는 데 쓴다. */
enum class TripListError {
    /** 통신 실패. 같은 요청을 그대로 다시 보낼 수 있다. */
    NETWORK,

    /** 그 밖의 실패. 잠시 후 다시 시도한다. */
    UNEXPECTED,
}

/**
 * 목록 화면의 표시 단계.
 *
 * `docs/design/ui-guidelines.md` 9절이 요구하는 네 상태를 그대로 옮겼다.
 */
sealed interface TripListPhase {
    /** 첫 페이지를 기다리는 중. */
    data object Loading : TripListPhase

    /** 표시할 여행이 있다. */
    data object Content : TripListPhase

    /** 조건에 맞는 여행이 없다. 이유는 [TripListUiState.filtered]로 구분한다. */
    data object Empty : TripListPhase

    /** 조회에 실패했다. */
    data class Failed(val error: TripListError) : TripListPhase
}

/**
 * 여행 목록 화면 상태.
 *
 * 검색어·상태 필터는 표시 단계와 무관하게 유지된다. 조회에 실패해도 사용자가 입력한
 * 조건이 사라지지 않아야 한다.
 *
 * @property hasNext 이어질 페이지가 남았는지 여부. 무한 스크롤 종료 판정에 쓴다.
 * @property loadingMore 다음 페이지를 받아오는 중인지 여부.
 */
data class TripListUiState(
    val query: String = "",
    val statusFilter: TripStatus? = null,
    val trips: List<TripDto> = emptyList(),
    val phase: TripListPhase = TripListPhase.Loading,
    val loadingMore: Boolean = false,
    val hasNext: Boolean = false,
) {
    /**
     * 검색어나 상태 필터가 걸린 상태인지 여부.
     *
     * 여행이 아예 없는 것과 조건에 맞는 결과가 없는 것은 안내 문구와 다음 행동이 다르다.
     */
    val filtered: Boolean get() = query.isNotBlank() || statusFilter != null
}

/**
 * 여행 목록 화면의 상태 보유자.
 *
 * 검색어 입력은 마지막 값 하나만 조회한다. 글자마다 요청하면 목록이 깜빡이고 서버
 * 부하도 커진다. 검색어나 상태 필터가 바뀌면 cursor를 버리고 첫 페이지부터 다시
 * 받는다. cursor는 최초 요청의 조건에 묶여 있어 그대로 쓰면 서버가 거절한다.
 *
 * @property repository 여행 데이터 접근 지점.
 */
class TripListViewModel(private val repository: TripRepository) : ViewModel() {

    private val _state = MutableStateFlow(TripListUiState())

    /** 화면이 관찰하는 현재 목록 상태. */
    val state: StateFlow<TripListUiState> = _state.asStateFlow()

    /** 다음 페이지 요청에 쓸 cursor. 첫 페이지이거나 마지막 페이지면 `null`이다. */
    private var nextCursor: String? = null

    /** 진행 중인 조회. 조건이 바뀌면 취소하고 새로 시작한다. */
    private var loadJob: Job? = null

    /** 첫 페이지를 조회한다. 이미 받아 둔 목록이 있으면 새로 채운다. */
    fun load() {
        startLoad(debounce = false)
    }

    /** 조회에 실패한 뒤 같은 조건으로 다시 시도한다. */
    fun retry() {
        startLoad(debounce = false)
    }

    /** 검색어를 반영한다. 입력이 멈춘 뒤에 한 번만 조회한다. */
    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
        startLoad(debounce = true)
    }

    /** 상태 필터를 반영한다. 같은 값을 다시 고르면 해제하는 것은 화면이 정한다. */
    fun onStatusFilterChange(status: TripStatus?) {
        _state.update { it.copy(statusFilter = status) }
        startLoad(debounce = false)
    }

    /**
     * 다음 페이지를 이어서 받는다.
     *
     * 마지막 페이지이거나 이미 받아오는 중이면 아무것도 하지 않는다. 목록 끝에 닿을
     * 때마다 호출되므로 중복 요청을 여기서 막는다.
     */
    fun loadMore() {
        val cursor = nextCursor ?: return
        if (_state.value.loadingMore || loadJob?.isActive == true) return

        _state.update { it.copy(loadingMore = true) }
        loadJob = viewModelScope.launch {
            val current = _state.value
            when (val result = fetch(current, cursor)) {
                is AuthResult.Success -> {
                    val page = result.value
                    nextCursor = page.nextCursor.takeIf { page.hasNext }
                    _state.update {
                        it.copy(
                            trips = it.trips + page.trips,
                            hasNext = page.hasNext,
                            loadingMore = false,
                        )
                    }
                }

                // 이미 보여 주고 있는 목록은 지우지 않는다. 추가 로드만 실패한 것이다.
                is AuthResult.Failure -> _state.update { it.copy(loadingMore = false) }
            }
        }
    }

    /**
     * 첫 페이지 조회를 시작한다.
     *
     * @param debounce 입력이 멈출 때까지 기다릴지 여부. 검색어 입력에만 쓴다.
     */
    private fun startLoad(debounce: Boolean) {
        loadJob?.cancel()
        nextCursor = null
        loadJob = viewModelScope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MILLIS)
            _state.update { it.copy(phase = TripListPhase.Loading, loadingMore = false) }

            val current = _state.value
            when (val result = fetch(current, cursor = null)) {
                is AuthResult.Success -> {
                    val page = result.value
                    nextCursor = page.nextCursor.takeIf { page.hasNext }
                    _state.update {
                        it.copy(
                            trips = page.trips,
                            hasNext = page.hasNext,
                            phase = if (page.trips.isEmpty()) TripListPhase.Empty
                            else TripListPhase.Content,
                        )
                    }
                }

                is AuthResult.Failure -> _state.update {
                    it.copy(phase = TripListPhase.Failed(result.error.toListError()))
                }
            }
        }
    }

    /** 현재 조건으로 한 페이지를 요청한다. */
    private suspend fun fetch(state: TripListUiState, cursor: String?) = repository.listTrips(
        query = state.query,
        status = state.statusFilter,
        cursor = cursor,
        limit = PAGE_LIMIT,
    )

    companion object {

        /** 입력이 멈췄다고 보는 시간. 짧으면 깜빡이고 길면 반응이 느리게 느껴진다. */
        private const val SEARCH_DEBOUNCE_MILLIS = 300L

        /**
         * 한 번에 받을 여행 수. 계약이 허용하는 최대값이다.
         *
         * 그룹 헤더는 그룹 전체를 한 화면에 놓고 봐야 뜻이 통한다. 20개씩 나눠 받으면
         * 다음 페이지에서 같은 그룹이 다시 나오거나 그룹 경계가 페이지에 걸린다.
         *
         * cursor 추가 조회는 그대로 둔다(FR-009). 여행이 100개를 넘으면 지금처럼
         * 무한 스크롤로 이어 받고, [TripListUiState.hasNext]가 그 상태를 알린다.
         */
        private const val PAGE_LIMIT = 100

        /**
         * 화면이 사용할 의존성을 조립한다.
         *
         * DI 도구를 두지 않는 F001 방식을 그대로 따른다.
         */
        fun factory(context: Context): ViewModelProvider.Factory {
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
                    TripListViewModel(
                        TripRepository(
                            api = createTripRetrofit(BuildConfig.API_BASE_URL)
                                .create(TripService::class.java),
                            auth = auth,
                        ),
                    )
                }
            }
        }
    }
}

/** 통신·서버 실패를 화면이 안내할 수 있는 원인으로 좁힌다. */
private fun AuthError.toListError(): TripListError = when (this) {
    is AuthError.Offline -> TripListError.NETWORK
    else -> TripListError.UNEXPECTED
}

/**
 * 진행 중인 여행이 오늘 며칠째인지.
 *
 * 시작일 당일이 `1일째`다. 마지막 날도 따로 다루지 않고 그대로 `N일째`로 센다.
 *
 * 기준 시각대는 Asia/Seoul이다. [TripStatus]를 서버가 KST 기준으로 산정하므로
 * (`spec.md` FR-006), 화면이 다른 시각대로 세면 상태와 일수가 어긋난다.
 *
 * @param startDate 계약이 정한 `yyyy-MM-dd` 문자열.
 * @param today 기준 날짜. 화면은 [KST]의 오늘을 넘긴다.
 */
internal fun tripDayIndex(startDate: String, today: LocalDate): Int =
    (ChronoUnit.DAYS.between(LocalDate.parse(startDate), today) + 1).toInt()

/** 여행 상태와 일수 계산의 기준 시각대. `spec.md` FR-006. */
internal val KST: ZoneId = ZoneId.of("Asia/Seoul")
