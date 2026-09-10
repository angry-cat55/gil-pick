package com.gilpick.replacement

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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.gilpick.auth.SuccessEnvelope
import com.gilpick.route.readyRoute
import com.gilpick.ui.theme.GilpickTheme
import com.gilpick.ui.theme.LocalGilpickColors
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test

/**
 * T036: 미리보기 화면의 상태별 screenshot 증빙(UI-009·UI-010). 되돌리기 2장은 F006
 * `ActiveTravelScreenshotTest`에 둔다.
 *
 * 검증이 아니라 기록이다. 각 상태를 그려 기기 저장소에 PNG로 남기고 `adb pull`로 꺼내 사람이
 * Figma `RoutePreviewScreen`과 대조한다. 지도는 SDK 인증 없이 그릴 수 있도록 `darkMap` 색 자리
 * 표시로 바꿔 끼운다.
 *
 * 저장 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/`.
 */
class RoutePreviewScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 미리보기_content() = capture("replacement_preview_content") { Screen(content()) }

    @Test
    fun 미리보기_content_360dp_최대_글자배율() = capture("replacement_preview_content_360dp_fontscale2") { Narrow { Screen(content()) } }

    @Test
    fun 미리보기_정보_없음() = capture("replacement_preview_no_closes_at") { Screen(content(previewWithoutClosingTimeJson())) }

    @Test
    fun 미리보기_error() = capture("replacement_preview_error") { Screen(PreviewUiState.Error(ReplacementError.RouteUnavailable(retryable = true))) }

    @Test
    fun 미리보기_error_360dp_최대_글자배율() = capture("replacement_preview_error_360dp_fontscale2") {
        Narrow { Screen(PreviewUiState.Error(ReplacementError.RouteUnavailable(retryable = true))) }
    }

    @Test
    fun 승인_실패() = capture("replacement_approve_failed") { Screen(content().copy(approveFailure = ReplacementError.ScheduleChanged)) }

    /** 360dp·2.0에서는 실패 블록이 첫 화면 아래로 내려가므로 그 블록까지 스크롤한 뒤 찍는다. */
    @Test
    fun 승인_실패_360dp_최대_글자배율() = capture("replacement_approve_failed_360dp_fontscale2", scrollTo = TAG_APPROVE_FAILURE) {
        Narrow { Screen(content().copy(approveFailure = ReplacementError.ScheduleChanged)) }
    }

    @Test
    fun 승인_실패_대체_장소_이용_불가_360dp_최대_글자배율() =
        capture("replacement_approve_failed_unavailable_360dp_fontscale2", scrollTo = TAG_APPROVE_FAILURE) {
            Narrow { Screen(content().copy(approveFailure = ReplacementError.AlternativeUnavailable)) }
        }

    @Test
    fun 미리보기_content_행동_360dp_최대_글자배율() = capture("replacement_preview_actions_360dp_fontscale2", scrollTo = TAG_OTHER_CANDIDATES) {
        Narrow { Screen(content()) }
    }

    @Test
    fun 승인_중() = capture("replacement_approving") { Screen(content().copy(approving = true)) }

    private fun content(json: String = routePreviewJson()) = PreviewUiState.Content(
        preview = previewJson.decodeFromString<SuccessEnvelope<RoutePreviewDto>>(json).data,
        originalRoute = readyRoute(),
    )

    @Composable
    private fun Screen(state: PreviewUiState) {
        RoutePreviewScreen(
            state = state,
            onBack = {},
            onRetry = {},
            onApprove = {},
            onOtherCandidates = {},
            onReauthenticate = {},
            map = { _, modifier -> Box(modifier = modifier.fillMaxSize().background(LocalGilpickColors.current.darkMap)) },
            modifier = Modifier.fillMaxSize(),
        )
    }

    /** 360dp 너비 + 시스템 글자 확대 최대 배율(2.0). */
    @Composable
    private fun Narrow(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        Box(modifier = Modifier.width(360.dp)) {
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) { content() }
        }
    }

    private fun capture(name: String, scrollTo: String? = null, content: @Composable () -> Unit) {
        composeRule.setContent { GilpickTheme { content() } }
        composeRule.waitForIdle()
        scrollTo?.let { composeRule.onNodeWithTag(it).performScrollTo() }
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        val previewJson = Json { ignoreUnknownKeys = true }
    }
}
