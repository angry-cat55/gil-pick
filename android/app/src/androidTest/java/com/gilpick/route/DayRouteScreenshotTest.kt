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
import com.gilpick.itinerary.ItemStatus
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

    /** #687: 대중교통 단계 형상이 있는 경로. 도보 점선·버스·지하철 범례가 함께 보인다. */
    @Test
    fun 경로_content_대중교통_단계_선_360dp_최대_글자배율() = capture("route_content_transit_lines_360dp_fontscale2") {
        Box(modifier = Modifier.width(360.dp)) { LargeFont { Screen(RouteUiState.Content(transitStepLinesRoute())) } }
    }

    private fun transitStepLinesRoute(): RouteDto = readyRoute().let { base ->
        fun step(type: RouteStepType, from: Double) = RouteStepDto(
            type = type, durationSeconds = 180, distanceMeters = 300,
            geometry = RouteGeometryDto("LineString", listOf(listOf(from, 37.58), listOf(from + 0.001, 37.579))),
        )
        base.copy(
            segments = base.segments.map { segment ->
                if (segment.sequence != 2) segment else segment.copy(
                    steps = listOf(
                        step(RouteStepType.WALK, 126.9831),
                        step(RouteStepType.BUS, 126.9841),
                        step(RouteStepType.WALK, 126.9851),
                        step(RouteStepType.SUBWAY, 126.9861),
                        step(RouteStepType.WALK, 126.9871),
                    ),
                )
            },
        )
    }

    @Test
    fun 경로_content_진행_중() = capture("route_content_in_progress") { Screen(RouteUiState.Content(readyRoute(), inProgressMarks())) }

    @Test
    fun 경로_content_진행_중_360dp_최대_글자배율() = capture("route_content_in_progress_360dp_fontscale2") {
        Box(modifier = Modifier.width(360.dp)) { LargeFont { Screen(RouteUiState.Content(readyRoute(), inProgressMarks())) } }
    }

    @Test
    fun 경로_content_7곳_360dp() = capture("route_content_seven_360dp") {
        Box(modifier = Modifier.width(360.dp)) { Screen(RouteUiState.Content(sevenRoute())) }
    }

    /** 긴 장소명이 줄바꿈되는 경우. 카드 하단이 어긋나지 않는지 본다(#618). */
    @Test
    fun 경로_content_긴_장소명_360dp() = capture("route_content_long_name_360dp") {
        Box(modifier = Modifier.width(360.dp)) { Screen(RouteUiState.Content(longNameRoute())) }
    }

    @Test
    fun 경로_content_긴_장소명_360dp_최대_글자배율() = capture("route_content_long_name_360dp_fontscale2") {
        Box(modifier = Modifier.width(360.dp)) { LargeFont { Screen(RouteUiState.Content(longNameRoute())) } }
    }

    private fun longNameRoute(): RouteDto {
        val base = readyRoute()
        return base.copy(markers = base.markers.mapIndexed { i, marker -> if (i == 2) marker.copy(name = "대학로자유극장 소극장 무대") else marker })
    }

    private fun inProgressMarks() = RouteMarks(
        start = listOf(126.97, 37.57),
        statuses = mapOf(ITEM_A to ItemStatus.COMPLETED, ITEM_B to ItemStatus.EN_ROUTE, ITEM_C to ItemStatus.PLANNED),
    )

    /** 7곳: 카드 n등분이 최소 폭보다 좁아져 가로 스크롤로 넘어가는 경우. */
    private fun sevenRoute(): RouteDto {
        val base = readyRoute()
        val names = listOf("경복궁", "북촌한옥마을", "인사동거리", "창덕궁", "덕수궁", "남산서울타워", "명동")
        val markers = names.mapIndexed { i, name -> RouteMarkerDto("item-$i", i + 1, name, 37.57 + i * 0.001, 126.97 + i * 0.001) }
        val segments = markers.zipWithNext().mapIndexed { i, (from, to) ->
            base.segments.first().copy(sequence = i + 1, fromItemId = from.itemId, toItemId = to.itemId)
        }
        return base.copy(markers = markers, segments = segments)
    }

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
            map = { _, _, _, _, modifier -> Box(modifier = modifier.fillMaxSize().background(LocalGilpickColors.current.darkMap)) },
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
