package com.gilpick.route

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
import com.gilpick.itinerary.TransportMode
import com.gilpick.ui.theme.GilpickTheme
import com.gilpick.ui.theme.LocalGilpickColors
import java.io.File
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

/**
 * T024: 날짜 경로 화면의 상태별 screenshot 증빙.
 *
 * 검증이 아니라 기록이다. F004 `ItineraryEditScreenshotTest`와 같은 방식으로 각 상태를 그려 기기
 * 저장소에 PNG로 남기고 `adb pull`로 꺼내 사람이 Figma `DayRouteScreen`과 대조한다. 지도는 SDK
 * 인증 없이 그릴 수 있도록 `darkMap` 색 자리 표시로 바꿔 끼운다(실제 지도는 T026·T034 수동 확인).
 *
 * 저장 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/`.
 */
class DayRouteScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 경로_empty() = capture("route_empty") { Screen(RouteUiState.Empty) }

    @Test
    fun 경로_error_조회_실패() = capture("route_error_network") { Screen(RouteUiState.Error(RouteProblem.Request(RouteError.Network))) }

    @Test
    fun 경로_error_계산_실패() = capture("route_error_failed") {
        Screen(RouteUiState.Error(RouteProblem.Calculation(routeFailure(RouteFailureCodes.PROVIDER_TIMEOUT), 3)))
    }

    @Test
    fun 경로_content() = capture("route_content") { Screen(RouteUiState.Content(readyRoute())) }

    @Test
    fun 경로_content_혼합_이동수단() = capture("route_content_mixed") { Screen(RouteUiState.Content(mixedRoute())) }

    @Test
    fun 경로_content_1곳() = capture("route_content_single") { Screen(RouteUiState.Content(singleRoute())) }

    @Test
    fun 경로_content_360dp() = capture("route_content_360dp") {
        Box(modifier = Modifier.width(360.dp)) { Screen(RouteUiState.Content(mixedRoute())) }
    }

    @Test
    fun 경로_content_최대_글자배율() = capture("route_content_fontscale2") { LargeFont { Screen(RouteUiState.Content(mixedRoute())) } }

    @Test
    fun 경로_content_360dp_최대_글자배율() = capture("route_content_360dp_fontscale2") {
        Box(modifier = Modifier.width(360.dp)) { LargeFont { Screen(RouteUiState.Content(mixedRoute())) } }
    }

    @Test
    fun 경로_empty_360dp_최대_글자배율() = capture("route_empty_360dp_fontscale2") {
        Box(modifier = Modifier.width(360.dp)) { LargeFont { Screen(RouteUiState.Empty) } }
    }

    @Test
    fun 경로_error_360dp_최대_글자배율() = capture("route_error_360dp_fontscale2") {
        Box(modifier = Modifier.width(360.dp)) {
            LargeFont { Screen(RouteUiState.Error(RouteProblem.Calculation(routeFailure(RouteFailureCodes.PROVIDER_TIMEOUT), 3))) }
        }
    }

    @Test
    fun 경로_loading() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { GilpickTheme { Screen(RouteUiState.Loading) } }
        composeRule.mainClock.advanceTimeBy(1_200)
        save("route_loading")
    }

    private fun mixedRoute() = readyRoute().copy(
        totalDurationSeconds = 5_160,
        totalDistanceMeters = 16_545,
        markers = readyRoute().markers + RouteMarkerDto("d", 4, "남산서울타워", 37.5512, 126.9882),
        segments = readyRoute().segments + RouteSegmentDto(
            sequence = 3, fromItemId = ITEM_C, toItemId = "d",
            transportMode = TransportMode.CAR, provider = RouteProvider.TMAP,
            durationSeconds = 3_660, distanceMeters = 12_345,
            geometry = RouteGeometryDto("LineString", listOf(listOf(126.9857, 37.5744), listOf(126.9882, 37.5512))),
            providerAttribution = TMAP_ATTRIBUTION,
        ),
    )

    private fun singleRoute() = readyRoute().copy(
        totalDurationSeconds = 0,
        totalDistanceMeters = 0,
        markers = listOf(RouteMarkerDto(ITEM_A, 1, "경복궁", 37.5796, 126.977)),
        segments = emptyList(),
        providerAttributions = emptyList(),
    )

    @Composable
    private fun Screen(state: RouteUiState) {
        DayRouteScreen(
            state = state,
            dayNumber = 2,
            date = LocalDate.of(2026, 5, 21),
            onBack = {},
            onRetry = {},
            onAddPlace = {},
            onReauthenticate = {},
            map = { _, modifier -> Box(modifier = modifier.fillMaxSize().background(LocalGilpickColors.current.darkMap)) },
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
