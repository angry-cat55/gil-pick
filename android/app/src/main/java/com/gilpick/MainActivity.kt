package com.gilpick

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gilpick.auth.AuthUiState
import com.gilpick.auth.AuthViewModel
import com.gilpick.auth.LoginScreen
import com.gilpick.auth.RefreshOfflineScreen
import com.gilpick.alternative.AlternativePlacesRoute
import com.gilpick.alternative.SelectedAlternative
import com.gilpick.alternative.alternativeGraph
import com.gilpick.itinerary.ItineraryEditRoute
import com.gilpick.itinerary.itineraryGraph
import com.gilpick.itinerary.returnAddToSchedule
import com.gilpick.notification.GilpickMessagingService
import com.gilpick.notification.NotificationListRoute
import com.gilpick.notification.NotificationTarget
import com.gilpick.notification.PendingNotificationTarget
import com.gilpick.notification.notificationGraph
import com.gilpick.notification.tripNameOf
import com.gilpick.place.PlaceDetailRoute
import com.gilpick.place.placeGraph
import com.gilpick.progress.ActiveTravelRoute
import com.gilpick.progress.progressGraph
import com.gilpick.route.DayRouteRoute
import com.gilpick.route.routeGraph
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.gilpick.trip.TripDeletePhase
import com.gilpick.trip.TripDetailPhase
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
 * `singleTask`이므로 인증 완료 App Link와 푸시 알림 탭은 새 Activity가 아니라 [onNewIntent]로 도착한다.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AuthViewModel by viewModels { AuthViewModel.factory(this) }

    /**
     * 아직 이동하지 않은 푸시 알림 탭 대상(F011, data-model.md 4.3).
     *
     * 로그인 전에 탭하면 인증이 끝나 `NavHost`가 생길 때까지 들고 있다가 이동한다. 이동한 뒤
     * 비우므로 화면 재생성으로 같은 intent가 다시 와도 두 번 가지 않는다.
     */
    private var pendingNotification by mutableStateOf<PendingNotificationTarget?>(null)

    /**
     * Custom Tab을 열어 두고 결과를 기다리는 중인지 여부.
     *
     * App Link 없이 화면으로 돌아오면 사용자가 인증을 취소한 것이다. 인증 완료 intent는
     * [onNewIntent]에서 이 표시를 먼저 내리므로 취소로 오인하지 않는다.
     */
    private var awaitingKakaoAuth = false

    /** Android 13+ 알림 권한 결과. 거부해도 다른 기능은 그대로다(FR-020)라 결과를 쓰지 않는다. */
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.restore()
        createNotificationChannel()
        askNotificationPermission()
        handleAppLink(intent)
        handleNotificationTap(intent)

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            GilpickApp(
                state = state,
                onKakaoLogin = ::startKakaoLogin,
                onRetry = ::startKakaoLogin,
                onRetryRefresh = viewModel::retryRefresh,
                onLogout = viewModel::logout,
                onSessionExpired = viewModel::onSessionExpired,
                pendingNotification = pendingNotification,
                onNotificationConsumed = { pendingNotification = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAppLink(intent)
        handleNotificationTap(intent)
    }

    /**
     * F011 알림 채널. Android 8 이상은 채널 없이 알림을 표시할 수 없어 첫 진입에 만든다.
     * 이미 있으면 시스템이 무시하므로 매번 불러도 된다.
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            GilpickMessagingService.CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = getString(R.string.notification_channel_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Android 13+는 `POST_NOTIFICATIONS` 런타임 권한이 없으면 채널 알림이 표시되지 않는다(F011).
     * 시스템이 두 번 거부 뒤에는 대화상자를 다시 띄우지 않으므로 매 진입에 불러도 된다.
     */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** 푸시 알림 탭 extras를 대상으로 옮긴다. 알림 탭이 아닌 intent는 기존 대상을 건드리지 않는다. */
    private fun handleNotificationTap(intent: Intent?) {
        PendingNotificationTarget.fromIntent(intent)?.let { pendingNotification = it }
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
 * @param pendingNotification 아직 이동하지 않은 푸시 알림 탭 대상. 로그인 뒤 `NavHost`가 처리한다.
 * @param onNotificationConsumed [pendingNotification]으로 이동을 마쳤다. 호출자가 대상을 비운다.
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
    pendingNotification: PendingNotificationTarget? = null,
    onNotificationConsumed: () -> Unit = {},
) {
    // 테마를 여기서 적용해 화면과 test·preview가 같은 토큰 위에서 동작하게 한다.
    GilpickTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            AuthRoute(state, onKakaoLogin, onRetry, modifier, onRetryRefresh, onLogout, onSessionExpired, pendingNotification, onNotificationConsumed)
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
    pendingNotification: PendingNotificationTarget?,
    onNotificationConsumed: () -> Unit,
) {
    when (state) {
        is AuthUiState.Authenticated -> TripRoute(
            modifier = modifier,
            onLogout = onLogout,
            onSessionExpired = onSessionExpired,
            pendingNotification = pendingNotification,
            onNotificationConsumed = onNotificationConsumed,
        )

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
private fun TripRoute(
    modifier: Modifier,
    onLogout: () -> Unit,
    onSessionExpired: () -> Unit,
    pendingNotification: PendingNotificationTarget?,
    onNotificationConsumed: () -> Unit,
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    // F011 푸시 알림 탭(T025·T030). NavHost가 준비된 뒤 유형별 화면으로 가고, 도착한 화면이 재개 조회로
    // 서버 상태를 다시 받는다(FR-019, `LifecycleResumeEffect`). 대상을 특정할 수 없으면 알림 목록으로 간다.
    LaunchedEffect(pendingNotification) {
        val target = pendingNotification ?: return@LaunchedEffect
        when (val route = target.route) {
            is NotificationTarget.Alternative -> navController.navigate(AlternativePlacesRoute(route.detectionId, route.tripId))
            is NotificationTarget.Progress -> navController.navigate(ActiveTravelRoute(route.tripId, tripNameOf(context, route.tripId)))
            null -> navController.navigate(NotificationListRoute)
        }
        onNotificationConsumed()
    }

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
                onNotifications = { navController.navigate(NotificationListRoute) },
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
            val routes by viewModel.routes.collectAsStateWithLifecycle()

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
                onRetryItinerary = viewModel::retryItinerary,
                // `일정 편집`은 여행을 받은 뒤에만 그려지므로 첫 날짜는 그 여행의 startDate다.
                // 일정 개요 응답을 기다리지 않아 개요 조회가 실패한 상태에서도 편집으로 갈 수 있다.
                onEditItinerary = {
                    (state.phase as? TripDetailPhase.Content)?.let { content ->
                        navController.navigate(ItineraryEditRoute(tripId, content.trip.startDate))
                    }
                },
                // 날짜 헤더의 `추가`는 그 날짜의 편집을 거쳐 바로 검색으로 간다. F003 결과가
                // 편집 entry로 돌아와야 하므로(FR-015) 검색을 직접 열지 않는다.
                onAddPlace = { date ->
                    navController.navigate(ItineraryEditRoute(tripId, date, openSearch = true))
                },
                onSelectPlace = { placeId -> navController.navigate(PlaceDetailRoute(placeId)) },
                routes = routes,
                // F005 날짜별 경로. 상세로 돌아오면 위 load()가 개요와 경로 상태를 다시 받는다.
                onOpenRoute = { date, dayNumber -> navController.navigate(DayRouteRoute(tripId, date, dayNumber)) },
                onRetryRoute = viewModel::retryRoute,
                // F006 오늘 여행 시작. 위치 권한 요청은 화면이 끝내고 ViewModel이 위치 취득·시작 요청을 한다.
                // 시작되면(방금이든 이미든) 진행 화면으로 간다. 여행명은 상세가 이미 알고 있어 route로 나른다.
                onStartToday = viewModel::startToday,
                onRetryStart = viewModel::retryStart,
                onOpenProgress = {
                    (state.phase as? TripDetailPhase.Content)?.let { content ->
                        navController.navigate(ActiveTravelRoute(tripId, content.trip.name))
                    }
                },
                onLaunchConsumed = viewModel::consumeLaunched,
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
                // 기간 축소로 삭제될 일정 동의는 수정에만 있다. 생성에는 기존 일정이
                // 없으므로 서버가 확인을 요구하지 않는다.
                onConfirmDeleteOutOfRangeItems = viewModel::confirmDeleteOutOfRangeItems,
                onCancelDeleteConfirmation = viewModel::cancelDeleteConfirmation,
            )
        }

        // F004 일정 편집. destination 정의는 com.gilpick.itinerary가 소유한다. 여행 상세의
        // `일정 편집`·날짜별 `추가`가 위 TripDetailRoute에서 이 route로 들어온다.
        itineraryGraph(navController, onSessionExpired = onSessionExpired)

        // F005 날짜별 경로. destination 정의는 com.gilpick.route가 소유한다. 여행 상세의
        // `경로 보기`가 이 route로 들어오고, 빈 상태의 `장소 추가`는 위 itineraryGraph로 간다.
        routeGraph(navController, onSessionExpired = onSessionExpired)

        // F006 진행 화면. destination 정의는 com.gilpick.progress가 소유한다. 여행 상세의
        // `오늘 여행 시작`·`여행 진행 화면으로`가 이 route로 들어오고, `장소 추가`·`경로 보기`는
        // 위 itineraryGraph·routeGraph로 간다.
        progressGraph(navController, onSessionExpired = onSessionExpired, onNotifications = { navController.navigate(NotificationListRoute) })

        // F009 대체 장소. destination 정의는 com.gilpick.alternative가 소유한다. 진행 화면의 변수 경고
        // 배너가 이 route로 들어오고, `기존 일정 그대로 진행`은 진행 화면으로 돌아간다.
        alternativeGraph(
            navController,
            onSessionExpired = onSessionExpired,
            onSelectPlace = ::openRoutePreview,
            onDismissed = { navController.popBackStack() },
        )

        // F011 알림 목록·감지 목록. destination 정의는 com.gilpick.notification이 소유한다. 알림 탭은
        // 유형에 따라 위 alternativeGraph·progressGraph로 간다. 헤더 벨 진입점은 여행 목록·progressGraph의 onNotifications다.
        notificationGraph(
            navController,
            onSessionExpired = onSessionExpired,
            onOpenDetection = { detectionId, tripId -> navController.navigate(AlternativePlacesRoute(detectionId, tripId)) },
            onOpenProgress = { tripId, tripName -> navController.navigate(ActiveTravelRoute(tripId, tripName)) },
        )

        // F003 장소 검색·상세. destination 정의는 com.gilpick.place가 소유하고 여기서는
        // 등록만 한다. `일정에 추가` 결과는 편집 화면 entry로 돌려주고 검색·상세를 닫는다.
        placeGraph(
            navController,
            onSessionExpired = onSessionExpired,
            onAddToSchedule = navController::returnAddToSchedule,
        )
    }
}

/**
 * 진행 화면 헤더 여행명. 푸시 payload에는 없어(FR-006) 여행을 조회한다. 실패하면 빈 이름으로 가되
 * 진행 화면이 자기 조회에서 같은 원인(오프라인·세션 만료)을 안내한다.
 */
/**
 * F010 변경 경로 미리보기 진입 지점.
 *
 * F009는 후보·직접 검색에서 고른 장소를 [SelectedAlternative]로 여기까지만 전달하고 일정을 바꾸지
 * 않는다(F009 FR-015). F010이 미리보기 화면을 붙일 때 이 함수 본문을 그 route 이동으로 바꾼다.
 */
@Suppress("UNUSED_PARAMETER")
private fun openRoutePreview(selected: SelectedAlternative) = Unit

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
