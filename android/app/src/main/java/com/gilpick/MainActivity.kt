package com.gilpick

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as PlatformColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gilpick.auth.AuthUiState
import com.gilpick.auth.AuthViewModel
import com.gilpick.auth.LoginScreen
import com.gilpick.auth.RefreshOfflineScreen
import com.gilpick.alternative.AlternativePlacesRoute
import com.gilpick.alternative.alternativeGraph
import com.gilpick.itinerary.ItineraryEditRoute
import com.gilpick.itinerary.itineraryGraph
import com.gilpick.itinerary.returnAddToSchedule
import com.gilpick.notification.GilpickMessagingService
import com.gilpick.notification.NotificationListRoute
import com.gilpick.notification.NotificationTarget
import com.gilpick.notification.PendingNotificationTarget
import com.gilpick.notification.VariableMonitorRoute
import com.gilpick.notification.notificationGraph
import com.gilpick.notification.tripNameOf
import com.gilpick.replacement.RoutePreviewRoute
import com.gilpick.replacement.replacementGraph
import com.gilpick.place.PlaceDetailRoute
import com.gilpick.place.placeGraph
import com.gilpick.progress.ActiveTravelRoute
import com.gilpick.progress.progressGraph
import com.gilpick.route.DayRouteRoute
import com.gilpick.route.routeGraph
import com.gilpick.settings.SettingsRepository
import com.gilpick.settings.SettingsRoute
import com.gilpick.settings.settingsGraph
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.gilpick.trip.ActiveTripTabPhase
import com.gilpick.trip.ActiveTripTabScreen
import com.gilpick.trip.ActiveTripTabViewModel
import com.gilpick.trip.TripDeletePhase
import com.gilpick.trip.TripDetailPhase
import com.gilpick.trip.TripDetailScreen
import com.gilpick.trip.TripDetailViewModel
import com.gilpick.trip.TripFormScreen
import com.gilpick.trip.TripFormViewModel
import com.gilpick.trip.TripListScreen
import com.gilpick.trip.TripListViewModel
import com.gilpick.trip.openNewTripItinerary
import com.gilpick.ui.theme.GilpickTheme
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickSpacing
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
        // 화면들이 이미 system bar inset을 직접 처리한다(UI-006). 창을 edge-to-edge로 열어야 각 화면의 상단 배경이
        // 상태 표시줄 뒤까지 이어진다(#590). API 35+는 플랫폼이 강제하지만 그 아래 기기는 이 호출이 없으면
        // 기본 테마의 회색 `statusBarColor`가 칠해져 앱 화면과 끊겨 보인다.
        // 앱에 어두운 배색이 없으므로 기기의 다크 모드와 무관하게 밝은 배경 기준(어두운 아이콘)으로 고정한다.
        // 어두운 상단을 쓰는 화면은 [LightSystemBarIcons]로 그동안만 뒤집는다.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(PlatformColor.TRANSPARENT, PlatformColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(PlatformColor.TRANSPARENT, PlatformColor.TRANSPARENT),
        )
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
 * @param settingsRepository F012 설정 데이터 접근. UI test가 서버 없이 최상위 탭 왕복을 보려고 바꿔 끼운다.
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
    settingsRepository: (Context) -> SettingsRepository = SettingsRepository::default,
) {
    // 테마를 여기서 적용해 화면과 test·preview가 같은 토큰 위에서 동작하게 한다.
    GilpickTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            AuthRoute(state, onKakaoLogin, onRetry, modifier, onRetryRefresh, onLogout, onSessionExpired, pendingNotification, onNotificationConsumed, settingsRepository)
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
    settingsRepository: (Context) -> SettingsRepository,
) {
    when (state) {
        is AuthUiState.Authenticated -> TripRoute(
            modifier = modifier,
            // 설정 화면의 계정 표시는 이미 손에 있는 session 값을 그대로 쓴다(#402, FR-009).
            // 프로필 조회 API를 따로 두지 않기로 했으므로 여기가 유일한 출처다.
            nickname = state.nickname,
            profileImageUrl = state.profileImageUrl,
            onLogout = onLogout,
            onSessionExpired = onSessionExpired,
            pendingNotification = pendingNotification,
            onNotificationConsumed = onNotificationConsumed,
            settingsRepository = settingsRepository,
        )

        // 통신 장애로 갱신이 중단된 상태다. session은 유지한 채 보호 기능만 막는다.
        is AuthUiState.RefreshOffline -> RefreshOfflineScreen(
            onRetry = onRetryRefresh,
            modifier = modifier,
            onLogout = onLogout,
        )

        // 저장된 session을 복원하는 동안이다. 로그인 화면을 먼저 보였다가 메인으로 넘기면 flicker가 된다(#616).
        is AuthUiState.Loading -> LaunchSurface(modifier = modifier)

        else -> LoginScreen(
            state = state,
            onKakaoLogin = onKakaoLogin,
            onRetry = onRetry,
            modifier = modifier,
        )
    }
}

/**
 * 인증 상태가 정해지기 전에 보이는 브랜드 화면(#616).
 *
 * 창 배경(`Theme.Gilpick`의 `windowBackground`)·system splash와 같은 색이라 앱이 뜨는 동안 색이 끊기지 않는다.
 * 로그인 수단이나 진행 표시를 두지 않는다. `restore()`는 저장소에서 session을 읽어 복호화할 뿐이라 화면에 남는
 * 시간이 짧고, 여기에 spinner를 두면 그 자체가 깜빡임이 된다.
 */
@Composable
private fun LaunchSurface(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.gilpick_logo),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier
                .widthIn(max = LAUNCH_LOGO_WIDTH)
                .fillMaxWidth(LAUNCH_LOGO_FRACTION)
                .aspectRatio(LAUNCH_LOGO_RATIO),
        )
    }
}

/** launch surface 로고. 로그인 화면과 같은 비율·최대 폭이라 이어서 볼 때 크기가 튀지 않는다. */
private val LAUNCH_LOGO_WIDTH = 240.dp
private const val LAUNCH_LOGO_FRACTION = 0.6f
private const val LAUNCH_LOGO_RATIO = 801f / 311f

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
    nickname: String?,
    profileImageUrl: String?,
    onLogout: () -> Unit,
    onSessionExpired: () -> Unit,
    pendingNotification: PendingNotificationTarget?,
    onNotificationConsumed: () -> Unit,
    settingsRepository: (Context) -> SettingsRepository,
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    // F012 최상위 탭(T026, Figma 하단 탐색). 탭 화면 위에서만 보이고 상세·편집 같은 하위 화면에서는
    // 내려가 기존 화면의 배치를 바꾸지 않는다. 첫 composition에 entry가 아직 없으면 시작 화면인
    // 여행 목록 탭으로 보아 첫 프레임에 막대가 깜빡이지 않게 한다.
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val selectedTab = when {
        destination == null -> TopLevelTab.Trips
        destination.hasRoute<TripListRoute>() -> TopLevelTab.Trips
        // 여행 중 화면은 상세의 `여행 진행 화면으로`로 들어와도 Figma처럼 하단 탭과 함께 보인다(#502).
        destination.hasRoute<ActiveTripTabRoute>() || destination.hasRoute<ActiveTravelRoute>() -> TopLevelTab.Active
        destination.hasRoute<SettingsRoute>() -> TopLevelTab.Settings
        else -> null
    }

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

    // contentWindowInsets를 0으로 둔다. 각 화면이 system bar inset을 이미 스스로 처리하고 있어
    // Scaffold가 한 번 더 더하면 위쪽이 밀린다. 하단 탭은 NavigationBar가 navigation bar inset을
    // 자체 처리하고, content는 탭 높이만큼만 padding을 받아 로그아웃 같은 마지막 항목이 가려지지 않는다(UI-006).
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (selectedTab != null) {
                TopLevelTabBar(
                    selected = selectedTab,
                    onSelect = { tab ->
                        // 이미 보고 있는 탭은 다시 열지 않는다. `여행 중`은 다시 열면 조회부터 다시 해 화면이 깜빡인다.
                        if (tab == selectedTab) return@TopLevelTabBar
                        navController.navigateToTab(tab.route)
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TripListRoute,
            // 탭 높이만큼 준 padding을 inset으로도 소비한다. 하단 시스템 여백을 스스로 더하는 화면(여행 중 화면)이
            // 탭 막대가 이미 처리한 navigation bar 여백을 한 번 더 더하지 않게 한다(#502).
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
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
                    onNotifications = { navController.navigate(NotificationListRoute) },
                )
            }

            composable<TripFormRoute> {
                val viewModel: TripFormViewModel = viewModel(
                    factory = TripFormViewModel.factory(LocalContext.current),
                )
                val state by viewModel.state.collectAsStateWithLifecycle()

                // 생성에 성공하면 폼을 빼고 새 여행의 일정 편집으로 간다(#498). 뒤로 가면 새 여행 상세, 그다음 목록이다.
                LaunchedEffect(state.savedTripId) {
                    val tripId = state.savedTripId ?: return@LaunchedEffect
                    val startDate = state.startDate
                    viewModel.consumeSaved()
                    if (startDate != null) {
                        navController.openNewTripItinerary<TripFormRoute>(TripDetailRoute(tripId), tripId, startDate)
                    } else {
                        // 저장은 기간 검증을 통과해야 하므로 오지 않는 경로다. 오면 기존처럼 목록으로 돌아간다.
                        navController.popBackStack()
                    }
                }

                TripFormScreen(
                    state = state,
                    onNameChange = viewModel::onNameChange,
                    onPeriodChange = viewModel::onPeriodChange,
                    onSubmit = viewModel::submit,
                    onBack = { navController.popBackStack() },
                    onImagePicked = viewModel::onImagePicked,
                    onRemoveImage = viewModel::onRemoveImage,
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

                // 수정 화면에서 여행을 삭제하면(#443) 상세도 사라진 여행이라 목록까지 돌아간다.
                LaunchedEffect(state.deletion) {
                    if (state.deletion is TripDeletePhase.Deleted) {
                        viewModel.consumeDeleted()
                        navController.popBackStack(TripListRoute, inclusive = false)
                    }
                }

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
                    onBack = { navController.popBackStack() },
                    onDelete = viewModel::delete,
                    onDeleteErrorShown = viewModel::clearDeleteError,
                    onImagePicked = viewModel::onImagePicked,
                    onRemoveImage = viewModel::onRemoveImage,
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
            // #502 하단 `여행 중` 탭. 진행 중 여행을 찾으면 이 진입 화면을 빼고 여행 중 화면으로 바꾼다.
            // 뒤로 가면 시작 탭(내 여행)이고, 탭을 다시 누르면 새로 찾는다(여행이 끝났을 수 있다).
            composable<ActiveTripTabRoute> {
                val viewModel: ActiveTripTabViewModel = viewModel(factory = ActiveTripTabViewModel.factory(LocalContext.current))
                val phase by viewModel.phase.collectAsStateWithLifecycle()

                LaunchedEffect(Unit) { viewModel.load() }
                LaunchedEffect(phase) {
                    val trip = (phase as? ActiveTripTabPhase.Found)?.trip ?: return@LaunchedEffect
                    navController.navigate(ActiveTravelRoute(trip.tripId, trip.name)) {
                        popUpTo<ActiveTripTabRoute> { inclusive = true }
                    }
                }

                ActiveTripTabScreen(
                    phase = phase,
                    onRetry = viewModel::load,
                    onOpenTrips = { navController.navigateToTab(TripListRoute) },
                )
            }

            progressGraph(
                navController,
                onSessionExpired = onSessionExpired,
                onNotifications = { navController.navigate(NotificationListRoute) },
                onOpenVariableMonitor = { tripId -> navController.navigate(VariableMonitorRoute(tripId)) },
            )

            // F009 대체 장소. destination 정의는 com.gilpick.alternative가 소유한다. 진행 화면의 변수 경고
            // 배너가 이 route로 들어오고, `기존 일정 그대로 진행`은 진행 화면으로 돌아간다.
            alternativeGraph(
                navController,
                onSessionExpired = onSessionExpired,
                // 추천 후보와 직접 검색 양쪽 모두 같은 미리보기 화면으로 들어간다. F009는 고른 값만
                // 넘기고 일정을 바꾸지 않으므로(F009 FR-015) 여기서 처음으로 F010 흐름이 시작된다.
                onSelectPlace = { selected ->
                    navController.navigate(
                        RoutePreviewRoute(
                            detectionId = selected.detectionId,
                            placeId = selected.placeId,
                            candidateId = selected.candidateId,
                            placeName = selected.name,
                        ),
                    )
                },
                onDismissed = { navController.popBackStack() },
                // 후보 행 탭은 같은 후보 정보를 들고 장소 상세로 간다. 상세의 `장소 변경`이 아래 placeGraph의
                // onReplacePlace로 돌아와 같은 미리보기 route를 연다(#660).
                onOpenPlace = { selected ->
                    navController.navigate(
                        PlaceDetailRoute(
                            placeId = selected.placeId,
                            detectionId = selected.detectionId,
                            candidateId = selected.candidateId,
                        ),
                    )
                },
            )

            // F010 변경 경로 미리보기. destination 정의는 com.gilpick.replacement가 소유한다. 위
            // alternativeGraph의 후보 선택이 이 route로 들어오고, `다른 후보 보기`는 그 자리로 돌아간다.
            replacementGraph(navController, onSessionExpired = onSessionExpired)

            // F011 알림 목록·감지 목록. destination 정의는 com.gilpick.notification이 소유한다. 알림 탭은
            // 유형에 따라 위 alternativeGraph·progressGraph로 간다. 헤더 벨 진입점은 여행 목록·progressGraph의 onNotifications다.
            notificationGraph(
                navController,
                onSessionExpired = onSessionExpired,
                onOpenDetection = { detectionId, tripId -> navController.navigate(AlternativePlacesRoute(detectionId, tripId)) },
                onOpenProgress = { tripId, tripName -> navController.navigate(ActiveTravelRoute(tripId, tripName)) },
            )

            // F012 설정. destination 정의는 com.gilpick.settings가 소유한다. 로그아웃은 앱 전체 인증 상태를
            // 가진 AuthViewModel.logout으로만 이어진다(FR-010). 진입은 위 하단 탭 `설정`이다(T026).
            settingsGraph(
                onLogout = onLogout,
                onSessionExpired = onSessionExpired,
                nickname = nickname,
                profileImageUrl = profileImageUrl,
                repository = settingsRepository,
            )

            // F003 장소 검색·상세. destination 정의는 com.gilpick.place가 소유하고 여기서는
            // 등록만 한다. `일정에 추가` 결과는 편집 화면 entry로 돌려주고 검색·상세를 닫는다.
            placeGraph(
                navController,
                onSessionExpired = onSessionExpired,
                onAddToSchedule = navController::returnAddToSchedule,
                onReplacePlace = { detectionId, candidateId, place ->
                    navController.navigate(
                        RoutePreviewRoute(
                            detectionId = detectionId,
                            placeId = place.placeId,
                            candidateId = candidateId,
                            placeName = place.name,
                        ),
                    )
                },
            )
        }
    }
}

/**
 * 최상위 탭으로 간다. 탭 전환은 back stack을 쌓지 않는다. 떠난 탭의 상태는 저장했다가 돌아올 때
 * 되살리므로 설정 화면은 마지막으로 저장에 성공한 값을 다시 보인다(UI-002).
 *
 * 시작 화면 탭(`내 여행`)은 되살리지 않는다(#552). 시작 화면까지 pop하며 저장한 화면(상세 → 여행 중)은
 * 시작 화면 몫으로도 저장되므로, 되살리면 목록 대신 방금 떠난 여행 중 화면이 다시 열린다.
 */
internal fun NavController.navigateToTab(route: Any) {
    val start = graph.findStartDestination()
    navigate(route) {
        popUpTo(start.id) { saveState = true }
        launchSingleTop = true
        restoreState = !start.hasRoute(route::class)
    }
}

/**
 * 하단 최상위 탭(Figma `App.tsx` 하단 탐색, F012 T026).
 *
 * `여행 중`은 진행 중 여행 하나로 간다(#502). 사용자별 여행 기간 중복 금지(F002 FR-002a)로 진행 중 여행은 많아야 하나다.
 */
private enum class TopLevelTab(
    val route: Any,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    val tag: String,
) {
    Trips(TripListRoute, R.string.trips_title, R.drawable.ic_lucide_home, TAG_NAV_TRIPS),
    Active(ActiveTripTabRoute, R.string.nav_active_travel, R.drawable.ic_lucide_navigation, TAG_NAV_ACTIVE),
    Settings(SettingsRoute, R.string.settings_title, R.drawable.ic_lucide_settings, TAG_NAV_SETTINGS),
}

/**
 * Figma 하단 탐색(`App.tsx`): 흰 바탕, 위 1px `outlineVariant`(`#E2E8F0`), 위 12dp 여백, 22dp 아이콘·4dp 간격·11sp 600 라벨,
 * 선택 탭은 `primary`와 아래 4dp 점, 나머지는 `muted`(#502).
 *
 * M3 `NavigationBar`는 높이가 80dp로 고정돼 Figma보다 커서 직접 그린다. 아래 여백은 Figma `pb-6`(24dp)이 iOS 홈 표시 영역을
 * 포함한 값이라 Android에서는 점이 들어갈 12dp에 navigation bar inset을 더한다. 선택은 색만이 아니라 점·굵기와
 * `selected` 의미로도 전달하고(UI-005, 10절), 각 탭은 48dp 이상 누를 수 있다.
 */
@Composable
private fun TopLevelTabBar(selected: TopLevelTab, onSelect: (TopLevelTab) -> Unit) {
    val colors = LocalGilpickColors.current
    val spacing = LocalGilpickSpacing.current
    Column(
        modifier = Modifier
            .testTag(TAG_NAV_BAR)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(TAB_DIVIDER).background(MaterialTheme.colorScheme.outlineVariant))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = spacing.space4, end = spacing.space4, top = spacing.space3 - TAB_TOUCH_INSET),
        ) {
            TopLevelTab.entries.forEach { tab ->
                val isSelected = tab == selected
                val tint = if (isSelected) MaterialTheme.colorScheme.primary else colors.muted
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .sizeIn(minHeight = TAB_MIN_TOUCH)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) })
                        .testTag(tab.tag)
                        .padding(top = TAB_TOUCH_INSET),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing.space1),
                ) {
                    Icon(painterResource(tab.icon), contentDescription = null, tint = tint, modifier = Modifier.size(TAB_ICON))
                    Text(
                        text = stringResource(tab.label),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                            letterSpacing = 0.sp,
                        ),
                        color = tint,
                    )
                    // Figma `-bottom-3` 점. 선택되지 않은 탭도 같은 자리를 비워 높이가 흔들리지 않게 한다.
                    Box(
                        modifier = Modifier
                            .padding(top = TAB_DOT_GAP, bottom = spacing.space2)
                            .size(TAB_DOT)
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
                    )
                }
            }
        }
    }
}

/** Figma 하단 탐색 실측. 앱 막대 전용이라 테마 토큰이 아니라 여기에 둔다. */
private val TAB_DIVIDER = 1.dp
private val TAB_ICON = 22.dp
private val TAB_DOT = 4.dp
private val TAB_DOT_GAP = 4.dp
private val TAB_MIN_TOUCH = 48.dp

/** 누르는 영역을 탭 위쪽 여백까지 넓힌 만큼 바깥 여백을 줄여 Figma `pt-3` 위치를 맞춘다. */
private val TAB_TOUCH_INSET = 4.dp

internal const val TAG_NAV_BAR = "nav_bar"
internal const val TAG_NAV_TRIPS = "nav_trips"
internal const val TAG_NAV_ACTIVE = "nav_active"
internal const val TAG_NAV_SETTINGS = "nav_settings"

/** 여행 목록. 로그인 후 첫 화면이다. */
@Serializable
private object TripListRoute

/** 하단 `여행 중` 탭 진입(#502). 진행 중 여행을 찾아 여행 중 화면으로 넘기거나 빈·오류 상태를 보인다. */
@Serializable
private object ActiveTripTabRoute

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
