package com.gilpick.trip

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.gilpick.itinerary.ItineraryEditRoute
import java.time.LocalDate
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * #498: 새 여행 생성 뒤 일정 편집으로 가는 백스택 검증.
 *
 * `MainActivity.kt`의 생성 폼이 쓰는 [openNewTripItinerary]를 그대로 부른다. 화면 내용은 이 test의 관심사가 아니므로
 * 각 destination은 자리 표시 글자만 그린다. 기대 백스택: `목록 → 새 여행 상세 → 일정 편집`(폼은 빠진다).
 */
class TripCreateNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

    @Test
    fun 생성하면_폼을_빼고_일정_편집으로_가며_뒤로_가면_상세_다음_목록이다() {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = ListRoute) {
                composable<ListRoute> { Text("목록") }
                composable<FormRoute> { Text("폼") }
                composable<DetailRoute> { Text("상세") }
                composable<ItineraryEditRoute> { Text("일정 편집") }
            }
        }
        composeRule.runOnIdle { navController.navigate(FormRoute) }

        composeRule.runOnIdle {
            navController.openNewTripItinerary<FormRoute>(DetailRoute(TRIP_ID), TRIP_ID, LocalDate.of(2026, 9, 1))
        }

        composeRule.runOnIdle {
            val edit = navController.currentBackStackEntry!!.toRoute<ItineraryEditRoute>()
            assertEquals(ItineraryEditRoute(TRIP_ID, "2026-09-01", newTrip = true), edit)
            // 폼은 백스택에 남지 않는다.
            assertTrue(navController.currentBackStack.value.none { it.destination.hasRoute<FormRoute>() })

            navController.popBackStack()
            assertEquals(DetailRoute(TRIP_ID), navController.currentBackStackEntry!!.toRoute<DetailRoute>())

            navController.popBackStack()
            assertTrue(navController.currentBackStackEntry!!.destination.hasRoute<ListRoute>())
        }
    }

    @Serializable
    private object ListRoute

    @Serializable
    private object FormRoute

    @Serializable
    private data class DetailRoute(val tripId: String)

    private companion object {
        const val TRIP_ID = "trip-1"
    }
}
