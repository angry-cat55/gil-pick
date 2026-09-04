package com.gilpick.place

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.gilpick.ui.theme.GilpickTheme
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * T025: 상세 화면의 상태별 screenshot 증빙.
 *
 * 검증이 아니라 기록이다. 각 상태를 그려 기기 저장소에 PNG로 남기고 `adb pull`로 꺼내
 * 사람이 확인한다. 최대 글자 배율(2.0)은 `LocalDensity`의 fontScale로 흉내 낸다.
 *
 * 저장 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/`.
 * `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`로 실행해야 파일이 남는다.
 */
class PlaceDetailScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 상세_content() = capture("detail_content") { Screen(detailState()) }

    @Test
    fun 상세_content_정보_누락() = capture("detail_missing") {
        Screen(PlaceDetailUiState(PlaceDetailPhase.Content(testPlace("tourapi:2", name = "정보가 적은 장소", imageUrl = null))))
    }

    @Test
    fun 상세_content_긴_설명_최대_글자배율() = capture("detail_content_fontscale2") {
        LargeFont { Screen(detailState()) }
    }

    @Test
    fun 상세_not_found() = capture("detail_not_found") { Screen(PlaceDetailUiState(PlaceDetailPhase.NotFound)) }

    @Test
    fun 상세_error() = capture("detail_error") {
        Screen(PlaceDetailUiState(PlaceDetailPhase.Failed(PlaceError(PlaceErrorKind.RATE_LIMITED, retryable = false))))
    }

    @Test
    fun 상세_loading() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { GilpickTheme { Screen(PlaceDetailUiState(PlaceDetailPhase.Loading)) } }
        composeRule.mainClock.advanceTimeBy(1_200)
        save("detail_loading")
    }

    private fun detailState() = PlaceDetailUiState(
        PlaceDetailPhase.Content(
            testPlace(
                "tourapi:1",
                name = "경복궁",
                address = "서울특별시 종로구 사직로 161",
                description = "조선 왕조 제일의 법궁으로 1395년에 창건됐습니다. 근정전과 경회루를 중심으로 궁궐 건축의 원형을 볼 수 있고, 수문장 교대식은 매시 정각에 열립니다. ".repeat(2).trim(),
                phone = "02-3700-3900",
                operatingGuide = "이용시간: 09:00~18:00 · 휴무일: 매주 화요일",
                rating = 4.6,
                userRatingCount = 12450,
                businessStatus = PlaceBusinessStatus.OPERATIONAL,
                regularOpeningHours = listOf("월요일: 오전 9:00~오후 6:00", "화요일: 휴무", "수요일: 오전 9:00~오후 6:00"),
                googleAttributions = listOf("Google"),
            ),
        ),
    )

    @Composable
    private fun Screen(state: PlaceDetailUiState) {
        PlaceDetailScreen(state = state, onBack = {}, onRetry = {}, onReauthenticate = {})
    }

    /** 시스템 글자 확대 최대 배율(2.0)을 흉내 낸다. */
    @Composable
    private fun LargeFont(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
            content()
        }
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
