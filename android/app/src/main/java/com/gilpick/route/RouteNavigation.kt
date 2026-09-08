package com.gilpick.route

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.gilpick.itinerary.ItineraryEditRoute
import com.gilpick.progress.ProgressRepository
import com.gilpick.progress.ProgressViewModel
import java.time.LocalDate
import kotlinx.serialization.Serializable

/**
 * 날짜별 경로 화면 route.
 *
 * @property tripId 여행.
 * @property date 경로를 볼 날짜(`yyyy-MM-dd`).
 * @property dayNumber 헤더 제목의 `N일차`. 여행 상세가 이미 알고 있어 다시 조회하지 않는다.
 */
@Serializable
data class DayRouteRoute(
    val tripId: String,
    val date: String,
    val dayNumber: Int,
)

/**
 * 날짜별 경로 destination을 app navigation graph에 등록한다.
 *
 * `empty`의 `장소 추가`는 그 날짜의 일정 편집(장소 검색 바로 열기)으로 간다. 저장하고 돌아오면
 * 이 화면은 back stack에 남아 있으므로 ViewModel이 다시 조회한다.
 *
 * @param navController 일정 편집으로 이동하고 뒤로 가는 데 쓴다.
 * @param onSessionExpired 자격이 무효로 확정됐다. F001 재인증 흐름으로 넘긴다.
 * @param repository 경로 데이터 접근 지점을 만든다. 기본값은 실제 서버이며 navigation test가 바꿔 끼운다.
 * @param progressRepository 진행 현황 접근 지점을 만든다. 시작된 날짜의 상태를 지도·목록에 겹치는 데 쓴다(T031).
 * @param map 지도 영역. 기본값은 Naver [RouteMap]이며 UI test가 자리 표시로 바꿔 끼운다.
 */
fun NavGraphBuilder.routeGraph(
    navController: NavController,
    onSessionExpired: () -> Unit,
    repository: (Context) -> RouteRepository = RouteViewModel::defaultRepository,
    progressRepository: (Context) -> ProgressRepository? = ProgressViewModel::defaultRepository,
    map: @Composable (RouteDto, RouteMarks, Modifier) -> Unit = { route, marks, modifier -> RouteMap(route = route, marks = marks, modifier = modifier) },
) {
    composable<DayRouteRoute> { entry ->
        val route = entry.toRoute<DayRouteRoute>()
        val date = LocalDate.parse(route.date)
        val context = LocalContext.current
        val factory = remember(entry) {
            RouteViewModel.factory(tripId = route.tripId, date = date, repository = repository(context), progressRepository = progressRepository(context))
        }
        val viewModel: RouteViewModel = viewModel(factory = factory)
        val state by viewModel.state.collectAsStateWithLifecycle()

        DayRouteScreen(
            state = state,
            dayNumber = route.dayNumber,
            date = date,
            onBack = { navController.popBackStack() },
            onRetry = viewModel::retry,
            onAddPlace = { navController.navigate(ItineraryEditRoute(route.tripId, route.date, openSearch = true)) },
            onReauthenticate = onSessionExpired,
            map = map,
        )
    }
}
