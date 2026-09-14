package com.gilpick.ui.component

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.gilpick.ui.theme.GilpickTheme
import com.gilpick.ui.theme.LocalGilpickSpacing
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * #431: `GradientButton` 계열 3종 × 기본·처리 중, 360dp·글자 2.0배 screenshot 기록.
 *
 * 검증이 아니라 기록이다. PNG를 기기 저장소에 남기고 `adb pull`로 꺼내 Figma gradient 버튼과 대조한다.
 * 처리 중 spinner는 회전 중이라 각도가 캡처마다 다를 수 있다.
 *
 * 저장 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/`.
 */
class GradientButtonScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 주_기본() = capture("gradient_button_primary") { GradientButton(label = "변경 승인", onClick = {}, modifier = Modifier.fillMaxWidth()) }

    @Test
    fun 주_처리_중() = capture("gradient_button_primary_processing") {
        GradientButton(label = "변경하는 중", onClick = {}, modifier = Modifier.fillMaxWidth(), processing = true)
    }

    @Test
    fun 성공_기본() = capture("gradient_button_success") {
        GradientButton(label = "도착했어요", onClick = {}, modifier = Modifier.fillMaxWidth(), tone = GradientTone.Success, width = GradientButtonWidth.Split, height = 48.dp)
    }

    @Test
    fun 성공_처리_중() = capture("gradient_button_success_processing") {
        GradientButton(
            label = "도착했어요",
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
            tone = GradientTone.Success,
            width = GradientButtonWidth.Split,
            height = 48.dp,
            processing = true,
        )
    }

    @Test
    fun 경고_기본() = capture("gradient_button_warning") {
        GradientButton(label = "다시 만들기", onClick = {}, modifier = Modifier.fillMaxWidth(), tone = GradientTone.Warning)
    }

    @Test
    fun 경고_처리_중() = capture("gradient_button_warning_processing") {
        GradientButton(label = "다시 만드는 중", onClick = {}, modifier = Modifier.fillMaxWidth(), tone = GradientTone.Warning, processing = true)
    }

    @Test
    fun 주_기본_360dp_최대_글자배율() = capture("gradient_button_primary_360dp_fontscale2") {
        Narrow { GradientButton(label = "여행 만들고 일정 편집하기", onClick = {}, modifier = Modifier.fillMaxWidth()) }
    }

    @Test
    fun 주_처리_중_360dp_최대_글자배율() = capture("gradient_button_primary_processing_360dp_fontscale2") {
        Narrow { GradientButton(label = "변경하는 중", onClick = {}, modifier = Modifier.fillMaxWidth(), processing = true) }
    }

    /** 360dp 너비 + 시스템 글자 확대 최대 배율(2.0). */
    @Composable
    private fun Narrow(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        Box(modifier = Modifier.width(360.dp)) {
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) { content() }
        }
    }

    /** 그림자가 보이도록 `background` 위에 여백을 두고 그린다. */
    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            GilpickTheme {
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.background)
                        .padding(LocalGilpickSpacing.current.space4),
                ) { content() }
            }
        }
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
