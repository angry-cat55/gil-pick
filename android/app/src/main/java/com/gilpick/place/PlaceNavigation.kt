package com.gilpick.place

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.gilpick.ui.theme.LocalGilpickSpacing
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
 * F003은 route 등록과 검색 → 상세 → 뒤로가기까지만 담당한다. 진입점은 pen의
 * `04. 일정 편집 화면`에 `장소 추가`로 설계돼 있으나 그 화면이 F004 범위여서 아직
 * 없다. F003이 임시 진입 UI를 대신 만들지 않기로 T035에서 확정했다.
 *
 * 상세는 #139에서 실제 화면이다. ViewModel이 상세 destination의 back stack entry에 묶여
 * 회전·복귀에도 상태가 남고, 뒤로가면 검색 entry가 살아 있어 조건·결과가 유지된다(UI-009).
 *
 * @param navController 상세로 이동하고 뒤로 돌아오는 데 쓴다.
 */
fun NavGraphBuilder.placeGraph(navController: NavController) {
    composable<PlaceSearchRoute> {
        PlaceSearchPlaceholderScreen(
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
        )
    }
}

/**
 * US1 검색 화면이 들어올 자리를 지키는 임시 화면.
 *
 * **임시 화면이다.** #142(T017)에서 실제 검색 화면으로 교체한다. route 등록과 back
 * stack 동작을 검증할 최소 요소만 두었고 `loading`·`empty`·`error`·`content` 상태와
 * 접근성·screenshot 검증은 그 Issue에서 수행한다. 색·간격을 직접 쓰지 않으려고
 * theme 토큰만 사용한다.
 */
@Composable
private fun PlaceSearchPlaceholderScreen(
    onPlaceClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(LocalGilpickSpacing.current.space4)) {
        Text(text = "장소 검색", style = MaterialTheme.typography.titleLarge)
        Button(onClick = { onPlaceClick(PLACEHOLDER_PLACE_ID) }) {
            Text(text = "첫 번째 결과 열기")
        }
    }
}

/** 임시 검색 화면이 상세로 넘기는 place ID. 실제 검색 화면이 들어오면 사라진다. */
internal const val PLACEHOLDER_PLACE_ID = "tourapi:126508"
