package com.gilpick.place

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.ui.theme.GilpickTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T013·T017: 검색 화면의 표시 단계·상호작용·접근성 검증.
 *
 * `spec.md` UI-001(입력·칩·요약), UI-002(네 상태), UI-004(행 선택·`+`), UI-005(이미지 대체),
 * UI-007(48dp)이 대상이다. 상태 전이 자체는 `PlaceSearchViewModelTest`가 다루므로 여기서는
 * 상태를 직접 넣고 화면이 어떻게 보이고 무엇을 호출하는지만 본다.
 */
@RunWith(AndroidJUnit4::class)
class PlaceSearchScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 검색_전에는_안내를_보여주고_입력과_칩이_있다() {
        setScreen(PlaceSearchUiState())

        composeRule.onNodeWithText("장소 추가").assertIsDisplayed()
        composeRule.onNodeWithText("어떤 장소를 찾고 계세요?").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("장소 이름 검색").assertIsDisplayed()
        composeRule.onNodeWithText("전체").assertIsSelected()
        composeRule.onNodeWithText("문화·역사").assertIsDisplayed()
    }

    @Test
    fun 키보드_검색_동작이_검색을_실행하고_입력만으로는_실행하지_않는다() {
        var searches = 0
        var query = ""
        setScreen(PlaceSearchUiState(), onQueryChange = { query = it }, onSearch = { searches++ })

        composeRule.onNodeWithContentDescription("장소 이름 검색").performTextInput("경복궁")
        composeRule.runOnIdle { assertEquals(0, searches) }

        composeRule.onNodeWithContentDescription("장소 이름 검색").performImeAction()
        composeRule.runOnIdle {
            assertEquals("경복궁", query)
            assertEquals(1, searches)
        }
    }

    @Test
    fun 칩을_누르면_category만_바뀌고_지우기는_입력을_비운다() {
        var category: PlaceCategory? = PlaceCategory.OTHER
        var cleared = 0
        setScreen(PlaceSearchUiState(query = "경복궁"), onCategoryChange = { category = it }, onClearQuery = { cleared++ })

        composeRule.onNodeWithText("카페").performClick()
        composeRule.runOnIdle { assertEquals(PlaceCategory.CAFE, category) }
        composeRule.onNodeWithText("전체").performClick()
        composeRule.runOnIdle { assertEquals(null, category) }

        composeRule.onNodeWithContentDescription("검색어 지우기").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, cleared) }
    }

    @Test
    fun loading은_1초_전에는_표시하지_않고_이후_대기_표시를_띄운다() {
        composeRule.mainClock.autoAdvance = false
        setScreen(PlaceSearchUiState(phase = PlaceSearchPhase.Loading))

        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithContentDescription("검색 결과를 불러오는 중").assertIsNotDisplayed()

        composeRule.mainClock.advanceTimeBy(700)
        composeRule.onNodeWithContentDescription("검색 결과를 불러오는 중").assertIsDisplayed()
    }

    @Test
    fun content는_요약과_행을_보여주고_행은_상세로_플러스는_시트로_간다() {
        var opened: String? = null
        setScreen(
            content(
                testPlace("tourapi:1", name = "경복궁", rating = 4.8, businessStatus = PlaceBusinessStatus.OPERATIONAL, imageUrl = "https://example.test/a.jpg"),
                testPlace("google:x", name = "구글 카페", category = PlaceCategory.CAFE),
            ),
            onPlaceClick = { opened = it },
        )

        composeRule.onNodeWithText("검색 결과 2곳").assertIsDisplayed()
        // 칩 하나와 첫 행의 category 한 번.
        composeRule.onAllNodes(hasText("문화·역사")).assertCountEquals(2)
        composeRule.onNodeWithText("4.8").assertIsDisplayed()
        composeRule.onNodeWithText("운영 중").assertIsDisplayed()
        composeRule.onAllNodes(hasText("TOUR_API")).assertCountEquals(0)

        composeRule.onNodeWithText("구글 카페").performClick()
        composeRule.runOnIdle { assertEquals("google:x", opened) }

        composeRule.onNodeWithContentDescription("경복궁 일정에 추가")
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithText("이동 수단 선택").assertIsDisplayed()
        composeRule.onNodeWithText("경복궁까지 어떻게 이동하시겠어요?").assertIsDisplayed()
    }

    @Test
    fun 시트에서_확정하면_장소와_선택값을_넘긴다() {
        var received: Pair<PlaceDto, AddToScheduleRequest>? = null
        setScreen(content(testPlace("tourapi:1", name = "경복궁")), onAddToSchedule = { place, request -> received = place to request })

        composeRule.onNodeWithContentDescription("경복궁 일정에 추가").performClick()
        composeRule.onNodeWithText("도보").performClick()
        composeRule.onNodeWithTag(ADD_TO_SCHEDULE_CONFIRM_TAG).performClick()

        composeRule.runOnIdle {
            assertEquals("tourapi:1", received?.first?.placeId)
            assertEquals(AddToScheduleRequest(PlaceTransport.WALK, 90), received?.second)
        }
    }

    @Test
    fun 이미지와_평점_영업상태가_없어도_이름과_선택_행동은_유지된다() {
        var opened: String? = null
        setScreen(content(testPlace("tourapi:1", name = "정보 적은 장소", imageUrl = null)), onPlaceClick = { opened = it })

        composeRule.onNodeWithText("정보 적은 장소").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals("tourapi:1", opened) }
        composeRule.onAllNodes(hasText("★")).assertCountEquals(0)
    }

    @Test
    fun 긴_장소명도_잘리지_않고_행동이_유지된다() {
        val longName = "아주 긴 이름을 가진 장소 이름을 가진 장소 이름을 가진 장소 이름을 가진 장소"
        setScreen(content(testPlace("tourapi:1", name = longName)))

        composeRule.onNodeWithText(longName).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("$longName 일정에 추가").assertIsDisplayed()
    }

    @Test
    fun empty는_검색어와_함께_안내하고_카테고리로_찾기를_제공한다() {
        var byCategory = 0
        setScreen(
            PlaceSearchUiState(query = "없는곳", committedQuery = "없는곳", phase = PlaceSearchPhase.Empty),
            onSearchByCategory = { byCategory++ },
        )

        composeRule.onNodeWithText("'없는곳' 검색 결과가 없어요").assertIsDisplayed()
        composeRule.onNodeWithText("카테고리로 찾기").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, byCategory) }
    }

    @Test
    fun 짧은_키워드는_2글자_이상_입력을_안내한다() {
        setScreen(PlaceSearchUiState(query = "궁", phase = PlaceSearchPhase.Invalid(InvalidReason.TOO_SHORT)))
        composeRule.onNodeWithText("검색어를 2글자 이상 입력해 주세요").assertIsDisplayed()
    }

    @Test
    fun 조건이_없으면_조건_입력을_안내한다() {
        setScreen(PlaceSearchUiState(phase = PlaceSearchPhase.Invalid(InvalidReason.NO_CONDITION)))
        composeRule.onNodeWithText("검색어를 입력하거나 카테고리를 골라 주세요").assertIsDisplayed()
    }

    @Test
    fun error는_원인을_안내하고_재시도를_제공한다() {
        var retries = 0
        setScreen(
            PlaceSearchUiState(phase = PlaceSearchPhase.Failed(PlaceError(PlaceErrorKind.TIMEOUT, retryable = true))),
            onRetry = { retries++ },
        )

        composeRule.onNodeWithText("장소 정보 제공이 지연되고 있어요. 잠시 후 다시 시도해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithText("다시 시도").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun 재시도할_수_없는_error에는_재시도_버튼이_없다() {
        setScreen(PlaceSearchUiState(phase = PlaceSearchPhase.Failed(PlaceError(PlaceErrorKind.RATE_LIMITED, retryable = false))))

        composeRule.onAllNodes(hasText("다시 시도")).assertCountEquals(0)
    }

    @Test
    fun 추가_조회_실패는_기존_결과를_남기고_다음_결과_재시도를_제공한다() {
        var retries = 0
        setScreen(content(testPlace("tourapi:1", name = "경복궁")).copy(loadMoreFailed = true), onRetryLoadMore = { retries++ })

        composeRule.onNodeWithText("경복궁").assertIsDisplayed()
        composeRule.onNodeWithText("다음 결과를 불러오지 못했어요").assertIsDisplayed()
        composeRule.onNodeWithText("다음 결과 다시 시도").performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    private fun content(vararg places: PlaceDto) = PlaceSearchUiState(
        query = "검색어",
        committedQuery = "검색어",
        results = places.toList(),
        phase = PlaceSearchPhase.Content,
    )

    private fun setScreen(
        state: PlaceSearchUiState,
        onQueryChange: (String) -> Unit = {},
        onClearQuery: () -> Unit = {},
        onCategoryChange: (PlaceCategory?) -> Unit = {},
        onSearch: () -> Unit = {},
        onRetry: () -> Unit = {},
        onRetryLoadMore: () -> Unit = {},
        onSearchByCategory: () -> Unit = {},
        onPlaceClick: (String) -> Unit = {},
        onAddToSchedule: (PlaceDto, AddToScheduleRequest) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            GilpickTheme {
                PlaceSearchScreen(
                    state = state,
                    onBack = {},
                    onQueryChange = onQueryChange,
                    onClearQuery = onClearQuery,
                    onCategoryChange = onCategoryChange,
                    onSearch = onSearch,
                    onRetry = onRetry,
                    onLoadMore = {},
                    onRetryLoadMore = onRetryLoadMore,
                    onSearchByCategory = onSearchByCategory,
                    onPlaceClick = onPlaceClick,
                    onAddToSchedule = onAddToSchedule,
                )
            }
        }
    }
}
