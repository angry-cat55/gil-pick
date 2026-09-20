package com.gilpick.progress

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.itinerary.TransportMode
import com.gilpick.ui.theme.GilpickTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** #731: 위치 권한이 없으면 `첫 장소로 이동하기`를 잠그고 안내하되, 현장 시작은 권한과 무관하게 이어지는지 검증한다. */
@RunWith(AndroidJUnit4::class)
class StartTravelFlowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 권한이_있으면_안내_없이_선택한_이동수단으로_시작한다() {
        val started = mutableListOf<Pair<StartMode, TransportMode?>>()
        setFlow(hasLocationPermission = true, started = started)

        composeRule.onNode(hasStateDescription("위치 권한 필요")).assertDoesNotExist()
        composeRule.onNodeWithText("자동차").performClick()
        composeRule.onNodeWithText("시작하기").performClick()

        composeRule.onNodeWithText(NOTICE).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(listOf(StartMode.MOVE_TO_FIRST to TransportMode.CAR), started)
        }
    }

    @Test
    fun 권한이_없으면_첫_장소로_이동하기는_잠기고_누르면_안내가_뜨며_선택되지_않는다() {
        val started = mutableListOf<Pair<StartMode, TransportMode?>>()
        setFlow(hasLocationPermission = false, started = started)

        composeRule.onNode(hasStateDescription("위치 권한 필요")).assertIsDisplayed()
        composeRule.onNodeWithText("첫 장소까지 이동 수단").assertDoesNotExist()
        composeRule.onNodeWithText("첫 장소로 이동하기").performClick()

        composeRule.onNodeWithText(NOTICE).assertIsDisplayed()
        composeRule.onNodeWithText("첫 장소까지 이동 수단").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(emptyList<Pair<StartMode, TransportMode?>>(), started) }
    }

    @Test
    fun 안내를_닫으면_시트로_돌아와_첫_장소에서_시작하기로_시작할_수_있다() {
        val started = mutableListOf<Pair<StartMode, TransportMode?>>()
        setFlow(hasLocationPermission = false, started = started)

        composeRule.onNodeWithText("첫 장소로 이동하기").performClick()
        composeRule.onNodeWithText("닫기").performClick()

        composeRule.onNodeWithText(NOTICE).assertDoesNotExist()
        composeRule.onNodeWithTag(TAG_START_MODE_SHEET).assertIsDisplayed()
        composeRule.onNodeWithText("시작하기").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf<Pair<StartMode, TransportMode?>>(StartMode.AT_FIRST_PLACE to null), started)
        }
    }

    @Test
    fun 안내에서_허용하기를_누르면_권한_안내_화면이_뜨고_나중에_하기는_시작하지_않고_시트로_돌아온다() {
        val started = mutableListOf<Pair<StartMode, TransportMode?>>()
        setFlow(hasLocationPermission = false, started = started)

        composeRule.onNodeWithText("첫 장소로 이동하기").performClick()
        composeRule.onNodeWithText("위치 권한 허용하기").performClick()

        composeRule.onNodeWithText("위치 권한이 필요해요").assertIsDisplayed()
        composeRule.onNodeWithText("나중에 하기").performClick()

        composeRule.onNodeWithText("위치 권한이 필요해요").assertDoesNotExist()
        composeRule.onNodeWithTag(TAG_START_MODE_SHEET).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(emptyList<Pair<StartMode, TransportMode?>>(), started) }
    }

    @Test
    fun 권한이_없어도_첫_장소에서_시작하기는_그대로_시작한다() {
        val started = mutableListOf<Pair<StartMode, TransportMode?>>()
        setFlow(hasLocationPermission = false, started = started)

        composeRule.onNodeWithText("첫 장소에서 시작하기").performClick()
        composeRule.onNodeWithText("시작하기").performClick()

        composeRule.onNodeWithText(NOTICE).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(listOf<Pair<StartMode, TransportMode?>>(StartMode.AT_FIRST_PLACE to null), started)
        }
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

    private companion object {
        const val NOTICE = "위치 기반 서비스 승인 후,\n사용해주세요"
    }
}
