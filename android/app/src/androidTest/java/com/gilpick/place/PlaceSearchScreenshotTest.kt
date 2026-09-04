package com.gilpick.place

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
import org.junit.Rule
import org.junit.Test

/**
 * T018: 검색 화면의 상태별 screenshot 증빙.
 *
 * 검증이 아니라 기록이다. 각 상태를 그려 기기 저장소에 PNG로 남기고 `adb pull`로 꺼내
 * 사람이 확인한다. 최대 글자 배율(2.0)은 `LocalDensity`의 fontScale로, 360dp 너비는
 * 고정 폭 상자로 흉내 낸다.
 *
 * 저장 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/`.
 * `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`로 실행해야 파일이 남는다.
 */
class PlaceSearchScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 검색_idle() = capture("search_idle") { Screen(PlaceSearchUiState()) }

    @Test
    fun 검색_content() = capture("search_content") { Screen(contentState()) }

    @Test
    fun 검색_content_긴_이름_이미지_누락() = capture("search_content_long_missing") {
        Screen(
            contentState(
                testPlace("tourapi:9", name = "아주 긴 이름을 가진 장소 이름을 가진 장소 이름을 가진 장소 이름을 가진 장소", imageUrl = null),
            ),
        )
    }

    @Test
    fun 검색_content_최대_글자배율() = capture("search_content_fontscale2") { LargeFont { Screen(contentState()) } }

    @Test
    fun 검색_content_360dp() = capture("search_content_360dp") {
        Box(modifier = Modifier.width(360.dp)) { Screen(contentState()) }
    }

    @Test
    fun 검색_empty() = capture("search_empty") {
        Screen(PlaceSearchUiState(query = "없는곳", committedQuery = "없는곳", phase = PlaceSearchPhase.Empty))
    }

    @Test
    fun 검색_error() = capture("search_error") {
        Screen(PlaceSearchUiState(query = "경복궁", phase = PlaceSearchPhase.Failed(PlaceError(PlaceErrorKind.TIMEOUT, retryable = true))))
    }

    @Test
    fun 검색_invalid() = capture("search_invalid") {
        Screen(PlaceSearchUiState(query = "궁", phase = PlaceSearchPhase.Invalid(InvalidReason.TOO_SHORT)))
    }

    @Test
    fun 검색_loading() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { GilpickTheme { Screen(PlaceSearchUiState(query = "경복궁", phase = PlaceSearchPhase.Loading)) } }
        composeRule.mainClock.advanceTimeBy(1_200)
        save("search_loading")
    }

    @Test
    fun 검색_추가_조회_실패() = capture("search_load_more_failed") {
        Screen(contentState().copy(loadMoreError = PlaceError(PlaceErrorKind.TIMEOUT, retryable = true)))
    }

    @Test
    fun 검색_error_호출_제한() = capture("search_error_rate_limited") {
        Screen(PlaceSearchUiState(query = "경복궁", phase = PlaceSearchPhase.Failed(PlaceError(PlaceErrorKind.RATE_LIMITED, retryable = false))))
    }

    @Test
    fun 검색_error_인증_만료() = capture("search_error_session_expired") {
        Screen(PlaceSearchUiState(query = "경복궁", phase = PlaceSearchPhase.Failed(PlaceError(PlaceErrorKind.SESSION_EXPIRED, retryable = false))))
    }

    private fun contentState(vararg extra: PlaceDto) = PlaceSearchUiState(
        query = "고궁",
        category = PlaceCategory.HISTORY_CULTURE,
        committedQuery = "고궁",
        committedCategory = PlaceCategory.HISTORY_CULTURE,
        results = listOf(
            testPlace("tourapi:1", name = "경복궁", rating = 4.8, businessStatus = PlaceBusinessStatus.OPERATIONAL, imageUrl = IMAGE),
            testPlace("tourapi:2", name = "북촌 한옥마을", rating = 4.6, businessStatus = PlaceBusinessStatus.OPERATIONAL, imageUrl = IMAGE),
            testPlace("tourapi:3", name = "남산서울타워", category = PlaceCategory.NATURE, rating = 4.7, imageUrl = null),
            testPlace("tourapi:4", name = "창덕궁", rating = 4.5, businessStatus = PlaceBusinessStatus.CLOSED_TEMPORARILY, imageUrl = IMAGE),
            testPlace("google:5", name = "인사동 거리", category = PlaceCategory.SHOPPING, rating = 4.3, imageUrl = IMAGE),
        ) + extra,
        phase = PlaceSearchPhase.Content,
    )

    @Composable
    private fun Screen(state: PlaceSearchUiState) {
        PlaceSearchScreen(
            state = state,
            onBack = {},
            onQueryChange = {},
            onClearQuery = {},
            onCategoryChange = {},
            onSearch = {},
            onRetry = {},
            onReauthenticate = {},
            onLoadMore = {},
            onRetryLoadMore = {},
            onSearchByCategory = {},
            onPlaceClick = {},
        )
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

    private companion object {
        const val IMAGE = "https://images.unsplash.com/photo-1583417319070-4a69db38a482?w=160&h=160&fit=crop&auto=format"
    }
}
