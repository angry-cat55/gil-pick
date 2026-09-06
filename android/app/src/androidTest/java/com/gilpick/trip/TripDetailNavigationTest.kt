package com.gilpick.trip

import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.platform.app.InstrumentationRegistry
import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthService
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.createAuthRetrofit
import com.gilpick.itinerary.ItineraryEditRoute
import com.gilpick.itinerary.ItineraryRepository
import com.gilpick.itinerary.ItineraryService
import com.gilpick.itinerary.createItineraryRetrofit
import com.gilpick.itinerary.itineraryGraph
import com.gilpick.itinerary.returnAddToSchedule
import com.gilpick.place.PlaceDetailRoute
import com.gilpick.place.placeGraph
import com.gilpick.ui.theme.GilpickTheme
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * T028: 여행 상세 → `일정 편집`·날짜별 `추가`·장소 행 navigation 검증.
 *
 * `MainActivity.kt`의 `TripDetailRoute` 배선을 그대로 옮겨 [itineraryGraph]·[placeGraph]와 한
 * NavHost에 둔다. 여행·일정 개요는 [MockWebServer]가 주고, F003 검색·상세는 network 없이도
 * 제목이 그려지므로 그 화면이 열렸는지만 본다(내용은 F003 test가 검증한다).
 */
class TripDetailNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val server = MockWebServer()
    private lateinit var navController: NavHostController
    private lateinit var repository: TripRepository
    private lateinit var itineraryRepository: ItineraryRepository

    @Before
    fun setUp() {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/itinerary") -> json(OVERVIEW_JSON)
                    path.contains("/trips/") -> json(TRIP_JSON)
                    // F003 장소 상세. 제목만 확인하므로 내용은 주지 않는다.
                    else -> MockResponse(code = 404)
                }
            }
        }
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
        repository = TripRepository(
            api = createTripRetrofit(server.url("/api/v1/").toString()).create(TripService::class.java),
            auth = auth,
        )
        itineraryRepository = ItineraryRepository(
            api = createItineraryRetrofit(server.url("/api/v1/").toString()).create(ItineraryService::class.java),
            auth = auth,
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun 일정_편집을_누르면_첫_날짜의_편집_화면이_열린다() {
        setGraph()
        awaitDetail()

        composeRule.onNodeWithText("일정 편집").performClick()

        awaitEditContent()
        composeRule.onNodeWithText("9월 1일 방문 장소").assertIsDisplayed()
        composeRule.onNodeWithText("1일차 · 1곳").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(ItineraryEditRoute(TRIP_ID, "2026-09-01"), currentRoute<ItineraryEditRoute>())
        }
    }

    @Test
    fun 날짜_헤더의_추가를_누르면_그_날짜의_편집을_거쳐_검색으로_간다() {
        setGraph()
        awaitDetail()

        // 날짜 순서대로 두 개의 `추가` pill이 있다. 둘째 날(9/2)의 것을 누른다.
        composeRule.onAllNodesWithText("추가")[1].performClick()

        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodesWithContentDescription("장소 이름 검색").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle {
            assertEquals(
                ItineraryEditRoute(TRIP_ID, "2026-09-02", openSearch = true),
                navController.getBackStackEntry<ItineraryEditRoute>().toRoute<ItineraryEditRoute>(),
            )
        }

        // 검색에서 뒤로 가면 그 날짜의 편집이다.
        composeRule.onNodeWithContentDescription("뒤로 가기").performClick()
        awaitEditContent()
        composeRule.onNodeWithText("9월 2일 방문 장소").assertIsDisplayed()
        composeRule.onNodeWithText("2일차 · 0곳").assertIsDisplayed()
    }

    @Test
    fun 장소_행을_누르면_장소_상세가_열리고_뒤로_가면_상세가_유지된다() {
        setGraph()
        awaitDetail()

        composeRule.onNodeWithText("경복궁").performClick()

        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodesWithText("장소 상세").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle { assertEquals(PlaceDetailRoute(PLACE_ID), currentRoute<PlaceDetailRoute>()) }

        composeRule.runOnIdle { assertTrue(navController.popBackStack()) }
        awaitDetail()
        composeRule.onNodeWithText("서울 여행").assertIsDisplayed()
        composeRule.onNodeWithText("경복궁").assertIsDisplayed()
    }

    private inline fun <reified T : Any> currentRoute(): T? = navController.currentBackStackEntry?.toRoute<T>()

    private fun awaitDetail() {
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodesWithText("경복궁").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitEditContent() {
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodesWithText("방문 장소", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** `MainActivity.kt`의 `TripDetailRoute` 배선을 그대로 옮긴다. */
    private fun setGraph() {
        composeRule.setContent {
            navController = rememberNavController()
            GilpickTheme {
                NavHost(navController = navController, startDestination = DetailRoute(TRIP_ID)) {
                    composable<DetailRoute> { entry ->
                        val tripId = entry.toRoute<DetailRoute>().tripId
                        val viewModel = remember(tripId) {
                            TripDetailViewModel(
                                repository = repository,
                                itineraryRepository = itineraryRepository,
                                tripId = tripId,
                            )
                        }
                        val state by viewModel.state.collectAsStateWithLifecycle()

                        LaunchedEffect(Unit) { viewModel.load() }

                        TripDetailScreen(
                            state = state,
                            onBack = { navController.popBackStack() },
                            onRetry = viewModel::retry,
                            onEdit = {},
                            onDelete = {},
                            onDeleteErrorShown = {},
                            onRetryItinerary = viewModel::retryItinerary,
                            onEditItinerary = {
                                (state.phase as? TripDetailPhase.Content)?.let { content ->
                                    navController.navigate(ItineraryEditRoute(tripId, content.trip.startDate))
                                }
                            },
                            onAddPlace = { date ->
                                navController.navigate(ItineraryEditRoute(tripId, date, openSearch = true))
                            },
                            onSelectPlace = { placeId -> navController.navigate(PlaceDetailRoute(placeId)) },
                        )
                    }
                    itineraryGraph(navController, onSessionExpired = {}, repository = { itineraryRepository })
                    placeGraph(navController, onSessionExpired = {}, onAddToSchedule = navController::returnAddToSchedule)
                }
            }
        }
    }

    private fun json(body: String) = MockResponse(
        code = 200,
        headers = Headers.headersOf("Content-Type", "application/json"),
        body = body,
    )

    @Serializable
    private data class DetailRoute(val tripId: String)

    private companion object {
        const val WAIT_MILLIS = 5_000L
        const val TRIP_ID = "3f1d2c4b-5a6e-4f70-8a91-b2c3d4e5f607"
        const val PLACE_ID = "tourapi:126508"
        const val REQUEST_ID = "11111111-2222-4333-8444-555555555555"
        val TRIP_JSON = """
            {"success":true,
             "data":{"tripId":"$TRIP_ID","name":"서울 여행","startDate":"2026-09-01",
                     "endDate":"2026-09-02","status":"UPCOMING","dayCount":2,"version":1},
             "meta":{"requestId":"$REQUEST_ID"}}
        """.trimIndent()
        val OVERVIEW_JSON = """
            {"success":true,"data":{"tripId":"$TRIP_ID","days":[
              {"date":"2026-09-01","dayNumber":1,"version":1,"routeStatus":"NOT_CALCULATED","items":[
                {"itemId":"aaaaaaaa-1111-4222-8333-444444444444",
                 "place":{"placeId":"$PLACE_ID","name":"경복궁","category":"HISTORY_CULTURE",
                          "address":"서울 종로구","imageUrl":null},
                 "sequence":1,"plannedStayMinutes":90,"staySource":"RECOMMENDED",
                 "transportModeToNext":null,"status":"PLANNED"}
              ]},
              {"date":"2026-09-02","dayNumber":2,"version":0,"routeStatus":"NOT_CALCULATED","items":[]}
            ]},"meta":{"requestId":"$REQUEST_ID"}}
        """.trimIndent()
    }
}
