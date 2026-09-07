package com.gilpick.route

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
import com.gilpick.itinerary.ItemStatus
import com.gilpick.itinerary.ItineraryItemDto
import com.gilpick.itinerary.ItineraryPlaceDto
import com.gilpick.itinerary.RouteStatus
import com.gilpick.itinerary.StaySource
import com.gilpick.itinerary.TransportMode
import com.gilpick.place.PlaceCategory
import com.gilpick.trip.DayRoutePhase
import com.gilpick.trip.ItineraryOverviewPhase
import com.gilpick.trip.TripDetailPhase
import com.gilpick.trip.TripDetailScreen
import com.gilpick.trip.TripDetailUiState
import com.gilpick.trip.TripDto
import com.gilpick.trip.TripStatus
import com.gilpick.ui.theme.GilpickTheme
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * T032: 여행 상세 경로 영역(READY 요약·FAILED 재시도·계산 중)의 screenshot 증빙.
 *
 * 검증이 아니라 기록이다. 360dp·최대 글자 배율에서 실패 원인과 `다시 시도`가 잘리지 않는지 사람이 본다.
 * 저장 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/`.
 */
class TripDetailRouteScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 상세_경로_요약_실패_계산중() = capture("trip_detail_route_states") { Screen() }

    @Test
    fun 상세_경로_360dp_최대_글자배율() = capture("trip_detail_route_states_360dp_fontscale2") {
        Box(modifier = Modifier.width(360.dp)) { LargeFont { Screen() } }
    }

    @Composable
    private fun Screen() {
        val days = listOf(
            day("2026-09-01", 1, listOf(item("경복궁", 1, TransportMode.WALK), item("북촌한옥마을", 2, TransportMode.TRANSIT), item("인사동거리", 3))),
            day("2026-09-02", 2, listOf(item("창덕궁", 1, TransportMode.WALK), item("종묘", 2))).copy(routeStatus = RouteStatus.FAILED),
            day("2026-09-03", 3, listOf(item("남산서울타워", 1))).copy(routeStatus = RouteStatus.FAILED),
        )
        TripDetailScreen(
            state = TripDetailUiState(
                phase = TripDetailPhase.Content(TripDto("t1", "서울 여행", "2026-09-01", "2026-09-03", TripStatus.UPCOMING, 3, 1)),
                itinerary = ItineraryOverviewPhase.Content(days),
            ),
            onBack = {}, onRetry = {}, onEdit = {}, onDelete = {}, onDeleteErrorShown = {}, onRetryItinerary = {},
            onEditItinerary = {}, onAddPlace = {}, onSelectPlace = {},
            routes = mapOf(
                "2026-09-01" to DayRoutePhase.Ready(readyRoute(scheduleVersion = 1).copy(markers = readyRoute().markers.mapIndexed { i, m -> m.copy(itemId = "item-${i + 1}") }, segments = readyRoute().segments.mapIndexed { i, s -> s.copy(fromItemId = "item-${i + 1}", toItemId = "item-${i + 2}") })),
                "2026-09-02" to DayRoutePhase.Failed(routeFailure(RouteFailureCodes.PROVIDER_TIMEOUT), requestError = RouteError.Network),
                "2026-09-03" to DayRoutePhase.Calculating,
            ),
        )
    }

    private fun day(date: String, dayNumber: Int, items: List<ItineraryItemDto>) =
        DayItineraryDto(date = date, dayNumber = dayNumber, version = 1, routeStatus = RouteStatus.READY, items = items)

    private fun item(name: String, sequence: Int, toNext: TransportMode? = null) = ItineraryItemDto(
        itemId = "item-$sequence",
        place = ItineraryPlaceDto("place-$sequence", name, PlaceCategory.HISTORY_CULTURE, null, null),
        sequence = sequence, plannedStayMinutes = 90, staySource = StaySource.RECOMMENDED,
        transportModeToNext = toNext, status = ItemStatus.PLANNED,
    )

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
