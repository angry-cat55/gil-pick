package com.gilpick.place

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gilpick.auth.AuthResult
import com.gilpick.progress.CurrentLocationDto
import com.gilpick.progress.CurrentLocationProvider
import com.gilpick.progress.DeviceLocationProvider
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 검색 화면의 표시 단계.
 *
 * 검색을 아직 실행하지 않은 [Idle]이 따로 있다. 검색어 입력만으로는 검색하지 않으므로
 * (`spec.md` FR-003a) "아직 안 찾았다"와 "찾았는데 없다"([Empty])는 다른 화면이다.
 */
sealed interface PlaceSearchPhase {
    /** 검색을 실행한 적이 없다. Figma `어떤 장소를 찾고 계세요?`. */
    data object Idle : PlaceSearchPhase

    /** 첫 페이지를 기다리는 중. */
    data object Loading : PlaceSearchPhase

    /** 결과가 있다. 목록은 [PlaceSearchUiState.results]다. */
    data object Content : PlaceSearchPhase

    /** 정상 응답이지만 결과가 없다. */
    data object Empty : PlaceSearchPhase

    /** 위치 권한이 없거나 현재 좌표를 얻지 못해 주변 조회를 시작할 수 없다. */
    data object LocationUnavailable : PlaceSearchPhase

    /** 조건이 계약에 맞지 않아 요청하지 않았다(FR-003·FR-003b). */
    data class Invalid(val reason: InvalidReason) : PlaceSearchPhase

    /** 첫 페이지 조회에 실패했다. */
    data class Failed(val error: PlaceError) : PlaceSearchPhase
}

/** 요청 전에 걸러진 이유. */
enum class InvalidReason {
    /** 키워드도 category도 없다. */
    NO_CONDITION,

    /** 공백을 뗀 키워드가 2글자 미만이다. */
    TOO_SHORT,
}

/**
 * 검색 화면 상태.
 *
 * [query]·[category]는 사용자가 고치는 중인 **draft**, [committedQuery]·[committedCategory]는
 * 마지막으로 실행한 검색의 조건이다. 결과 요약·빈 결과 문구는 committed 값을 쓴다. draft만 바꾸고
 * 검색하지 않았을 때 목록이 새 조건의 결과처럼 보이면 안 된다(UI-003).
 *
 * @property query 입력창의 현재 값.
 * @property category 선택한 칩. `null`이면 `전체`.
 * @property results 화면에 보이는 결과. [PlaceSearchPhase.Content]가 아니면 비어 있다.
 * @property hasNext 이어질 페이지가 남았는지 여부.
 * @property loadingMore 다음 페이지를 받는 중인지 여부.
 * @property loadMoreError 다음 페이지 조회만 실패한 원인. 기존 결과는 유지한다(FR-012). `null`이면 실패하지 않았다.
 * @property distanceOrigin `거리순` 기준점(현재 위치). `null`이 아니면 [displayedResults]가 이 점에서 가까운 순이다(#505).
 * @property locating `거리순`을 눌러 현재 위치를 얻는 중.
 * @property distanceSortUnavailable 현재 위치를 얻지 못해(권한 없음·실패) `거리순`을 쓸 수 없다.
 */
data class PlaceSearchUiState(
    val query: String = "",
    val category: PlaceCategory? = null,
    val committedQuery: String = "",
    val committedCategory: PlaceCategory? = null,
    val results: List<PlaceDto> = emptyList(),
    val phase: PlaceSearchPhase = PlaceSearchPhase.Idle,
    val hasNext: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreError: PlaceError? = null,
    val nearbyOrigin: CurrentLocationDto? = null,
    val distanceOrigin: CurrentLocationDto? = null,
    val locating: Boolean = false,
    val distanceSortUnavailable: Boolean = false,
) {
    /** 화면에 그리는 순서. `거리순`이면 받은 결과를 기준점에서 가까운 순으로, 아니면 서버 순서 그대로다. */
    val displayedResults: List<PlaceDto>
        get() = distanceOrigin?.let { results.sortedByDistanceFrom(it.latitude, it.longitude) } ?: results
}

/**
 * 검색 화면의 상태 보유자.
 *
 * 검색은 [search]와 칩 선택으로 시작한다. 입력 변경은 draft만 바꾼다(FR-003a). 다음 페이지는 cursor로
 * 이어 받고 `placeId`가 겹치는 항목은 버린다(FR-005). destination-scoped라 상세에서 돌아와도
 * 조건·결과가 남는다(UI-009).
 *
 * @property repository 장소 데이터 접근 지점.
 * @property locationProvider `거리순` 기준점인 현재 위치를 한 번 얻는다.
 */
class PlaceSearchViewModel(
    private val repository: PlaceRepository,
    private val locationProvider: CurrentLocationProvider = CurrentLocationProvider { null },
) : ViewModel() {

    private val _state = MutableStateFlow(PlaceSearchUiState())

    /** 화면이 관찰하는 현재 검색 상태. */
    val state: StateFlow<PlaceSearchUiState> = _state.asStateFlow()

    /** 다음 페이지 요청에 쓸 cursor. 첫 페이지이거나 마지막 페이지면 `null`이다. */
    private var nextCursor: String? = null

    /** 진행 중인 조회. 새 검색이 시작되면 취소한다. */
    private var loadJob: Job? = null

    /** 현재 위치를 얻는 작업. 검색 요청과 별도로 취소한다. */
    private var locationJob: Job? = null

    /** 입력창 값을 반영한다. 검색하지 않는다. */
    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
    }

    /** 입력창을 비운다. 검색하지 않는다. */
    fun onClearQuery() {
        onQueryChange("")
    }

    /**
     * 칩 선택을 반영하고 곧바로 현재 검색어와 함께 다시 검색한다(FR-003a).
     *
     * 이미 고른 칩을 다시 누르면 아무것도 하지 않는다. 검색어가 없으면 저장한 현재 좌표로
     * 주변 카테고리 또는 주변 전체를 다시 조회한다.
     */
    fun onCategoryChange(category: PlaceCategory?) {
        if (category == _state.value.category) return
        _state.update { it.copy(category = category) }
        if (_state.value.query.isBlank()) loadNearby() else search()
    }

    /**
     * 빈 결과의 `카테고리로 찾기`: 키워드를 지우고 검색 전 상태로 돌아가 칩을 고르게 한다.
     *
     * 결과 목록도 비운다. 지운 검색어의 결과가 남아 있으면 무엇의 결과인지 알 수 없다.
     */
    fun onSearchByCategory() {
        _state.update { it.copy(query = "") }
        loadNearby()
    }

    /**
     * 현재 draft 조건으로 검색을 실행한다. 키보드의 검색 동작과 칩 선택이 부른다.
     *
     * 짧은 검색어는 요청하지 않고 [PlaceSearchPhase.Invalid]로 안내한다(FR-003b).
     * 앞선 결과는 새 결과로 교체된다(UI-003).
     */
    fun search() {
        locationJob?.cancel()
        val current = _state.value
        val query = current.query.trim()
        val category = current.category
        val invalid = when {
            query.isNotEmpty() && query.length < MIN_QUERY_LENGTH -> InvalidReason.TOO_SHORT
            else -> null
        }
        if (invalid != null) {
            loadJob?.cancel()
            nextCursor = null
            _state.update { it.copy(results = emptyList(), phase = PlaceSearchPhase.Invalid(invalid), hasNext = false, loadingMore = false, loadMoreError = null) }
            return
        }
        if (query.isEmpty()) {
            loadNearby()
            return
        }
        _state.update {
            it.copy(
                committedQuery = query,
                committedCategory = category,
                nearbyOrigin = null,
                distanceOrigin = null,
            )
        }
        startSearch()
    }

    /**
     * 현재 위치를 얻어 검색어 없는 5km 주변 조회를 시작한다(#607).
     *
     * 카테고리 변경 때는 이미 얻은 좌표를 재사용한다. 좌표가 없으면 임의 목록으로 대체하지 않고
     * [PlaceSearchPhase.LocationUnavailable]에서 사용자가 재시도할 수 있게 한다.
     */
    fun loadNearby() {
        val saved = _state.value.nearbyOrigin
        if (saved != null) {
            _state.update {
                it.copy(
                    committedQuery = "",
                    committedCategory = it.category,
                    distanceOrigin = saved,
                )
            }
            startSearch()
            return
        }
        locationJob?.cancel()
        loadJob?.cancel()
        nextCursor = null
        _state.update {
            it.copy(
                results = emptyList(),
                phase = PlaceSearchPhase.Loading,
                locating = true,
                hasNext = false,
                loadingMore = false,
                loadMoreError = null,
            )
        }
        locationJob = viewModelScope.launch {
            val origin = locationProvider.current()
            if (origin == null) {
                _state.update { it.copy(phase = PlaceSearchPhase.LocationUnavailable, locating = false) }
                return@launch
            }
            _state.update {
                it.copy(
                    committedQuery = "",
                    committedCategory = it.category,
                    nearbyOrigin = origin,
                    distanceOrigin = origin,
                    locating = false,
                )
            }
            startSearch()
        }
    }

    /**
     * `거리순`을 켜고 끈다(#505).
     *
     * 켤 때마다 현재 위치를 새로 얻는다. 얻지 못하면 기준점이 없으므로 `거리순`을 비활성으로 둔다.
     */
    fun toggleDistanceSort() {
        val current = _state.value
        if (current.locating || current.distanceSortUnavailable) return
        if (current.distanceOrigin != null) {
            _state.update { it.copy(distanceOrigin = null) }
            return
        }
        _state.update { it.copy(locating = true) }
        viewModelScope.launch {
            val origin = locationProvider.current()
            _state.update { it.copy(distanceOrigin = origin, locating = false, distanceSortUnavailable = origin == null) }
        }
    }

    /** 첫 페이지 조회에 실패한 뒤 같은 조건으로 다시 시도한다. */
    fun retry() {
        if (_state.value.committedQuery.isBlank() && _state.value.nearbyOrigin == null) {
            loadNearby()
        } else {
            startSearch()
        }
    }

    /**
     * 다음 페이지를 이어서 받는다.
     *
     * 마지막 페이지이거나 이미 받는 중이거나 앞선 추가 조회가 실패해 있으면 아무것도 하지 않는다.
     * 실패 뒤에는 사용자가 [retryLoadMore]로만 다시 시도한다(FR-012). 목록 끝에 닿을 때마다
     * 불리므로 자동으로 재시도하면 실패를 반복한다.
     */
    fun loadMore() {
        if (_state.value.loadMoreError != null) return
        fetchNextPage()
    }

    /** 실패한 추가 조회를 다시 시도한다. */
    fun retryLoadMore() {
        _state.update { it.copy(loadMoreError = null) }
        fetchNextPage()
    }

    private fun fetchNextPage() {
        val cursor = nextCursor ?: return
        if (_state.value.loadingMore || loadJob?.isActive == true) return

        _state.update { it.copy(loadingMore = true) }
        loadJob = viewModelScope.launch {
            val current = _state.value
            when (
                val result = repository.searchPlaces(
                    current.committedQuery,
                    current.committedCategory,
                    latitude = current.nearbyOrigin?.latitude,
                    longitude = current.nearbyOrigin?.longitude,
                    cursor = cursor,
                )
            ) {
                is AuthResult.Success -> {
                    val page = result.value
                    nextCursor = page.nextCursor.takeIf { page.hasNext }
                    _state.update {
                        val known = it.results.mapTo(HashSet()) { place -> place.placeId }
                        it.copy(
                            results = it.results + page.places.filter { place -> known.add(place.placeId) },
                            hasNext = page.hasNext,
                            loadingMore = false,
                        )
                    }
                }

                // 이미 보여 주고 있는 목록은 지우지 않는다. 추가 조회만 실패한 것이라 원인만 붙인다.
                is AuthResult.Failure -> _state.update {
                    it.copy(loadingMore = false, loadMoreError = result.error.toPlaceError())
                }
            }
        }
    }

    private fun startSearch() {
        loadJob?.cancel()
        nextCursor = null
        // 조회를 시작하기 전에 동기적으로 바꾼다. 앞선 결과가 새 조건의 결과처럼 남지 않게 한다.
        _state.update { it.copy(results = emptyList(), phase = PlaceSearchPhase.Loading, hasNext = false, loadingMore = false, loadMoreError = null) }

        loadJob = viewModelScope.launch {
            val current = _state.value
            when (
                val result = repository.searchPlaces(
                    current.committedQuery,
                    current.committedCategory,
                    latitude = current.nearbyOrigin?.latitude,
                    longitude = current.nearbyOrigin?.longitude,
                    cursor = null,
                )
            ) {
                is AuthResult.Success -> {
                    val page = result.value
                    nextCursor = page.nextCursor.takeIf { page.hasNext }
                    val places = page.places.distinctBy { it.placeId }
                    _state.update {
                        it.copy(
                            results = places,
                            hasNext = page.hasNext,
                            phase = if (places.isEmpty()) PlaceSearchPhase.Empty else PlaceSearchPhase.Content,
                        )
                    }
                }

                is AuthResult.Failure -> _state.update {
                    it.copy(phase = PlaceSearchPhase.Failed(result.error.toPlaceError()))
                }
            }
        }
    }

    companion object {

        /** 키워드 최소 길이(FR-003b). 공백을 뗀 뒤 잰다. */
        const val MIN_QUERY_LENGTH = 2

        /** 화면이 사용할 의존성을 조립한다. */
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer { PlaceSearchViewModel(createPlaceRepository(appContext), DeviceLocationProvider.create(appContext)) }
            }
        }
    }
}

/**
 * 받은 결과를 ([latitude], [longitude])에서 가까운 순으로 정렬한다. 좌표 없는 장소는 원래 순서대로 끝에 둔다.
 *
 * ponytail: 이미 받은 페이지만 정렬한다. 다음 페이지가 붙으면 더 가까운 장소가 중간에 끼어들 수 있다.
 * 전체 결과의 가까운 순이 필요해지면 서버 정렬(BE 계약 추가)로 옮긴다.
 */
internal fun List<PlaceDto>.sortedByDistanceFrom(latitude: Double, longitude: Double): List<PlaceDto> =
    sortedBy { place ->
        val lat = place.latitude
        val lng = place.longitude
        if (lat == null || lng == null) Double.MAX_VALUE else distanceMeters(latitude, longitude, lat, lng)
    }

/** 두 좌표 사이의 대원 거리(m, haversine). */
internal fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(a))
}

private const val EARTH_RADIUS_METERS = 6_371_000.0
