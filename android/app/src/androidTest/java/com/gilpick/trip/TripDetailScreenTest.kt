package com.gilpick.trip

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.ui.theme.GilpickTheme
import kotlinx.serialization.Serializable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T028·T029: 여행 상세 화면의 표시 단계와 목록·상세 사이 이동 검증.
 *
 * `docs/design/ui-guidelines.md` 9절의 화면 상태와 10절 접근성 최저선 가운데 화면
 * 코드가 책임지는 부분, 그리고 `spec.md` US3 Acceptance Scenario 1~3의 화면 표현을
 * 확인한다.
 *
 * 실제 production composable(`TripListScreen`, `TripDetailScreen`)과 navigation-compose를
 * 그대로 쓰되, route 타입은 `MainActivity`의 것이 private이라 같은 구조로 여기에 다시
 * 선언한다. 따라서 이 test는 화면과 navigation 동작을 검증하고 `MainActivity`의 배선
 * 자체는 검증하지 않는다. 실제 배선과 백엔드를 포함한 종단간 확인은 #108이 다룬다.
 */
@RunWith(AndroidJUnit4::class)
class TripDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Serializable
    private object ListRoute

    @Serializable
    private data class DetailRoute(val tripId: String)

    @Serializable
    private data class EditRoute(val tripId: String)

    // --- 1. 목록에서 카드 탭 → 상세 진입 ---

    @Test
    fun 목록에서_카드를_누르면_해당_여행의_상세로_이동한다() {
        setGraph(trips = (1..3).map { sample("t$it", name = "여행 $it") })

        composeRule.onNodeWithText("여행 2").performClick()

        composeRule.onNodeWithText("여행 상세").assertIsDisplayed()
        // 누른 카드의 tripId가 상세로 전달됐는지 확인한다.
        composeRule.onNodeWithText("t2 여행").assertIsDisplayed()
    }

    // --- 2. 상세 화면 상태 ---

    @Test
    fun 상세_content는_이름과_기간과_상태뱃지를_보여준다() {
        setDetail(
            TripDetailUiState(
                TripDetailPhase.Content(
                    sample(
                        "t1",
                        name = "북촌·인사동 탐방",
                        startDate = "2026-09-02",
                        endDate = "2026-09-06",
                        status = TripStatus.IN_PROGRESS,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("북촌·인사동 탐방").assertIsDisplayed()
        composeRule.onNodeWithText("2026-09-02 ~ 2026-09-06").assertIsDisplayed()
        composeRule.onNodeWithText("여행 중").assertIsDisplayed()
    }

    @Test
    fun 상세_loading은_1초를_넘길_때만_대기_표시를_띄운다() {
        // 9절: 금방 끝나는 조회에서 표시가 깜빡이면 오히려 느리게 느껴진다.
        composeRule.mainClock.autoAdvance = false
        setDetail(TripDetailUiState(TripDetailPhase.Loading))

        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithContentDescription("여행 정보를 불러오는 중").assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(700)
        composeRule.onNodeWithContentDescription("여행 정보를 불러오는 중").assertIsDisplayed()
    }

    @Test
    fun 상세_403은_권한_안내와_목록으로_돌아가기를_보여준다() {
        setDetail(TripDetailUiState(TripDetailPhase.Failed(TripDetailError.FORBIDDEN)))

        composeRule.onNodeWithText("이 여행을 볼 권한이 없습니다.").assertIsDisplayed()
        composeRule.onNodeWithText("목록으로 돌아가기").assertIsDisplayed()
    }

    @Test
    fun 상세_404는_삭제됨_안내와_목록으로_돌아가기를_보여준다() {
        setDetail(TripDetailUiState(TripDetailPhase.Failed(TripDetailError.NOT_FOUND)))

        composeRule.onNodeWithText("이미 삭제되었거나 없는 여행입니다.").assertIsDisplayed()
        composeRule.onNodeWithText("목록으로 돌아가기").assertIsDisplayed()
    }

    @Test
    fun 상세_통신실패는_재시도_버튼을_보여준다() {
        setDetail(TripDetailUiState(TripDetailPhase.Failed(TripDetailError.NETWORK)))

        composeRule.onNodeWithText("연결을 확인한 뒤 다시 시도해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithText("다시 시도").assertIsDisplayed()
    }

    // --- 3. 403·404의 목록으로 돌아가기 동작 ---

    @Test
    fun 목록으로_돌아가기를_누르면_목록으로_복귀한다() {
        setGraph(
            trips = listOf(sample("t1", name = "여행 1")),
            detailState = TripDetailUiState(TripDetailPhase.Failed(TripDetailError.NOT_FOUND)),
        )
        composeRule.onNodeWithText("여행 1").performClick()
        composeRule.onNodeWithText("목록으로 돌아가기").assertIsDisplayed()

        composeRule.onNodeWithText("목록으로 돌아가기").performClick()

        composeRule.onNodeWithText("내 여행").assertIsDisplayed()
    }

    // --- 4. Back 버튼으로 복귀했을 때 목록 상태 유지 ---

    @Test
    fun back으로_돌아오면_목록_스크롤_위치가_유지된다() {
        setGraph(trips = (1..30).map { sample("t$it", name = "여행 $it") })
        composeRule.onNode(hasScrollAction()).performScrollToIndex(25)
        composeRule.onNodeWithText("여행 26").assertIsDisplayed()

        composeRule.onNodeWithText("여행 26").performClick()
        composeRule.onNodeWithText("여행 상세").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("뒤로 가기").performClick()

        // 목록 맨 위로 튀지 않고 보고 있던 자리가 그대로 있어야 한다.
        composeRule.onNodeWithText("여행 26").assertIsDisplayed()
    }

    @Test
    fun back으로_돌아오면_검색어와_필터가_유지된다() {
        setGraph(trips = listOf(sample("t1", name = "여행 1")), query = "서울", filter = TripStatus.UPCOMING)
        composeRule.onNodeWithText("여행 1").performClick()
        composeRule.onNodeWithContentDescription("뒤로 가기").performClick()

        // 조건은 view model이 들고 있으므로 화면을 오가도 남아 있어야 한다.
        composeRule.onNodeWithText("서울").assertIsDisplayed()
    }

    /** 목록과 상세를 실제 route로 연결한 graph를 띄운다. */
    private fun setGraph(
        trips: List<TripDto>,
        detailState: TripDetailUiState? = null,
        query: String = "",
        filter: TripStatus? = null,
    ) {
        composeRule.setContent {
            GilpickTheme {
                val navController = rememberNavController()
                var listState by remember {
                    mutableStateOf(
                        TripListUiState(
                            trips = trips,
                            phase = TripListPhase.Content,
                            query = query,
                            statusFilter = filter,
                        ),
                    )
                }

                NavHost(navController = navController, startDestination = ListRoute) {
                    composable<ListRoute> {
                        TripListScreen(
                            state = listState,
                            onQueryChange = { listState = listState.copy(query = it) },
                            onStatusFilterChange = { listState = listState.copy(statusFilter = it) },
                            onRetry = {},
                            onLoadMore = {},
                            onCreateTrip = {},
                            onTripClick = { navController.navigate(DetailRoute(it)) },
                        )
                    }
                    composable<EditRoute> { entry ->
                        Text("수정 화면 " + entry.toRoute<EditRoute>().tripId)
                    }
                    composable<DetailRoute> { entry ->
                        val tripId = entry.toRoute<DetailRoute>().tripId
                        TripDetailScreen(
                            state = detailState
                                ?: TripDetailUiState(
                                    TripDetailPhase.Content(sample(tripId, name = "$tripId 여행")),
                                ),
                            onBack = { navController.popBackStack() },
                            onRetry = {},
                            onEdit = { navController.navigate(EditRoute(tripId)) },
                            onDelete = {},
                            onDeleteErrorShown = {},
                        )
                    }
                }
            }
        }
    }

    /** 상세 화면 하나만 띄운다. */
    private fun setDetail(state: TripDetailUiState) {
        composeRule.setContent {
            GilpickTheme {
                TripDetailScreen(
                    state = state,
                    onBack = {},
                    onRetry = {},
                    onEdit = {},
                    onDelete = {},
                    onDeleteErrorShown = {},
                )
            }
        }
    }

    /** 확인용 여행 하나. unit test의 helper는 다른 source set이라 여기서 다시 만든다. */
    private fun sample(
        id: String,
        name: String = "서울 여행",
        startDate: String = "2026-09-01",
        endDate: String = "2026-09-03",
        status: TripStatus = TripStatus.UPCOMING,
    ): TripDto = TripDto(
        tripId = id,
        name = name,
        startDate = startDate,
        endDate = endDate,
        status = status,
        dayCount = 3,
        version = 1,
    )

}
