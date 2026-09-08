package com.gilpick.progress

import android.content.Context
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
 * @param map 지도 영역. 기본값은 Naver [RouteMap]이며 UI test가 자리 표시로 바꿔 끼운다.
 */
fun NavGraphBuilder.progressGraph(
    navController: NavController,
    onSessionExpired: () -> Unit,
    repository: (Context) -> ProgressRepository = ProgressViewModel::defaultRepository,
    itineraryRepository: (Context) -> ItineraryRepository = ItineraryEditViewModel::defaultRepository,
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
            )
        }
        val viewModel: ProgressViewModel = viewModel(factory = factory)
        val state by viewModel.state.collectAsStateWithLifecycle()

        LifecycleResumeEffect(Unit) {
            viewModel.load()
            onPauseOrDispose {}
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
            map = map,
        )
    }
}
