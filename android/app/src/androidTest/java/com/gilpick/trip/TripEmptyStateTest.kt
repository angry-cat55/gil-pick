package com.gilpick.trip

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.ui.theme.GilpickTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #152: 빈 상태 화면의 아이콘과 pen 기준 문구 검증.
 *
 * pen `23. 빈 상태 – 여행 0개`와 `24. 빈 상태 – 검색 0건`의 `EmptyState` 구조를
 * 화면이 실제로 그리는지 본다. 아이콘 모양과 색은 test로 검증하지 않는다. 그쪽은
 * screenshot과 픽셀 대조로 확인하며 결과를 PR에 남긴다.
 *
 * 파일을 `TripListScreenTest`와 나눈 이유는 두 작업이 서로 다른 브랜치에서 같은
 * 화면을 건드리기 때문이다. 한 파일에 모으면 병합할 때 충돌한다.
 */
@RunWith(AndroidJUnit4::class)
class TripEmptyStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- 1. 여행 0개 ---

    @Test
    fun 여행이_하나도_없으면_pen_23의_문구를_보여준다() {
        setScreen(TripListUiState(phase = TripListPhase.Empty))

        composeRule.onNodeWithText("아직 만든 여행이 없어요").assertIsDisplayed()
        composeRule
            .onNodeWithText("여행을 만들면 날짜별 일정과 이동 경로를 한 번에 정리할 수 있어요.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("첫 여행 만들기").assertIsDisplayed()
    }

    @Test
    fun 여행이_하나도_없으면_헤더의_여행_만들기와_다른_문구를_쓴다() {
        setScreen(TripListUiState(phase = TripListPhase.Empty))

        // 헤더 버튼은 그대로 "여행 만들기"다. 빈 상태 버튼만 "첫 여행 만들기"로 바뀐다.
        composeRule.onNodeWithText("여행 만들기").assertIsDisplayed()
        composeRule.onNodeWithText("첫 여행 만들기").assertIsDisplayed()
    }

    // --- 2. 조건에 맞는 결과 0건 ---

    @Test
    fun 조건에_맞는_여행이_없으면_필터_초기화_문구를_보여준다() {
        setScreen(filteredEmpty())

        composeRule.onNodeWithText("조건에 맞는 여행이 없어요").assertIsDisplayed()
        composeRule
            .onNodeWithText("검색어를 다시 확인하거나 상태 필터를 바꿔 보세요.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("조건 초기화").assertIsDisplayed()
    }

    @Test
    fun 두_빈_상태는_서로_다른_문구를_쓴다() {
        setScreen(filteredEmpty())

        composeRule.onNodeWithText("아직 만든 여행이 없어요").assertDoesNotExist()
        composeRule.onNodeWithText("첫 여행 만들기").assertDoesNotExist()
    }

    // --- 3. 아이콘은 장식 ---

    @Test
    fun 빈_상태_아이콘은_TalkBack이_읽지_않는다() {
        setScreen(TripListUiState(phase = TripListPhase.Empty))

        // `contentDescription = null`이면 아이콘은 semantics 노드를 남기지 않는다.
        // 빈 상태에서는 화면 어디에도 contentDescription이 없어야 한다. loading·목록
        // 쪽 clearAndSetSemantics는 이 단계에서 그려지지 않는다.
        assertEquals(emptyList<String>(), contentDescriptions())
    }

    @Test
    fun 검색_결과_없음_아이콘도_TalkBack이_읽지_않는다() {
        setScreen(filteredEmpty())

        assertEquals(emptyList<String>(), contentDescriptions())
    }

    /** 검색어와 상태 필터가 모두 걸린 빈 상태. */
    private fun filteredEmpty() = TripListUiState(
        query = "고궁",
        statusFilter = TripStatus.COMPLETED,
        phase = TripListPhase.Empty,
    )

    /** 화면 전체 semantics 트리에 붙은 `contentDescription`을 모은다. */
    private fun contentDescriptions(): List<String> {
        val found = mutableListOf<String>()

        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.let(found::addAll)
            node.children.forEach(::walk)
        }

        // 병합된 트리는 자식 semantics를 감추므로 원본 트리를 본다.
        walk(composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return found
    }

    private fun setScreen(state: TripListUiState) {
        composeRule.setContent {
            GilpickTheme {
                TripListScreen(
                    state = state,
                    onQueryChange = {},
                    onStatusFilterChange = {},
                    onRetry = {},
                    onLoadMore = {},
                    onCreateTrip = {},
                    onTripClick = {},
                )
            }
        }
    }
}
