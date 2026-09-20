package com.gilpick.progress

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.gilpick.alternative.AlternativePlacesRoute
import com.gilpick.alternative.AlternativeRepository
import com.gilpick.replacement.ReplacementRepository
import com.gilpick.itinerary.ItineraryEditRoute
import com.gilpick.itinerary.ItineraryEditViewModel
import com.gilpick.itinerary.ItineraryRepository
import com.gilpick.route.DayRouteRoute
import com.gilpick.route.RouteDto
import com.gilpick.route.RouteMap
import com.gilpick.route.RouteMarks
import com.gilpick.trip.KST
import java.time.Clock
import kotlinx.serialization.Serializable

/**
 * 진행 화면 route(T021).
 *
 * @property tripId 여행.
 * @property tripName 헤더의 여행명. 여행 상세가 이미 알고 있어 다시 조회하지 않는다(F005 `dayNumber`와 같은 이유).
 */
@Serializable
data class ActiveTravelRoute(
    val tripId: String,
    val tripName: String,
)

/**
 * 진행 화면 destination을 app navigation graph에 등록한다.
 *
 * 진입점은 내 여행 목록의 진행 중 여행과 하단 `여행 중` 탭이다(#711). `오늘 여행 시작하기`도 이 화면에 있고,
 * 헤더 `일정 편집`은 일정 상세로 간다. `장소 추가`는 오늘 날짜의
 * 일정 편집(장소 검색 바로 열기)으로, `경로 보기`는 보고 있는 날짜의 F005 경로 화면으로 간다. 돌아오면
 * 이 entry가 다시 RESUMED가 되므로 [LifecycleResumeEffect]가 개요·진행 현황을 다시 조회한다. 앱을
 * 잠시 나갔다 와도 같은 경로로 재조회한다.
 *
 * @param navController 일정 편집·경로 화면으로 이동하는 데 쓴다.
 * @param onSessionExpired 자격이 무효로 확정됐다. F001 재인증 흐름으로 넘긴다.
 * @param repository 진행 데이터 접근 지점을 만든다. 기본값은 실제 서버이며 navigation test가 바꿔 끼운다.
 * @param itineraryRepository 일정 개요 접근 지점을 만든다. 기본값은 실제 서버이며 navigation test가 바꿔 끼운다.
 * @param alternativeRepository F009 배너용 감지 목록 접근 지점을 만든다. `null`을 돌려주면 배너를 조회하지 않는다.
 * @param replacementRepository F010 장소 변경 되돌리기 접근 지점을 만든다. `null`을 돌려주면 되돌리기를 보내지 않는다.
 * @param onOpenTripDetail 헤더 `일정 편집`. `MainActivity`가 일정 상세로 잇는다. 돌아오면 재개 조회가 바뀐 일정을 받는다(#711).
 * @param locationProvider 시작 요청에 실을 현재 위치의 출처를 만든다. navigation test가 바꿔 끼운다.
 * @param hasBackgroundPermission 자동 감지에 필요한 백그라운드 위치 권한 확인. navigation test가 바꿔 끼운다.
 * @param clock 오늘 날짜의 출처. navigation test가 고정한다.
 * @param onOpenVariableMonitor 헤더 변수 감지 경고 버튼. `MainActivity`가 `VariableMonitorRoute(tripId)`로 잇는다.
 * @param map 지도 영역. 기본값은 Naver [RouteMap]이며 UI test가 자리 표시로 바꿔 끼운다.
 */
fun NavGraphBuilder.progressGraph(
    navController: NavController,
    onSessionExpired: () -> Unit,
    repository: (Context) -> ProgressRepository = ProgressViewModel::defaultRepository,
    itineraryRepository: (Context) -> ItineraryRepository = ItineraryEditViewModel::defaultRepository,
    alternativeRepository: (Context) -> AlternativeRepository? = AlternativeRepository::default,
    replacementRepository: (Context) -> ReplacementRepository? = ReplacementRepository::default,
    onNotifications: () -> Unit = {},
    onOpenVariableMonitor: (tripId: String) -> Unit = {},
    onOpenTripDetail: (tripId: String) -> Unit = {},
    locationProvider: (Context) -> CurrentLocationProvider = { DeviceLocationProvider.create(it.applicationContext) },
    hasBackgroundPermission: (Context) -> Boolean = ProgressViewModel::hasBackgroundLocationPermission,
    clock: Clock = Clock.system(KST),
    map: @Composable (RouteDto, RouteMarks, Modifier) -> Unit = { route, marks, modifier ->
        RouteMap(route = route, marks = marks, modifier = modifier, sheetFraction = 0f, myLocation = true)
    },
) {
    composable<ActiveTravelRoute> { entry ->
        val route = entry.toRoute<ActiveTravelRoute>()
        val context = LocalContext.current
        val factory = remember(entry) {
            ProgressViewModel.factory(
                tripId = route.tripId,
                progressRepository = repository(context),
                itineraryRepository = itineraryRepository(context),
                detectionRepository = DetectionRepository.default(context),
                // 서버가 감지 대상을 내려주기 시작하면(#261) 그대로 등록된다. 그전에는 빈 목록이라 아무 것도 걸지 않는다.
                geofenceManager = GeofenceManager(
                    client = PlayServicesGeofenceClient(context),
                    session = PrefsDetectionSessionStore(context),
                ),
                hasBackgroundPermission = { hasBackgroundPermission(context) },
                alternativeRepository = alternativeRepository(context),
                replacementRepository = replacementRepository(context),
                locationProvider = locationProvider(context),
                clock = clock,
            )
        }
        val viewModel: ProgressViewModel = viewModel(factory = factory)
        val state by viewModel.state.collectAsStateWithLifecycle()

        LifecycleResumeEffect(Unit) {
            // 재개할 때마다 조회하고, 그 안에서 권한을 다시 확인한다. 설정에서 바꾸고 돌아온 경우와
            // 진행 중 권한을 회수한 경우가 모두 여기로 들어온다.
            viewModel.onResume()
            // 탭 이동·다른 여행 화면으로 가려지면 이 여행의 반복 조회와 감지 동기화를 멈춘다(#684).
            onPauseOrDispose { viewModel.onPause() }
        }

        // 백그라운드 위치는 앱 사용 중 권한과 같은 화면에서 함께 물을 수 없다(research 8절).
        // 앱 사용 중 권한을 먼저 받고 두 번째 단계로 백그라운드 권한을 요청한다.
        val backgroundLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { viewModel.onBackgroundPermissionResult() }

        // 자동 감지 꺼짐 안내의 `권한 허용`은 앱 설정으로 바로 가지 않고 위치 권한 안내 화면을 먼저 연다(F007 UI-005).
        // 약관 동의를 서버에 기록하기 전에는 위치 권한을 요청하지 않는다(FR-024a).
        var permissionGuideOpen by rememberSaveable { mutableStateOf(false) }
        val foregroundLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            // 앱 사용 중 권한이 있을 때만 백그라운드 단계로 잇는다. 이미 허용된 경우 시스템은 창 없이 바로 돌려준다.
            if (DeviceLocationProvider.hasLocationPermission(context)) {
                requestBackgroundLocation(context, backgroundLauncher)
            }
        }
        if (permissionGuideOpen) {
            LocationPermissionGuideDialog(
                onClose = { permissionGuideOpen = false },
                onConsented = {
                    permissionGuideOpen = false
                    foregroundLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    )
                },
            )
        }

        ActiveTravelScreen(
            state = state,
            tripName = route.tripName,
            onRetry = viewModel::load,
            onAddPlace = { navController.navigate(ItineraryEditRoute(route.tripId, viewModel.today.toString(), openSearch = true)) },
            onOpenRoute = { date, dayNumber -> navController.navigate(DayRouteRoute(route.tripId, date, dayNumber)) },
            onReauthenticate = onSessionExpired,
            onArrive = viewModel::arrive,
            onSkip = viewModel::skip,
            onDepart = viewModel::depart,
            onRetryAction = viewModel::retryAction,
            onDismissActionError = viewModel::dismissActionError,
            onStatusAction = viewModel::updateStatus,
            onSelectDate = viewModel::selectDate,
            onReturnToToday = viewModel::returnToToday,
            onDecide = viewModel::decide,
            onRetryDecision = viewModel::retryDecision,
            onDismissCandidate = viewModel::dismissCandidate,
            onUndo = viewModel::undo,
            onUndoReplacement = viewModel::undoReplacement,
            onEnableDetection = { permissionGuideOpen = true },
            onDismissDetectionNotice = viewModel::dismissDetectionNotice,
            // F009 배너 → 그 감지의 대체 장소 화면. 돌아오면 위 재개 조회가 배너를 다시 맞춘다(거절 후 소멸).
            onOpenAlternatives = { detectionId -> navController.navigate(AlternativePlacesRoute(detectionId, route.tripId)) },
            onNotifications = onNotifications,
            onOpenVariableMonitor = { onOpenVariableMonitor(route.tripId) },
            // 일정 상세로 간다. 돌아오면 위 재개 조회가 새 일정을 받고 보던 날짜는 유지된다(#711).
            onEdit = { onOpenTripDetail(route.tripId) },
            onStartToday = viewModel::startToday,
            onRetryStart = viewModel::retryStart,
            map = map,
        )
    }
}

/**
 * 백그라운드 위치 권한을 요청한다(T033).
 *
 * Android 11 이상은 이 권한을 시스템 대화상자로 바로 허용할 수 없어 앱 설정 화면으로 보낸다.
 * 그 이전 버전은 일반 권한 요청으로 받는다. 어느 쪽이든 거부해도 진행은 막지 않는다(FR-024).
 */
private fun requestBackgroundLocation(
    context: Context,
    launcher: ManagedActivityResultLauncher<String, Boolean>,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } else {
        launcher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
}
