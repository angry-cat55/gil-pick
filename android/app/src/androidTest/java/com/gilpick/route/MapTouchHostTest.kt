package com.gilpick.route

import android.content.Context
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 스크롤 화면 안에 놓인 지도가 제스처를 끝까지 받는지 본다(#706).
 *
 * 실제 `MapView`는 지도 인증 key와 화면이 필요하므로, 터치를 소비하는 View로 자리를 대신한다. 확인하려는 것은
 * [MapTouchHost]가 Compose 스크롤 조상의 가로채기를 막느냐이므로 지도 자체는 필요 없다.
 */
class MapTouchHostTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var scroll: ScrollState

    @Test
    fun 지도_위_드래그는_화면을_스크롤하지_않는다() {
        setContentWithMap()

        compose.onNodeWithTag("map").performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertEquals(0, scroll.value)
    }

    @Test
    fun 지도_밖_드래그는_화면을_스크롤한다() {
        setContentWithMap()

        compose.onNodeWithTag("above").performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertTrue(scroll.value > 0)
    }

    private fun setContentWithMap() {
        compose.setContent {
            scroll = rememberScrollState()
            Column(modifier = Modifier.fillMaxSize().verticalScroll(scroll)) {
                Spacer(modifier = Modifier.fillMaxWidth().height(300.dp).testTag("above"))
                AndroidView(
                    factory = { context -> MapTouchHost(context).apply { addView(TouchConsumingView(context)) } },
                    modifier = Modifier.fillMaxWidth().height(200.dp).testTag("map"),
                )
                Spacer(modifier = Modifier.fillMaxWidth().height(2000.dp))
            }
        }
    }

    /** 지도처럼 자기에게 온 터치를 전부 가져가는 View. */
    private class TouchConsumingView(context: Context) : View(context) {
        override fun onTouchEvent(event: MotionEvent): Boolean = true
    }
}
