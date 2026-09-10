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
import com.gilpick.replacement.ReplacementError
import com.gilpick.ui.theme.GilpickTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T026: 장소 변경 되돌리기 토스트와 두 되돌리기의 표시 우선순위 검증(quickstart FE 5, spec UI-006·UI-006a·UI-007·UI-008).
 *
 * 남은 시간은 서버가 준 `undoExpiresAt`으로 **표시만** 하고 만료 판정은 서버가 한다(FR-015).
 * 앱은 만료된 뒤 행동을 감춰 헛된 요청을 줄이기만 한다.
 */
@RunWith(AndroidJUnit4::class)
class ReplacementUndoTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- UI-006 표시 ---

    @Test
    fun 토스트는_바뀐_장소와_남은_시간과_되돌리기를_보여준다() {
        setToast(now = NOW_UNDOABLE)

        composeRule.onNodeWithText("장소가 창덕궁(으)로 변경되었습니다").assertIsDisplayed()
        composeRule.onNodeWithText("20초").assertIsDisplayed()
        composeRule.onNodeWithText("되돌리기").assertIsDisplayed()
    }

    @Test
    fun 되돌릴_수_있는_시간이_지나면_행동만_감추고_무엇이_바뀌었는지는_남긴다() {
        // UI-006. 되돌릴 수 없게 돼도 사용자는 무엇이 바뀌었는지 계속 알아야 한다.
        setToast(now = NOW_UNDO_EXPIRED)

        composeRule.onNodeWithText("장소가 창덕궁(으)로 변경되었습니다").assertIsDisplayed()
        composeRule.onNodeWithText("되돌리기").assertDoesNotExist()
        composeRule.onNodeWithText("20초").assertDoesNotExist()
    }

    @Test
    fun 되돌리기는_48dp_이상이고_누르면_호출된다() {
        var undos = 0
        setToast(now = NOW_UNDOABLE, onUndo = { undos++ })

        composeRule.onNodeWithText("되돌리기").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, undos) }
    }

    @Test
    fun 되돌리는_중에는_행동이_잠긴다() {
        // UI-007. 같은 되돌리기를 두 번 보내지 않는다.
        var undos = 0
        setToast(now = NOW_UNDOABLE, submitting = true, onUndo = { undos++ })

        composeRule.onNodeWithText("되돌리기").assertIsNotEnabled().performClick()
        composeRule.runOnIdle { assertEquals(0, undos) }
    }

    // --- UI-007 실패 안내 ---

    @Test
    fun 시간이_지나_실패하면_원인과_일정_편집_안내를_보인다() {
        // FR-019. 되돌릴 수 없게 된 뒤에도 사용자에게 남은 길이 있음을 알려야 한다.
        setToast(now = NOW_UNDOABLE, error = ReplacementError.UndoExpired)

        composeRule.onNodeWithText("되돌릴 수 있는 시간이 지났어요. 일정 편집에서 바꿀 수 있어요.").assertIsDisplayed()
        composeRule.onNodeWithText("되돌리기").assertDoesNotExist()
    }

    @Test
    fun 후속_변경으로_실패하면_다른_원인_문구와_일정_편집_안내를_보인다() {
        setToast(now = NOW_UNDOABLE, error = ReplacementError.FollowUpChangeExists)

        composeRule.onNodeWithText("그 뒤에 일정이 또 바뀌었어요. 일정 편집에서 바꿀 수 있어요.").assertIsDisplayed()
    }

    @Test
    fun 두_실패_원인은_서로_다른_문구를_보인다() {
        // UI-007이 원인 두 가지를 구분하라고 한다. 같은 문구로 뭉뚱그리면 사용자가 이유를 모른다.
        setToast(now = NOW_UNDOABLE, error = ReplacementError.UndoExpired)
        composeRule.onNodeWithText("그 뒤에 일정이 또 바뀌었어요. 일정 편집에서 바꿀 수 있어요.").assertDoesNotExist()
    }

    @Test
    fun 그_밖의_실패도_일정_편집_안내를_함께_보인다() {
        setToast(now = NOW_UNDOABLE, error = ReplacementError.Network)

        composeRule.onNodeWithText("지금은 되돌릴 수 없어요. 일정 편집에서 바꿀 수 있어요.").assertIsDisplayed()
    }

    // --- UI-006a 표시 우선순위 ---

    @Test
    fun 두_되돌리기가_동시에_가능하면_겹치지_않고_장소_변경이_먼저_보인다() {
        // SC-008. 표시 지점은 하나뿐이라 남은 시간이 짧은 장소 변경(20초)을 먼저 보여야
        // 사용자가 두 되돌리기를 모두 쓸 수 있다. 자동 확정(240초)은 그 뒤에 이어진다.
        setScreen(
            content(
                progress = movingProgress().copy(
                    undoable = arrivalUndoable(),
                    undoableReplacement = placeReplacementUndo(),
                ),
                now = NOW_UNDOABLE,
            ),
        )

        composeRule.onNodeWithText("장소가 창덕궁(으)로 변경되었습니다").assertIsDisplayed()
        composeRule.onNodeWithText("북촌한옥마을 도착으로 자동 처리했어요").assertDoesNotExist()
    }

    @Test
    fun 장소_변경_되돌리기가_사라지면_자동_확정이_이어서_보인다() {
        // 되돌렸거나 시간이 지나 서버가 더 이상 싣지 않는 상태다. 남은 자동 확정이 자리를 잇는다.
        setScreen(
            content(
                progress = movingProgress().copy(undoable = arrivalUndoable(), undoableReplacement = null),
                now = NOW_UNDOABLE,
            ),
        )

        composeRule.onNodeWithText("북촌한옥마을 도착으로 자동 처리했어요").assertIsDisplayed()
        composeRule.onNodeWithText("장소가 창덕궁(으)로 변경되었습니다").assertDoesNotExist()
    }

    @Test
    fun 지난_날짜를_보고_있으면_장소_변경_되돌리기를_보이지_않는다() {
        // 되돌리기는 오늘 진행 중인 일정의 행동이다.
        val state = content(
            progress = movingProgress().copy(undoableReplacement = placeReplacementUndo()),
            now = NOW_UNDOABLE,
        ).copy(viewingDate = java.time.LocalDate.parse("2026-09-07"))
        setScreen(state)

        composeRule.onNodeWithText("장소가 창덕궁(으)로 변경되었습니다").assertDoesNotExist()
    }

    private fun setToast(
        now: Instant,
        submitting: Boolean = false,
        error: ReplacementError? = null,
        onUndo: () -> Unit = {},
    ) {
        composeRule.setContent {
            GilpickTheme {
                ReplacementUndoToast(
                    undo = placeReplacementUndo(),
                    now = now,
                    submitting = submitting,
                    error = error,
                    onUndo = onUndo,
                )
            }
        }
    }

    private fun setScreen(state: ProgressUiState) {
        composeRule.setContent {
            GilpickTheme {
                ActiveTravelScreen(
                    state = state,
                    tripName = "서울 여행",
                    onRetry = {},
                    onAddPlace = {},
                    onOpenRoute = { _, _ -> },
                    onReauthenticate = {},
                    map = { _, _, modifier -> Box(modifier = modifier) },
                )
            }
        }
    }
}
