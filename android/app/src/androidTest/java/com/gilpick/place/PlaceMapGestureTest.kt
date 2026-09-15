package com.gilpick.place

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.platform.app.InstrumentationRegistry
import com.gilpick.R
import com.gilpick.ui.theme.GilpickTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * #503: 장소 상세 130dp 지도 미리보기에서 지도를 움직일 수 있고, 화면 세로 스크롤도 여전히 되는지 확인한다.
 *
 * 실제 Naver 지도를 그리므로 `NCP_KEY_ID`와 network가 필요하다. 지도는 SurfaceView라 Compose 캡처에 찍히지 않아
 * 기기 화면(`UiAutomation`)을 찍어 지도 영역 픽셀이 바뀌었는지 본다. 증빙 PNG를 함께 남긴다.
 * 저장 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/`.
 */
class PlaceMapGestureTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun 지도_위에서_끌면_지도가_움직이고_화면은_스크롤되지_않는다() {
        composeRule.setContent {
            GilpickTheme {
                PlaceDetailScreen(state = state(), onBack = {}, onRetry = {}, onReauthenticate = {})
            }
        }
        val map = composeRule.onNodeWithContentDescription(instrumentation.targetContext.getString(R.string.place_map_description, NAME))
        awaitMapTiles()

        val mapTop = map.getUnclippedBoundsInRoot().top
        val before = screen("place_map_gesture_before")

        // 지도 위에서 끌면 지도가 움직이고 화면은 스크롤되지 않는다.
        map.performTouchInput { swipeLeft() }
        map.performTouchInput { swipeUp() }
        awaitMapTiles()
        val after = screen("place_map_gesture_after")

        assertEquals("지도 위 제스처가 화면을 스크롤했다", mapTop, map.getUnclippedBoundsInRoot().top)
        assertNotEquals("지도가 움직이지 않았다", mapPixels(before, map.getUnclippedBoundsInRoot()), mapPixels(after, map.getUnclippedBoundsInRoot()))
    }

    /** 글자 2.0배로 정보 영역을 스크롤할 만큼 길게 만든 뒤, 지도 밖(주소 행)에서 끌면 화면이 스크롤된다. */
    @Test
    fun 지도_밖에서_끌면_화면이_여전히_스크롤된다() {
        composeRule.setContent {
            GilpickTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                    PlaceDetailScreen(state = state(), onBack = {}, onRetry = {}, onReauthenticate = {})
                }
            }
        }
        val map = composeRule.onNodeWithContentDescription(instrumentation.targetContext.getString(R.string.place_map_description, NAME))
        composeRule.waitForIdle()
        val mapTop = map.getUnclippedBoundsInRoot().top

        // hero는 고정이고 그 아래 정보 영역만 스크롤된다. hero와 정보 행에 주소가 둘 있어 스크롤 영역 쪽(마지막)을 민다.
        composeRule.onAllNodesWithText(ADDRESS).onLast().performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        assertTrue("지도 밖 제스처가 화면을 스크롤하지 않았다", map.getUnclippedBoundsInRoot().top < mapTop)
    }

    private fun state() = PlaceDetailUiState(
        PlaceDetailPhase.Content(
            testPlace(
                "tourapi:1",
                name = NAME,
                address = ADDRESS,
                description = "조선 왕조 제일의 법궁으로 1395년에 창건됐습니다. ".repeat(12).trim(),
                phone = "02-3700-3900",
                // 정보 영역이 화면보다 길어 스크롤할 수 있도록 운영시간 행을 채운다.
                regularOpeningHours = listOf("월", "화", "수", "목", "금", "토", "일").map { "${it}요일: 오전 9:00~오후 6:00" },
                latitude = 37.5796,
                longitude = 126.9770,
            ),
        ),
    )

    /** 지도 tile이 그려질 시간을 준다. SDK가 끝났다는 신호를 Compose에 주지 않아 시간으로 기다린다. */
    private fun awaitMapTiles() {
        composeRule.waitForIdle()
        Thread.sleep(TILE_WAIT_MILLIS)
    }

    private fun screen(name: String): Bitmap {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    /**
     * 기기 화면에서 지도 영역만 잘라 픽셀을 비교할 값으로 만든다. Compose root와 기기 화면의 원점 차이(상태 표시줄)는
     * 두 번 모두 같으므로 비교에는 영향이 없다. 가장자리 8dp는 그림자·곡률을 피하려고 뺀다.
     */
    private fun mapPixels(screen: Bitmap, bounds: DpRect): List<Int> {
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val inset = (8.dp.value * density).toInt()
        val left = (bounds.left.value * density).toInt() + inset
        val top = (bounds.top.value * density).toInt() + inset
        val width = (bounds.width.value * density).toInt() - inset * 2
        val height = (bounds.height.value * density).toInt() - inset * 2
        val pixels = IntArray(width * height)
        screen.getPixels(pixels, 0, width, left, top, width, height)
        return pixels.toList()
    }

    private companion object {
        const val NAME = "경복궁"
        const val ADDRESS = "서울특별시 종로구 사직로 161"
        const val TILE_WAIT_MILLIS = 4_000L
    }
}
