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
import androidx.compose.ui.test.isDialog
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
 * #443: 새 여행·여행 수정 화면 screenshot 기록(Figma `CreateTripScreen`·`EditTripScreen` 대조용).
 *
 * 검증이 아니라 기록이다. 저장 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/`.
 */
class TripFormScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 새_여행_기본() = capture("trip_form_create") { Screen(created()) }

    @Test
    fun 새_여행_오류() = capture("trip_form_create_error") {
        Screen(TripFormUiState(name = "A", startDate = LocalDate.of(2026, 9, 1), endDate = LocalDate.of(2026, 9, 10)))
    }

    @Test
    fun 새_여행_제출_중() = capture("trip_form_create_submitting") { Screen(created().copy(submitting = true)) }

    @Test
    fun 새_여행_360dp_최대_글자배율() = capture("trip_form_create_360dp_fontscale2") {
        Box(modifier = Modifier.width(360.dp)) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) { Screen(created()) }
        }
    }

    @Test
    fun 여행_수정_기간_축소() = capture("trip_form_edit_shrunk") { Screen(edited().copy(endDate = LocalDate.of(2026, 9, 2))) }

    @Test
    fun 기간_축소_확인_대화상자() {
        composeRule.setContent { GilpickTheme { Screen(edited().copy(endDate = LocalDate.of(2026, 9, 2), deleteConfirmation = 2)) } }
        composeRule.waitForIdle()
        save("trip_form_shrink_dialog", composeRule.onNode(isDialog()).captureToImage().asAndroidBitmap())
    }

    private fun created() = TripFormUiState(name = "서울 자유여행", startDate = LocalDate.of(2026, 9, 12), endDate = LocalDate.of(2026, 9, 16))

    private fun edited() = TripFormUiState(
        name = "서울 자유여행",
        startDate = LocalDate.of(2026, 9, 1),
        endDate = LocalDate.of(2026, 9, 3),
        originalStartDate = LocalDate.of(2026, 9, 1),
        originalEndDate = LocalDate.of(2026, 9, 3),
        mode = FormMode.Edit(tripId = "t1", version = 1, status = TripStatus.UPCOMING),
    )

    @Composable
    private fun Screen(state: TripFormUiState) {
        TripFormScreen(state = state, onNameChange = {}, onPeriodChange = { _, _ -> }, onSubmit = {})
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent { GilpickTheme { content() } }
        composeRule.waitForIdle()
        save(name, composeRule.onRoot().captureToImage().asAndroidBitmap())
    }

    private fun save(name: String, bitmap: Bitmap) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
