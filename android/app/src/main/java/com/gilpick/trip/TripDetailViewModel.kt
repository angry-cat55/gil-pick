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
 * @property deletion 삭제 요청의 진행 단계. 조회 단계와 독립적이라 [phase]에 섞지 않는다.
 */
data class TripDetailUiState(
    val phase: TripDetailPhase = TripDetailPhase.Loading,
    val deletion: TripDeletePhase = TripDeletePhase.Idle,
)

/**
 * 여행 상세 화면의 상태 보유자.
 *
 * 여행 상태(`예정`/`여행 중`/`완료`)는 서버가 KST 기준으로 계산해 내려주므로
 * (`spec.md` FR-006) 여기서 다시 계산하지 않고 [TripDto]를 그대로 화면에 넘긴다.
 *
 * @property repository 여행 데이터 접근 지점.
 * @property tripId 이 화면이 보여 줄 여행.
 */
class TripDetailViewModel(
    private val repository: TripRepository,
    private val tripId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(TripDetailUiState())

    /** 화면이 관찰하는 현재 상세 상태. */
    val state: StateFlow<TripDetailUiState> = _state.asStateFlow()

    /** 진행 중인 조회. 재시도하면 앞선 요청을 취소한다. */
    private var loadJob: Job? = null

    /** 여행 상세를 조회한다. */
    fun load() {
        loadJob?.cancel()
        // 조회를 시작하기 전에 동기적으로 바꾼다. coroutine 안에서 바꾸면 dispatcher가
        // 돌기 전까지 앞선 실패 화면이 남아 재시도가 눌렸는지 알 수 없다.
        _state.value = TripDetailUiState(TripDetailPhase.Loading)

        loadJob = viewModelScope.launch {
            val phase = when (val result = repository.getTrip(tripId)) {
                is AuthResult.Success -> TripDetailPhase.Content(result.value)
                is AuthResult.Failure -> TripDetailPhase.Failed(result.error.toDetailError())
            }
            _state.value = TripDetailUiState(phase)
        }
    }

    /** 조회에 실패한 뒤 같은 여행을 다시 조회한다. */
    fun retry() {
        load()
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
                        tripId = tripId,
                    )
                }
            }
        }
    }
}
