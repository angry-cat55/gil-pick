package com.gilpick.alternative

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.gilpick.route.Position
import kotlinx.serialization.Serializable

/**
 * 대체 장소 화면 route(T026).
 *
 * @property detectionId 기준 감지. 화면은 이 식별자 하나로 열린다(spec FR-028, F011 푸시 진입도 같은 route).
 * @property tripId 감지가 속한 여행. F010이 변경 경로 미리보기에서 일정을 찾는 데 쓴다.
 */
@Serializable
data class AlternativePlacesRoute(
    val detectionId: String,
    val tripId: String,
)

/**
 * 직접 검색 화면 route(T032). 대체 장소 화면의 `직접 검색`·`직접 검색해서 고르기`로만 연다.
 *
 * @property detectionId 기준 감지. ALT-002 경로와 선택 값의 기준이다.
 * @property tripId 감지가 속한 여행. [AlternativePlacesRoute]와 같은 이유로 들고 다닌다.
 */
@Serializable
data class AlternativeSearchRoute(
    val detectionId: String,
    val tripId: String,
)

/**
 * 대체 장소 destination을 app navigation graph에 등록한다.
 *
 * 진입점은 진행 화면의 변수 경고 배너다(FR-028). 돌아오면 entry가 다시 RESUMED가 되므로
 * [LifecycleResumeEffect]가 상세·후보를 다시 조회한다. 후보 선택은 [onSelectPlace]로 F010에 값을
 * 넘길 뿐 일정을 바꾸지 않고(FR-015), 거절 성공은 [onDismissed]로 알린다. `직접 검색`은 [AlternativeSearchRoute]로
 * 이어지고 그 선택도 같은 [onSelectPlace]에 `candidateId = null`로 닿는다.
 *
 * @param navController 뒤로 가기·`돌아가기`·`진행 화면으로`에 쓴다.
 * @param onSessionExpired 자격이 무효로 확정됐다. F001 재인증 흐름으로 넘긴다.
 * @param onSelectPlace 후보의 `경로 비교`·`비교`와 직접 검색 행 선택. F010 변경 경로 미리보기로 넘길 값이다.
 * @param onDismissed `기존 일정 그대로 진행`이 성공했다. 호출자가 진행 화면으로 돌린다.
 * @param repository 감지·대체 장소 데이터 접근 지점을 만든다. 기본값은 실제 서버이며 navigation test가 바꿔 끼운다.
 * @param map 지도 영역. 기본값은 Naver [AlternativeMap]이며 UI test가 자리 표시로 바꿔 끼운다.
 */
fun NavGraphBuilder.alternativeGraph(
    navController: NavController,
    onSessionExpired: () -> Unit,
    onSelectPlace: (SelectedAlternative) -> Unit,
    onDismissed: () -> Unit,
    repository: (Context) -> AlternativeRepository = AlternativeRepository::default,
    map: @Composable (Position?, List<AlternativeCandidateDto>, Modifier) -> Unit = { origin, candidates, modifier ->
        AlternativeMap(origin = origin, candidates = candidates, modifier = modifier)
    },
) {
    composable<AlternativePlacesRoute> { entry ->
        val route = entry.toRoute<AlternativePlacesRoute>()
        val context = LocalContext.current
        val factory = remember(entry) { AlternativeViewModel.factory(detectionId = route.detectionId, repository = repository(context)) }
        val viewModel: AlternativeViewModel = viewModel(factory = factory)
        val state by viewModel.state.collectAsStateWithLifecycle()
        val dismissed by viewModel.dismissed.collectAsStateWithLifecycle()

        LifecycleResumeEffect(Unit) {
            viewModel.load()
            onPauseOrDispose {}
        }
        // 거절 성공은 한 번만 알린다. ViewModel이 값을 되돌리지 않으므로 재구성돼도 다시 부르지 않는다.
        LaunchedEffect(dismissed) {
            if (dismissed) onDismissed()
        }

        AlternativePlacesScreen(
            state = state,
            onBack = { navController.popBackStack() },
            onRetry = viewModel::load,
            onSelect = { candidate -> onSelectPlace(viewModel.select(candidate)) },
            onSearch = { navController.navigate(AlternativeSearchRoute(detectionId = route.detectionId, tripId = route.tripId)) },
            onKeep = viewModel::dismiss,
            onDismissKeepError = viewModel::dismissError,
            onReauthenticate = onSessionExpired,
            map = map,
            modifier = Modifier.fillMaxSize(),
        )
    }
    composable<AlternativeSearchRoute> { entry ->
        val route = entry.toRoute<AlternativeSearchRoute>()
        val context = LocalContext.current
        val factory = remember(entry) { AlternativeSearchViewModel.factory(detectionId = route.detectionId, repository = repository(context)) }
        val viewModel: AlternativeSearchViewModel = viewModel(factory = factory)
        val state by viewModel.state.collectAsStateWithLifecycle()

        AlternativeSearchScreen(
            state = state,
            onBack = { navController.popBackStack() },
            onQueryChange = viewModel::onQueryChange,
            onClearQuery = viewModel::onClearQuery,
            onSearch = viewModel::search,
            onRetry = viewModel::retry,
            onReauthenticate = onSessionExpired,
            onLoadMore = viewModel::loadMore,
            onRetryLoadMore = viewModel::retryLoadMore,
            onSelect = { item -> onSelectPlace(viewModel.select(item)) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
