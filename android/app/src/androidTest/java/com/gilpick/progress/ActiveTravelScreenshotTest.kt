package com.gilpick.progress

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
import com.gilpick.itinerary.ItemStatus
import com.gilpick.route.ITEM_B
import com.gilpick.ui.theme.GilpickTheme
import com.gilpick.ui.theme.LocalGilpickColors
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * T020: 진행 화면의 상태별 screenshot 증빙(UI-012).
 *
 * 검증이 아니라 기록이다. F005 `DayRouteScreenshotTest`와 같은 방식으로 각 상태를 그려 기기 저장소에
 * PNG로 남기고 `adb pull`로 꺼내 사람이 Figma `ActiveTravelScreen`과 대조한다. 지도는 SDK 인증 없이
 * 그릴 수 있도록 `darkMap` 색 자리 표시로 바꿔 끼운다.
 *
 * 저장 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/`.
 */
class ActiveTravelScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 진행_이동_중() = capture("progress_moving") { Screen(content()) }

    @Test
    fun 진행_지연() = capture("progress_overdue") { Screen(content(now = NOW_AFTER_ETA)) }

    @Test
    fun 진행_정보_없음() = capture("progress_no_eta") { Screen(content(progress = movingWithoutEtaProgress(), days = overviewDays(todayItinerary(route = null)))) }

    @Test
    fun 진행_도착() = capture("progress_arrived") { Screen(content(progress = arrivedProgress())) }

    @Test
    fun 진행_당일_완료_건너뜀_포함() = capture("progress_all_done") { Screen(content(progress = allDoneProgress())) }

    @Test
    fun 진행_시작_전() = capture("progress_not_started") { Screen(content(progress = notStartedProgress())) }

    @Test
    fun 진행_요청_중() = capture("progress_pending") {
        Screen(content().copy(pendingAction = ProgressAction(ITEM_B, ItemStatus.ARRIVED)))
    }

    @Test
    fun 진행_전환_실패() = capture("progress_action_error") {
        Screen(content().copy(actionError = ProgressActionFailure(ProgressAction(ITEM_B, ItemStatus.ARRIVED), ProgressError.Network)))
    }

    @Test
    fun 진행_empty() = capture("progress_empty") { Screen(ProgressUiState.Empty) }

    @Test
    fun 진행_error() = capture("progress_error") { Screen(ProgressUiState.Error(ProgressError.Network)) }

    @Test
    fun 진행_loading() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { GilpickTheme { Screen(ProgressUiState.Loading) } }
        composeRule.mainClock.advanceTimeBy(1_200)
        save("progress_loading")
    }

    @Test
    fun 진행_이동_중_360dp() = capture("progress_moving_360dp") {
        Box(modifier = Modifier.width(360.dp)) { Screen(content()) }
    }

    @Test
    fun 진행_이동_중_최대_글자배율() = capture("progress_moving_fontscale2") { LargeFont { Screen(content()) } }

    @Test
    fun 진행_이동_중_360dp_최대_글자배율() = capture("progress_moving_360dp_fontscale2") {
        Box(modifier = Modifier.width(360.dp)) { LargeFont { Screen(content()) } }
    }

    @Test
    fun 진행_도착_360dp_최대_글자배율() = capture("progress_arrived_360dp_fontscale2") {
        Box(modifier = Modifier.width(360.dp)) { LargeFont { Screen(content(progress = arrivedProgress())) } }
    }

    @Test
    fun 진행_당일_완료_360dp_최대_글자배율() = capture("progress_all_done_360dp_fontscale2") {
        Box(modifier = Modifier.width(360.dp)) { LargeFont { Screen(content(progress = allDoneProgress())) } }
    }

    @Composable
    private fun Screen(state: ProgressUiState) {
        ActiveTravelScreen(
            state = state,
            tripName = "서울 자유여행",
            onRetry = {},
            onAddPlace = {},
            onOpenRoute = { _, _ -> },
            onReauthenticate = {},
            map = { _, _, modifier -> Box(modifier = modifier.fillMaxSize().background(LocalGilpickColors.current.darkMap)) },
        )
    }

    /** 시스템 글자 확대 최대 배율(2.0)을 흉내 낸다. */
    @Composable
    private fun LargeFont(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) { content() }
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent { GilpickTheme { content() } }
        composeRule.waitForIdle()
        save(name)
    }

    private fun save(name: String) {
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
