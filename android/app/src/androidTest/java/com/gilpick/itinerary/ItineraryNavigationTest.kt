package com.gilpick.itinerary

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthService
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.createAuthRetrofit
import com.gilpick.place.AddToScheduleRequest
import com.gilpick.place.PlaceCategory
import com.gilpick.place.PlaceDetailRoute
import com.gilpick.place.PlaceDto
import com.gilpick.place.PlaceSource
import com.gilpick.place.PlaceTransport
import com.gilpick.place.placeGraph
import com.gilpick.ui.theme.GilpickTheme
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * T018: `ItineraryEditRoute` 등록과 편집 → 검색 → 상세 → `일정에 추가` → 편집 결과 반환 검증.
 *
 * `MainActivity.kt`와 같은 방식으로 [itineraryGraph]와 [placeGraph]를 한 NavHost에 등록한다.
 * 일정 개요는 MockWebServer가 주어 편집 화면이 content 상태가 되고, F003 검색 결과·상세는
 * network 없이는 내용이 없으므로 `PlaceNavigationTest`처럼 route로 직접 상세에 간 뒤 시트가
 * 부르는 [returnAddToSchedule]을 test가 직접 호출한다. 시트가 이 콜백을 부르는 것은
 * `PlaceDetailScreenTest`·`PlaceSearchScreenTest`가 검증한다.
 */
class ItineraryNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val server = MockWebServer()
    private lateinit var navController: NavHostController
    private lateinit var repository: ItineraryRepository

    @Before
    fun setUp() {
        server.start()
        // 사흘 여행(9/8~9/10)의 빈 개요. 편집 화면은 이 응답으로 content가 된다.
        server.enqueue(MockResponse(code = 200, body = OVERVIEW_JSON))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val auth = AuthRepository(
            store = AuthSessionStore.create(context),
            api = createAuthRetrofit(server.url("/api/v1/").toString()).create(AuthService::class.java),
            appLinkHandler = AuthAppLinkHandler("app.gilpick.example"),
        )
        runBlocking {
            auth.onSignedIn(
                sessionId = "session-1",
                userId = "user-1",
                nickname = null,
                profileImageUrl = null,
                accessToken = "access-token",
                refreshToken = "session-1.refresh-token",
                accessExpiresAtEpochSeconds = 4_102_444_800,
                refreshExpiresAtEpochSeconds = 4_102_444_800,
            )
        }
        repository = ItineraryRepository(
            api = createItineraryRetrofit(server.url("/api/v1/").toString()).create(ItineraryService::class.java),
            auth = auth,
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun 편집에서_검색_상세를_거쳐_일정에_추가하면_항목이_추가된_편집으로_돌아온다() {
        composeRule.setContent { NavHostUnderTest(ItineraryEditRoute(TRIP_ID, "2026-09-08")) }
        awaitEditContent()

        composeRule.onNodeWithContentDescription("장소 추가").performClick()
        composeRule.onNodeWithContentDescription("장소 이름 검색").assertIsDisplayed()
        openDetail()

        composeRule.runOnIdle {
            navController.returnAddToSchedule(place("경복궁"), AddToScheduleRequest(PlaceTransport.WALK, 90))
        }

        composeRule.onNodeWithText("일정 편집").assertIsDisplayed()
        composeRule.onNodeWithText("경복궁").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("1번째").assertIsDisplayed()
        composeRule.onNodeWithText("1일차 · 1곳").assertIsDisplayed()
        // 검색·상세는 back stack에서 사라져 시스템 뒤로 가기가 편집을 닫는다(변경이 있으므로 확인부터).
        composeRule.runOnIdle { assertFalse(navController.popBackStack()) }
    }

    @Test
    fun 시트를_취소하고_돌아오면_일정은_그대로다() {
        composeRule.setContent { NavHostUnderTest(ItineraryEditRoute(TRIP_ID, "2026-09-08")) }
        awaitEditContent()

        composeRule.onNodeWithContentDescription("장소 추가").performClick()
        composeRule.onNodeWithContentDescription("장소 이름 검색").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("뒤로 가기").performClick()

        composeRule.onNodeWithText("일정 편집").assertIsDisplayed()
        composeRule.onNodeWithText("1일차 · 0곳").assertIsDisplayed()
        composeRule.onNodeWithText("아직 방문 장소가 없어요").assertIsDisplayed()
    }

    @Test
    fun openSearch로_진입하면_검색으로_바로_가고_뒤로_가면_그_날짜의_편집이다() {
        composeRule.setContent { NavHostUnderTest(ItineraryEditRoute(TRIP_ID, "2026-09-09", openSearch = true)) }

        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodesWithContentDescription("장소 이름 검색").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("뒤로 가기").performClick()

        awaitEditContent()
        composeRule.onNodeWithText("9월 9일 방문 장소").assertIsDisplayed()
        composeRule.onNodeWithText("2일차 · 0곳").assertIsDisplayed()
    }

    private fun awaitEditContent() {
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodesWithText("방문 장소", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openDetail() {
        composeRule.runOnIdle { navController.navigate(PlaceDetailRoute(PLACE_ID)) }
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodesWithText("장소 상세").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Composable
    private fun NavHostUnderTest(start: ItineraryEditRoute) {
        navController = rememberNavController()
        GilpickTheme {
            NavHost(navController = navController, startDestination = start) {
                itineraryGraph(navController, onSessionExpired = {}, repository = { repository })
                placeGraph(navController, onSessionExpired = {}, onAddToSchedule = navController::returnAddToSchedule)
            }
        }
    }

    private fun place(name: String): PlaceDto = PlaceDto(
        placeId = PLACE_ID,
        source = PlaceSource.TOUR_API,
        sourcePlaceId = "126508",
        name = name,
        category = PlaceCategory.HISTORY_CULTURE,
        tourApiCategory = null,
        address = "서울 종로구",
        latitude = 37.5796,
        longitude = 126.977,
        imageUrl = null,
        recommendedStayMinutes = 90,
        rating = null,
        userRatingCount = null,
        businessStatus = null,
        regularOpeningHours = null,
        currentOpeningHours = null,
        googleAttributions = null,
    )

    private companion object {
        const val WAIT_MILLIS = 5_000L
        const val TRIP_ID = "3f1d2c4b-5a6e-4f70-8a91-b2c3d4e5f607"
        const val PLACE_ID = "tourapi:126508"
        val OVERVIEW_JSON = """
            {"success":true,"data":{"tripId":"$TRIP_ID","days":[
              {"date":"2026-09-08","dayNumber":1,"version":0,"routeStatus":"NOT_CALCULATED","items":[]},
              {"date":"2026-09-09","dayNumber":2,"version":0,"routeStatus":"NOT_CALCULATED","items":[]},
              {"date":"2026-09-10","dayNumber":3,"version":0,"routeStatus":"NOT_CALCULATED","items":[]}
            ]},"meta":{"requestId":"11111111-2222-4333-8444-555555555555"}}
        """.trimIndent()
    }
}
