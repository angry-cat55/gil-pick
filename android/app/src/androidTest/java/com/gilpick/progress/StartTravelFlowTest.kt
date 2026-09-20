package com.gilpick.progress

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.itinerary.TransportMode
import com.gilpick.ui.theme.GilpickTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** #731: 위치 권한 유무와 관계없이 명세된 수동 시작 fallback이 이어지는지 검증한다. */
@RunWith(AndroidJUnit4::class)
class StartTravelFlowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 권한이_있으면_안내_없이_선택한_이동수단으로_시작한다() {
        val started = mutableListOf<Pair<StartMode, TransportMode?>>()
        setFlow(hasLocationPermission = true, started = started)

        composeRule.onNodeWithText("자동차").performClick()
        composeRule.onNodeWithText("시작하기").performClick()

        composeRule.onNodeWithText("나중에 하기").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(listOf(StartMode.MOVE_TO_FIRST to TransportMode.CAR), started)
        }
    }

    @Test
    fun 권한이_없어도_나중에_하기를_누르면_선택한_이동수단으로_시작한다() {
        val started = mutableListOf<Pair<StartMode, TransportMode?>>()
        setFlow(hasLocationPermission = false, started = started)

        composeRule.onNodeWithText("대중교통").performClick()
        composeRule.onNodeWithText("시작하기").performClick()
        composeRule.onNodeWithText("나중에 하기").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(StartMode.MOVE_TO_FIRST to TransportMode.TRANSIT), started)
        }
    }

    @Test
    fun 시스템_권한을_모두_거부해도_위치_없는_시작을_계속한다() {
        val started = mutableListOf<Pair<StartMode, TransportMode?>>()

        continueMoveToFirstAfterPermissionResult(
            result = mapOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION to false,
                android.Manifest.permission.ACCESS_COARSE_LOCATION to false,
            ),
            transport = TransportMode.WALK,
            onStart = { mode, transport -> started += mode to transport },
        )

        assertEquals(listOf(StartMode.MOVE_TO_FIRST to TransportMode.WALK), started)
    }

    private fun setFlow(
        hasLocationPermission: Boolean,
        started: MutableList<Pair<StartMode, TransportMode?>>,
    ) {
        composeRule.setContent {
            GilpickTheme {
                var open by remember { mutableStateOf(true) }
                StartTravelFlow(
                    open = open,
                    firstPlaceName = "경복궁",
                    onDismiss = { open = false },
                    onStart = { mode, transport -> started += mode to transport },
                    hasLocationPermission = { hasLocationPermission },
                )
            }
        }
    }
}
