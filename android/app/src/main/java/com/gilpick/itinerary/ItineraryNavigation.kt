package com.gilpick.itinerary

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.gilpick.place.AddToScheduleRequest
import com.gilpick.place.PlaceDto
import com.gilpick.place.PlaceSearchRoute
import com.gilpick.place.PlaceTransport
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 일정 편집 화면 route(`research.md` 10절).
 *
 * @property tripId 편집할 여행.
 * @property date 진입 시 선택할 날짜(`yyyy-MM-dd`). 여행 상세의 `일정 편집`은 첫 날짜를,
 *   날짜 헤더의 `추가`는 그 날짜를 넘긴다.
 * @property openSearch 진입 직후 F003 장소 검색으로 바로 갈지. 날짜 헤더의 `추가`가 `true`다.
 */
@Serializable
data class ItineraryEditRoute(
    val tripId: String,
    val date: String,
    val openSearch: Boolean = false,
)

/**
 * F003 시트가 확정한 결과. 편집 화면 back stack entry의 `SavedStateHandle`로 돌아온다.
 *
 * `SavedStateHandle`은 `Parcelable`·`Serializable`만 받으므로 JSON 문자열로 싣는다.
 */
@Serializable
private data class AddToScheduleResult(
    val place: PlaceDto,
    val transport: PlaceTransport,
    val stayMinutes: Int,
)

private const val KEY_ADD_RESULT = "itinerary.addToScheduleResult"
private val resultJson = Json { ignoreUnknownKeys = true }

/**
 * F003 `일정에 추가` 결과를 편집 화면에 돌려주고 검색·상세를 닫는다.
 *
 * `placeGraph`의 `onAddToSchedule`에 그대로 넘긴다. 편집 화면이 back stack에 없으면 F003을
 * 편집 화면 밖에서 연 것이므로 결과를 버린다.
 */
fun NavController.returnAddToSchedule(place: PlaceDto, request: AddToScheduleRequest) {
    val editEntry = runCatching { getBackStackEntry<ItineraryEditRoute>() }.getOrNull() ?: return
    editEntry.savedStateHandle[KEY_ADD_RESULT] =
        resultJson.encodeToString(AddToScheduleResult(place, request.transport, request.stayMinutes))
    popBackStack<ItineraryEditRoute>(inclusive = false)
}

/**
 * 일정 편집 destination을 app navigation graph에 등록한다.
 *
 * ViewModel은 entry의 `SavedStateHandle`을 그대로 받아 초안을 보존하고, F003 결과도 같은
 * handle로 받는다. `장소 추가`와 `openSearch`는 F003 [PlaceSearchRoute]로 간다.
 *
 * @param navController 검색으로 이동하고 저장·취소 뒤 여행 상세로 돌아가는 데 쓴다.
 * @param onSessionExpired 자격이 무효로 확정됐다. F001 재인증 흐름으로 넘긴다.
 * @param repository 일정 데이터 접근 지점을 만든다. 기본값은 실제 서버이며 navigation test가 바꿔 끼운다.
 */
fun NavGraphBuilder.itineraryGraph(
    navController: NavController,
    onSessionExpired: () -> Unit,
    repository: (Context) -> ItineraryRepository = ItineraryEditViewModel::defaultRepository,
) {
    composable<ItineraryEditRoute> { entry ->
        val route = entry.toRoute<ItineraryEditRoute>()
        val context = LocalContext.current
        // factory는 첫 호출에만 쓰이지만 repository 조립(Retrofit 생성)이 recomposition마다 반복되지 않게 기억한다.
        val factory = remember(entry) {
            ItineraryEditViewModel.factory(
                savedState = entry.savedStateHandle,
                tripId = route.tripId,
                date = LocalDate.parse(route.date),
                openSearch = route.openSearch,
                repository = repository(context),
            )
        }
        val viewModel: ItineraryEditViewModel = viewModel(factory = factory)
        val state by viewModel.state.collectAsStateWithLifecycle()
        val pendingResult by entry.savedStateHandle
            .getStateFlow<String?>(KEY_ADD_RESULT, null)
            .collectAsStateWithLifecycle()

        LaunchedEffect(Unit) {
            if (viewModel.takeOpenSearch()) navController.navigate(PlaceSearchRoute)
        }
        LaunchedEffect(pendingResult) {
            val json = pendingResult ?: return@LaunchedEffect
            entry.savedStateHandle.remove<String>(KEY_ADD_RESULT)
            val result = resultJson.decodeFromString<AddToScheduleResult>(json)
            viewModel.addFromSearch(result.place, AddToScheduleRequest(result.transport, result.stayMinutes))
        }
        LaunchedEffect(state.saved, state.exit) {
            if (state.saved || state.exit) {
                viewModel.consumeSaved()
                viewModel.consumeExit()
                navController.popBackStack()
            }
        }

        ItineraryEditScreen(
            state = state,
            onClose = viewModel::requestClose,
            onSelectDate = viewModel::selectDate,
            onAddPlace = { navController.navigate(PlaceSearchRoute) },
            onSave = viewModel::save,
            onRetry = viewModel::retry,
            onReauthenticate = onSessionExpired,
            onDismissDialog = viewModel::dismissDialog,
            onConfirmDiscard = viewModel::confirmDiscard,
            onNoticeShown = viewModel::dismissNotice,
        )
    }
}
