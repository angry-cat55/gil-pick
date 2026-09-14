package com.gilpick.progress

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gilpick.ui.theme.GilpickTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** #440: 위치 권한 안내 화면의 두 버튼이 각 행동을 한 번씩 부르고, 좁은 폭·큰 글자에서도 48dp 이상으로 보이는지 확인한다. */
class LocationPermissionScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 허용과_나중에_하기가_각_행동을_부른다() {
        var allowed = 0
        var later = 0
        composeRule.setContent {
            GilpickTheme {
                LocationPermissionScreen(onAllow = { allowed++ }, onLater = { later++ }, modifier = Modifier.fillMaxSize())
            }
        }

        composeRule.onNodeWithText("위치 권한 허용하기").performClick()
        composeRule.onNodeWithText("나중에 하기").performClick()

        assertEquals(1, allowed)
        assertEquals(1, later)
    }

    @Test
    fun 좁은_폭_최대_글자배율에서_버튼이_보이고_48dp_이상이다() {
        composeRule.setContent {
            GilpickTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                    Box(modifier = Modifier.width(360.dp)) {
                        LocationPermissionScreen(onAllow = {}, onLater = {}, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }

        composeRule.onNodeWithText("위치 권한 허용하기").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("나중에 하기").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }
}
