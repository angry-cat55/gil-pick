package com.gilpick.ui.component

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * #590: 어두운 화면이 보이는 동안만 system bar 아이콘이 밝아지는지 확인한다.
 *
 * 화면 사진으로는 검증할 수 없다(Compose capture에는 system bar가 들어가지 않는다). 창의 실제 설정값을 읽는다.
 */
class SystemBarIconsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun 어두운_화면이_보이는_동안만_아이콘이_밝아진다() {
        var dark by mutableStateOf(false)
        // 앱 기본값(밝은 배경 기준의 어두운 아이콘)을 만든다. test activity는 `Theme.Gilpick`을 쓰지 않는다.
        composeRule.activity.runOnUiThread { setLightSystemBarIcons(true) }
        composeRule.setContent { if (dark) LightSystemBarIcons() }
        composeRule.waitForIdle()

        assertTrue(lightStatusBarIcons())
        assertTrue(lightNavigationBarIcons())

        dark = true
        composeRule.waitForIdle()
        assertFalse("어두운 화면에서는 상태 표시줄 아이콘이 밝아야 한다", lightStatusBarIcons())
        assertFalse("어두운 화면에서는 navigation bar 아이콘도 밝아야 한다", lightNavigationBarIcons())

        dark = false
        composeRule.waitForIdle()
        assertTrue("화면을 벗어나면 기본값으로 돌아와야 한다", lightStatusBarIcons())
        assertTrue("화면을 벗어나면 navigation bar도 기본값으로 돌아와야 한다", lightNavigationBarIcons())
    }

    private fun lightStatusBarIcons(): Boolean = controller().isAppearanceLightStatusBars

    private fun lightNavigationBarIcons(): Boolean = controller().isAppearanceLightNavigationBars

    private fun setLightSystemBarIcons(light: Boolean) {
        controller().isAppearanceLightStatusBars = light
        controller().isAppearanceLightNavigationBars = light
    }

    private fun controller() = composeRule.activity.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
}
