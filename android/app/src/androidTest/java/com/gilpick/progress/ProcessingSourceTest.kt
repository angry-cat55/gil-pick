package com.gilpick.progress

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.route.ITEM_B
import com.gilpick.ui.theme.GilpickTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #313: 항목별 처리 출처와 이벤트 거절 이유가 화면에 어떻게 나타나는지 검증한다.
 *
 * 이전에는 `자동 처리` 표시를 되돌릴 수 있는 전환([ProgressData.undoable])으로 판단해, 되돌리기
 * 시간이 지나면 표시까지 사라졌다. 이제 항목별 [ProgressItemDto.processingSource]로 판단하므로
 * 그 뒤에도 남아야 한다(UI-004).
 */
@RunWith(AndroidJUnit4::class)
class ProcessingSourceTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- UI-004 자동 처리 표시 ---

    @Test
    fun 자동으로_처리된_장소에_표시가_붙는다() {
        setScreen(content(progress = withSource(ProgressProcessingSource.AUTO)))

        composeRule.onNodeWithText("자동 처리").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun 되돌릴_수_없게_된_뒤에도_표시가_남는다() {
        // undoable이 사라져도 처리 출처는 남으므로 무엇이 자동으로 바뀌었는지 계속 알 수 있다.
        val progress = withSource(ProgressProcessingSource.AUTO).copy(undoable = null)
        setScreen(content(progress = progress))

        composeRule.onNodeWithText("자동 처리").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun 사용자가_확인한_전환에는_표시가_붙지_않는다() {
        // 확인 시트에 답해 확정된 것은 사용자의 결정이므로 MANUAL이다.
        setScreen(content(progress = withSource(ProgressProcessingSource.MANUAL)))

        composeRule.onNodeWithText("자동 처리").assertDoesNotExist()
    }

    @Test
    fun 처리_이력이_없으면_표시가_붙지_않는다() {
        setScreen(content(progress = withSource(null)))

        composeRule.onNodeWithText("자동 처리").assertDoesNotExist()
    }

    // --- UI-005 정확도 부족 안내 ---

    @Test
    fun 정확도_미달로_거절되면_원인을_안내한다() {
        setScreen(content(progress = withRejection(EventRejectionReason.LOW_ACCURACY)))

        composeRule.onNodeWithText("위치가 정확하지 않아 자동 감지가 멈춰 있어요").assertIsDisplayed()
        composeRule.onNodeWithText("실내나 지하에서는 위치가 흐려질 수 있어요. 도착·출발은 직접 처리하시면 됩니다.")
            .assertIsDisplayed()
        // 사용자가 권한으로 풀 수 있는 문제가 아니므로 켜는 행동을 주지 않는다.
        composeRule.onNodeWithText("권한 허용").assertDoesNotExist()
        composeRule.onNodeWithText("닫기").assertIsDisplayed()
    }

    @Test
    fun 정확도_밖의_거절_이유는_안내하지_않는다() {
        setScreen(content(progress = withRejection(EventRejectionReason.DETECTION_PAUSED)))

        composeRule.onNodeWithTag(TAG_DETECTION_OFF).assertDoesNotExist()
    }

    @Test
    fun 권한_없음이_정확도_부족보다_먼저_보인다() {
        setScreen(
            content(progress = withRejection(EventRejectionReason.LOW_ACCURACY))
                .copy(detectionOff = DetectionOffReason.PermissionMissing),
        )

        composeRule.onNodeWithText("위치 권한이 없어 자동 감지가 꺼져 있어요").assertIsDisplayed()
        composeRule.onNodeWithText("위치가 정확하지 않아 자동 감지가 멈춰 있어요").assertDoesNotExist()
    }

    /** 북촌한옥마을(도착)의 처리 출처만 바꾼 진행 현황. */
    private fun withSource(source: ProgressProcessingSource?) = arrivedProgress().let { data ->
        data.copy(
            undoable = arrivalUndoable(),
            items = data.items.map { if (it.itemId == ITEM_B) it.copy(processingSource = source) else it },
        )
    }

    /** 북촌한옥마을의 최신 위치 이벤트가 거절된 진행 현황. */
    private fun withRejection(reason: EventRejectionReason) = movingProgress().let { data ->
        data.copy(items = data.items.map { if (it.itemId == ITEM_B) it.copy(eventRejectionReason = reason) else it })
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
                    map = { _, _, modifier -> FakeMap(modifier) },
                )
            }
        }
    }

    @Composable
    private fun FakeMap(modifier: Modifier) {
        Box(modifier = modifier.fillMaxSize().testTag(TAG_FAKE_MAP))
    }

    private companion object {
        const val TAG_FAKE_MAP = "processing_source_fake_map"
    }
}
