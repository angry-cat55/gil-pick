package com.gilpick.notification

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gilpick.alternative.AlternativeRepository
import com.gilpick.alternative.DetectionListItemDto
import com.gilpick.alternative.DetectionStatus
import com.gilpick.alternative.retryable
import com.gilpick.alternative.toAlternativeError
import com.gilpick.auth.AuthResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 감지 목록 화면의 상태 보유자(T039, US6).
 *
 * F009 [AlternativeRepository]를 읽기로만 쓴다: DETECT-001(`status=ACTIVE`) 한 페이지를 받고, 카드의
 * 변수별 판정을 위해 항목마다 DETECT-002를 병렬로 받는다. 상세 조회 하나가 실패해도 목록은 보여 주되
 * 그 카드의 판정 줄만 비운다(없는 값을 지어내지 않는다, UI-008). 어떤 요청도 감지 상태를 바꾸지 않는다.
 *
 * 정렬([toggleSort])은 로컬 상태다. 재조회([load])는 정렬을 유지하고 내용이 있으면 조용히 갱신한다.
 */
class VariableMonitorViewModel(
    private val tripId: String,
    private val repository: AlternativeRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<VariableMonitorUiState>(VariableMonitorUiState.Loading)

    /** 화면이 관찰하는 현재 상태. */
    val state: StateFlow<VariableMonitorUiState> = _state.asStateFlow()

    /** 진행 중인 조회. 재진입·`다시 시도` 연타로 겹치는 조회를 막는다. */
    private var job: Job? = null

    init {
        load()
    }

    /** 목록을 조회한다. 화면 진입·재개와 `다시 시도하기`가 부른다. 갱신 실패는 보던 목록을 지우지 않는다. */
    fun load() {
        if (job?.isActive == true) return
        _state.update { current ->
            if (current is VariableMonitorUiState.Content) current.copy(refreshing = true) else VariableMonitorUiState.Loading
        }
        job = viewModelScope.launch {
            val next = fetch()
            _state.update { current ->
                when {
                    current is VariableMonitorUiState.Content && next is VariableMonitorUiState.Error -> current.copy(refreshing = false)
                    current is VariableMonitorUiState.Content && next is VariableMonitorUiState.Content -> next.copy(sort = current.sort)
                    else -> next
                }
            }
        }
    }

    /** 헤더 정렬 토글: 시간순 ↔ 위험순. */
    fun toggleSort() {
        _state.update { current ->
            val content = current as? VariableMonitorUiState.Content ?: return@update current
            content.copy(sort = if (content.sort == DetectionSort.TIME) DetectionSort.RISK else DetectionSort.TIME)
        }
    }

    private suspend fun fetch(): VariableMonitorUiState =
        when (val result = repository.listDetections(tripId, status = DetectionStatus.ACTIVE, limit = PAGE_LIMIT)) {
            is AuthResult.Success -> {
                val items = result.value.items
                if (items.isEmpty()) VariableMonitorUiState.Empty else VariableMonitorUiState.Content(items.withVariables())
            }
            is AuthResult.Failure -> {
                val error = result.error.toAlternativeError()
                VariableMonitorUiState.Error(error, error.retryable)
            }
        }

    /** 항목마다 DETECT-002를 병렬로 받아 변수별 판정을 붙인다. 실패한 항목은 `variables = null`이다. */
    private suspend fun List<DetectionListItemDto>.withVariables(): List<DetectionUi> = coroutineScope {
        map { item ->
            async {
                val detail = repository.getDetection(item.detectionId) as? AuthResult.Success
                DetectionUi(item = item, variables = detail?.value?.variables)
            }
        }.awaitAll()
    }

    companion object {
        /** 한 페이지로 끝낸다. 진행 화면 배너와 같은 상한이다(research R7). */
        private const val PAGE_LIMIT = 50

        /** 화면이 사용할 의존성을 조립한다. DI 도구를 두지 않는 F001 방식이다. */
        fun factory(tripId: String, repository: AlternativeRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { VariableMonitorViewModel(tripId = tripId, repository = repository) }
        }
    }
}
