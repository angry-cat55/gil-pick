package com.gilpick.trip

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.itinerary.DayItineraryDto
import com.gilpick.itinerary.ItemStatus
import com.gilpick.itinerary.ItineraryError
import com.gilpick.itinerary.ItineraryItemDto
import com.gilpick.itinerary.ItineraryPlaceDto
import com.gilpick.itinerary.RouteStatus
import com.gilpick.itinerary.StaySource
import com.gilpick.itinerary.TransportMode
import com.gilpick.route.RouteDto
import com.gilpick.route.RouteError
import com.gilpick.route.RouteFailureDto
import com.gilpick.route.RouteMarkerDto
import com.gilpick.place.PlaceCategory
import com.gilpick.ui.theme.GilpickTheme
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
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

    // --- 5. 일정 영역: US3 Acceptance Scenario 1·2·4·5 (T025) ---

    @Test
    fun 일정_content는_날짜와_장소_수와_순서와_체류시간과_이동수단을_보여준다() {
        setDetail(
            detailWith(
                ItineraryOverviewPhase.Content(
                    listOf(
                        day(
                            "2026-09-01",
                            dayNumber = 1,
                            items = listOf(
                                item("경복궁", 1, stayMinutes = 90, toNext = TransportMode.TRANSIT),
                                item("북촌한옥마을", 2, stayMinutes = 60),
                            ),
                        ),
                    ),
                ),
            ),
        )

        // font scale 2.0에서는 한 화면에 다 들어오지 않으므로 스크롤해서 확인한다.
        composeRule.onNodeWithText("9월 1일").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("경복궁").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("북촌한옥마을").performScrollTo().assertIsDisplayed()
        // 순서 번호와 체류 시간, 구간 이동 수단이 함께 보인다(FR-014). `1`은 일차 배지와
        // 첫 장소의 순서 번호가 함께 쓰므로 두 번째 순서 번호로 확인한다.
        composeRule.onNodeWithText("2").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("1시간 30분").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("1시간").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("대중교통").performScrollTo().assertIsDisplayed()
        // 날짜 헤더의 `2곳`과 통계의 `총 방문지 2곳`이 같은 문구를 쓴다.
        assertEquals(2, composeRule.onAllNodesWithText("2곳").fetchSemanticsNodes().size)
    }

    @Test
    fun 일정이_없는_날짜는_비어_있음이_구분되어_보인다() {
        setDetail(
            detailWith(
                ItineraryOverviewPhase.Content(
                    listOf(
                        day("2026-09-01", dayNumber = 1, items = listOf(item("경복궁", 1))),
                        day("2026-09-02", dayNumber = 2),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("9월 2일").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("0곳").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("아직 담은 장소가 없어요").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun 일정_loading은_1초를_넘길_때만_대기_표시를_띄운다() {
        composeRule.mainClock.autoAdvance = false
        setDetail(detailWith(ItineraryOverviewPhase.Loading))

        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithContentDescription("일정을 불러오는 중").assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(700)
        composeRule.onNodeWithContentDescription("일정을 불러오는 중").assertIsDisplayed()
    }

    @Test
    fun 일정만_실패하면_여행_정보는_남고_일정_영역만_다시_시도를_보여준다() {
        setDetail(
            detailWith(
                ItineraryOverviewPhase.Failed(ItineraryError.Network),
                trip = sample("t1", name = "북촌·인사동 탐방"),
            ),
        )

        // 여행 정보는 그대로다(US3 Acceptance Scenario 4).
        composeRule.onNodeWithText("북촌·인사동 탐방").assertIsDisplayed()
        composeRule.onNodeWithText("연결을 확인한 뒤 다시 시도해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithText("다시 시도").assertIsDisplayed()
    }

    @Test
    fun 일정_다시_시도를_누르면_일정만_다시_조회한다() {
        var retried = 0
        setDetail(
            detailWith(ItineraryOverviewPhase.Failed(ItineraryError.Network)),
            onRetryItinerary = { retried++ },
        )

        composeRule.onNodeWithText("다시 시도").performClick()

        assertEquals(1, retried)
    }

    @Test
    fun 일정_편집을_누르면_편집_진입_콜백이_호출된다() {
        var edited = 0
        setDetail(
            detailWith(ItineraryOverviewPhase.Content(listOf(day("2026-09-01", dayNumber = 1)))),
            onEditItinerary = { edited++ },
        )

        composeRule.onNodeWithText("일정 편집").performClick()

        assertEquals(1, edited)
    }

    @Test
    fun 날짜_헤더의_추가를_누르면_그_날짜로_장소_추가_콜백이_호출된다() {
        val dates = mutableListOf<String>()
        setDetail(
            detailWith(ItineraryOverviewPhase.Content(listOf(day("2026-09-02", dayNumber = 2)))),
            onAddPlace = { dates += it },
        )

        composeRule.onNodeWithText("추가").performClick()

        // 계약이 쓰는 `yyyy-MM-dd`를 그대로 넘긴다. 화면 표기(`9월 2일`)와 다르다.
        assertEquals(listOf("2026-09-02"), dates)
    }

    @Test
    fun 장소_행을_누르면_그_장소로_상세_콜백이_호출된다() {
        val placeIds = mutableListOf<String>()
        setDetail(
            detailWith(
                ItineraryOverviewPhase.Content(
                    listOf(day("2026-09-01", dayNumber = 1, items = listOf(item("경복궁", 1)))),
                ),
            ),
            onSelectPlace = { placeIds += it },
        )

        composeRule.onNodeWithText("경복궁").performClick()

        assertEquals(listOf("place-1"), placeIds)
    }

    @Test
    fun F005와_F006_범위인_값은_비활성이거나_정보_없음으로_둔다() {
        // spec UI-006: 총 이동 시간과 `오늘 여행 시작`은 F004에서 값이 없다.
        setDetail(detailWith(ItineraryOverviewPhase.Content(emptyList())))

        composeRule.onNodeWithText("오늘 여행 시작").assertIsNotEnabled()
        composeRule.onNodeWithText("여행 진행 기능은 준비 중이에요").assertIsDisplayed()
        composeRule.onNodeWithText("총 이동").assertIsDisplayed()
        composeRule.onNodeWithText("정보 없음").assertIsDisplayed()
    }

    // --- F005 T030: 경로 실패 재시도(UI-002a, FR-010·019) ---

    @Test
    fun 경로_실패_날짜는_일정을_유지한_채_원인과_다시_시도를_보이고_누르면_그_날짜로_재시도한다() {
        val retried = mutableListOf<String>()
        val days = listOf(day("2026-09-01", 1, listOf(item("경복궁", 1, toNext = TransportMode.WALK), item("북촌", 2))).copy(routeStatus = RouteStatus.FAILED))
        setDetail(
            detailWith(ItineraryOverviewPhase.Content(days)),
            routes = mapOf("2026-09-01" to DayRoutePhase.Failed(RouteFailureDto("ROUTE_PROVIDER_TIMEOUT", "timeout", true))),
            onRetryRoute = { retried += it },
        )

        composeRule.onNodeWithText("경복궁").assertIsDisplayed()
        composeRule.onNodeWithText("북촌").assertIsDisplayed()
        composeRule.onNodeWithText("경로 서비스 응답이 늦어", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("9월 1일 경로 다시 시도").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(listOf("2026-09-01"), retried) }
        // 정상 경로에는 재계산 행동이 없다.
        composeRule.onNodeWithText("경로 보기").assertDoesNotExist()
    }

    @Test
    fun 경로_계산_중에는_일정을_유지한_채_계산_중만_보이고_다시_시도가_없다() {
        val days = listOf(day("2026-09-01", 1, listOf(item("경복궁", 1, toNext = TransportMode.WALK), item("북촌", 2))).copy(routeStatus = RouteStatus.FAILED))
        setDetail(detailWith(ItineraryOverviewPhase.Content(days)), routes = mapOf("2026-09-01" to DayRoutePhase.Calculating))

        composeRule.onNodeWithText("경복궁").assertIsDisplayed()
        composeRule.onNodeWithText("경로 계산 중").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("9월 1일 경로 다시 시도").assertDoesNotExist()
    }

    @Test
    fun 재시도_요청이_실패하면_원인을_유지한_채_요청_실패를_함께_알린다() {
        val days = listOf(day("2026-09-01", 1, listOf(item("경복궁", 1))).copy(routeStatus = RouteStatus.FAILED))
        setDetail(
            detailWith(ItineraryOverviewPhase.Content(days)),
            routes = mapOf("2026-09-01" to DayRoutePhase.Failed(RouteFailureDto("ROUTE_NOT_FOUND", "x", false), requestError = RouteError.Network)),
        )

        composeRule.onNodeWithText("이동 경로를 찾지 못했어요", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("연결을 확인한 뒤 다시 시도해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("9월 1일 경로 다시 시도").assertIsDisplayed()
    }

    @Test
    fun 정상_READY_날짜에는_다시_시도가_없고_경로_보기만_있다() {
        val days = listOf(day("2026-09-01", 1, listOf(item("경복궁", 1))).copy(routeStatus = RouteStatus.READY))
        val route = RouteDto(
            routeId = "r", scheduleVersion = 1, totalDurationSeconds = 0, totalDistanceMeters = 0,
            markers = listOf(RouteMarkerDto("item-1", 1, "경복궁", 37.5, 127.0)), segments = emptyList(),
            providerAttributions = emptyList(), calculatedAt = "2026-09-07T00:00:00Z",
        )
        setDetail(detailWith(ItineraryOverviewPhase.Content(days)), routes = mapOf("2026-09-01" to DayRoutePhase.Ready(route)))

        composeRule.onNodeWithText("경로 보기").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("9월 1일 경로 다시 시도").assertDoesNotExist()
        composeRule.onNodeWithText("경로 계산 중").assertDoesNotExist()
    }

    /** 여행은 받았고 일정 영역만 [itinerary] 상태인 상세 화면 상태를 만든다. */
    private fun detailWith(
        itinerary: ItineraryOverviewPhase,
        trip: TripDto = sample("t1"),
    ): TripDetailUiState = TripDetailUiState(
        phase = TripDetailPhase.Content(trip),
        itinerary = itinerary,
    )

    /** 하루치 일정. 저장된 적 없는 날짜는 항목이 비고 version이 0이다. */
    private fun day(
        date: String,
        dayNumber: Int,
        items: List<ItineraryItemDto> = emptyList(),
    ): DayItineraryDto = DayItineraryDto(
        date = date,
        dayNumber = dayNumber,
        version = if (items.isEmpty()) 0 else 1,
        routeStatus = RouteStatus.NOT_CALCULATED,
        items = items,
    )

    /** 일정 항목 하나. 마지막 항목의 이동 수단은 계약상 `null`이다. */
    private fun item(
        name: String,
        sequence: Int,
        stayMinutes: Int = 90,
        toNext: TransportMode? = null,
    ): ItineraryItemDto = ItineraryItemDto(
        itemId = "item-$sequence",
        place = ItineraryPlaceDto(
            placeId = "place-$sequence",
            name = name,
            category = PlaceCategory.HISTORY_CULTURE,
            address = null,
            imageUrl = null,
        ),
        sequence = sequence,
        plannedStayMinutes = stayMinutes,
        staySource = StaySource.RECOMMENDED,
        transportModeToNext = toNext,
        status = ItemStatus.PLANNED,
    )

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
                            onRetryItinerary = {},
                            onEditItinerary = {},
                            onAddPlace = {},
                            onSelectPlace = {},
                        )
                    }
                }
            }
        }
    }

    /** 상세 화면 하나만 띄운다. 일정 영역 콜백은 호출 여부를 확인할 수 있게 받는다. */
    private fun setDetail(
        state: TripDetailUiState,
        onRetryItinerary: () -> Unit = {},
        onEditItinerary: () -> Unit = {},
        onAddPlace: (String) -> Unit = {},
        onSelectPlace: (String) -> Unit = {},
        routes: Map<String, DayRoutePhase> = emptyMap(),
        onRetryRoute: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            GilpickTheme {
                TripDetailScreen(
                    state = state,
                    onBack = {},
                    onRetry = {},
                    onEdit = {},
                    onDelete = {},
                    onDeleteErrorShown = {},
                    onRetryItinerary = onRetryItinerary,
                    onEditItinerary = onEditItinerary,
                    onAddPlace = onAddPlace,
                    onSelectPlace = onSelectPlace,
                    routes = routes,
                    onRetryRoute = onRetryRoute,
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
