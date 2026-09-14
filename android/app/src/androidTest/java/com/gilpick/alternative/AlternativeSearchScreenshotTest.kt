package com.gilpick.alternative

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
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
import com.gilpick.place.PlaceCategory
import com.gilpick.ui.theme.GilpickTheme
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * #450 T046: 직접 검색 지도형 — 검색 전, 결과(방문 불가·선택 상태), 결과 없음, 오류, 칩·배지 켜짐(참고용), 360dp·글자 2.0배.
 * 검증이 아니라 기록이다. 지도는 SDK key가 없어 `primaryContainer` 자리 표시로 대신한다.
 * 저장 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/`.
 */
class AlternativeSearchScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 검색_전() = capture("alternative_search_idle") { Screen(AlternativeSearchUiState()) }

    @Test
    fun 결과_선택_상태() = capture("alternative_search_content") { Screen(content().copy(selectedPlaceId = "tourapi:126508")) }

    @Test
    fun 결과_360dp_최대_글자배율() = capture("alternative_search_content_360dp_fontscale2") {
        Narrow { Screen(content().copy(selectedPlaceId = "tourapi:126508")) }
    }

    @Test
    fun 결과_없음() = capture("alternative_search_empty") {
        Screen(AlternativeSearchUiState(query = "없는곳", committedQuery = "없는곳", phase = AlternativeSearchPhase.Empty))
    }

    @Test
    fun 오류() = capture("alternative_search_error") {
        Screen(AlternativeSearchUiState(query = "궁궐", committedQuery = "궁궐", phase = AlternativeSearchPhase.Failed(AlternativeError.Network)))
    }

    /** 참고용: 서버가 카테고리·반경·혼잡·마감 값을 내려줄 때의 모양. 현재 계약에서는 보이지 않는다. */
    @Test
    fun 칩_배지_켜짐_참고용() = capture("alternative_search_filters_reference") {
        val items = searchItems().mapIndexed { index, item ->
            when (index) {
                0 -> item.copy(crowded = true)
                1 -> item.copy(closesAt = "2026-09-14T11:00:00Z")
                else -> item
            }
        }
        Screen(
            content().copy(
                results = items,
                category = PlaceCategory.HISTORY_CULTURE,
                filters = AlternativeSearchFilters(
                    categories = listOf(PlaceCategory.HISTORY_CULTURE, PlaceCategory.FOOD, PlaceCategory.CAFE, PlaceCategory.SHOPPING, PlaceCategory.NATURE),
                    originName = "경복궁",
                    radiusMeters = 2000,
                ),
            ),
        )
    }

    @Test
    fun 칩_배지_켜짐_360dp_최대_글자배율_참고용() = capture("alternative_search_filters_reference_360dp_fontscale2") {
        Narrow {
            Screen(
                content().copy(
                    results = searchItems().mapIndexed { index, item -> if (index == 0) item.copy(crowded = true, closesAt = "2026-09-14T11:00:00Z") else item },
                    filters = AlternativeSearchFilters(categories = listOf(PlaceCategory.FOOD, PlaceCategory.CAFE), originName = "경복궁", radiusMeters = 2000),
                ),
            )
        }
    }

    private fun content() = AlternativeSearchUiState(query = "궁궐", committedQuery = "궁궐", results = searchItems(), phase = AlternativeSearchPhase.Content)

    @Composable
    private fun Screen(state: AlternativeSearchUiState) {
        AlternativeSearchScreen(
            state = state,
            onBack = {},
            onQueryChange = {},
            onClearQuery = {},
            onSearch = {},
            onRetry = {},
            onReauthenticate = {},
            onLoadMore = {},
            onRetryLoadMore = {},
            onSelect = {},
            map = { _, _, _, modifier -> Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer)) },
        )
    }

    /** 360dp 너비 + 최대 글자 배율(2.0). */
    @Composable
    private fun Narrow(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        Box(modifier = Modifier.width(360.dp)) {
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) { content() }
        }
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
