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
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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

    /** #442: hero·비활성 배너가 360dp·최대 글자 배율에서 잘리지 않는지 기록한다. */
    @Test
    fun 기간_밖_360dp_최대_글자배율() = capture("trip_detail_start_not_travel_day_360dp_fontscale2") {
        Box(modifier = Modifier.width(360.dp)) { LargeFont { Screen(TripStartPhase.NotTravelDay) } }
    }

    /** #442: hero 더보기 메뉴 카드(popup 창)를 찍는다. */
    @Test
    fun 더보기_메뉴() {
        composeRule.setContent { GilpickTheme { Screen(TripStartPhase.Ready("2026-09-02", 0)) } }
        composeRule.onNodeWithContentDescription("더보기").performClick()
        composeRule.waitForIdle()
        save("trip_detail_menu", composeRule.onNode(isPopup()).captureToImage().asAndroidBitmap())
    }

    /** #442: 삭제 확인 다이얼로그(dialog 창)를 찍는다. */
    @Test
    fun 삭제_확인() {
        composeRule.setContent { GilpickTheme { Screen(TripStartPhase.Ready("2026-09-02", 0)) } }
        composeRule.onNodeWithContentDescription("더보기").performClick()
        composeRule.onNodeWithText("여행 삭제").performClick()
        composeRule.waitForIdle()
        save("trip_detail_delete_dialog", composeRule.onNode(isDialog()).captureToImage().asAndroidBitmap())
    }

    /** #442: 여행 조회 실패 — 빈 hero + 뒤로 가기와 기존 오류 안내. */
    @Test
    fun 조회_오류() = capture("trip_detail_error") { Screen(TripStartPhase.Loading, phase = TripDetailPhase.Failed(TripDetailError.NETWORK)) }

    /** #446: 권한 없음은 재시도 없이 `목록으로 돌아가기`만 주버튼인 공통 오류 화면이다. */
    @Test
    fun 조회_권한_없음() = capture("trip_detail_error_forbidden") { Screen(TripStartPhase.Loading, phase = TripDetailPhase.Failed(TripDetailError.FORBIDDEN)) }

    /** #500: 대표 이미지가 있으면 hero 배경에 그리고 gradient 위로 흰 글자·버튼이 읽힌다. */
    @Test
    fun hero_이미지_있음() = capture("trip_detail_hero_image") { Screen(TripStartPhase.Ready("2026-09-02", 0), heroImage = sampleImage()) }

    /** #500: 대표 이미지가 없으면 `faint` 대체 배경이다. */
    @Test
    fun hero_이미지_없음() = capture("trip_detail_hero_no_image") { Screen(TripStartPhase.Ready("2026-09-02", 0)) }

    /** #500: `imageUrl`은 있지만 원본을 받지 못했다. 대체 배경을 그대로 보인다. */
    @Test
    fun hero_이미지_로드_실패() = capture("trip_detail_hero_image_failed") {
        Screen(TripStartPhase.Ready("2026-09-02", 0), imageUrl = "http://api.example/trips/t1/image/content")
    }

    /** 대각선 그라데이션 PNG. 실제 사진 대신 hero 배치를 보기 위한 표본이다. */
    private fun sampleImage(): ByteArray {
        val bitmap = Bitmap.createBitmap(390, 180, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 390f, 180f, 0xFFF59E0B.toInt(), 0xFF3B7BF8.toInt(), android.graphics.Shader.TileMode.CLAMP)
        }
        android.graphics.Canvas(bitmap).drawRect(0f, 0f, 390f, 180f, paint)
        return java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Composable
    private fun Screen(
        start: TripStartPhase,
        phase: TripDetailPhase? = null,
        heroImage: ByteArray? = null,
        imageUrl: String? = null,
    ) {
        val days = (1..3).map { DayItineraryDto("2026-09-0$it", it, 0, RouteStatus.NOT_CALCULATED, emptyList()) }
        TripDetailScreen(
            state = TripDetailUiState(
                phase = phase ?: TripDetailPhase.Content(TripDto("t1", "서울 여행", "2026-09-01", "2026-09-03", TripStatus.IN_PROGRESS, 3, 1, imageUrl = imageUrl)),
                itinerary = ItineraryOverviewPhase.Content(days),
                start = start,
                heroImage = heroImage,
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
        save(name, composeRule.onRoot().captureToImage().asAndroidBitmap())
    }

    private fun save(name: String, bitmap: Bitmap) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
