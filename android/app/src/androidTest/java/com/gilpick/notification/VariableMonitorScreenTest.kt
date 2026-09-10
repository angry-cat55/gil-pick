package com.gilpick.notification

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.alternative.AlternativeError
import com.gilpick.ui.theme.GilpickTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T041: 감지 목록 화면 네 상태·정렬·이동값·변수 제외 문구·접근성 검증(quickstart AND 5.1~5.3, UI-008).
 *
 * 상태는 [VariableMonitorUiState]를 직접 넣는다. ViewModel 전이는 `VariableMonitorViewModelTest`가, route 배선은
 * `NotificationNavigationTest`가 본다.
 */
@RunWith(AndroidJUnit4::class)
class VariableMonitorScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var sort = DetectionSort.TIME
    private val opened = mutableListOf<String>()
    private var backs = 0
    private var retries = 0

    @Test
    fun loading은_1초_전에는_표시하지_않고_이후_대기_표시를_띄운다() {
        composeRule.mainClock.autoAdvance = false
        setScreen(VariableMonitorUiState.Loading)

        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithContentDescription("감지 결과를 불러오는 중").assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(600)
        composeRule.onNodeWithContentDescription("감지 결과를 불러오는 중").assertIsDisplayed()
    }

    @Test
    fun content는_배너_건수_장소명_요약_변수별_판정_방문_예정_시각_감지_경과를_보여준다() {
        setScreen(content())

        composeRule.onNodeWithText("방문이 어려울 수 있어요").assertIsDisplayed()
        composeRule.onNodeWithText("남은 일정 3곳에서 변수 감지").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_MONITOR_COUNT).assertTextEquals("감지 3건")

        scrollTo(MONITOR_DETECTION_1)
        composeRule.onNodeWithText("경복궁").assertIsDisplayed()
        composeRule.onNodeWithText("오늘 오후 방문이 어려울 수 있어요").assertIsDisplayed()
        composeRule.onNodeWithText("혼잡", substring = false).assertIsDisplayed()
        composeRule.onNodeWithText("비 확률 80%").assertIsDisplayed()
        composeRule.onNodeWithText("18:00 마감").assertIsDisplayed()
        composeRule.onNodeWithText("14:00 방문 예정 · 8분 전 감지").assertIsDisplayed()

        scrollTo(MONITOR_DETECTION_2)
        composeRule.onNodeWithText("곧 마감 11:45").assertIsDisplayed()
        composeRule.onNodeWithText("강수 없음 확률 10%").assertIsDisplayed()
    }

    @Test
    fun 정렬_토글은_시간순과_위험순을_오가며_카드_순서를_바꾼다() {
        setScreen(content())

        composeRule.onNodeWithTag(TAG_MONITOR_SORT).assertTextEquals("시간순")
        assertEquals(listOf(MONITOR_DETECTION_2, MONITOR_DETECTION_1, MONITOR_DETECTION_3), cardOrder())

        composeRule.onNodeWithTag(TAG_MONITOR_SORT).performClick()

        composeRule.onNodeWithTag(TAG_MONITOR_SORT).assertTextEquals("위험순")
        assertEquals(listOf(MONITOR_DETECTION_1, MONITOR_DETECTION_3, MONITOR_DETECTION_2), cardOrder())
    }

    @Test
    fun 제외된_변수는_값_대신_사유_문구만_보인다() {
        setScreen(content())

        scrollTo(MONITOR_DETECTION_3)
        composeRule.onNodeWithText("운영 시간 정보를 불러오지 못해 이 변수는 제외했습니다.").assertIsDisplayed()
        composeRule.onNodeWithText("소나기 확률 70%").assertIsDisplayed()
        // 제외된 카드에는 운영 종료 줄이 없다. 값을 지어내지 않는다.
        composeRule.onNodeWithTag(TAG_MONITOR_CARD_PREFIX + MONITOR_DETECTION_3).onChildren().filter(hasText("운영 종료")).assertCountEquals(0)
    }

    @Test
    fun 상세를_못_받은_카드는_판정_줄_없이_안내_문구만_보인다() {
        setScreen(VariableMonitorUiState.Content(listOf(monitorDetections().first().copy(variables = null))))

        composeRule.onNodeWithText("경복궁").assertIsDisplayed()
        composeRule.onNodeWithText("변수별 판정을 불러오지 못했어요.").assertIsDisplayed()
        composeRule.onAllNodesWithText("혼잡도").assertCountEquals(0)
    }

    @Test
    fun 대체_장소_보기는_그_감지의_detectionId를_넘긴다() {
        setScreen(content())

        scrollTo(MONITOR_DETECTION_3)
        composeRule.onNodeWithTag(TAG_MONITOR_OPEN_PREFIX + MONITOR_DETECTION_3).performClick()

        assertEquals(listOf(MONITOR_DETECTION_3), opened)
    }

    @Test
    fun empty는_예정대로_문구와_진행_화면으로를_보여준다() {
        setScreen(VariableMonitorUiState.Empty)

        composeRule.onNodeWithTag(TAG_MONITOR_EMPTY).assertTextEquals("모든 일정이 예정대로예요")
        composeRule.onNodeWithText("10분마다 다시 확인하고,\n변수가 생기면 바로 알려드릴게요.").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_MONITOR_TO_PROGRESS).assertIsDisplayed().performClick()

        assertEquals(1, backs)
    }

    @Test
    fun error는_F009_문구와_다시_시도하기를_보여준다() {
        setScreen(VariableMonitorUiState.Error(AlternativeError.Network, retryable = true))

        composeRule.onNodeWithText("감지 결과를 불러올 수 없어요").assertIsDisplayed()
        composeRule.onNodeWithText("연결을 확인한 뒤 다시 시도해 주세요. 기존 일정은 그대로예요.").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_MONITOR_RETRY).performClick()

        assertEquals(1, retries)
    }

    @Test
    fun 세션_만료는_다시_시도_대신_다시_로그인을_보여준다() {
        setScreen(VariableMonitorUiState.Error(AlternativeError.SessionExpired, retryable = false))

        composeRule.onNodeWithText("다시 로그인").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_MONITOR_RETRY).assertDoesNotExist()
    }

    @Test
    fun 터치_대상은_48dp_이상이다() {
        setScreen(content())

        composeRule.onNodeWithTag(TAG_MONITOR_BACK).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(TAG_MONITOR_SORT).assertHeightIsAtLeast(48.dp)
        scrollTo(MONITOR_DETECTION_2)
        composeRule.onNodeWithTag(TAG_MONITOR_OPEN_PREFIX + MONITOR_DETECTION_2).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(TAG_MONITOR_TO_PROGRESS).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun 좁은_화면_최대_글자_배율에서도_카드와_버튼이_보인다() {
        composeRule.setContent {
            GilpickTheme {
                val density = LocalDensity.current
                Box(modifier = Modifier.width(360.dp)) {
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) { Screen(content()) }
                }
            }
        }

        composeRule.onNodeWithText("방문이 어려울 수 있어요").assertIsDisplayed()
        scrollTo(MONITOR_DETECTION_3)
        composeRule.onNodeWithText("운영 시간 정보를 불러오지 못해 이 변수는 제외했습니다.").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_MONITOR_OPEN_PREFIX + MONITOR_DETECTION_3).assertIsDisplayed()
    }

    private fun content() = VariableMonitorUiState.Content(monitorDetections(), sort = sort)

    private fun scrollTo(detectionId: String) {
        composeRule.onNodeWithTag(TAG_MONITOR_LIST).performScrollToNode(hasTestTag(TAG_MONITOR_CARD_PREFIX + detectionId))
    }

    /** 현재 구성된 카드의 detectionId를 화면 순서대로. */
    private fun cardOrder(): List<String> =
        composeRule.onAllNodes(SemanticsMatcher("card") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(TAG_MONITOR_CARD_PREFIX) == true })
            .fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.TestTag].removePrefix(TAG_MONITOR_CARD_PREFIX) }

    private fun setScreen(state: VariableMonitorUiState) {
        composeRule.setContent { GilpickTheme { Screen(state) } }
    }

    @Composable
    private fun Screen(initial: VariableMonitorUiState) {
        var state by remember { mutableStateOf(initial) }
        VariableMonitorScreen(
            state = state,
            onBack = { backs++ },
            onRetry = { retries++ },
            onToggleSort = {
                val content = state as? VariableMonitorUiState.Content ?: return@VariableMonitorScreen
                state = content.copy(sort = if (content.sort == DetectionSort.TIME) DetectionSort.RISK else DetectionSort.TIME)
            },
            onOpenDetection = { opened += it },
            onReauthenticate = {},
            now = NOW,
        )
    }
}
