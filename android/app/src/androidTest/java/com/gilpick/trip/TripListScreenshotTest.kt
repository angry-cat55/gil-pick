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
import com.gilpick.ui.theme.GilpickTheme
import java.io.File
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

/**
 * #441: 내 여행 화면 screenshot 기록(Figma `MyTripsScreen` 대조용).
 *
 * 검증이 아니라 기록이다. 저장 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/`.
 */
class TripListScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 세_그룹_content() = capture("trips_content") { Screen(content()) }

    @Test
    fun 세_그룹_content_360dp_최대_글자배율() = capture("trips_content_360dp_fontscale2") {
        Box(modifier = Modifier.width(360.dp)) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) { Screen(content()) }
        }
    }

    @Test
    fun 여행_없음() = capture("trips_empty") { Screen(TripListUiState(phase = TripListPhase.Empty)) }

    @Test
    fun 검색_결과_없음() = capture("trips_no_results") {
        Screen(TripListUiState(phase = TripListPhase.Empty, query = "부산", statusFilter = TripStatus.UPCOMING))
    }

    @Test
    fun 오류() = capture("trips_error") { Screen(TripListUiState(phase = TripListPhase.Failed(TripListError.NETWORK))) }

    private fun content(): TripListUiState {
        val today = LocalDate.now(KST)
        fun trip(id: String, name: String, start: LocalDate, days: Int, status: TripStatus) =
            TripDto(id, name, start.toString(), start.plusDays(days - 1L).toString(), status, days, 1)
        return TripListUiState(
            phase = TripListPhase.Content,
            trips = listOf(
                trip("t1", "서울 자유여행", today.minusDays(1), 5, TripStatus.IN_PROGRESS),
                trip("t2", "북촌·인사동 탐방", today.plusDays(7), 5, TripStatus.UPCOMING),
                trip("t3", "남산 단기 여행", today.plusDays(56), 1, TripStatus.UPCOMING),
                trip("t4", "한강 나들이", today.minusYears(1), 7, TripStatus.COMPLETED),
            ),
        )
    }

    @Composable
    private fun Screen(state: TripListUiState) {
        TripListScreen(
            state = state,
            onQueryChange = {},
            onStatusFilterChange = {},
            onRetry = {},
            onLoadMore = {},
            onCreateTrip = {},
            onTripClick = {},
        )
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
