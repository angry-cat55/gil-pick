package com.gilpick.ui.component

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.gilpick.ui.theme.GilpickTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** #434: 주·보조 행동 콜백, 원인 카드 2행의 표시·생략, 안내 배너 생략, 빈 상태 행동 슬롯. */
class StateMessageTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 주_보조_버튼이_각_콜백을_호출한다() {
        var primary = 0
        var secondary = 0
        composeRule.setContent {
            GilpickTheme {
                ErrorState(
                    description = "경로를 업데이트하지 못했어요.",
                    primaryLabel = "다시 시도하기",
                    onPrimary = { primary++ },
                    secondaryLabel = "여행 진행으로 돌아가기",
                    onSecondary = { secondary++ },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule.onNodeWithText("문제가 발생했어요").assertExists()
        composeRule.onNodeWithText("다시 시도하기").assertTouchHeightIsEqualTo(54.dp).performClick()
        composeRule.onNodeWithText("여행 진행으로 돌아가기").assertTouchHeightIsEqualTo(48.dp).performClick()

        assertEquals(1, primary)
        assertEquals(1, secondary)
    }

    @Test
    fun 원인_카드는_아는_행만_보이고_배너는_없으면_그리지_않는다() {
        composeRule.setContent {
            GilpickTheme {
                ErrorState(
                    description = "설명",
                    primaryLabel = "다시 시도하기",
                    onPrimary = {},
                    cause = ErrorCause(occurredAt = Instant.parse("2026-09-14T05:32:00Z")),
                )
            }
        }

        composeRule.onNodeWithText("오류 정보").assertExists()
        composeRule.onNodeWithText("발생 시각").assertExists()
        composeRule.onNodeWithText("오후 2:32").assertExists()
        composeRule.onNodeWithText("마지막 동작").assertDoesNotExist()
        composeRule.onNodeWithText("여행 진행으로 돌아가기").assertDoesNotExist()
    }

    @Test
    fun 원인을_모르면_카드가_없고_배너는_문장을_보인다() {
        composeRule.setContent {
            GilpickTheme {
                ErrorState(
                    description = "설명",
                    primaryLabel = "다시 시도하기",
                    onPrimary = {},
                    cause = ErrorCause(),
                    hint = "인터넷 연결을 확인한 후 재시도해주세요",
                )
            }
        }

        composeRule.onNodeWithText("오류 정보").assertDoesNotExist()
        composeRule.onNodeWithText("인터넷 연결을 확인한 후 재시도해주세요").assertExists()
    }

    @Test
    fun 빈_상태는_행동_슬롯을_그대로_그린다() {
        var clicks = 0
        composeRule.setContent {
            GilpickTheme {
                EmptyState(
                    icon = com.gilpick.R.drawable.ic_lucide_search,
                    title = "아직 만든 여행이 없어요",
                    body = "여행을 만들면 정리할 수 있어요",
                    action = { GradientButton(label = "첫 여행 만들기", onClick = { clicks++ }) },
                )
            }
        }

        composeRule.onNodeWithText("아직 만든 여행이 없어요").assertExists()
        composeRule.onNodeWithText("첫 여행 만들기").performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun 빈_상태는_행동이_없으면_제목과_설명만_있다() {
        composeRule.setContent {
            GilpickTheme {
                EmptyState(icon = com.gilpick.R.drawable.ic_lucide_shield_check, title = "모든 일정이 예정대로예요", body = "10분마다 다시 확인해요", tone = EmptyStateTone.Success)
            }
        }

        composeRule.onNodeWithText("모든 일정이 예정대로예요").assertExists()
        composeRule.onNodeWithText("10분마다 다시 확인해요").assertExists()
    }
}
