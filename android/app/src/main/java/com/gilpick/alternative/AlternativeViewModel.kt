package com.gilpick.alternative

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gilpick.auth.AuthResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 대체 장소 화면의 상태 보유자(T022).
 *
 * 감지 상세(DETECT-002)와 후보(ALT-001)를 병렬로 조회해 [AlternativeUiState.Content]로 합친다.
 * 화면이 다시 보일 때마다([load]) 같은 조회를 반복하되 내용이 있으면 대기 표시로 돌아가지 않고
 * 조용히 갱신한다. 후보 선택([select])은 일정을 바꾸지 않고 F010에 넘길 값만 만들며(FR-023),
 * 상태를 바꾸는 유일한 요청은 [dismiss](DETECT-004)다.
 *
 * @param detectionId 기준 감지.
 */
class AlternativeViewModel(
    private val repository: AlternativeRepository,
    private val detectionId: String,
) : ViewModel() {

    private val _state = MutableStateFlow<AlternativeUiState>(AlternativeUiState.Loading)

    /** 화면이 관찰하는 현재 상태. */
    val state: StateFlow<AlternativeUiState> = _state.asStateFlow()

    private val _dismissed = MutableStateFlow(false)

    /** 거절이 성공해 화면을 닫아야 한다. 한 번 `true`가 되면 되돌리지 않는다. */
    val dismissed: StateFlow<Boolean> = _dismissed.asStateFlow()

    /** 진행 중인 조회. 재진입·`다시 시도` 연타로 겹치는 조회를 막는다. */
    private var job: Job? = null

    init {
        load()
    }

    /**
     * 상세와 후보를 함께 조회한다. 화면 진입·재개와 `다시 시도하기`가 부른다.
     *
     * 상세 실패도 후보 실패도 `Error`다(후보가 핵심 결과). `409`는 [AlternativeUiState.Closed].
     * 내용이 이미 있으면 [AlternativeUiState.Content.refreshing]만 켠 채 두고, 갱신 실패는 보던 내용을
     * 지우지 않는다(UI-006). 거절 진행 중이면 조회로 잠금을 풀지 않는다.
     */
    fun load() {
        if (job?.isActive == true) return
        _state.update { current ->
            if (current is AlternativeUiState.Content) current.copy(refreshing = true) else AlternativeUiState.Loading
        }
        job = viewModelScope.launch {
            val next = fetch()
            _state.update { current ->
                val kept = current as? AlternativeUiState.Content
                when {
                    kept == null -> next
                    next is AlternativeUiState.Error -> kept.copy(refreshing = false)
                    next is AlternativeUiState.Content -> next.copy(
                        dismissPending = kept.dismissPending,
                        dismissError = kept.dismissError,
                    )
                    else -> next
                }
            }
        }
    }

    /**
     * `기존 일정 그대로 진행`: 감지를 거절한다(DETECT-004, FR-016).
     *
     * 요청 중이면 무시한다. 응답이 `DISMISSED`면 [dismissed]를 켜고, 이미 처리된 감지면
     * [AlternativeUiState.Closed]로 알린다(UI-006). 실패하면 내용은 그대로 두고 원인만 남긴다(UI-005).
     */
    fun dismiss() {
        val content = _state.value as? AlternativeUiState.Content ?: return
        if (content.dismissPending) return
        _state.value = content.copy(dismissPending = true, dismissError = null)
        viewModelScope.launch {
            when (val result = repository.dismissDetection(detectionId)) {
                is AuthResult.Success -> if (result.value.status == DetectionStatus.DISMISSED) {
                    _dismissed.value = true
                    _state.update { (it as? AlternativeUiState.Content)?.copy(dismissPending = false) ?: it }
                } else {
                    _state.value = AlternativeUiState.Closed(result.value.status)
                }
                is AuthResult.Failure -> when (val error = result.error.toAlternativeError()) {
                    is AlternativeError.NotActive -> _state.value = AlternativeUiState.Closed(error.status)
                    else -> _state.update {
                        (it as? AlternativeUiState.Content)?.copy(dismissPending = false, dismissError = error) ?: it
                    }
                }
            }
        }
    }

    /** 거절 실패 안내를 닫는다. */
    fun dismissError() {
        _state.update { (it as? AlternativeUiState.Content)?.copy(dismissError = null) ?: it }
    }

    /** 후보의 선택 버튼(`경로 비교`·`비교`): F010에 넘길 값을 만든다(data-model.md §3.2). 일정은 바꾸지 않는다. */
    fun select(candidate: AlternativeCandidateDto) = SelectedAlternative(
        detectionId = detectionId,
        placeId = candidate.place.placeId,
        candidateId = candidate.candidateId,
        name = candidate.place.name,
        distanceMeters = candidate.distanceMeters,
        displayScore = candidate.displayScore,
    )

    private suspend fun fetch(): AlternativeUiState = coroutineScope {
        val detail = async { repository.getDetection(detectionId) }
        val candidates = async { repository.listAlternatives(detectionId) }
        val detection = when (val result = detail.await()) {
            is AuthResult.Success -> result.value
            is AuthResult.Failure -> return@coroutineScope result.error.toAlternativeError().toState()
        }
        when (val result = candidates.await()) {
            is AuthResult.Success -> AlternativeUiState.Content(detection = detection, candidates = result.value)
            is AuthResult.Failure -> result.error.toAlternativeError().toState()
        }
    }

    private fun AlternativeError.toState(): AlternativeUiState = when (this) {
        is AlternativeError.NotActive -> AlternativeUiState.Closed(status)
        else -> AlternativeUiState.Error(this, retryable)
    }

    companion object {
        /** 화면이 사용할 의존성을 조립한다. DI 도구를 두지 않는 F001 방식이다. */
        fun factory(detectionId: String, repository: AlternativeRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { AlternativeViewModel(repository = repository, detectionId = detectionId) }
        }
    }
}

/**
 * 직접 검색 화면의 상태 보유자(T032). F003 `PlaceSearchViewModel`에서 카테고리를 뺀 흐름이다.
 *
 * 검색은 [search]로만 시작하고 입력은 draft만 바꾼다. 공백을 뗀 검색어가 2글자 미만이면 요청하지 않고
 * [AlternativeSearchPhase.TooShort]로 안내한다(US3 Scenario 4). 다음 페이지는 cursor로 이어 받고
 * `placeId`가 겹치는 항목은 버린다. 선택([select])은 F010에 넘길 값만 만든다(FR-015).
 *
 * @param detectionId 기준 감지. ALT-002 경로와 [SelectedAlternative.detectionId]에 쓴다.
 */
class AlternativeSearchViewModel(
    private val repository: AlternativeRepository,
    private val detectionId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(AlternativeSearchUiState())

    /** 화면이 관찰하는 현재 검색 상태. */
    val state: StateFlow<AlternativeSearchUiState> = _state.asStateFlow()

    /** 다음 페이지 요청에 쓸 cursor. 첫 페이지이거나 마지막 페이지면 `null`이다. */
    private var nextCursor: String? = null

    /** 진행 중인 조회. 새 검색이 시작되면 취소한다. */
    private var loadJob: Job? = null

    /** 입력창 값을 반영한다. 검색하지 않는다. */
    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
    }

    /** 입력창을 비운다. 검색하지 않는다. */
    fun onClearQuery() {
        onQueryChange("")
    }

    /** 현재 입력으로 검색을 실행한다. 키보드의 검색 동작이 부른다. 앞선 결과는 새 결과로 교체된다. */
    fun search() {
        val query = _state.value.query.trim()
        if (query.length < MIN_QUERY_LENGTH) {
            loadJob?.cancel()
            nextCursor = null
            _state.update { it.copy(results = emptyList(), phase = AlternativeSearchPhase.TooShort, hasNext = false, loadingMore = false, loadMoreError = null) }
            return
        }
        _state.update { it.copy(committedQuery = query) }
        startSearch()
    }

    /** 첫 페이지 조회에 실패한 뒤 같은 검색어로 다시 시도한다. */
    fun retry() {
        startSearch()
    }

    /** 다음 페이지를 이어서 받는다. 마지막 페이지·수신 중·앞선 추가 조회 실패면 아무것도 하지 않는다. */
    fun loadMore() {
        if (_state.value.loadMoreError != null) return
        fetchNextPage()
    }

    /** 실패한 추가 조회를 다시 시도한다. */
    fun retryLoadMore() {
        _state.update { it.copy(loadMoreError = null) }
        fetchNextPage()
    }

    /** 방문 가능한 결과의 선택: 후보 토큰·점수가 없는 [SelectedAlternative]다(data-model.md §3.2). */
    fun select(item: AlternativeSearchItemDto) = SelectedAlternative(
        detectionId = detectionId,
        placeId = item.place.placeId,
        candidateId = null,
        name = item.place.name,
        distanceMeters = item.distanceMeters,
        displayScore = null,
    )

    private fun startSearch() {
        loadJob?.cancel()
        nextCursor = null
        _state.update { it.copy(results = emptyList(), phase = AlternativeSearchPhase.Loading, hasNext = false, loadingMore = false, loadMoreError = null) }
        loadJob = viewModelScope.launch {
            when (val result = repository.searchAlternatives(detectionId, _state.value.committedQuery)) {
                is AuthResult.Success -> {
                    val page = result.value
                    nextCursor = page.nextCursor.takeIf { page.hasNext }
                    val items = page.items.distinctBy { it.place.placeId }
                    _state.update {
                        it.copy(
                            results = items,
                            hasNext = page.hasNext,
                            phase = if (items.isEmpty()) AlternativeSearchPhase.Empty else AlternativeSearchPhase.Content,
                        )
                    }
                }
                is AuthResult.Failure -> _state.update {
                    it.copy(phase = AlternativeSearchPhase.Failed(result.error.toAlternativeError()))
                }
            }
        }
    }

    private fun fetchNextPage() {
        val cursor = nextCursor ?: return
        if (_state.value.loadingMore || loadJob?.isActive == true) return
        _state.update { it.copy(loadingMore = true) }
        loadJob = viewModelScope.launch {
            when (val result = repository.searchAlternatives(detectionId, _state.value.committedQuery, cursor)) {
                is AuthResult.Success -> {
                    val page = result.value
                    nextCursor = page.nextCursor.takeIf { page.hasNext }
                    _state.update {
                        val known = it.results.mapTo(HashSet()) { item -> item.place.placeId }
                        it.copy(
                            results = it.results + page.items.filter { item -> known.add(item.place.placeId) },
                            hasNext = page.hasNext,
                            loadingMore = false,
                        )
                    }
                }
                // 보여 주고 있는 목록은 지우지 않는다. 추가 조회만 실패한 것이라 원인만 붙인다.
                is AuthResult.Failure -> _state.update {
                    it.copy(loadingMore = false, loadMoreError = result.error.toAlternativeError())
                }
            }
        }
    }

    companion object {
        /** 검색어 최소 길이. 공백을 뗀 뒤 잰다(ALT-002 `query`). */
        const val MIN_QUERY_LENGTH = 2

        /** 화면이 사용할 의존성을 조립한다. */
        fun factory(detectionId: String, repository: AlternativeRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { AlternativeSearchViewModel(repository = repository, detectionId = detectionId) }
        }
    }
}
