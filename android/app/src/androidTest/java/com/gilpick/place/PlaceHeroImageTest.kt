package com.gilpick.place

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import com.gilpick.ui.theme.GilpickTheme
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * #515: 장소 상세 hero 사진의 정상·URL 없음·로딩 실패 세 상태.
 *
 * 화면(대체 아이콘)과 화면 낭독기 설명이 같은 상태를 말하는지 본다. 정상 사진은 network 없이 기기에 쓴 PNG를
 * `file://`로, 실패는 닿지 않는 주소로 만든다. 각 상태 screenshot도 남긴다.
 * 저장 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/`.
 */
class PlaceHeroImageTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 사진을_받으면_사진_설명만_주고_대체_표현을_치운다() {
        setHero(imageUrl = samplePngUri())
        awaitDescription("$NAME 대표 사진")

        composeRule.onNodeWithTag(TAG_HERO_IMAGE_FALLBACK).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("대표 사진을 불러오지 못했어요").assertDoesNotExist()
        save("detail_hero_image_loaded")
    }

    @Test
    fun 사진_주소가_없으면_대체_표현과_사진_없음을_알린다() {
        setHero(imageUrl = null)

        composeRule.onNodeWithTag(TAG_HERO_IMAGE_FALLBACK).assertExists()
        composeRule.onNodeWithContentDescription("대표 사진 없음").assertExists()
        composeRule.onNodeWithContentDescription("$NAME 대표 사진").assertDoesNotExist()
        save("detail_hero_image_missing")
    }

    @Test
    fun 사진을_받지_못하면_대체_표현과_불러오지_못함을_알리고_사진_설명은_주지_않는다() {
        // 닫힌 local port라 연결이 곧바로 거절된다.
        setHero(imageUrl = "http://127.0.0.1:9/missing.png")
        awaitDescription("대표 사진을 불러오지 못했어요")

        composeRule.onNodeWithTag(TAG_HERO_IMAGE_FALLBACK).assertExists()
        composeRule.onNodeWithContentDescription("$NAME 대표 사진").assertDoesNotExist()
        save("detail_hero_image_failed")
    }

    private fun setHero(imageUrl: String?) {
        composeRule.setContent {
            GilpickTheme {
                PlaceDetailScreen(
                    state = PlaceDetailUiState(PlaceDetailPhase.Content(testPlace("tourapi:1", name = NAME, imageUrl = imageUrl))),
                    onBack = {},
                    onRetry = {},
                    onReauthenticate = {},
                )
            }
        }
    }

    /** Coil은 background에서 사진을 받아 Compose idle만으로는 끝을 알 수 없어 설명이 나타날 때까지 기다린다. */
    private fun awaitDescription(description: String) {
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** 대각선 그라데이션 PNG를 기기 cache에 쓰고 `file://` 주소를 돌려준다. */
    private fun samplePngUri(): String {
        val bitmap = Bitmap.createBitmap(390, 240, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 390f, 240f, 0xFF10B981.toInt(), 0xFF3B7BF8.toInt(), android.graphics.Shader.TileMode.CLAMP)
        }
        android.graphics.Canvas(bitmap).drawRect(0f, 0f, 390f, 240f, paint)
        val file = File(context.cacheDir, "place-hero-sample.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "file://${file.absolutePath}"
    }

    private fun save(name: String) {
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val NAME = "경복궁"
        const val WAIT_MILLIS = 5_000L
    }
}
