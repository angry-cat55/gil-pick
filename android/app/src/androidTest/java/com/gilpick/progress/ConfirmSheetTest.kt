package com.gilpick.progress

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.ui.theme.GilpickTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T012: 도착·출발 확인 시트의 표시와 행동 검증(UI-001·UI-002·UI-006·UI-007·UI-008).
 *
 * 시트 창(`ModalBottomSheet`)이 아니라 내용 composable을 그대로 렌더한다. F004·F006 시트 test와
 * 같은 방식이며, 창 애니메이션 없이 내용만 확인할 수 있다.
 */
@RunWith(AndroidJUnit4::class)
class ConfirmSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- UI-001 표시 ---

    @Test
    fun 도착_시트는_장소명과_감지_근거와_남은_시간과_두_행동을_보여준다() {
        setSheet(candidate = arrivalCandidate())

        composeRule.onNodeWithText("북촌한옥마을에 도착하셨나요?").assertIsDisplayed()
        composeRule.onNodeWithText("이 근처에서 6분 머무는 중 · 오후 2:33 감지").assertIsDisplayed()
        // 남은 시간은 서버가 준 autoFinalizeAt과 기기 시각으로 표시만 한다(FR-018).
        composeRule.onNodeWithText("4분 뒤 자동으로 도착 처리돼요").assertIsDisplayed()
        composeRule.onNodeWithText("네, 도착했어요").assertIsDisplayed()
        composeRule.onNodeWithText("아직이에요").assertIsDisplayed()
    }

    @Test
    fun 출발_시트는_같은_구조에_출발_행동을_보여준다() {
        setSheet(candidate = departureCandidate())

        composeRule.onNodeWithText("북촌한옥마을에서 출발하셨나요?").assertIsDisplayed()
        composeRule.onNodeWithText("이 근처를 벗어났어요 · 오후 2:33 감지").assertIsDisplayed()
        composeRule.onNodeWithText("4분 뒤 자동으로 출발 처리돼요").assertIsDisplayed()
        composeRule.onNodeWithText("네, 출발했어요").assertIsDisplayed()
        composeRule.onNodeWithText("아직 머무는 중").assertIsDisplayed()
    }

    @Test
    fun 서버가_허용하지_않은_응답은_보이지_않는다() {
        // 앱이 종류별 규칙을 따로 갖지 않고 allowedDecisions만 따른다.
        setSheet(candidate = arrivalCandidate().copy(allowedDecisions = listOf(TransitionDecision.CONFIRM)))

        composeRule.onNodeWithText("네, 도착했어요").assertIsDisplayed()
        composeRule.onNodeWithText("아직이에요").assertDoesNotExist()
    }

    @Test
    fun 자동_확정_시각이_지났으면_0분으로_표시한다() {
        // autoFinalizeAt(14:38 KST)보다 뒤인 15:00 KST.
        setSheet(candidate = arrivalCandidate(), now = Instant.parse("2026-09-08T06:00:00Z"))

        composeRule.onNodeWithText("0분 뒤 자동으로 도착 처리돼요").assertIsDisplayed()
    }

    // --- 행동 ---

    @Test
    fun 도착했어요를_누르면_CONFIRM을_보낸다() {
        val decisions = mutableListOf<TransitionDecision>()
        setSheet(candidate = arrivalCandidate(), onDecide = { decisions += it })

        composeRule.onNodeWithText("네, 도착했어요").performClick()

        assertEquals(listOf(TransitionDecision.CONFIRM), decisions)
    }

    @Test
    fun 아직이에요를_누르면_NOT_ARRIVED를_보낸다() {
        val decisions = mutableListOf<TransitionDecision>()
        setSheet(candidate = arrivalCandidate(), onDecide = { decisions += it })

        composeRule.onNodeWithText("아직이에요").performClick()

        assertEquals(listOf(TransitionDecision.NOT_ARRIVED), decisions)
    }

    @Test
    fun 아직_머무는_중을_누르면_STILL_HERE를_보낸다() {
        val decisions = mutableListOf<TransitionDecision>()
        setSheet(candidate = departureCandidate(), onDecide = { decisions += it })

        composeRule.onNodeWithText("아직 머무는 중").performClick()

        assertEquals(listOf(TransitionDecision.STILL_HERE), decisions)
    }

    // --- UI-006 상태 ---

    @Test
    fun 응답을_보내는_동안_두_행동이_잠기고_진행을_표시한다() {
        setSheet(candidate = arrivalCandidate(), submitting = true)

        composeRule.onNodeWithText("네, 도착했어요").assertDoesNotExist()
        composeRule.onNodeWithText("아직이에요").assertIsNotEnabled()
    }

    @Test
    fun 실패하면_원인과_다시_시도를_보이고_후보는_그대로_둔다() {
        setSheet(candidate = arrivalCandidate(), error = DetectionError.Network)

        composeRule.onNodeWithText("연결을 확인한 뒤 다시 시도해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithText("다시 시도").assertIsDisplayed()
        // 후보를 임의로 취소하지 않으므로 질문과 거절 행동은 남아 있다.
        composeRule.onNodeWithText("북촌한옥마을에 도착하셨나요?").assertIsDisplayed()
        composeRule.onNodeWithText("아직이에요").assertIsEnabled()
    }

    @Test
    fun 다시_시도를_누르면_재전송_콜백이_불린다() {
        var retried = 0
        setSheet(candidate = arrivalCandidate(), error = DetectionError.Network, onRetry = { retried++ })

        composeRule.onNodeWithText("다시 시도").performClick()

        assertEquals(1, retried)
    }

    @Test
    fun 출발_시트도_보내는_동안_두_행동이_잠긴다() {
        setSheet(candidate = departureCandidate(), submitting = true)

        composeRule.onNodeWithText("네, 출발했어요").assertDoesNotExist()
        composeRule.onNodeWithText("아직 머무는 중").assertIsNotEnabled()
    }

    @Test
    fun 출발_시트도_실패하면_원인과_다시_시도를_보이고_거절_행동은_남는다() {
        // `아직 머무는 중`이 실패해도 사용자가 다시 그 답을 고를 수 있어야 한다.
        setSheet(candidate = departureCandidate(), error = DetectionError.Network)

        composeRule.onNodeWithText("연결을 확인한 뒤 다시 시도해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithText("다시 시도").assertIsDisplayed()
        composeRule.onNodeWithText("북촌한옥마을에서 출발하셨나요?").assertIsDisplayed()
        composeRule.onNodeWithText("아직 머무는 중").assertIsEnabled()
    }

    // --- UI-008 접근성 ---

    @Test
    fun 두_행동의_터치_영역이_48dp_이상이다() {
        setSheet(candidate = arrivalCandidate())

        composeRule.onNodeWithText("네, 도착했어요").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("아직이에요").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun 출발_시트_두_행동의_터치_영역도_48dp_이상이다() {
        setSheet(candidate = departureCandidate())

        composeRule.onNodeWithText("네, 출발했어요").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("아직 머무는 중").assertHeightIsAtLeast(48.dp)
    }

    private fun setSheet(
        candidate: TransitionCandidateDto,
        now: Instant = NOW,
        submitting: Boolean = false,
        error: DetectionError? = null,
        onDecide: (TransitionDecision) -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        composeRule.setContent {
            GilpickTheme {
                Box {
                    ConfirmSheetContent(
                        candidate = candidate,
                        placeName = "북촌한옥마을",
                        now = now,
                        submitting = submitting,
                        error = error,
                        onDecide = onDecide,
                        onRetry = onRetry,
                    )
                }
            }
        }
    }

    private fun arrivalCandidate() = TransitionCandidateDto(
        transitionId = TRANSITION_ID,
        itemId = ITEM_ID,
        type = DetectionKind.ARRIVAL,
        status = TransitionStatus.PENDING_CONFIRMATION,
        detectedAt = DETECTED_AT,
        autoFinalizeAt = AUTO_FINALIZE_AT,
        allowedDecisions = listOf(TransitionDecision.CONFIRM, TransitionDecision.NOT_ARRIVED),
        evidence = CandidateEvidenceDto(occurredAt = DETECTED_AT, accuracyMeters = 18.0, dwellMinutes = 6),
    )

    private fun departureCandidate() = arrivalCandidate().copy(
        type = DetectionKind.DEPARTURE,
        allowedDecisions = listOf(TransitionDecision.CONFIRM, TransitionDecision.STILL_HERE),
        evidence = CandidateEvidenceDto(occurredAt = DETECTED_AT, accuracyMeters = 22.0, dwellMinutes = null),
    )

    private companion object {
        const val TRANSITION_ID = "8a2918f7-e6d5-4c4b-8a29-18f7e6d5c4b3"
        const val ITEM_ID = "9c8b7a6f-5e4d-4c3b-8a29-18f7e6d5c4b3"

        /** 오후 2:33 KST. */
        const val DETECTED_AT = "2026-09-08T14:33:00+09:00"

        /** 오후 2:38 KST. [NOW]에서 4분 남았다. */
        const val AUTO_FINALIZE_AT = "2026-09-08T14:38:00+09:00"

        /** 오후 2:34 KST. */
        val NOW: Instant = Instant.parse("2026-09-08T05:34:00Z")
    }
}
