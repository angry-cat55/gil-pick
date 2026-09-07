package com.gilpick.route

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.gilpick.trip.TripDetailScreen
import com.gilpick.trip.TripDetailViewModel
import com.gilpick.trip.TripRepository
import com.gilpick.trip.TripService
import com.gilpick.trip.createTripRetrofit
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
 * T028: 여행 상세의 경로 요약·`경로 보기` → 날짜 경로 화면 → 뒤로 가기 상태 보존 검증.
 *
 * `MainActivity.kt`의 `TripDetailRoute` 배선을 그대로 옮겨 [routeGraph]·[itineraryGraph]와 한 NavHost에
 * 둔다. 여행·일정 개요·경로는 [MockWebServer]가 준다. 9/1은 `READY`(구간·전체 이동시간 표시), 9/2는
 * `FAILED`(일정은 그대로, 경로 영역만 실패)다. 지도는 SDK 인증이 필요해 자리 표시로 바꿔 끼운다.
 */
class RouteNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val server = MockWebServer()
    private lateinit var navController: NavHostController
    private lateinit var repository: TripRepository
    private lateinit var itineraryRepository: ItineraryRepository
    private lateinit var routeRepository: RouteRepository
    private val routeRequests = mutableListOf<String>()

    @Before
    fun setUp() {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/itinerary") -> json(OVERVIEW_JSON)
                    path.endsWith("/2026-09-01/route") -> {
                        routeRequests += path
                        json(routeEnvelopeJson("READY", route = readyRouteJson(scheduleVersion = 1), scheduleVersion = 1))
                    }
                    path.endsWith("/2026-09-02/route") -> {
                        routeRequests += path
                        json(routeEnvelopeJson("FAILED", failure = failureJson(), scheduleVersion = 1))
                    }
                    path.contains("/trips/") -> json(TRIP_JSON)
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
        val base = server.url("/api/v1/").toString()
        repository = TripRepository(api = createTripRetrofit(base).create(TripService::class.java), auth = auth)
        itineraryRepository = ItineraryRepository(api = createItineraryRetrofit(base).create(ItineraryService::class.java), auth = auth)
        routeRepository = RouteRepository(api = createRouteRetrofit(base).create(RouteService::class.java), auth = auth)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun 상세의_READY_날짜는_구간과_전체_이동시간을_보이고_FAILED_날짜는_일정을_유지한_채_경로만_실패다() {
        setGraph()
        awaitDetail()

        // 9/1 READY: 구간 이동시간·거리, 날짜 전체 이동, `경로 보기`.
        composeRule.onNodeWithText("도보 10분 · 800m").assertIsDisplayed()
        composeRule.onNodeWithText("대중교통 15분 · 3.4km").assertIsDisplayed()
        composeRule.onNodeWithText("이동 25분 · 4.2km").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("9월 1일 경로 보기").assertIsDisplayed()

        // 9/2 FAILED: 장소 행은 그대로, 경로 영역만 실패 안내. `경로 보기`는 없다.
        composeRule.onNodeWithText("창덕궁").performScrollTo().assertIsDisplayed()
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodesWithText("경로 서비스 응답이 늦어", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("경로 서비스 응답이 늦어", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("9월 2일 경로 다시 시도").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("9월 2일 경로 보기").assertDoesNotExist()

        // 총 이동 통계는 모든 날짜가 READY가 아니므로 `정보 없음`이다.
        composeRule.onNodeWithText("정보 없음").assertIsDisplayed()
    }

    @Test
    fun 경로_보기를_누르면_그_날짜의_경로_화면이_열리고_뒤로_가면_상세가_유지된다() {
        setGraph()
        awaitDetail()

        composeRule.onNodeWithContentDescription("9월 1일 경로 보기").performClick()

        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodesWithText("총 이동 25분 · 4.2km").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("1일차 경로").assertIsDisplayed()
        composeRule.onNodeWithText("9월 1일 · 3곳").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(DayRouteRoute(TRIP_ID, "2026-09-01", 1), navController.currentBackStackEntry?.toRoute<DayRouteRoute>())
            // 상세가 FAILED 날짜(9/2)의 원인을 먼저 조회했고, 경로 화면이 9/1을 조회했다.
            assertEquals("/api/v1/trips/$TRIP_ID/days/2026-09-01/route", routeRequests.last())
            assertEquals(1, routeRequests.count { it.endsWith("/2026-09-01/route") })
        }

        composeRule.onNodeWithContentDescription("뒤로 가기").performClick()
        awaitDetail()
        composeRule.onNodeWithText("서울 여행").assertIsDisplayed()
        composeRule.onNodeWithText("이동 25분 · 4.2km").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(navController.currentBackStackEntry?.toRoute<DetailRoute>() == DetailRoute(TRIP_ID)) }
    }

    private fun awaitDetail() {
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodesWithText("이동 25분 · 4.2km").fetchSemanticsNodes().isNotEmpty()
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
                                routeRepository = routeRepository,
                                tripId = tripId,
                            )
                        }
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        val routes by viewModel.routes.collectAsStateWithLifecycle()

                        LaunchedEffect(Unit) { viewModel.load() }

                        TripDetailScreen(
                            state = state,
                            onBack = { navController.popBackStack() },
                            onRetry = viewModel::retry,
                            onEdit = {},
                            onDelete = {},
                            onDeleteErrorShown = {},
                            onRetryItinerary = viewModel::retryItinerary,
                            onEditItinerary = {},
                            onAddPlace = { date -> navController.navigate(ItineraryEditRoute(tripId, date, openSearch = true)) },
                            onSelectPlace = {},
                            routes = routes,
                            onOpenRoute = { date, dayNumber -> navController.navigate(DayRouteRoute(tripId, date, dayNumber)) },
                        )
                    }
                    itineraryGraph(navController, onSessionExpired = {}, repository = { itineraryRepository })
                    routeGraph(
                        navController,
                        onSessionExpired = {},
                        repository = { routeRepository },
                        map = { _, modifier -> Box(modifier = modifier.fillMaxSize().testTag(TAG_MAP)) },
                    )
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
        const val TRIP_ID = ROUTE_TRIP_ID
        const val REQUEST_ID = "11111111-2222-4333-8444-555555555555"
        val TRIP_JSON = """
            {"success":true,
             "data":{"tripId":"$TRIP_ID","name":"서울 여행","startDate":"2026-09-01",
                     "endDate":"2026-09-02","status":"UPCOMING","dayCount":2,"version":1},
             "meta":{"requestId":"$REQUEST_ID"}}
        """.trimIndent()

        private fun itemJson(itemId: String, name: String, sequence: Int, transport: String?) = """
            {"itemId":"$itemId",
             "place":{"placeId":"tourapi:$sequence","name":"$name","category":"HISTORY_CULTURE","address":null,"imageUrl":null},
             "sequence":$sequence,"plannedStayMinutes":90,"staySource":"RECOMMENDED",
             "transportModeToNext":${transport?.let { "\"$it\"" } ?: "null"},"status":"PLANNED"}
        """.trimIndent()

        val OVERVIEW_JSON = """
            {"success":true,"data":{"tripId":"$TRIP_ID","days":[
              {"date":"2026-09-01","dayNumber":1,"version":1,"routeStatus":"READY","items":[
                ${itemJson(ITEM_A, "경복궁", 1, "WALK")},
                ${itemJson(ITEM_B, "북촌한옥마을", 2, "TRANSIT")},
                ${itemJson(ITEM_C, "인사동거리", 3, null)}
              ],"route":${readyRouteJson(scheduleVersion = 1)}},
              {"date":"2026-09-02","dayNumber":2,"version":1,"routeStatus":"FAILED","items":[
                ${itemJson("dddddddd-1111-4222-8333-444444444444", "창덕궁", 1, "WALK")},
                ${itemJson("eeeeeeee-1111-4222-8333-444444444444", "종묘", 2, null)}
              ],"route":null}
            ]},"meta":{"requestId":"$REQUEST_ID"}}
        """.trimIndent()
    }
}
