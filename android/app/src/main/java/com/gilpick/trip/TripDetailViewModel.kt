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
 * 여행 상세 화면 상태.
 *
 * @property phase 현재 표시 단계.
 */
data class TripDetailUiState(
    val phase: TripDetailPhase = TripDetailPhase.Loading,
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
