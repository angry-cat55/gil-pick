package com.gilpick.progress

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavDestination.Companion.hasRoute
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
import com.gilpick.place.PlaceSearchRoute
import com.gilpick.route.DayRouteRoute
import com.gilpick.route.RouteRepository
import com.gilpick.route.RouteService
import com.gilpick.route.createRouteRetrofit
import com.gilpick.route.routeGraph
import com.gilpick.trip.KST
import com.gilpick.trip.TripDetailScreen
import com.gilpick.trip.TripDetailViewModel
import com.gilpick.trip.TripRepository
import com.gilpick.trip.TripService
import com.gilpick.trip.createTripRetrofit
import com.gilpick.ui.theme.GilpickTheme
import java.time.Clock
import java.time.Instant
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
 * T021·T036·#711: 진행 화면 → `일정 편집`(일정 상세)·`장소 추가`·`경로 보기`·뒤로 가기, 다른 날짜 조회와
 * 그 날짜의 `경로 보기`, 편집에서 돌아온 뒤 재조회 검증.
 *
 * 진행 화면이 시작 지점이다. 내 여행 목록의 진행 중 여행과 하단 `여행 중` 탭이 여기로 바로 들어온다(#711).
 * `MainActivity.kt`의 `TripDetailRoute` 배선을 옮겨 [progressGraph]·[itineraryGraph]·[routeGraph]와 한 NavHost에
 * 둔다. 여행·일정 개요·진행 현황은 [MockWebServer]가 준다. 오늘은 [progressGraph]의 `clock`으로 9/8에 고정한다.
 * 지도는 SDK 인증이 필요해 자리 표시로 바꿔 끼운다.
 */
class ProgressNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val server = MockWebServer()
    private lateinit var navController: NavHostController
    private lateinit var tripRepository: TripRepository
    private lateinit var itineraryRepository: ItineraryRepository
    private lateinit var routeRepository: RouteRepository
    private lateinit var progressRepository: ProgressRepository
    private val progressRequests = mutableListOf<String>()

    @Before
    fun setUp() {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/itinerary") -> json(overviewJson(threeDays()))
                    path.endsWith("/progress") -> {
                        progressRequests += path
                        json(progressJson(movingProgress()))
                    }
                    path.endsWith("/route") -> json(com.gilpick.route.routeEnvelopeJson("READY", route = com.gilpick.route.readyRouteJson(scheduleVersion = 3), scheduleVersion = 3))
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
        tripRepository = TripRepository(api = createTripRetrofit(base).create(TripService::class.java), auth = auth)
        itineraryRepository = ItineraryRepository(api = createItineraryRetrofit(base).create(ItineraryService::class.java), auth = auth)
        routeRepository = RouteRepository(api = createRouteRetrofit(base).create(RouteService::class.java), auth = auth)
        progressRepository = ProgressRepository(api = createProgressRetrofit(base).create(ProgressService::class.java), auth = auth)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun 진행_화면의_일정_편집을_누르면_일정_상세가_열리고_뒤로_가면_진행_화면으로_돌아와_다시_조회한다() {
        setGraph()
        awaitProgress()
        composeRule.onNodeWithText("2일차 · 1/3 완료").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_FAKE_MAP).assertIsDisplayed()
        val requestsBefore = progressRequests.size
        composeRule.runOnIdle { assertTrue(progressRequests.first().endsWith("/days/$PROGRESS_DATE/progress")) }

        composeRule.onNodeWithTag(TAG_EDIT).performClick()

        awaitDetail()
        // 시작은 여행 중 화면이 맡는다. 일정 상세에는 시작 영역이 없다.
        composeRule.onAllNodesWithText("오늘 여행 시작").assertCountEquals(0)
        composeRule.onAllNodesWithText("여행 진행 화면으로").assertCountEquals(0)
        composeRule.runOnIdle { assertEquals(DetailRoute(PROGRESS_TRIP_ID), navController.currentBackStackEntry?.toRoute<DetailRoute>()) }

        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitProgress()
        composeRule.waitUntil(WAIT_MILLIS) { progressRequests.size > requestsBefore }
        composeRule.runOnIdle {
            assertEquals(ActiveTravelRoute(PROGRESS_TRIP_ID, "서울 여행"), navController.currentBackStackEntry?.toRoute<ActiveTravelRoute>())
        }
    }

    @Test
    fun 진행_화면의_장소_추가와_경로_보기는_오늘_날짜의_편집과_경로_화면으로_간다() {
        setGraph()
        awaitProgress()

        composeRule.onNodeWithText("경로 보기").performClick()
        composeRule.runOnIdle {
            assertEquals(DayRouteRoute(PROGRESS_TRIP_ID, PROGRESS_DATE, 2), navController.currentBackStackEntry?.toRoute<DayRouteRoute>())
        }
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitProgress()

        composeRule.onNodeWithTag(TAG_ADD_PLACE).performScrollTo().performClick()
        composeRule.waitUntil(WAIT_MILLIS) { navController.currentBackStackEntry?.destination?.hasRoute<PlaceSearchRoute>() == true }
        composeRule.runOnIdle {
            // 편집 entry가 back stack에 있고 그 위에 검색이 열렸다.
            assertEquals(
                ItineraryEditRoute(PROGRESS_TRIP_ID, PROGRESS_DATE, openSearch = true),
                navController.getBackStackEntry<ItineraryEditRoute>().toRoute<ItineraryEditRoute>(),
            )
        }
    }

    @Test
    fun 다른_날짜를_고르면_그_날짜의_경로_화면으로_가고_편집에서_돌아오면_진행_현황을_다시_조회한다() {
        setGraph()
        awaitProgress()
        val requestsBefore = progressRequests.size

        composeRule.onNodeWithContentDescription("1일차 9월 7일").performClick()
        composeRule.onNodeWithText("1일차 · 지난 일정").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_CARD_NEXT).assertDoesNotExist()
        composeRule.onNodeWithText("경로 보기").performClick()
        composeRule.runOnIdle {
            assertEquals(DayRouteRoute(PROGRESS_TRIP_ID, "2026-09-07", 1), navController.currentBackStackEntry?.toRoute<DayRouteRoute>())
        }
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitUntil(WAIT_MILLIS) { composeRule.onAllNodesWithText("오늘로 돌아가기").fetchSemanticsNodes().isNotEmpty() }
        // 돌아와도 보던 날짜를 유지한다. 재조회는 한 번 더 일어난다.
        composeRule.onNodeWithText("1일차 · 지난 일정").assertIsDisplayed()
        composeRule.onNodeWithText("오늘로 돌아가기").performClick()
        awaitProgress()

        // `장소 추가`는 보던 날짜와 무관하게 오늘 날짜의 편집으로 간다. 돌아오면 다시 조회한다.
        composeRule.onNodeWithTag(TAG_ADD_PLACE).performScrollTo().performClick()
        composeRule.waitUntil(WAIT_MILLIS) { navController.currentBackStackEntry?.destination?.hasRoute<PlaceSearchRoute>() == true }
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitProgress()
        composeRule.waitUntil(WAIT_MILLIS) { progressRequests.size >= requestsBefore + 2 }
        composeRule.runOnIdle {
            assertEquals(ActiveTravelRoute(PROGRESS_TRIP_ID, "서울 여행"), navController.currentBackStackEntry?.toRoute<ActiveTravelRoute>())
        }
    }

    @Test
    fun 자동_감지_꺼짐_안내의_권한_허용은_앱_설정이_아니라_위치_권한_안내_화면을_먼저_연다() {
        setGraph(hasBackgroundPermission = { false })
        awaitProgress()
        composeRule.waitUntil(WAIT_MILLIS) { composeRule.onAllNodesWithText("권한 허용").fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNodeWithText("권한 허용").performScrollTo().performClick()

        // 약관 동의를 거치는 안내 화면이 먼저 뜬다(F007 UI-005·FR-024a). 앱 설정으로 바로 넘어가지 않는다.
        composeRule.onNodeWithText("위치 권한이 필요해요").assertIsDisplayed()
        composeRule.onNodeWithText("나중에 하기").performClick()

        composeRule.onNodeWithText("위치 권한이 필요해요").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(ActiveTravelRoute(PROGRESS_TRIP_ID, "서울 여행"), navController.currentBackStackEntry?.toRoute<ActiveTravelRoute>())
        }
    }

    private fun awaitDetail() {
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodesWithText("일정 편집").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitProgress() {
        // 진행 화면이 시작 지점이라 process의 첫 test는 인증 저장소·Retrofit 초기화까지 이 대기 안에서 치른다.
        composeRule.waitUntil(2 * WAIT_MILLIS) {
            composeRule.onAllNodesWithText("다음 장소").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** `MainActivity.kt`의 배선을 그대로 옮긴다. 진행 화면에서 시작하고 오늘은 9/8로 고정한다. */
    private fun setGraph(hasBackgroundPermission: (Context) -> Boolean = ProgressViewModel::hasBackgroundLocationPermission) {
        composeRule.setContent {
            navController = rememberNavController()
            GilpickTheme {
                NavHost(navController = navController, startDestination = ActiveTravelRoute(PROGRESS_TRIP_ID, "서울 여행")) {
                    composable<DetailRoute> { entry ->
                        val tripId = entry.toRoute<DetailRoute>().tripId
                        val viewModel = remember(tripId) {
                            TripDetailViewModel(
                                repository = tripRepository,
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
                    // `openSearch = true`인 편집 화면은 진입 직후 F003 검색으로 가므로 자리만 둔다.
                    composable<PlaceSearchRoute> {}
                    routeGraph(
                        navController,
                        onSessionExpired = {},
                        repository = { routeRepository },
                        progressRepository = { progressRepository },
                        map = { _, _, _, _, modifier -> Box(modifier = modifier.fillMaxSize()) },
                    )
                    progressGraph(
                        navController,
                        onSessionExpired = {},
                        repository = { progressRepository },
                        itineraryRepository = { itineraryRepository },
                        onOpenTripDetail = { tripId -> navController.navigate(DetailRoute(tripId)) },
                        hasBackgroundPermission = hasBackgroundPermission,
                        clock = Clock.fixed(Instant.parse("${PROGRESS_DATE}T03:00:00Z"), KST),
                        map = { _, _, modifier -> Box(modifier = modifier.fillMaxSize().testTag(TAG_FAKE_MAP)) },
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
        const val TAG_FAKE_MAP = "fake_map"
        val TRIP_JSON = """
            {"success":true,
             "data":{"tripId":"$PROGRESS_TRIP_ID","name":"서울 여행","startDate":"2026-09-07",
                     "endDate":"2026-09-09","status":"IN_PROGRESS","dayCount":3,"version":1},
             "meta":{"requestId":"$PROGRESS_REQUEST_ID"}}
        """.trimIndent()
    }
}
