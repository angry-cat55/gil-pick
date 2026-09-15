package com.gilpick.trip

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gilpick.BuildConfig
import com.gilpick.R
import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthResult
import com.gilpick.auth.AuthService
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.SessionRevocationWorker
import com.gilpick.auth.createAuthRetrofit
import com.gilpick.notification.FcmTokenClearWorker
import com.gilpick.notification.FcmTokenSyncWorker
import com.gilpick.ui.component.EmptyState
import com.gilpick.ui.component.EmptyStateSize
import com.gilpick.ui.component.EmptyStateTone
import com.gilpick.ui.component.ErrorState
import com.gilpick.ui.component.GradientButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 하단 `여행 중` 탭이 진행 중 여행을 찾는 단계(#502).
 *
 * 같은 사용자의 여행 기간은 겹칠 수 없어(F002 FR-002a) 오늘 진행 중인 여행은 많아야 하나다.
 */
sealed interface ActiveTripTabPhase {
    /** 찾는 중. */
    data object Loading : ActiveTripTabPhase

    /** 진행 중 여행이 없다. */
    data object Empty : ActiveTripTabPhase

    /** 진행 중 여행을 찾았다. 화면은 곧바로 여행 중 화면으로 넘어간다. */
    data class Found(val trip: TripDto) : ActiveTripTabPhase

    /** 찾지 못했다. 원인과 `다시 시도`를 보인다. */
    data class Failed(val error: TripListError) : ActiveTripTabPhase
}

/**
 * `여행 중` 탭 진입 view model(#502). 서버가 계산한 `IN_PROGRESS` 상태로 한 건만 묻는다(FR-006, 앱이 날짜로 추정하지 않는다).
 *
 * @property repository 여행 목록 조회 지점.
 */
class ActiveTripTabViewModel(private val repository: TripRepository) : ViewModel() {

    private val _phase = MutableStateFlow<ActiveTripTabPhase>(ActiveTripTabPhase.Loading)

    /** 화면이 관찰하는 현재 단계. */
    val phase: StateFlow<ActiveTripTabPhase> = _phase.asStateFlow()

    /** 진행 중 여행을 찾는다. 다시 시도도 이 함수를 쓴다. */
    fun load() {
        _phase.value = ActiveTripTabPhase.Loading
        viewModelScope.launch {
            _phase.value = when (val result = repository.listTrips(status = TripStatus.IN_PROGRESS, limit = 1)) {
                is AuthResult.Success -> result.value.trips.firstOrNull()
                    ?.let { ActiveTripTabPhase.Found(it) }
                    ?: ActiveTripTabPhase.Empty

                is AuthResult.Failure -> ActiveTripTabPhase.Failed(
                    if (result.error is AuthError.Offline) TripListError.NETWORK else TripListError.UNEXPECTED,
                )
            }
        }
    }

    companion object {

        /** 화면이 사용할 의존성을 조립한다. DI 도구를 두지 않는 F001 방식을 따른다. */
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    val auth = AuthRepository(
                        store = AuthSessionStore.create(appContext),
                        api = createAuthRetrofit(BuildConfig.API_BASE_URL).create(AuthService::class.java),
                        appLinkHandler = AuthAppLinkHandler(BuildConfig.APP_LINK_HOST),
                        scheduleRevocation = SessionRevocationWorker.scheduler(appContext),
                        syncPushToken = { FcmTokenSyncWorker.enqueue(appContext) },
                        clearPushToken = { FcmTokenClearWorker.enqueue(appContext) },
                    )
                    ActiveTripTabViewModel(
                        TripRepository(
                            api = createTripRetrofit(BuildConfig.API_BASE_URL).create(TripService::class.java),
                            auth = auth,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * `여행 중` 탭의 대기·빈·오류 화면(#502). 진행 중 여행을 찾으면 호출부가 여행 중 화면으로 넘기므로 그 단계는 대기와 같게 그린다.
 *
 * Figma에는 진행 중 여행이 없는 상태 화면이 없어 공통 빈 상태(가이드라인 5절 `Screen`, `primaryContainer` 안내)와 개발자가 확인한 문구를 쓴다.
 *
 * @param onOpenTrips 빈 상태 `내 여행 보기`. `내 여행` 탭으로 간다.
 */
@Composable
fun ActiveTripTabScreen(
    phase: ActiveTripTabPhase,
    onRetry: () -> Unit,
    onOpenTrips: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        when (phase) {
            ActiveTripTabPhase.Loading, is ActiveTripTabPhase.Found -> {
                val label = stringResource(R.string.active_tab_loading)
                CircularProgressIndicator(modifier = Modifier.clearAndSetSemantics { contentDescription = label })
            }

            ActiveTripTabPhase.Empty -> EmptyState(
                icon = R.drawable.ic_lucide_navigation,
                title = stringResource(R.string.active_tab_empty_title),
                body = stringResource(R.string.active_tab_empty_body),
                size = EmptyStateSize.Screen,
                tone = EmptyStateTone.Primary,
                modifier = Modifier.fillMaxSize(),
                action = { GradientButton(label = stringResource(R.string.active_tab_open_trips), onClick = onOpenTrips) },
            )

            is ActiveTripTabPhase.Failed -> ErrorState(
                description = stringResource(
                    when (phase.error) {
                        TripListError.NETWORK -> R.string.trips_error_network
                        TripListError.UNEXPECTED -> R.string.active_tab_error_unexpected
                    },
                ),
                primaryLabel = stringResource(R.string.trips_retry),
                onPrimary = onRetry,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
