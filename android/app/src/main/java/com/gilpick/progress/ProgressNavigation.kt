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
import androidx.compose.runtime.remember
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
 * 진입점은 여행 상세의 `오늘 여행 시작`·`여행 진행 화면으로`뿐이다(plan.md). `장소 추가`는 오늘 날짜의
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
    map: @Composable (RouteDto, RouteMarks, Modifier) -> Unit = { route, marks, modifier ->
        RouteMap(route = route, marks = marks, modifier = modifier, sheetFraction = 0f)
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
                hasBackgroundPermission = { ProgressViewModel.hasBackgroundLocationPermission(context) },
                alternativeRepository = alternativeRepository(context),
                replacementRepository = replacementRepository(context),
            )
        }
        val viewModel: ProgressViewModel = viewModel(factory = factory)
        val state by viewModel.state.collectAsStateWithLifecycle()

        LifecycleResumeEffect(Unit) {
            // 재개할 때마다 조회하고, 그 안에서 권한을 다시 확인한다. 설정에서 바꾸고 돌아온 경우와
            // 진행 중 권한을 회수한 경우가 모두 여기로 들어온다.
            viewModel.load()
            onPauseOrDispose {}
        }

        // 백그라운드 위치는 앱 사용 중 권한과 같은 화면에서 함께 물을 수 없다(research 8절).
        // F006이 시작 시점에 앱 사용 중 권한을 받았고, 여기서 두 번째 단계만 요청한다.
        val backgroundLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { viewModel.onBackgroundPermissionResult() }

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
            onEnableDetection = { requestBackgroundLocation(context, backgroundLauncher) },
            onDismissDetectionNotice = viewModel::dismissDetectionNotice,
            // F009 배너 → 그 감지의 대체 장소 화면. 돌아오면 위 재개 조회가 배너를 다시 맞춘다(거절 후 소멸).
            onOpenAlternatives = { detectionId -> navController.navigate(AlternativePlacesRoute(detectionId, route.tripId)) },
            onNotifications = onNotifications,
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
