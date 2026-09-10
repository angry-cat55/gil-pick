package com.gilpick.replacement

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.auth.SuccessEnvelope
import com.gilpick.route.readyRoute
import com.gilpick.ui.theme.GilpickTheme
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T020: 승인 중 잠금과 실패 5원인의 서로 다른 안내·다음 행동 검증(quickstart FE 4, spec UI-005·SC-007·UI-008).
 *
 * 상태는 ViewModel 없이 [PreviewUiState.Content]의 `approving`·`approveFailure`를 직접 넣는다.
 * 실제 승인 요청과 상태 전이는 `PreviewViewModelTest`가 본다.
 */
@RunWith(AndroidJUnit4::class)
class RoutePreviewApproveTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 승인_중에는_두_행동이_잠기고_진행이_보인다() {
        // UI-005. 같은 승인을 두 번 보내지 않도록 행동을 잠근다.
        var approves = 0
        var others = 0
        setScreen(content(approving = true), onApprove = { approves++ }, onOtherCandidates = { others++ })

        composeRule.onNodeWithText("변경하는 중").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_APPROVE).performScrollTo().assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(TAG_OTHER_CANDIDATES).performScrollTo().assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(TAG_BACK).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(0, approves)
            assertEquals(0, others)
        }
    }

    @Test
    fun 평소에는_변경_승인이_보이고_눌리면_승인을_부른다() {
        var approves = 0
        setScreen(content(), onApprove = { approves++ })

        composeRule.onNodeWithTag(TAG_APPROVE).performScrollTo()
            .assertIsEnabled()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithText("변경 승인").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, approves) }
    }

    @Test
    fun 일정이_바뀌면_다시_만들기를_안내한다() {
        var recreates = 0
        setScreen(content(failure = ReplacementError.ScheduleChanged), onRetry = { recreates++ })

        assertFailure("이 날짜 일정이 바뀌어서 비교를 다시 만들어야 해요", "다시 만들기")
        composeRule.onNodeWithTag(TAG_APPROVE).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, recreates) }
    }

    @Test
    fun 미리보기가_만료되면_다시_만들기를_안내한다() {
        var recreates = 0
        setScreen(content(failure = ReplacementError.PreviewExpired), onRetry = { recreates++ })

        assertFailure("비교한 지 오래돼서 다시 만들어야 해요", "다시 만들기")
        composeRule.onNodeWithTag(TAG_APPROVE).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, recreates) }
    }

    @Test
    fun 이미_방문한_장소는_후보_목록으로를_안내한다() {
        var others = 0
        setScreen(content(failure = ReplacementError.AlreadyVisited), onOtherCandidates = { others++ })

        assertFailure("이미 그 장소에 도착해서 바꿀 수 없어요", "후보 목록으로")
        composeRule.onNodeWithTag(TAG_APPROVE).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, others) }
    }

    @Test
    fun 대체_장소를_방문할_수_없으면_장소명과_함께_후보_목록으로를_안내한다() {
        var others = 0
        setScreen(content(failure = ReplacementError.AlternativeUnavailable), onOtherCandidates = { others++ })

        // 어떤 장소가 막혔는지 알아야 다음 후보를 고를 수 있다(Figma 승인 실패 상태).
        assertFailure("창덕궁을(를) 지금은 방문할 수 없어요", "후보 목록으로")
        composeRule.onNodeWithTag(TAG_APPROVE).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, others) }
    }

    @Test
    fun 통신_실패는_다시_시도를_안내한다() {
        var approves = 0
        setScreen(content(failure = ReplacementError.Network), onApprove = { approves++ })

        assertFailure("연결이 불안정해요", "다시 시도")
        composeRule.onNodeWithTag(TAG_APPROVE).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, approves) }
    }

    @Test
    fun 실패_다섯_원인은_서로_다른_안내와_다음_행동을_보인다() {
        // SC-007. 한 화면에서 원인만 바꿔 가며 실제로 그려 본다. 문구나 행동이 겹치면 여기서 드러난다.
        val cases = listOf(
            ReplacementError.ScheduleChanged to ("이 날짜 일정이 바뀌어서 비교를 다시 만들어야 해요" to "다시 만들기"),
            ReplacementError.PreviewExpired to ("비교한 지 오래돼서 다시 만들어야 해요" to "다시 만들기"),
            ReplacementError.AlreadyVisited to ("이미 그 장소에 도착해서 바꿀 수 없어요" to "후보 목록으로"),
            ReplacementError.AlternativeUnavailable to ("창덕궁을(를) 지금은 방문할 수 없어요" to "후보 목록으로"),
            ReplacementError.Network to ("연결이 불안정해요" to "다시 시도"),
        )
        val failure = mutableStateOf<ReplacementError?>(null)
        composeRule.setContent {
            GilpickTheme {
                RoutePreviewScreen(
                    state = content(failure = failure.value),
                    onBack = {},
                    onRetry = {},
                    onApprove = {},
                    onOtherCandidates = {},
                    onReauthenticate = {},
                    map = { _, modifier -> Box(modifier.fillMaxSize().testTag(TAG_MAP_SLOT)) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        cases.forEach { (error, expected) ->
            val (message, actionLabel) = expected
            composeRule.runOnIdle { failure.value = error }
            composeRule.onNodeWithText(message).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText(actionLabel).assertIsDisplayed()
        }

        // 안내 문구는 다섯 가지 모두 다르고, 다음 행동은 세 종류로 갈린다(quickstart FE 4 표).
        assertEquals(5, cases.map { it.second.first }.toSet().size)
        assertEquals(3, cases.map { it.second.second }.toSet().size)
    }

    @Test
    fun 실패해도_기존_일정이_그대로임을_알리고_다른_후보_보기는_남는다() {
        // FR-007·UI-004와 같은 이유다. 사용자가 일정이 망가졌다고 오해하지 않아야 한다.
        setScreen(content(failure = ReplacementError.ScheduleChanged))

        composeRule.onNodeWithText("기존 일정은 그대로예요").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_OTHER_CANDIDATES).performScrollTo()
            .assertIsEnabled()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun 실패_안내가_없으면_실패_블록을_보이지_않는다() {
        setScreen(content())

        composeRule.onNodeWithTag(TAG_APPROVE_FAILURE).assertDoesNotExist()
        composeRule.onNodeWithText("변경하지 못했어요").assertDoesNotExist()
    }

    /** 실패 블록의 문구와 다음 행동 버튼을 함께 확인한다. 다음 행동은 48dp 이상이어야 한다(UI-008). */
    private fun assertFailure(message: String, actionLabel: String) {
        composeRule.onNodeWithTag(TAG_APPROVE_FAILURE).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("변경하지 못했어요").assertIsDisplayed()
        composeRule.onNodeWithText(message).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_APPROVE).performScrollTo()
            .assertIsEnabled()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText(actionLabel).assertIsDisplayed()
    }

    private fun content(
        approving: Boolean = false,
        failure: ReplacementError? = null,
    ) = PreviewUiState.Content(
        preview = previewJson.decodeFromString<SuccessEnvelope<RoutePreviewDto>>(routePreviewJson()).data,
        originalRoute = readyRoute(),
        approving = approving,
        approveFailure = failure,
    )

    private fun setScreen(
        state: PreviewUiState,
        onBack: () -> Unit = {},
        onRetry: () -> Unit = {},
        onApprove: () -> Unit = {},
        onOtherCandidates: () -> Unit = {},
        onReauthenticate: () -> Unit = {},
    ) {
        composeRule.setContent {
            GilpickTheme {
                RoutePreviewScreen(
                    state = state,
                    onBack = onBack,
                    onRetry = onRetry,
                    onApprove = onApprove,
                    onOtherCandidates = onOtherCandidates,
                    onReauthenticate = onReauthenticate,
                    map = { _, modifier -> Box(modifier.fillMaxSize().testTag(TAG_MAP_SLOT)) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    private companion object {
        val previewJson = Json { ignoreUnknownKeys = true }
    }
}
