package com.gilpick.ui.component

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.gilpick.ui.theme.GilpickTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** #431: 클릭 콜백, 처리 중·비활성 클릭 차단, 48dp 터치 영역(가이드라인 10절). */
class GradientButtonTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 누르면_콜백이_호출된다() {
        var clicks = 0
        composeRule.setContent { GilpickTheme { GradientButton(label = "변경 승인", onClick = { clicks++ }) } }

        composeRule.onNodeWithText("변경 승인").assertIsEnabled().performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun 처리_중에는_클릭이_막힌다() {
        var clicks = 0
        composeRule.setContent { GilpickTheme { GradientButton(label = "변경하는 중", onClick = { clicks++ }, processing = true) } }

        composeRule.onNodeWithText("변경하는 중", useUnmergedTree = true).assertExists()
        composeRule.onNode(buttonWithText("변경하는 중")).assertIsNotEnabled().performClick()

        assertEquals(0, clicks)
    }

    @Test
    fun 비활성이면_클릭이_막힌다() {
        var clicks = 0
        composeRule.setContent { GilpickTheme { GradientButton(label = "저장", onClick = { clicks++ }, enabled = false) } }

        composeRule.onNode(buttonWithText("저장")).assertIsNotEnabled().performClick()

        assertEquals(0, clicks)
    }

    @Test
    fun 높이가_48dp보다_작아도_터치_영역은_48dp다() {
        composeRule.setContent {
            GilpickTheme { GradientButton(label = "건너뛰기", onClick = {}, width = GradientButtonWidth.Split, height = 46.dp) }
        }

        composeRule.onNode(buttonWithText("건너뛰기")).assertTouchHeightIsEqualTo(48.dp)
    }

    private fun buttonWithText(text: String) =
        hasText(text) and hasClickAction()
}
