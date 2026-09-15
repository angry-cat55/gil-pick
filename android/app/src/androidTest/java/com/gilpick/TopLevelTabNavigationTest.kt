package com.gilpick

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.Serializable
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #552: 최상위 탭 이동 규칙([navigateToTab]) 검증.
 *
 * 앱 NavHost와 같은 모양(시작 화면 = 첫 탭)의 작은 graph로 본다. 실제 앱에서 상세 → 여행 중 back stack을 만들려면
 * 서버가 필요하고, 알림 진입으로 대신하면 test 환경에서 navigate가 main이 아닌 thread에서 불려 결과가 흔들린다.
 * 실제 화면 확인은 배포 서버 빌드로 했다(PR 참고).
 */
@RunWith(AndroidJUnit4::class)
class TopLevelTabNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Serializable private object Trips
    @Serializable private object Detail
    @Serializable private object Active
    @Serializable private object Settings

    private lateinit var navController: NavHostController

    @Before
    fun setUp() {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(navController, startDestination = Trips) {
                composable<Trips> {}
                composable<Detail> {}
                composable<Active> {}
                composable<Settings> {}
            }
        }
    }

    @Test
    fun 시작_화면_위에_쌓인_여행_중에서_시작_탭을_누르면_시작_화면이_보인다() {
        composeRule.runOnIdle {
            navController.navigate(Detail)
            navController.navigate(Active)
        }
        composeRule.runOnIdle { navController.navigateToTab(Trips) }

        composeRule.runOnIdle { assertTrue(navController.currentDestination!!.hasRoute<Trips>()) }
    }

    @Test
    fun 시작_화면이_아닌_탭은_떠났다_돌아오면_쌓아_둔_화면을_되살린다() {
        composeRule.runOnIdle { navController.navigateToTab(Settings) }
        composeRule.runOnIdle { navController.navigate(Detail) }
        composeRule.runOnIdle { navController.navigateToTab(Trips) }
        composeRule.runOnIdle { assertTrue(navController.currentDestination!!.hasRoute<Trips>()) }

        composeRule.runOnIdle { navController.navigateToTab(Settings) }

        composeRule.runOnIdle { assertTrue(navController.currentDestination!!.hasRoute<Detail>()) }
    }
}
