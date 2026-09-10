package com.gilpick.replacement

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.gilpick.alternative.AlternativeRepository
import com.gilpick.progress.ActiveTravelRoute
import com.gilpick.route.RouteRepository
import com.gilpick.route.RouteViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * 변경 경로 미리보기 route(T017).
 *
 * F009가 [com.gilpick.alternative.SelectedAlternative]로 넘기는 값 중 요청에 필요한 셋만 나른다.
 * `tripId`·`scheduleVersion`은 route에 싣지 않고 [PreviewViewModel]이 다시 조회한다. 앞 화면을
 * 열어 둔 사이 일정이 바뀌었을 수 있어 낡은 version을 나르면 승인이 거절되기 때문이다.
 *
 * @property detectionId 기준 감지.
 * @property placeId 사용자가 고른 대체 장소.
 * @property candidateId F009 추천 후보면 그 토큰, 직접 검색이면 `null`. 서버 검증 경로가 갈린다(FR-004).
 */
@Serializable
data class RoutePreviewRoute(
    val detectionId: String,
    val placeId: String,
    val candidateId: String?,
)

/**
 * 변경 경로 미리보기 destination을 app navigation graph에 등록한다.
 *
 * 진입점은 F009 `alternativeGraph(onSelectPlace)`다. 추천 후보와 직접 검색 **양쪽 모두** 이 route로
 * 들어오고 `candidateId` 유무만 다르다.
 *
 * 승인이 끝나면 진행 화면으로 돌아간다(T023). 되돌리기 안내는 진행 화면이 PROG-001의
 * `undoableReplacement`로 받아 보이므로(T029) 값을 들고 가지 않는다. 그 표시는 T030(#351)이 붙인다.
 *
 * `다른 후보 보기`는 REPL-003으로 미리보기를 폐기한 뒤 뒤로 간다(UI-003). 폐기 결과를 기다리지
 * 않고 화면을 닫는다. 폐기는 대상 상태로 결과가 정해지는 자연 멱등이라 실패해도 사용자가 할 일이
 * 없고, 남은 미리보기는 다음 미리보기가 만들어질 때 서버가 `SUPERSEDED`로 내린다(FR-006).
 * 돌아간 자리는 같은 감지 결과의 F009 후보 목록이라 다른 후보를 바로 다시 고를 수 있다.
 *
 * @param navController 뒤로 가기·`다른 후보 보기`에 쓴다.
 * @param onSessionExpired 자격이 무효로 확정됐다. F001 재인증 흐름으로 넘긴다.
 * @param replacements 변경 데이터 접근 지점. 기본값은 실제 서버이며 navigation test가 바꿔 끼운다.
 * @param detections 감지 상세 조회. `tripId`와 날짜를 얻는 데 쓴다.
 * @param routes 그 날짜 경로 조회. `scheduleVersion`과 기존 경로를 얻는 데 쓴다.
 * @param map 지도 영역. 기본값은 F005 지도이며 UI test가 자리 표시로 바꿔 끼운다.
 */
fun NavGraphBuilder.replacementGraph(
    navController: NavController,
    onSessionExpired: () -> Unit,
    replacements: (Context) -> ReplacementRepository = ReplacementRepository::default,
    detections: (Context) -> AlternativeRepository = AlternativeRepository::default,
    routes: (Context) -> RouteRepository = RouteViewModel::defaultRepository,
    map: @Composable (PreviewUiState.Content, Modifier) -> Unit = { content, modifier ->
        PreviewMap(content = content, modifier = modifier)
    },
) {
    composable<RoutePreviewRoute> { entry ->
        val route = entry.toRoute<RoutePreviewRoute>()
        val context = LocalContext.current
        val replacementRepository = remember(entry) { replacements(context) }
        val factory = remember(entry) {
            PreviewViewModel.factory(
                detectionId = route.detectionId,
                placeId = route.placeId,
                candidateId = route.candidateId,
                replacements = replacementRepository,
                detections = detections(context),
                routes = routes(context),
            )
        }
        val viewModel: PreviewViewModel = viewModel(factory = factory)
        val state by viewModel.state.collectAsStateWithLifecycle()
        val approved by viewModel.approved.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        // 승인 성공은 한 번만 처리한다. ViewModel이 값을 되돌리지 않으므로 재구성돼도 다시 가지 않는다.
        // 승인된 미리보기는 폐기하지 않는다(계약상 `409 ALREADY_APPROVED`).
        LaunchedEffect(approved) {
            if (approved == null) return@LaunchedEffect
            // 진행 화면이 back stack에 없을 수 있다. F011 푸시로 대체 장소 화면에 바로 들어온 경우다.
            if (!navController.popBackStack(ActiveTravelRoute::class, inclusive = false)) {
                navController.popBackStack()
            }
        }

        // 미리보기를 만든 뒤 화면을 떠나면 그 미리보기를 폐기한다. 뒤로 가기와 `다른 후보 보기`가
        // 같은 자리로 돌아가므로 두 경로 모두 여기를 지난다.
        val leave: () -> Unit = {
            (state as? PreviewUiState.Content)?.preview?.previewId?.let { previewId ->
                scope.launch { replacementRepository.rejectPreview(previewId) }
            }
            navController.popBackStack()
        }

        RoutePreviewScreen(
            state = state,
            onBack = leave,
            onRetry = viewModel::load,
            onApprove = viewModel::approve,
            onOtherCandidates = leave,
            onReauthenticate = onSessionExpired,
            map = map,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
