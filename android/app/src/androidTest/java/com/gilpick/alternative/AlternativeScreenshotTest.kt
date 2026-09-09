package com.gilpick.alternative

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
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
import com.gilpick.ui.theme.LocalGilpickColors
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * T037: 대체 장소 화면 네 상태 × (360dp 기본, 360dp fontScale 2.0) screenshot 증빙(UI-008·UI-009).
 *
 * 검증이 아니라 기록이다. F006 `ActiveTravelScreenshotTest`와 같은 방식으로 기기 저장소에 PNG로 남기고
 * `adb pull`로 꺼내 사람이 Figma `AlternativePlacesScreen`·`alternativesEmpty`와 대조한다. 지도는 SDK 인증
 * 없이 그릴 수 있도록 `darkMap` 색 자리 표시로 바꿔 끼운다. 실제 마커는 `gilpick_api36_play`에서 따로 확인한다.
 *
 * 저장 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/`.
 */
class AlternativeScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 후보_있음() = capture("alternative_candidates") { Screen(content()) }

    @Test
    fun 후보_있음_360dp_최대_글자배율() = capture("alternative_candidates_360dp_fontscale2") { Narrow { Screen(content()) } }

    @Test
    fun 후보_없음() = capture("alternative_empty") { Screen(content(candidates = alternatives(alternativesEmptyJson()))) }

    @Test
    fun 후보_없음_360dp_최대_글자배율() = capture("alternative_empty_360dp_fontscale2") {
        Narrow { Screen(content(candidates = alternatives(alternativesEmptyJson()))) }
    }

    @Test
    fun 추천_실패() = capture("alternative_error") { Screen(providerFailed()) }

    @Test
    fun 추천_실패_360dp_최대_글자배율() = capture("alternative_error_360dp_fontscale2") { Narrow { Screen(providerFailed()) } }

    @Test
    fun 처리된_감지() = capture("alternative_closed") { Screen(AlternativeUiState.Closed(DetectionStatus.DISMISSED)) }

    @Test
    fun 처리된_감지_360dp_최대_글자배율() = capture("alternative_closed_360dp_fontscale2") {
        Narrow { Screen(AlternativeUiState.Closed(DetectionStatus.DISMISSED)) }
    }

    private fun providerFailed() = AlternativeUiState.Error(AlternativeError.ProviderFailed(retryable = true), retryable = true)

    @Composable
    private fun Screen(state: AlternativeUiState) {
        AlternativePlacesScreen(
            state = state,
            onBack = {},
            onRetry = {},
            onSelect = {},
            onSearch = {},
            onKeep = {},
            onRetryKeep = {},
            onReauthenticate = {},
            map = { _, _, modifier -> Box(modifier = modifier.fillMaxSize().background(LocalGilpickColors.current.darkMap)) },
        )
    }

    /** 360dp 너비 + 최대 글자 배율(2.0). */
    @Composable
    private fun Narrow(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        Box(modifier = Modifier.width(360.dp)) {
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) { content() }
        }
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent { GilpickTheme { content() } }
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
