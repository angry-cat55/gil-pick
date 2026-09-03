package com.gilpick.place

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.gilpick.ui.theme.GilpickTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * T009: 장소 route 등록과 검색 → 상세 → 뒤로가기 navigation 검증.
 *
 * app navigation graph 전체가 아니라 [placeGraph]만 host하는 NavHost로 검증한다.
 * F002 여행 상세(#154)가 `MainActivity.kt`의 NavHost를 만드는 중이라 그 파일을
 * 건드리지 않기로 T035에서 조율했다. #154 반영 후 rebase해 app graph에
 * `placeGraph(navController)`를 등록하면 이 동작이 그대로 이어진다.
 *
 * 화면은 아직 임시다. 이 test가 검증하는 것은 route 등록, route 인자 전달, back
 * stack 동작이며 화면 내용은 T012·T013에서 실제 화면으로 교체된다.
 */
class PlaceNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

    @Test
    fun 검색에서_상세로_이동하고_뒤로_돌아온다() {
        composeRule.setContent { PlaceNavHostUnderTest() }

        composeRule.onNodeWithText("장소 검색").assertIsDisplayed()

        composeRule.onNodeWithText("첫 번째 결과 열기").performClick()
        composeRule.onNodeWithText("장소 상세").assertIsDisplayed()

        composeRule.onNodeWithText("뒤로").performClick()
        composeRule.onNodeWithText("장소 검색").assertIsDisplayed()
    }

    /**
     * 뒤로가기로 검색에 돌아온 뒤에는 back stack에 상세가 남지 않아야 한다.
     *
     * 남으면 시스템 뒤로가기를 한 번 더 눌렀을 때 상세로 되돌아간다.
     */
    @Test
    fun 뒤로가기_후_상세는_back_stack에_남지_않는다() {
        composeRule.setContent { PlaceNavHostUnderTest() }

        composeRule.onNodeWithText("첫 번째 결과 열기").performClick()
        composeRule.onNodeWithText("뒤로").performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertFalse(navController.popBackStack())
        }
    }

    /**
     * `placeId`의 `:`가 route 인자로 왕복해도 원본과 같아야 한다.
     *
     * server가 이 prefix로 조회 provider를 정하므로 encode 흔적이 남으면 계약의
     * `placeId` 패턴과 어긋난다.
     */
    @Test
    fun placeId의_prefix_구분자가_route_인자로_보존된다() {
        composeRule.setContent { PlaceNavHostUnderTest() }

        composeRule.onNodeWithText("첫 번째 결과 열기").performClick()

        composeRule.onNodeWithText(PLACEHOLDER_PLACE_ID).assertIsDisplayed()
        composeRule.runOnIdle {
            val route = navController.currentBackStackEntry?.toRoute<PlaceDetailRoute>()
            assertEquals(PLACEHOLDER_PLACE_ID, route?.placeId)
        }
    }

    /** [placeGraph]만 등록한 최소 NavHost. app graph의 나머지 destination은 다루지 않는다. */
    @Composable
    private fun PlaceNavHostUnderTest() {
        navController = rememberNavController()
        GilpickTheme {
            NavHost(navController = navController, startDestination = PlaceSearchRoute) {
                placeGraph(navController)
            }
        }
    }
}
