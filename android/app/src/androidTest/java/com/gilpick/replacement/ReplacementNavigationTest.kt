package com.gilpick.replacement

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import com.gilpick.alternative.AlternativePlacesRoute
import com.gilpick.alternative.AlternativeRepository
import com.gilpick.alternative.AlternativeService
import com.gilpick.alternative.CANDIDATE_ID
import com.gilpick.alternative.DETECTION_ID
import com.gilpick.alternative.TAG_CANDIDATE_PREFIX
import com.gilpick.alternative.TAG_SEARCH
import com.gilpick.alternative.TAG_SEARCH_ROW_PREFIX
import com.gilpick.alternative.TRIP_ID
import com.gilpick.alternative.alternativeGraph
import com.gilpick.alternative.alternativesJson
import com.gilpick.alternative.createAlternativeRetrofit
import com.gilpick.alternative.detectionDetailJson
import com.gilpick.alternative.searchJson
import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthService
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.createAuthRetrofit
import com.gilpick.route.RouteService
import com.gilpick.route.createRouteRetrofit
import com.gilpick.route.readyRouteJson
import com.gilpick.route.RouteRepository
import com.gilpick.route.routeEnvelopeJson
import com.gilpick.ui.theme.GilpickTheme
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * T017: F009 후보 선택 → 미리보기 화면 진입과 `다른 후보 보기` 복귀 검증(quickstart FE 1, spec UI-003).
 *
 * `MainActivity.kt`의 배선을 그대로 옮겨 [alternativeGraph]·[replacementGraph]를 한 NavHost에 둔다.
 * **추천 후보와 직접 검색 양쪽 모두** 같은 화면으로 들어오고 `candidateId` 유무만 요청에 반영되는지
 * 확인한다(FR-004). 지도는 SDK 인증이 필요해 자리 표시로 바꿔 낀다.
 *
 * `com.gilpick.alternative`는 읽기만 한다. 이 test는 그 패키지의 화면과 fixture를 쓰되 고치지 않는다.
 */
class ReplacementNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val server = MockWebServer()
    private lateinit var navController: NavHostController
    private lateinit var alternativeRepository: AlternativeRepository
    private lateinit var replacementRepository: ReplacementRepository
    private lateinit var routeRepository: RouteRepository

    /** 도착한 REPL-001 요청 body. `candidateId` 유무를 확인한다. */
    private val previewRequests = mutableListOf<CreatePreviewRequest>()

    /** 도착한 REPL-003 폐기 요청 경로. */
    private val rejectRequests = mutableListOf<String>()

    @Before
    fun setUp() {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/reject") -> {
                        rejectRequests += path
                        MockResponse(code = 204)
                    }
                    path.endsWith("/route-previews") -> {
                        previewRequests += requestJson.decodeFromString<CreatePreviewRequest>(request.body!!.utf8())
                        json(routePreviewJson())
                    }
                    path.endsWith("/alternatives/search") -> json(searchJson())
                    path.endsWith("/alternatives") -> json(alternativesJson())
                    path.endsWith("/route") -> json(
                        routeEnvelopeJson(
                            routeStatus = "READY",
                            route = readyRouteJson(scheduleVersion = REPL_SCHEDULE_VERSION),
                            scheduleVersion = REPL_SCHEDULE_VERSION,
                        ),
                    )
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
        alternativeRepository = AlternativeRepository(
            api = createAlternativeRetrofit(base).create(AlternativeService::class.java),
            auth = auth,
        )
        replacementRepository = ReplacementRepository(
            api = createReplacementRetrofit(base).create(ReplacementService::class.java),
            auth = auth,
        )
        routeRepository = RouteRepository(
            api = createRouteRetrofit(base).create(RouteService::class.java),
            auth = auth,
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun 추천_후보를_고르면_미리보기_화면이_열리고_candidateId가_요청에_실린다() {
        setGraph()
        awaitCandidates()

        composeRule.onNodeWithText("경로 비교").performScrollTo().performClick()

        awaitPreview()
        composeRule.runOnIdle {
            assertEquals(true, navController.currentBackStackEntry?.destination?.hasRoute<RoutePreviewRoute>())
            val body = previewRequests.single()
            assertEquals(REPL_PLACE_ID, body.placeId)
            assertEquals(CANDIDATE_ID, body.candidateId)
            assertEquals(REPL_SCHEDULE_VERSION, body.scheduleVersion)
        }
        composeRule.onNodeWithText("경복궁 → 창덕궁").assertIsDisplayed()
    }

    @Test
    fun 직접_검색으로_고른_장소도_같은_화면이_열리고_candidateId가_없다() {
        // FR-004. 두 진입의 응답 형식은 같고 서버 검증 경로만 갈린다.
        setGraph()
        awaitCandidates()

        composeRule.onNodeWithTag(TAG_SEARCH).performScrollTo().performClick()
        // F009 직접 검색은 두 글자 이상을 입력하고 검색을 실행해야 결과가 온다(F009 US3 Scenario 4).
        composeRule.onNodeWithContentDescription("장소 이름 검색").performTextInput("궁궐")
        composeRule.onNodeWithContentDescription("장소 이름 검색").performImeAction()
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodesWithTag(TAG_SEARCH_ROW_PREFIX + REPL_PLACE_ID).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(TAG_SEARCH_ROW_PREFIX + REPL_PLACE_ID).performClick()

        awaitPreview()
        composeRule.runOnIdle {
            assertEquals(true, navController.currentBackStackEntry?.destination?.hasRoute<RoutePreviewRoute>())
            assertNull(previewRequests.single().candidateId)
        }
    }

    @Test
    fun 다른_후보_보기는_미리보기를_폐기하고_같은_감지의_후보_목록으로_돌아간다() {
        // UI-003. 돌아간 자리에서 다른 후보를 다시 고를 수 있어야 한다(US1 시나리오 3).
        setGraph()
        awaitCandidates()
        composeRule.onNodeWithText("경로 비교").performScrollTo().performClick()
        awaitPreview()

        composeRule.onNodeWithTag(TAG_OTHER_CANDIDATES).performScrollTo().performClick()

        composeRule.waitUntil(WAIT_MILLIS) {
            navController.currentBackStackEntry?.destination?.hasRoute<AlternativePlacesRoute>() == true
        }
        composeRule.waitUntil(WAIT_MILLIS) { rejectRequests.isNotEmpty() }
        composeRule.runOnIdle {
            assertEquals("/api/v1/route-previews/$REPL_PREVIEW_ID/reject", rejectRequests.single())
        }
        // 후보 목록이 그대로라 다른 후보를 다시 고를 수 있다.
        awaitCandidates()
        composeRule.onNodeWithText("경복궁 · 방문 어려움 감지").assertIsDisplayed()
    }

    @Test
    fun 뒤로_가기도_미리보기를_폐기한다() {
        // 남은 PENDING 미리보기를 서버에 쌓지 않는다(FR-006).
        setGraph()
        awaitCandidates()
        composeRule.onNodeWithText("경로 비교").performScrollTo().performClick()
        awaitPreview()

        composeRule.onNodeWithTag(TAG_BACK).performClick()

        composeRule.waitUntil(WAIT_MILLIS) { rejectRequests.isNotEmpty() }
        composeRule.runOnIdle { assertTrue(rejectRequests.isNotEmpty()) }
    }

    private fun awaitCandidates() {
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodesWithTag(TAG_CANDIDATE_PREFIX + "1").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitPreview() {
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodesWithTag(TAG_CHANGE_SUMMARY).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** `MainActivity.kt`의 `alternativeGraph`·`replacementGraph` 배선을 그대로 옮긴다. */
    private fun setGraph() {
        composeRule.setContent {
            navController = rememberNavController()
            GilpickTheme {
                NavHost(
                    navController = navController,
                    startDestination = AlternativePlacesRoute(DETECTION_ID, TRIP_ID),
                ) {
                    alternativeGraph(
                        navController,
                        onSessionExpired = {},
                        onSelectPlace = { selected ->
                            navController.navigate(
                                RoutePreviewRoute(
                                    detectionId = selected.detectionId,
                                    placeId = selected.placeId,
                                    candidateId = selected.candidateId,
                                ),
                            )
                        },
                        onDismissed = { navController.popBackStack() },
                        repository = { alternativeRepository },
                        map = { _, _, modifier -> Box(modifier = modifier.fillMaxSize().testTag(TAG_FAKE_MAP)) },
                    )
                    replacementGraph(
                        navController,
                        onSessionExpired = {},
                        replacements = { replacementRepository },
                        detections = { alternativeRepository },
                        routes = { routeRepository },
                        map = { _, modifier -> Box(modifier = modifier.fillMaxSize().testTag(TAG_FAKE_MAP)) },
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

    private companion object {
        const val WAIT_MILLIS = 5_000L
        const val TAG_FAKE_MAP = "replacement_nav_fake_map"
        val requestJson = Json { ignoreUnknownKeys = true }
    }
}
