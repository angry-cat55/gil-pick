package com.gilpick.place

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gilpick.auth.AuthResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 상세 화면의 표시 단계.
 *
 * `empty`는 상세 조회에 성립하지 않는다. 장소 하나를 특정해 요청하므로 성공하면 반드시
 * 내용이 있고, 없으면 계약상 `404`라 [NotFound]다. 누락된 개별 field는 [Content] 안에서
 * `null`로 표현하고 화면이 "정보 없음"으로 구분한다(`spec.md` FR-007).
 */
sealed interface PlaceDetailPhase {
    /** 조회를 기다리는 중. */
    data object Loading : PlaceDetailPhase

    /** 장소를 받았다. */
    data class Content(val place: PlaceDto) : PlaceDetailPhase

    /** 없거나 더 이상 제공되지 않는 장소다. 재시도 대신 검색으로 돌아간다. */
    data object NotFound : PlaceDetailPhase

    /** 조회에 실패했다. */
    data class Failed(val error: PlaceError) : PlaceDetailPhase
}

/**
 * 장소 상세 화면 상태.
 *
 * @property phase 현재 표시 단계.
 */
data class PlaceDetailUiState(
    val phase: PlaceDetailPhase = PlaceDetailPhase.Loading,
)

/**
 * 장소 상세 화면의 상태 보유자.
 *
 * server가 nullable로 내려준 값을 그대로 화면에 넘긴다. 운영 안내로 현재 영업 여부를
 * 계산하지 않는다(`spec.md` FR-007). destination-scoped라 회전·복귀에도 상태가 남는다.
 *
 * @property repository 장소 데이터 접근 지점.
 * @property placeId 이 화면이 보여 줄 장소.
 */
class PlaceDetailViewModel(
    private val repository: PlaceRepository,
    private val placeId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(PlaceDetailUiState())

    /** 화면이 관찰하는 현재 상세 상태. */
    val state: StateFlow<PlaceDetailUiState> = _state.asStateFlow()

    /** 진행 중인 조회. 재시도하면 앞선 요청을 취소한다. */
    private var loadJob: Job? = null

    /**
     * 장소 상세를 조회한다.
     *
     * 이미 받아 둔 내용이 있거나 조회 중이면 다시 받지 않는다. 화면 회전으로 composable이
     * 다시 만들어질 때 불필요한 재조회를 막는다.
     */
    fun load() {
        if (_state.value.phase is PlaceDetailPhase.Content) return
        if (loadJob?.isActive == true) return
        reload()
    }

    /** 조회에 실패한 뒤 같은 장소를 다시 조회한다. */
    fun retry() {
        reload()
    }

    private fun reload() {
        loadJob?.cancel()
        // 조회를 시작하기 전에 동기적으로 바꾼다. coroutine 안에서 바꾸면 dispatcher가
        // 돌기 전까지 앞선 실패 화면이 남아 재시도가 눌렸는지 알 수 없다.
        _state.value = PlaceDetailUiState(PlaceDetailPhase.Loading)

        loadJob = viewModelScope.launch {
            val phase = when (val result = repository.getPlace(placeId)) {
                is AuthResult.Success -> PlaceDetailPhase.Content(result.value)
                is AuthResult.Failure -> {
                    val error = result.error.toPlaceError()
                    if (error.kind == PlaceErrorKind.NOT_FOUND) PlaceDetailPhase.NotFound
                    else PlaceDetailPhase.Failed(error)
                }
            }
            _state.value = PlaceDetailUiState(phase)
        }
    }

    companion object {

        /**
         * 화면이 사용할 의존성을 조립한다.
         *
         * DI 도구를 두지 않는 F001·F002 방식을 그대로 따른다.
         *
         * @param placeId 상세를 볼 장소. navigation route가 넘긴다.
         */
        fun factory(context: Context, placeId: String): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    PlaceDetailViewModel(repository = createPlaceRepository(appContext), placeId = placeId)
                }
            }
        }
    }
}
