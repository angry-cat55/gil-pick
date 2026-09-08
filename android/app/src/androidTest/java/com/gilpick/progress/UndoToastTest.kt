package com.gilpick.progress

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
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
 * T021: 되돌리기 토스트의 표시와 행동 검증(UI-003·UI-004·UI-008).
 *
 * quickstart FE 2·FE 3을 다룬다. 남은 시간은 서버가 준 `undoDeadline`으로 표시만 하고
 * 만료 판정은 서버가 한다는 규칙(FR-018)을 화면 쪽에서 확인한다.
 */
@RunWith(AndroidJUnit4::class)
class UndoToastTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- UI-003 표시 ---

    @Test
    fun 토스트는_바뀐_내용과_남은_시간과_되돌리기를_보여준다() {
        setToast(undoable = arrivalUndoable())

        composeRule.onNodeWithText("북촌한옥마을 도착으로 자동 처리했어요").assertIsDisplayed()
        composeRule.onNodeWithText("240초").assertIsDisplayed()
        composeRule.onNodeWithText("되돌리기").assertIsDisplayed()
    }

    @Test
    fun 출발_자동_처리는_출발_문구를_보여준다() {
        setToast(undoable = arrivalUndoable().copy(type = UndoableKind.DEPARTURE))

        composeRule.onNodeWithText("북촌한옥마을 출발로 자동 처리했어요").assertIsDisplayed()
    }

    @Test
    fun 복합_전환은_두_변경을_함께_알린다() {
        // 이전 장소를 벗어난 기록 없이 다음 도착이 확정된 경우다(FR-009).
        setToast(undoable = arrivalUndoable().copy(type = UndoableKind.COMPOSITE))

        composeRule.onNodeWithText("북촌한옥마을 도착과 이전 장소 완료를 자동 처리했어요").assertIsDisplayed()
    }

    // --- 만료 ---

    @Test
    fun 남은_시간이_0이면_되돌리기와_남은_시간이_사라진다() {
        // 되돌릴 수 없는 행동을 보여 주지 않는다.
        setToast(undoable = arrivalUndoable(), now = Instant.parse("2026-09-08T06:00:00Z"))

        composeRule.onNodeWithText("되돌리기").assertDoesNotExist()
        composeRule.onNodeWithText("0초").assertDoesNotExist()
        // 무엇이 자동으로 처리됐는지는 계속 알린다.
        composeRule.onNodeWithText("북촌한옥마을 도착으로 자동 처리했어요").assertIsDisplayed()
    }

    // --- 행동 ---

    @Test
    fun 되돌리기를_누르면_콜백이_호출된다() {
        var undone = 0
        setToast(undoable = arrivalUndoable(), onUndo = { undone++ })

        composeRule.onNodeWithText("되돌리기").performClick()

        assertEquals(1, undone)
    }

    @Test
    fun 되돌리는_동안_버튼이_잠긴다() {
        setToast(undoable = arrivalUndoable(), submitting = true)

        composeRule.onNodeWithText("되돌리기").assertIsNotEnabled()
    }

    @Test
    fun 실패하면_원인을_보여준다() {
        setToast(undoable = arrivalUndoable(), error = DetectionError.UndoWindowExpired)

        composeRule.onNodeWithText("되돌릴 수 있는 시간이 지났습니다. 상태 수정으로 바꿀 수 있어요.").assertIsDisplayed()
    }

    // --- UI-008 접근성 ---

    @Test
    fun 되돌리기_터치_영역이_48dp_이상이다() {
        setToast(undoable = arrivalUndoable())

        composeRule.onNodeWithText("되돌리기").assertHeightIsAtLeast(48.dp)
    }

    private fun setToast(
        undoable: UndoableTransitionDto,
        now: Instant = NOW,
        submitting: Boolean = false,
        error: DetectionError? = null,
        onUndo: () -> Unit = {},
    ) {
        composeRule.setContent {
            GilpickTheme {
                Box {
                    UndoToast(
                        undoable = undoable,
                        placeName = "북촌한옥마을",
                        now = now,
                        submitting = submitting,
                        error = error,
                        onUndo = onUndo,
                    )
                }
            }
        }
    }

    private fun arrivalUndoable() = UndoableTransitionDto(
        transitionId = TRANSITION_ID,
        itemId = ITEM_ID,
        type = UndoableKind.ARRIVAL,
        confirmedAt = "2026-09-08T14:38:00+09:00",
        undoDeadline = UNDO_DEADLINE,
    )

    private companion object {
        const val TRANSITION_ID = "8a2918f7-e6d5-4c4b-8a29-18f7e6d5c4b3"
        const val ITEM_ID = "9c8b7a6f-5e4d-4c3b-8a29-18f7e6d5c4b3"

        /** 오후 2:43 KST. [NOW]에서 240초 남았다. */
        const val UNDO_DEADLINE = "2026-09-08T14:43:00+09:00"

        /** 오후 2:39 KST. */
        val NOW: Instant = Instant.parse("2026-09-08T05:39:00Z")
    }
}
