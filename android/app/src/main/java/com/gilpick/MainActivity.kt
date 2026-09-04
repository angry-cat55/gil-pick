package com.gilpick

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gilpick.auth.AuthUiState
import com.gilpick.auth.AuthViewModel
import com.gilpick.auth.LoginScreen
import com.gilpick.auth.RefreshOfflineScreen
import com.gilpick.place.placeGraph
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.gilpick.trip.TripDeletePhase
import com.gilpick.trip.TripDetailScreen
import com.gilpick.trip.TripDetailViewModel
import com.gilpick.trip.TripFormScreen
import com.gilpick.trip.TripFormViewModel
import com.gilpick.trip.TripListScreen
import com.gilpick.trip.TripListViewModel
import com.gilpick.ui.theme.GilpickTheme
import kotlinx.serialization.Serializable

/**
 * 앱의 단일 Activity entrypoint.
 *
 * `singleTask`이므로 인증 완료 App Link는 새 Activity가 아니라 [onNewIntent]로 도착한다.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AuthViewModel by viewModels { AuthViewModel.factory(this) }

    /**
     * Custom Tab을 열어 두고 결과를 기다리는 중인지 여부.
     *
     * App Link 없이 화면으로 돌아오면 사용자가 인증을 취소한 것이다. 인증 완료 intent는
     * [onNewIntent]에서 이 표시를 먼저 내리므로 취소로 오인하지 않는다.
     */
    private var awaitingKakaoAuth = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.restore()
        handleAppLink(intent)

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            GilpickApp(
                state = state,
                onKakaoLogin = ::startKakaoLogin,
                onRetry = ::startKakaoLogin,
                onRetryRefresh = viewModel::retryRefresh,
                onLogout = viewModel::logout,
                onSessionExpired = viewModel::onSessionExpired,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAppLink(intent)
    }

    override fun onResume() {
        super.onResume()
        if (awaitingKakaoAuth) {
            awaitingKakaoAuth = false
            viewModel.cancelLogin()
        }
    }

    /**
     * 인증 완료 App Link를 repository로 넘긴다.
     *
     * 같은 intent가 화면 재생성으로 다시 전달되어도 ticket은 한 번만 소비된다.
     */
    private fun handleAppLink(intent: Intent?) {
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        awaitingKakaoAuth = false
        viewModel.completeLogin(data.toString())
    }

    /** transaction을 만들고 Kakao 인증을 Custom Tab으로 연다. */
    private fun startKakaoLogin() {
        viewModel.startLogin { authorizationUrl ->
            awaitingKakaoAuth = true
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(this, authorizationUrl.toUri())
        }
    }
}

/**
 * 인증 상태에 따라 로그인 화면과 여행 화면을 고른다.
 *
 * @param state 현재 인증 상태.
 * @param onKakaoLogin 카카오 로그인을 시작한다.
 * @param onRetry 로그인 실패 후 새 카카오 인증을 시작한다.
 * @param onRetryRefresh 통신 장애로 중단된 로그인 상태 갱신을 다시 시도한다.
 * @param onLogout 현재 기기에서 로그아웃한다.
 * @param onSessionExpired 보호 기능 호출에서 자격이 무효로 확정됐다. 로그인 화면으로 돌린다.
 */
@Composable
fun GilpickApp(
    state: AuthUiState,
    onKakaoLogin: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryRefresh: () -> Unit = {},
    onLogout: () -> Unit = {},
    onSessionExpired: () -> Unit = {},
) {
    // 테마를 여기서 적용해 화면과 test·preview가 같은 토큰 위에서 동작하게 한다.
    GilpickTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            AuthRoute(state, onKakaoLogin, onRetry, modifier, onRetryRefresh, onLogout, onSessionExpired)
        }
    }
}

/** 인증 상태에 따라 실제 화면을 고른다. */
@Composable
private fun AuthRoute(
    state: AuthUiState,
    onKakaoLogin: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
    onRetryRefresh: () -> Unit,
    onLogout: () -> Unit,
    onSessionExpired: () -> Unit,
) {
    when (state) {
        is AuthUiState.Authenticated -> TripRoute(modifier = modifier, onLogout = onLogout, onSessionExpired = onSessionExpired)

        // 통신 장애로 갱신이 중단된 상태다. session은 유지한 채 보호 기능만 막는다.
        is AuthUiState.RefreshOffline -> RefreshOfflineScreen(
            onRetry = onRetryRefresh,
            modifier = modifier,
            onLogout = onLogout,
        )

        else -> LoginScreen(
            state = state,
            onKakaoLogin = onKakaoLogin,
            onRetry = onRetry,
            modifier = modifier,
        )
    }
}

/**
 * 로그인 후 여행 화면 사이를 오간다.
 *
 * 목록·생성·상세 세 화면이 되면서 상태 하나로 고르던 방식을 navigation-compose로
 * 바꿨다. 상세는 어떤 여행인지를 인자로 받아야 하고 뒤로 가기가 예측 가능해야 하는데,
 * boolean 몇 개로는 back stack을 표현할 수 없다.
 *
 * route는 `@Serializable` 타입을 쓴다. 문자열 route는 인자 이름과 타입을 컴파일러가
 * 검사하지 못한다.
 */
@Composable
private fun TripRoute(modifier: Modifier, onLogout: () -> Unit, onSessionExpired: () -> Unit) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = TripListRoute,
        modifier = modifier,
    ) {
        composable<TripListRoute> {
            val viewModel: TripListViewModel = viewModel(
                factory = TripListViewModel.factory(LocalContext.current),
            )
            val state by viewModel.state.collectAsStateWithLifecycle()

            // 생성·상세에서 돌아올 때마다 다시 조회해 바뀐 내용이 목록에 반영되게 한다.
            LaunchedEffect(Unit) { viewModel.load() }

            TripListScreen(
                state = state,
                onQueryChange = viewModel::onQueryChange,
                onStatusFilterChange = viewModel::onStatusFilterChange,
                onRetry = viewModel::retry,
                onLoadMore = viewModel::loadMore,
                onCreateTrip = { navController.navigate(TripFormRoute) },
                onTripClick = { tripId -> navController.navigate(TripDetailRoute(tripId)) },
                onLogout = onLogout,
            )
        }

        composable<TripFormRoute> {
            val viewModel: TripFormViewModel = viewModel(
                factory = TripFormViewModel.factory(LocalContext.current),
            )
            val state by viewModel.state.collectAsStateWithLifecycle()

            // 생성에 성공하면 목록으로 돌아가 새 여행이 포함된 목록을 다시 받는다.
            LaunchedEffect(state.savedTripId) {
                if (state.savedTripId != null) {
                    viewModel.consumeSaved()
                    navController.popBackStack()
                }
            }

            TripFormScreen(
                state = state,
                onNameChange = viewModel::onNameChange,
                onPeriodChange = viewModel::onPeriodChange,
                onSubmit = viewModel::submit,
            )
        }

        composable<TripDetailRoute> { entry ->
            val tripId = entry.toRoute<TripDetailRoute>().tripId
            val viewModel: TripDetailViewModel = viewModel(
                factory = TripDetailViewModel.factory(LocalContext.current, tripId),
            )
            val state by viewModel.state.collectAsStateWithLifecycle()

            // 화면에 들어올 때마다 다시 조회한다. tripId를 key로 두면 수정하고 돌아와도
            // 같은 값이라 재조회가 일어나지 않아 낡은 version이 남고, 이어서 수정하면
            // 서버가 409 VERSION_CONFLICT로 거절한다.
            LaunchedEffect(Unit) { viewModel.load() }

            // 삭제에 성공하면 목록으로 돌아간다. 목록도 진입할 때마다 다시 조회하므로
            // (위 TripListRoute) 삭제된 여행은 돌아간 화면에서 이미 빠져 있다.
            // TripListViewModel에 삭제를 알리는 경로를 따로 두지 않는 이유다.
            LaunchedEffect(state.deletion) {
                if (state.deletion is TripDeletePhase.Deleted) {
                    viewModel.consumeDeleted()
                    navController.popBackStack()
                }
            }

            TripDetailScreen(
                state = state,
                onBack = { navController.popBackStack() },
                onRetry = viewModel::retry,
                onEdit = { navController.navigate(TripEditRoute(tripId)) },
                onDelete = viewModel::delete,
                onDeleteErrorShown = viewModel::clearDeleteError,
            )
        }

        composable<TripEditRoute> { entry ->
            val tripId = entry.toRoute<TripEditRoute>().tripId
            val viewModel: TripFormViewModel = viewModel(
                factory = TripFormViewModel.factory(LocalContext.current),
            )
            val state by viewModel.state.collectAsStateWithLifecycle()

            // 상세가 가진 값을 route로 나르지 않고 여기서 다시 조회한다. 상세를 열어 둔
            // 사이에 여행이 바뀌었을 수 있고, 그때 낡은 version으로 저장하면 실패한다.
            LaunchedEffect(tripId) { viewModel.loadForEdit(tripId) }

            // 저장에 성공하면 상세로 돌아간다. 상세는 진입할 때마다 다시 조회하므로
            // 최신 version이 반영된다.
            LaunchedEffect(state.savedTripId) {
                if (state.savedTripId != null) {
                    viewModel.consumeSaved()
                    navController.popBackStack()
                }
            }

            TripFormScreen(
                state = state,
                onNameChange = viewModel::onNameChange,
                onPeriodChange = viewModel::onPeriodChange,
                onSubmit = viewModel::submit,
            )
        }

        // F003 장소 검색·상세. destination 정의는 com.gilpick.place가 소유하고 여기서는
        // 등록만 한다. 사용자가 검색 화면에 도달하는 진입점은 pen의 일정 편집 화면에
        // 있으므로 F004에서 연결한다.
        placeGraph(navController, onSessionExpired = onSessionExpired)
    }
}

/** 여행 목록. 로그인 후 첫 화면이다. */
@Serializable
private object TripListRoute

/** 여행 생성 폼. */
@Serializable
private object TripFormRoute

/**
 * 여행 상세.
 *
 * @property tripId 보여 줄 여행. 목록에서 고른 항목의 식별자다.
 */
@Serializable
private data class TripDetailRoute(val tripId: String)

/**
 * 여행 수정.
 *
 * 생성 폼과 화면은 공용이지만 destination을 나눈다. 진입점(상세)과 복귀 지점이 생성과
 * 다르고, 수정 대상을 인자로 받아야 한다.
 *
 * @property tripId 수정할 여행.
 */
@Serializable
private data class TripEditRoute(val tripId: String)
