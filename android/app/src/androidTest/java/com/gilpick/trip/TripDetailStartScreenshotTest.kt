package com.gilpick.trip

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
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
import com.gilpick.itinerary.DayItineraryDto
import com.gilpick.itinerary.RouteStatus
import com.gilpick.progress.ProgressError
import com.gilpick.ui.theme.GilpickTheme
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * F006 T018: 여행 상세 `오늘 여행 시작` 영역의 상태별 screenshot 증빙.
 *
 * 검증이 아니라 기록이다. 기간 밖·장소 없음·시작 가능·시작 중·시작됨·실패 여섯 상태와 360dp·최대 글자
 * 배율에서 안내 문구와 버튼이 잘리지 않는지 사람이 본다.
 * 저장 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/`.
 */
class TripDetailStartScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 기간_밖() = capture("trip_detail_start_not_travel_day") { Screen(TripStartPhase.NotTravelDay) }

    @Test
    fun 장소_없음() = capture("trip_detail_start_no_places") { Screen(TripStartPhase.NoPlaces("2026-09-02")) }

    @Test
    fun 시작_가능() = capture("trip_detail_start_ready") { Screen(TripStartPhase.Ready("2026-09-02", 0)) }

    @Test
    fun 시작_중() = capture("trip_detail_start_starting") { Screen(TripStartPhase.Starting("2026-09-02")) }

    @Test
    fun 시작됨() = capture("trip_detail_start_started") { Screen(TripStartPhase.Started("2026-09-02")) }

    @Test
    fun 시작_실패() = capture("trip_detail_start_failed") {
        Screen(TripStartPhase.Failed(ProgressError.Network, TripStartPhase.Ready("2026-09-02", 0)))
    }

    @Test
    fun 장소_없음_360dp_최대_글자배율() = capture("trip_detail_start_no_places_360dp_fontscale2") {
        Box(modifier = Modifier.width(360.dp)) { LargeFont { Screen(TripStartPhase.NoPlaces("2026-09-02")) } }
    }

    @Test
    fun 시작_실패_360dp_최대_글자배율() = capture("trip_detail_start_failed_360dp_fontscale2") {
        Box(modifier = Modifier.width(360.dp)) {
            LargeFont { Screen(TripStartPhase.Failed(ProgressError.Network, TripStartPhase.Ready("2026-09-02", 0))) }
        }
    }

    @Composable
    private fun Screen(start: TripStartPhase) {
        val days = (1..3).map { DayItineraryDto("2026-09-0$it", it, 0, RouteStatus.NOT_CALCULATED, emptyList()) }
        TripDetailScreen(
            state = TripDetailUiState(
                phase = TripDetailPhase.Content(TripDto("t1", "서울 여행", "2026-09-01", "2026-09-03", TripStatus.IN_PROGRESS, 3, 1)),
                itinerary = ItineraryOverviewPhase.Content(days),
                start = start,
            ),
            onBack = {}, onRetry = {}, onEdit = {}, onDelete = {}, onDeleteErrorShown = {}, onRetryItinerary = {},
            onEditItinerary = {}, onAddPlace = {}, onSelectPlace = {},
        )
    }

    @Composable
    private fun LargeFont(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) { content() }
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
