package com.gilpick.alternative

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.platform.app.InstrumentationRegistry
import com.gilpick.auth.AuthAppLinkHandler
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
import com.gilpick.progress.TAG_VARIABLE_BANNER
import com.gilpick.progress.createProgressRetrofit
import com.gilpick.progress.movingProgress
import com.gilpick.progress.overviewJson
import com.gilpick.progress.progressGraph
import com.gilpick.progress.progressJson
import com.gilpick.progress.threeDays
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
 * T026: 진행 화면 배너 → 대체 장소 화면 진입, 후보 선택 → `onSelectPlace` 값, 거절 → 진행 화면 복귀 검증
 * (quickstart AND 2-1·2-3, AND 3-1).
 *
 * `MainActivity.kt`의 배선을 그대로 옮겨 [progressGraph]·[alternativeGraph]를 한 NavHost에 둔다. 개요·진행
 * 현황·감지·후보·거절은 [MockWebServer]가 준다. 진행 현황의 `date`(9/8)가 오늘 역할이라 배너 조건(오늘·당일
 * 미완료)을 만족한다. 지도는 SDK 인증이 필요해 자리 표시로 바꿔 끼운다.
 */
class AlternativeNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val server = MockWebServer()
    private lateinit var navController: NavHostController
    private lateinit var itineraryRepository: ItineraryRepository
    private lateinit var progressRepository: ProgressRepository
    private lateinit var alternativeRepository: AlternativeRepository

    /** DETECT-001 `status=ACTIVE` 응답. 거절 뒤에는 빈 목록으로 바꿔 배너 소멸을 확인한다. */
    private var activeListBody = activeDetectionsJson()
    private val dismissRequests = mutableListOf<String>()
    private val selected = mutableListOf<SelectedAlternative>()

    @Before
    fun setUp() {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/itinerary") -> json(overviewJson(threeDays()))
                    path.endsWith("/progress") -> json(progressJson(movingProgress()))
                    path.endsWith("/detections") -> json(activeListBody)
                    path.endsWith("/alternatives") -> json(alternativesJson())
                    path.endsWith("/dismiss") -> {
                        dismissRequests += path
                        json(dismissJson())
                    }
                    path.contains("/detections/") -> json(detectionDetailJson())
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
        itineraryRepository = ItineraryRepository(api = createItineraryRetrofit(base).create(ItineraryService::class.java), auth = auth)
        progressRepository = ProgressRepository(api = createProgressRetrofit(base).create(ProgressService::class.java), auth = auth)
        alternativeRepository = AlternativeRepository(api = createAlternativeRetrofit(base).create(AlternativeService::class.java), auth = auth)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun 배너를_누르면_그_감지의_대체_장소_화면이_열린다() {
        setGraph()
        awaitBanner()
        composeRule.onNodeWithText("경복궁 오후 2시 이후 강한 비 + 매우 높은 혼잡").assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_VARIABLE_BANNER).performClick()

        awaitAlternatives()
        composeRule.runOnIdle {
            assertEquals(
                AlternativePlacesRoute(DETECTION_ID, PROGRESS_TRIP_ID),
                navController.currentBackStackEntry?.toRoute<AlternativePlacesRoute>(),
            )
        }
        composeRule.onNodeWithText("경복궁 · 방문 어려움 감지").assertIsDisplayed()
    }

    @Test
    fun 후보를_고르면_onSelectPlace에_감지_후보_장소_값이_전달되고_일정은_그대로다() {
        setGraph()
        awaitBanner()
        composeRule.onNodeWithTag(TAG_VARIABLE_BANNER).performClick()
        awaitAlternatives()

        composeRule.onNodeWithText("경로 비교").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    SelectedAlternative(
                        detectionId = DETECTION_ID,
                        placeId = "tourapi:126508",
                        candidateId = CANDIDATE_ID,
                        name = "창덕궁",
                        distanceMeters = 820,
                        displayScore = 87,
                    ),
                ),
                selected,
            )
            // 선택은 값 전달뿐이다. 화면은 그대로이고 거절 요청도 없다.
            assertEquals(true, navController.currentBackStackEntry?.destination?.hasRoute<AlternativePlacesRoute>())
            assertEquals(emptyList<String>(), dismissRequests)
        }
    }

    @Test
    fun 기존_일정_그대로_진행은_거절을_보내고_진행_화면으로_돌아오며_배너가_사라진다() {
        setGraph()
        awaitBanner()
        composeRule.onNodeWithTag(TAG_VARIABLE_BANNER).performClick()
        awaitAlternatives()
        // 서버는 거절 뒤 `ACTIVE` 목록에서 그 감지를 뺀다(quickstart BE 6).
        activeListBody = emptyActiveList()

        composeRule.onNodeWithTag(TAG_KEEP).performScrollTo().performClick()

        composeRule.waitUntil(WAIT_MILLIS) { navController.currentBackStackEntry?.destination?.hasRoute<ActiveTravelRoute>() == true }
        composeRule.runOnIdle { assertEquals(listOf("/api/v1/detections/$DETECTION_ID/dismiss"), dismissRequests) }
        // 돌아오면 재개 조회가 배너를 다시 맞춘다.
        composeRule.waitUntil(WAIT_MILLIS) { composeRule.onAllNodesWithTag(TAG_VARIABLE_BANNER).fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithText("다음 장소").assertIsDisplayed()
    }

    private fun awaitBanner() {
        composeRule.waitUntil(WAIT_MILLIS) { composeRule.onAllNodesWithTag(TAG_VARIABLE_BANNER).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitAlternatives() {
        composeRule.waitUntil(WAIT_MILLIS) { composeRule.onAllNodesWithTag(TAG_CANDIDATE_PREFIX + "1").fetchSemanticsNodes().isNotEmpty() }
    }

    /** `MainActivity.kt`의 `progressGraph`·`alternativeGraph` 배선을 그대로 옮긴다. 시작 화면은 진행 화면이다. */
    private fun setGraph() {
        composeRule.setContent {
            navController = rememberNavController()
            GilpickTheme {
                NavHost(navController = navController, startDestination = ActiveTravelRoute(PROGRESS_TRIP_ID, "서울 여행")) {
                    progressGraph(
                        navController,
                        onSessionExpired = {},
                        repository = { progressRepository },
                        itineraryRepository = { itineraryRepository },
                        alternativeRepository = { alternativeRepository },
                        map = { _, _, modifier -> Box(modifier = modifier.fillMaxSize().testTag(TAG_FAKE_MAP)) },
                    )
                    alternativeGraph(
                        navController,
                        onSessionExpired = {},
                        onSelectPlace = { selected += it },
                        onDismissed = { navController.popBackStack() },
                        repository = { alternativeRepository },
                        map = { _, _, modifier -> Box(modifier = modifier.fillMaxSize().testTag(TAG_FAKE_MAP)) },
                    )
                }
            }
        }
    }

    private fun emptyActiveList() = """
        {"success": true, "data": {"items": []},
         "meta": {"requestId": "$ALT_REQUEST_ID", "pagination": {"nextCursor": null, "hasNext": false}}}
    """.trimIndent()

    private fun json(body: String) = MockResponse(
        code = 200,
        headers = Headers.headersOf("Content-Type", "application/json"),
        body = body,
    )

    private companion object {
        const val WAIT_MILLIS = 5_000L
        const val TAG_FAKE_MAP = "alternative_nav_fake_map"
    }
}
