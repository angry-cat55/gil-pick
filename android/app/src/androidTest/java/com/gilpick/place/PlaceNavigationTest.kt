package com.gilpick.place

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * T009·T024: 장소 route 등록과 검색 → 상세 → 뒤로가기 navigation 검증.
 *
 * `MainActivity.kt`의 app navigation graph는 `placeGraph(navController)`로 이 graph를
 * 등록한다. 여기서는 같은 [placeGraph]만 host하는 NavHost로 검증한다. app graph 전체를
 * 띄우려면 인증 상태와 여행 목록 network 응답까지 필요한데, 그것은 route 등록이 아니라
 * 다른 것을 검증하게 된다.
 *
 * 검색 화면은 #142 전까지 임시다. 상세는 실제 화면이며 network 없이 실패 상태가 되지만
 * AppBar 제목 `장소 상세`로 도착을 확인할 수 있다. 상세 내용 표현은
 * `PlaceDetailScreenTest`가 stateless 화면으로 검증한다.
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
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodesWithText("장소 상세").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("장소 상세").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("뒤로 가기").performClick()
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
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodesWithText("장소 상세").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("뒤로 가기").performClick()
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
        composeRule.waitForIdle()

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

    private companion object {
        const val WAIT_MILLIS = 5_000L
    }
}
