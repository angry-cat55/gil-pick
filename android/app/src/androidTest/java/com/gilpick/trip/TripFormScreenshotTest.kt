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

    /** #497: 시작·중간·종료 범위 띠가 원 위아래로 튀어나오지 않는지 기록한다(9/12 토요일 → 주가 바뀌는 범위). */
    @Test
    fun 달력_범위_선택() = capture("trip_form_calendar_range") { Screen(created()) }

    /** #497: 시작일만 고른 상태. 범위 띠가 없다. */
    @Test
    fun 달력_단일_선택() = capture("trip_form_calendar_single") { Screen(created().copy(endDate = null)) }

    /** #497: 시작일=종료일(당일 여행). 범위 띠가 없다. */
    @Test
    fun 달력_시작_종료_같은_날() = capture("trip_form_calendar_same_day") { Screen(created().copy(endDate = LocalDate.of(2026, 9, 12))) }

    /** #501: 다른 여행이 차지한 9/20~9/22는 흐리게 비활성으로 보인다. */
    @Test
    fun 달력_다른_여행_기간() = capture("trip_form_calendar_occupied") {
        Screen(created().copy(occupiedPeriods = listOf(OccupiedPeriod("t9", LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 22)))))
    }

    /** #499: 고른 사진이 커버에 보이고 `사진 변경`·`기본으로`가 나온다. */
    @Test
    fun 커버_사진_선택() = capture("trip_form_cover_picked") {
        Screen(created().copy(pickedImage = PickedTripImage(sampleImage(), "image/png")))
    }

    /** #499: 수정 화면에서 저장된 사진이 있을 때(`커스텀 이미지`). */
    @Test
    fun 수정_커버_커스텀() = capture("trip_form_edit_cover_custom") {
        Screen(edited().copy(imageUrl = "http://api.example/trips/t1/image/content", currentImage = sampleImage()))
    }

    /** #499: 360dp·최대 글자 배율에서 커버 라벨·버튼이 잘리지 않는지 기록한다. */
    @Test
    fun 수정_커버_360dp_최대_글자배율() = capture("trip_form_edit_cover_360dp_fontscale2") {
        Box(modifier = Modifier.width(360.dp)) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                Screen(edited().copy(imageUrl = "http://api.example/trips/t1/image/content", currentImage = sampleImage()))
            }
        }
    }

    /** 대각선 그라데이션 PNG. 실제 사진 대신 커버 배치를 보기 위한 표본이다. */
    private fun sampleImage(): ByteArray {
        val bitmap = Bitmap.createBitmap(390, 180, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 390f, 180f, 0xFF3B7BF8.toInt(), 0xFF10B981.toInt(), android.graphics.Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, 390f, 180f, paint)
        return java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
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
