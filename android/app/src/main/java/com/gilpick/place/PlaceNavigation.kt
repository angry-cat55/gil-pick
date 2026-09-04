package com.gilpick.place

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

/**
 * 장소 검색 화면 route.
 *
 * 인자가 없으므로 `object`다. 검색 조건은 destination-scoped ViewModel이 들고 있어
 * 상세에서 돌아올 때 그대로 복원된다.
 */
@Serializable
object PlaceSearchRoute

/**
 * 장소 상세 화면 route.
 *
 * @property placeId `tourapi:{contentId}` 또는 `google:{placeId}`. 검색 결과에서 받은
 *   값을 가공하지 않고 그대로 넘긴다. server가 이 prefix로 조회 provider를 정한다.
 */
@Serializable
data class PlaceDetailRoute(val placeId: String)

/**
 * 장소 검색·상세 destination을 app navigation graph에 등록한다.
 *
 * 문자열 route가 아니라 `@Serializable` 타입을 쓴다. 인자 이름과 타입을 컴파일러가
 * 검사하고 [PlaceDetailRoute.placeId]의 `:`도 Navigation이 알아서 encode·decode한다.
 *
 * F003은 route 등록과 검색 → 상세 → 뒤로가기까지만 담당한다. 진입점(`일정 편집`의 `장소 추가`)은
 * F004 범위라 아직 없다. F003이 임시 진입 UI를 대신 만들지 않기로 T035에서 확정했다.
 *
 * 두 ViewModel 모두 각 destination의 back stack entry에 묶여 회전·복귀에도 상태가 남고,
 * 상세에서 뒤로 가면 검색 entry가 살아 있어 조건·결과·목록 위치가 유지된다(UI-009).
 *
 * 로그인 만료는 화면이 안내하고 사용자가 `다시 로그인`을 누르면 [onSessionExpired]로 알린다.
 * 어느 화면이든 같은 F001 재인증 흐름(local session 제거 → 로그인 화면)으로 이어진다.
 *
 * @param navController 상세로 이동하고 뒤로 돌아오는 데 쓴다.
 * @param onSessionExpired 자격이 무효로 확정됐다. F001 재인증 흐름으로 넘긴다.
 */
fun NavGraphBuilder.placeGraph(navController: NavController, onSessionExpired: () -> Unit) {
    composable<PlaceSearchRoute> {
        val viewModel: PlaceSearchViewModel = viewModel(factory = PlaceSearchViewModel.factory(LocalContext.current))
        val state by viewModel.state.collectAsStateWithLifecycle()

        PlaceSearchScreen(
            state = state,
            onBack = { navController.popBackStack() },
            onQueryChange = viewModel::onQueryChange,
            onClearQuery = viewModel::onClearQuery,
            onCategoryChange = viewModel::onCategoryChange,
            onSearch = viewModel::search,
            onRetry = viewModel::retry,
            onReauthenticate = onSessionExpired,
            onLoadMore = viewModel::loadMore,
            onRetryLoadMore = viewModel::retryLoadMore,
            onSearchByCategory = viewModel::onSearchByCategory,
            onPlaceClick = { placeId -> navController.navigate(PlaceDetailRoute(placeId)) },
        )
    }
    composable<PlaceDetailRoute> { entry ->
        val placeId = entry.toRoute<PlaceDetailRoute>().placeId
        val viewModel: PlaceDetailViewModel = viewModel(
            factory = PlaceDetailViewModel.factory(LocalContext.current, placeId),
        )
        val state by viewModel.state.collectAsStateWithLifecycle()

        LaunchedEffect(placeId) { viewModel.load() }

        PlaceDetailScreen(
            state = state,
            onBack = { navController.popBackStack() },
            onRetry = viewModel::retry,
            onReauthenticate = onSessionExpired,
        )
    }
}
