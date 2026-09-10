package com.gilpick.notification

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.platform.app.InstrumentationRegistry
import com.gilpick.alternative.AlternativePlacesRoute
import com.gilpick.alternative.AlternativeRepository
import com.gilpick.alternative.AlternativeService
import com.gilpick.alternative.TAG_CANDIDATE_PREFIX
import com.gilpick.alternative.activeDetectionsJson
import com.gilpick.alternative.alternativeGraph
import com.gilpick.alternative.alternativesJson
import com.gilpick.alternative.createAlternativeRetrofit
import com.gilpick.alternative.detectionDetailJson
import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthResult
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthService
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.createAuthRetrofit
import com.gilpick.itinerary.ItineraryRepository
import com.gilpick.itinerary.ItineraryService
import com.gilpick.itinerary.createItineraryRetrofit
import com.gilpick.progress.ActiveTravelRoute
import com.gilpick.progress.PROGRESS_TRIP_ID
import com.gilpick.progress.ProgressRepository
import com.gilpick.progress.ProgressService
import com.gilpick.progress.TAG_NOTIFICATIONS
import com.gilpick.progress.createProgressRetrofit
import com.gilpick.progress.movingProgress
import com.gilpick.progress.overviewJson
import com.gilpick.progress.progressGraph
import com.gilpick.progress.progressJson
import com.gilpick.progress.threeDays
import com.gilpick.trip.TripRepository
import com.gilpick.trip.TripService
import com.gilpick.trip.createTripRetrofit
import com.gilpick.ui.theme.GilpickTheme
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * T033: 알림 목록 → 유형별 목적지, 읽음 요청, 대상 없음 안내, 진행 화면 벨 진입점 검증(quickstart AND 2.1~2.3, AND 7.2).
 * T041: 감지 목록 `대체 장소 보기` → `AlternativePlacesRoute` 배선도 여기서 본다.
 *
 * `MainActivity.kt`의 배선을 그대로 옮겨 [notificationGraph]·[progressGraph]·[alternativeGraph]를 한 NavHost에
 * 둔다. 알림·여행·개요·진행 현황·감지·후보는 [MockWebServer]가 준다. 지도는 SDK 인증이 필요해 자리 표시로
 * 바꿔 끼운다. 알림의 `tripId`는 진행 fixture의 여행과 같아 진행 화면 fixture를 그대로 쓴다.
 */
class NotificationNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val server = MockWebServer()
    private lateinit var navController: NavHostController
    private lateinit var itineraryRepository: ItineraryRepository
    private lateinit var progressRepository: ProgressRepository
    private lateinit var alternativeRepository: AlternativeRepository
    private lateinit var notificationRepository: NotificationRepository
    private lateinit var tripRepository: TripRepository

    /** `true`면 여행이 논리 삭제된 상태다. 여행·개요·진행 현황이 `404 TRIP_NOT_FOUND`다. */
    private var tripDeleted = false
    private val readRequests = mutableListOf<String>()

    @Before
    fun setUp() {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/notifications") -> json(notificationListJson(tripId = PROGRESS_TRIP_ID))
                    path.endsWith("/read") -> {
                        readRequests += path
                        json(markReadJson(path.substringAfter("/notifications/").substringBefore("/")))
                    }
                    path.endsWith("/detections") -> json(activeDetectionsJson())
                    // 여행 상세·개요·진행 현황은 모두 `/trips/{id}` 아래다. 논리 삭제면 셋 다 404다.
                    tripDeleted && path.contains("/trips/") ->
                        MockResponse(code = 404, headers = JSON_HEADERS, body = notificationErrorJson("TRIP_NOT_FOUND"))
                    path.endsWith("/itinerary") -> json(overviewJson(threeDays()))
                    path.endsWith("/progress") -> json(progressJson(movingProgress()))
                    path.contains("/trips/") -> json(tripJson())
                    path.endsWith("/alternatives") -> json(alternativesJson())
                    path.contains("/detections/") -> json(detectionDetailJson())
                    else -> MockResponse(code = 404)
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val base = server.url("/api/v1/").toString()
        val auth = AuthRepository(
            store = AuthSessionStore.create(context),
            api = createAuthRetrofit(base).create(AuthService::class.java),
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
        itineraryRepository = ItineraryRepository(api = createItineraryRetrofit(base).create(ItineraryService::class.java), auth = auth)
        progressRepository = ProgressRepository(api = createProgressRetrofit(base).create(ProgressService::class.java), auth = auth)
        alternativeRepository = AlternativeRepository(api = createAlternativeRetrofit(base).create(AlternativeService::class.java), auth = auth)
        notificationRepository = NotificationRepository(api = createNotificationRetrofit(base).create(NotificationService::class.java), auth = auth)
        tripRepository = TripRepository(api = createTripRetrofit(base).create(TripService::class.java), auth = auth)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun 장소_변경_제안_탭은_읽음_요청_뒤_그_감지의_대체_장소_화면을_연다() {
        setGraph()
        awaitList()

        composeRule.onNodeWithTag(TAG_ROW_PREFIX + NOTIF_SUGGESTION_ID).performClick()

        composeRule.waitUntil(WAIT_MILLIS) { composeRule.onAllNodesWithTag(TAG_CANDIDATE_PREFIX + "1").fetchSemanticsNodes().isNotEmpty() }
        composeRule.runOnIdle {
            assertEquals(listOf("/api/v1/notifications/$NOTIF_SUGGESTION_ID/read"), readRequests)
            assertEquals(
                AlternativePlacesRoute(NOTIF_DETECTION_ID, PROGRESS_TRIP_ID),
                navController.currentBackStackEntry?.toRoute<AlternativePlacesRoute>(),
            )
        }
    }

    @Test
    fun 도착_확인_탭은_여행명을_받아_그_여행의_진행_화면을_연다() {
        setGraph()
        awaitList()

        composeRule.onNodeWithTag(TAG_ROW_PREFIX + NOTIF_ARRIVAL_ID).performClick()

        composeRule.waitUntil(WAIT_MILLIS) { navController.currentBackStackEntry?.destination?.hasRoute<ActiveTravelRoute>() == true }
        composeRule.runOnIdle {
            assertEquals(listOf("/api/v1/notifications/$NOTIF_ARRIVAL_ID/read"), readRequests)
            assertEquals(ActiveTravelRoute(PROGRESS_TRIP_ID, "서울 여행"), navController.currentBackStackEntry?.toRoute<ActiveTravelRoute>())
        }
        composeRule.onNodeWithText("서울 여행").assertIsDisplayed()
        composeRule.waitUntil(WAIT_MILLIS) { composeRule.onAllNodesWithText("다음 장소").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun 여행이_삭제된_알림_탭은_진행_화면이_대상_없음을_안내하고_목록으로_돌아올_수_있다() {
        tripDeleted = true
        setGraph()
        awaitList()

        composeRule.onNodeWithTag(TAG_ROW_PREFIX + NOTIF_ARRIVAL_ID).performClick()

        composeRule.waitUntil(WAIT_MILLIS) { composeRule.onAllNodesWithText("이미 삭제되었거나 없는 여행입니다.").fetchSemanticsNodes().isNotEmpty() }
        composeRule.runOnIdle {
            assertEquals(ActiveTravelRoute(PROGRESS_TRIP_ID, ""), navController.currentBackStackEntry?.toRoute<ActiveTravelRoute>())
            navController.popBackStack()
        }
        composeRule.waitUntil(WAIT_MILLIS) { navController.currentBackStackEntry?.destination?.hasRoute<NotificationListRoute>() == true }
        awaitList()
    }

    @Test
    fun 진행_화면_헤더_벨은_알림_목록을_연다() {
        setGraph(start = ActiveTravelRoute(PROGRESS_TRIP_ID, "서울 여행"))
        composeRule.waitUntil(WAIT_MILLIS) { composeRule.onAllNodesWithTag(TAG_NOTIFICATIONS).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNodeWithTag(TAG_NOTIFICATIONS).performClick()

        composeRule.waitUntil(WAIT_MILLIS) { navController.currentBackStackEntry?.destination?.hasRoute<NotificationListRoute>() == true }
        awaitList()
    }

    @Test
    fun 감지_목록의_대체_장소_보기는_그_감지의_대체_장소_화면을_연다() {
        setGraph(start = VariableMonitorRoute(PROGRESS_TRIP_ID))
        composeRule.waitUntil(WAIT_MILLIS) { composeRule.onAllNodesWithTag(TAG_MONITOR_OPEN_PREFIX + NOTIF_DETECTION_ID).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNodeWithTag(TAG_MONITOR_OPEN_PREFIX + NOTIF_DETECTION_ID).performClick()

        composeRule.waitUntil(WAIT_MILLIS) { composeRule.onAllNodesWithTag(TAG_CANDIDATE_PREFIX + "1").fetchSemanticsNodes().isNotEmpty() }
        composeRule.runOnIdle {
            assertEquals(
                AlternativePlacesRoute(NOTIF_DETECTION_ID, PROGRESS_TRIP_ID),
                navController.currentBackStackEntry?.toRoute<AlternativePlacesRoute>(),
            )
        }
    }

    private fun awaitList() {
        composeRule.waitUntil(WAIT_MILLIS) { composeRule.onAllNodesWithTag(TAG_ROW_PREFIX + NOTIF_SUGGESTION_ID).fetchSemanticsNodes().isNotEmpty() }
    }

    /** `MainActivity.kt`의 `notificationGraph`·`progressGraph`·`alternativeGraph` 배선을 그대로 옮긴다. */
    private fun setGraph(start: Any = NotificationListRoute) {
        composeRule.setContent {
            navController = rememberNavController()
            GilpickTheme {
                NavHost(navController = navController, startDestination = start) {
                    notificationGraph(
                        navController,
                        onSessionExpired = {},
                        onOpenDetection = { detectionId, tripId -> navController.navigate(AlternativePlacesRoute(detectionId, tripId)) },
                        onOpenProgress = { tripId, tripName -> navController.navigate(ActiveTravelRoute(tripId, tripName)) },
                        repository = { notificationRepository },
                        tripName = { _, tripId -> tripNameFromServer(tripId) },
                        alternativeRepository = { alternativeRepository },
                    )
                    progressGraph(
                        navController,
                        onSessionExpired = {},
                        repository = { progressRepository },
                        itineraryRepository = { itineraryRepository },
                        alternativeRepository = { alternativeRepository },
                        onNotifications = { navController.navigate(NotificationListRoute) },
                        map = { _, _, modifier -> Box(modifier = modifier.fillMaxSize().testTag(TAG_FAKE_MAP)) },
                    )
                    alternativeGraph(
                        navController,
                        onSessionExpired = {},
                        onSelectPlace = {},
                        onDismissed = { navController.popBackStack() },
                        repository = { alternativeRepository },
                        map = { _, _, modifier -> Box(modifier = modifier.fillMaxSize().testTag(TAG_FAKE_MAP)) },
                    )
                }
            }
        }
    }

    /** `tripNameOf`와 같은 F002 조회를 MockWebServer 기반 repository로 한다. */
    private suspend fun tripNameFromServer(tripId: String): String =
        (tripRepository.getTrip(tripId) as? AuthResult.Success)?.value?.name ?: ""

    private fun tripJson() = """
        {"success":true,
         "data":{"tripId":"$PROGRESS_TRIP_ID","name":"서울 여행","startDate":"2026-09-07",
                 "endDate":"2026-09-09","status":"IN_PROGRESS","dayCount":3,"version":1},
         "meta":{"requestId":"$NOTIF_REQUEST_ID"}}
    """.trimIndent()

    private fun json(body: String) = MockResponse(code = 200, headers = JSON_HEADERS, body = body)

    private companion object {
        const val WAIT_MILLIS = 5_000L
        const val TAG_FAKE_MAP = "notification_nav_fake_map"
        val JSON_HEADERS: Headers = Headers.headersOf("Content-Type", "application/json")
    }
}
