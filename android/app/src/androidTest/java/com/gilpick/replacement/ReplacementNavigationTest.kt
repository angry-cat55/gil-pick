package com.gilpick.replacement

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.platform.app.InstrumentationRegistry
import com.gilpick.itinerary.ItineraryRepository
import com.gilpick.itinerary.ItineraryService
import com.gilpick.itinerary.createItineraryRetrofit
import com.gilpick.progress.ActiveTravelRoute
import com.gilpick.ui.component.TAG_HEADER_BACK
import com.gilpick.ui.component.TAG_SHEET_HANDLE
import com.gilpick.alternative.AlternativePlacesRoute
import com.gilpick.alternative.AlternativeRepository
import com.gilpick.alternative.AlternativeService
import com.gilpick.alternative.CANDIDATE_ID
import com.gilpick.alternative.DETECTION_ID
import com.gilpick.alternative.TAG_CANDIDATE_PREFIX
import com.gilpick.alternative.TAG_COMPARE_PREFIX
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
    private lateinit var itineraryRepository: ItineraryRepository

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
                    path.endsWith("/approve") -> json(approvedReplacementJson())
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
        itineraryRepository = ItineraryRepository(
            api = createItineraryRetrofit(base).create(ItineraryService::class.java),
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

        composeRule.onNodeWithTag(TAG_COMPARE_PREFIX + 1).performScrollTo().performClick()

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

        composeRule.onNodeWithTag(TAG_SEARCH).performClick()
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
        composeRule.onNodeWithTag(TAG_COMPARE_PREFIX + 1).performScrollTo().performClick()
        awaitPreview()

        composeRule.onNodeWithTag(TAG_OTHER_CANDIDATES).performClick()

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

    /** #660: 떠나기 전에 바꿔 둔 후보 목록 sheet 높이가 돌아왔을 때도 그대로여야 한다. */
    @Test
    fun 미리보기에서_돌아오면_후보_목록_sheet_상태가_유지된다() {
        setGraph()
        awaitCandidates()
        val handle = composeRule.onNodeWithTag(TAG_SHEET_HANDLE)
        handle.performClick()
        composeRule.waitForIdle()
        handle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "펼침"))

        composeRule.onNodeWithTag(TAG_COMPARE_PREFIX + 1).performScrollTo().performClick()
        awaitPreview()
        composeRule.onNodeWithTag(TAG_OTHER_CANDIDATES).performClick()

        awaitCandidates()
        composeRule.onNodeWithTag(TAG_SHEET_HANDLE).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "펼침"))
    }

    @Test
    fun 뒤로_가기도_미리보기를_폐기한다() {
        // 남은 PENDING 미리보기를 서버에 쌓지 않는다(FR-006).
        setGraph()
        awaitCandidates()
        composeRule.onNodeWithTag(TAG_COMPARE_PREFIX + 1).performScrollTo().performClick()
        awaitPreview()

        composeRule.onNodeWithTag(TAG_HEADER_BACK).performClick()

        composeRule.waitUntil(WAIT_MILLIS) { rejectRequests.isNotEmpty() }
        composeRule.runOnIdle { assertTrue(rejectRequests.isNotEmpty()) }
    }

    /**
     * #624: 알림으로 바로 들어와 진행 화면이 back stack에 없어도 승인 후 그 여행의 진행 화면으로 간다.
     * 뒤로 가기로 승인된 미리보기·후보 화면이 다시 나타나지 않는지도 함께 본다.
     */
    @Test
    fun 진행_화면_없이_알림으로_들어와_승인해도_그_여행의_진행_화면으로_간다() {
        setGraph()
        awaitCandidates()
        composeRule.onNodeWithTag(TAG_COMPARE_PREFIX + 1).performScrollTo().performClick()
        awaitPreview()

        composeRule.onNodeWithTag(TAG_APPROVE).performClick()
        continueAfterApproval()

        composeRule.waitUntil(WAIT_MILLIS) {
            navController.currentBackStackEntry?.destination?.hasRoute<ActiveTravelRoute>() == true
        }
        composeRule.runOnIdle {
            assertEquals(REPL_TRIP_ID, navController.currentBackStackEntry?.toRoute<ActiveTravelRoute>()?.tripId)
            // 후보·미리보기가 걷혀 뒤로 가기로 돌아갈 화면이 없다.
            assertEquals(false, navController.popBackStack())
        }
    }

    /** #624: 진행 화면에서 시작한 기존 흐름은 그대로 그 진행 화면으로 돌아간다. */
    @Test
    fun 진행_화면에서_시작하면_승인_후_같은_진행_화면으로_돌아간다() {
        setGraph(startFromProgress = true)
        composeRule.runOnIdle {
            navController.navigate(AlternativePlacesRoute(DETECTION_ID, TRIP_ID))
        }
        awaitCandidates()
        composeRule.onNodeWithTag(TAG_COMPARE_PREFIX + 1).performScrollTo().performClick()
        awaitPreview()

        composeRule.onNodeWithTag(TAG_APPROVE).performClick()
        continueAfterApproval()

        composeRule.waitUntil(WAIT_MILLIS) {
            navController.currentBackStackEntry?.destination?.hasRoute<ActiveTravelRoute>() == true
        }
        composeRule.runOnIdle {
            // 새로 열지 않고 원래 화면으로 돌아왔으므로 처음 넣은 여행명이 그대로다.
            assertEquals(PROGRESS_TRIP_NAME, navController.currentBackStackEntry?.toRoute<ActiveTravelRoute>()?.tripName)
        }
    }

    /** #736: 승인이 끝나면 완료 모양이 보이고, `여행 진행 화면으로 돌아가기`를 눌러야 떠난다. */
    private fun continueAfterApproval() {
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodesWithText("경로 재생성 완료!").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(TAG_RECALC_CONTINUE).performClick()
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
    private fun setGraph(startFromProgress: Boolean = false) {
        composeRule.setContent {
            navController = rememberNavController()
            GilpickTheme {
                NavHost(
                    navController = navController,
                    startDestination = if (startFromProgress) {
                        ActiveTravelRoute(TRIP_ID, PROGRESS_TRIP_NAME)
                    } else {
                        AlternativePlacesRoute(DETECTION_ID, TRIP_ID)
                    },
                ) {
                    // 진행 화면은 이 test의 관심사가 아니라 자리만 둔다. 도착 여부와 route 인자만 확인한다.
                    composable<ActiveTravelRoute> { Box(modifier = Modifier.fillMaxSize().testTag(TAG_FAKE_PROGRESS)) }
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
                        map = { _, _, _, modifier -> Box(modifier = modifier.fillMaxSize().testTag(TAG_FAKE_MAP)) },
                    )
                    replacementGraph(
                        navController,
                        onSessionExpired = {},
                        replacements = { replacementRepository },
                        detections = { alternativeRepository },
                        routes = { routeRepository },
                        // 일정 개요는 이 test의 가짜 서버가 404를 준다. 앱은 ETA 날짜로 대체 판정한다(#593).
                        itineraries = { itineraryRepository },
                        // 여행명 조회는 실제 서버 호출이라 test에서는 고정값으로 바꿔 낀다(#624).
                        tripName = { _, _ -> APPROVED_TRIP_NAME },
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

private const val TAG_FAKE_PROGRESS = "fake_progress"
private const val PROGRESS_TRIP_NAME = "서울 여행"
private const val APPROVED_TRIP_NAME = "승인 후 여행"
