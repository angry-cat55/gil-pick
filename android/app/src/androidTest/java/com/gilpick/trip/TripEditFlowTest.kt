package com.gilpick.trip

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.R
import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthService
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.createAuthRetrofit
import com.gilpick.itinerary.ItineraryRepository
import com.gilpick.itinerary.ItineraryService
import com.gilpick.itinerary.createItineraryRetrofit
import com.gilpick.progress.CurrentLocationProvider
import com.gilpick.progress.ProgressRepository
import com.gilpick.progress.ProgressService
import com.gilpick.progress.createProgressRetrofit
import com.gilpick.route.RouteRepository
import com.gilpick.route.RouteService
import com.gilpick.route.createRouteRetrofit
import com.gilpick.ui.theme.GilpickTheme
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T038: 상세 → 수정 → 저장 → 상세 복귀 흐름 검증.
 *
 * 화면 상태를 test가 직접 만들어 주는 다른 계측 test와 달리, 실제 view model과
 * repository를 그대로 쓰고 서버만 [MockWebServer]로 바꾼다. 낙관적 동시성 제어는
 * 요청에 실린 `version`이 맞아야 통과하므로, 화면이 최신 값을 다시 받아 오는지까지
 * 봐야 검증이 된다.
 *
 * 상세와 수정 화면은 진입할 때마다 서버에서 여행을 다시 받는다. `waitForIdle`은 그
 * 조회를 기다리지 않는다. compose의 idle 판정에는 view model coroutine과 network 왕복이
 * 들어가지 않기 때문이다. 그래서 화면을 만지기 전에 [awaitTrip]으로 값이 실제로 그려질
 * 때까지 기다린다.
 *
 * 실제 백엔드를 붙인 종단간 확인은 #108이 다룬다.
 */
@RunWith(AndroidJUnit4::class)
class TripEditFlowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var server: MockWebServer
    private lateinit var repository: TripRepository
    private lateinit var itineraryRepository: ItineraryRepository
    private lateinit var routeRepository: RouteRepository
    private lateinit var progressRepository: ProgressRepository

    /** 서버가 들고 있는 현재 여행. 수정 요청이 오면 version을 올린다. */
    private var storedName = "서울 여행"
    private var storedVersion = 1

    /** 서버가 들고 있는 종료일. 기간을 줄이면 삭제 확인을 요구한다. */
    private var storedEndDate = "2026-09-03"

    /** 새 기간 밖으로 밀려날 장소 수. 서버가 `CONFIRMATION_REQUIRED`에 실어 보낸다. */
    private var outOfRangeItemCount = 2

    /** 서버가 거절한 요청. 비어 있어야 정상이다. */
    private val conflicts = mutableListOf<Int>()

    /** 도착한 수정 요청의 `confirmDeleteOutOfRangeItems` 값. 순서대로 쌓인다. */
    private val updateConfirmFlags = mutableListOf<Boolean>()

    /** 수정 화면이 만든 view model. 기간 선택은 달력 대신 여기로 바꾼다. */
    private var formViewModel: TripFormViewModel? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                // F004 일정 개요. 이 test의 관심사가 아니므로 빈 일정을 준다.
                if (request.url.encodedPath.endsWith("/itinerary")) return json(itineraryJson())

                if (request.method == "GET") return json(tripJson())

                // PATCH. 계약대로 version이 맞을 때만 수정한다(FR-011a).
                val body = request.body?.utf8().orEmpty()
                val sent = VERSION.find(body)?.groupValues?.get(1)?.toInt() ?: -1
                if (sent != storedVersion) {
                    conflicts += sent
                    return json(errorJson(TripErrorCodes.VERSION_CONFLICT), code = 409)
                }
                val requestedEnd = END_DATE.find(body)?.groupValues?.get(1)
                val confirmed = body.contains(""""confirmDeleteOutOfRangeItems":true""")
                updateConfirmFlags += confirmed

                // 기간을 줄이면 새 기간 밖 장소가 삭제된다. 동의 없이는 저장하지 않고
                // 삭제될 수를 알린다(spec FR-013).
                val shrinking = requestedEnd != null && requestedEnd < storedEndDate
                if (shrinking && outOfRangeItemCount > 0 && !confirmed) {
                    return json(confirmationRequiredJson(), code = 409)
                }

                storedName = NAME.find(body)?.groupValues?.get(1) ?: storedName
                if (requestedEnd != null) storedEndDate = requestedEnd
                storedVersion += 1
                return json(tripJson())
            }
        }

        val auth = AuthRepository(
            store = AuthSessionStore.create(context),
            api = createAuthRetrofit(server.url("/api/v1/").toString())
                .create(AuthService::class.java),
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
                accessExpiresAtEpochSeconds = 3_600,
                refreshExpiresAtEpochSeconds = 2_592_000,
            )
        }
        repository = TripRepository(
            api = createTripRetrofit(server.url("/api/v1/").toString())
                .create(TripService::class.java),
            auth = auth,
        )
        itineraryRepository = ItineraryRepository(
            api = createItineraryRetrofit(server.url("/api/v1/").toString())
                .create(ItineraryService::class.java),
            auth = auth,
        )
        routeRepository = RouteRepository(
            api = createRouteRetrofit(server.url("/api/v1/").toString()).create(RouteService::class.java),
            auth = auth,
        )
        progressRepository = ProgressRepository(
            api = createProgressRetrofit(server.url("/api/v1/").toString()).create(ProgressService::class.java),
            auth = auth,
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun 상세에서_메뉴로_수정에_들어가_저장하면_상세로_돌아온다() {
        setGraph()

        awaitTrip()
        composeRule.onNodeWithText("서울 여행").assertIsDisplayed()
        openEditor()

        // 폼이 조회한 값으로 채워져 있어야 한다.
        composeRule.onNodeWithText("서울 여행").assertIsDisplayed()

        rename("부산 여행")
        composeRule.onNodeWithText(string(R.string.trip_form_edit_submit)).performClick()
        composeRule.waitForIdle()

        // 상세로 돌아오고, 돌아온 상세는 서버에서 다시 받은 값을 보여준다.
        awaitDetailAfterSave()
        composeRule.onNodeWithText(string(R.string.trip_detail_title)).assertIsDisplayed()
        composeRule.onNodeWithText("부산 여행").assertIsDisplayed()
        assertEquals(2, storedVersion)
    }

    @Test
    fun 연속으로_두_번_수정해도_버전_충돌이_나지_않는다() {
        // 상세가 복귀할 때 재조회하지 않으면 두 번째 저장이 낡은 version을 보내
        // 409 VERSION_CONFLICT로 실패한다. T038의 핵심이다.
        setGraph()

        openEditor()
        rename("부산 여행")
        composeRule.onNodeWithText(string(R.string.trip_form_edit_submit)).performClick()
        composeRule.waitForIdle()
        awaitDetailAfterSave()

        openEditor()
        rename("대구 여행")
        composeRule.onNodeWithText(string(R.string.trip_form_edit_submit)).performClick()
        composeRule.waitForIdle()

        awaitDetailAfterSave()
        composeRule.onNodeWithText(string(R.string.trip_detail_title)).assertIsDisplayed()
        composeRule.onNodeWithText("대구 여행").assertIsDisplayed()
        assertEquals(3, storedVersion)
        assertEquals(emptyList<Int>(), conflicts)
    }

    // --- T030: 기간 축소 삭제 확인 (US4 Acceptance 4) ---

    @Test
    fun 기간을_줄여_저장하면_삭제될_장소_수를_묻고_아직_저장하지_않는다() {
        setGraph()

        openEditor()
        shrinkPeriod()
        submit()

        awaitDialog()
        composeRule.onNodeWithText(shrinkTitle(2)).assertIsDisplayed()
        // 동의 전에는 서버가 그대로다.
        assertEquals("2026-09-03", storedEndDate)
        assertEquals(1, storedVersion)
        assertEquals(listOf(false), updateConfirmFlags)
    }

    @Test
    fun 대화상자에서_저장하기를_누르면_동의를_실어_다시_보내고_저장된다() {
        setGraph()

        openEditor()
        shrinkPeriod()
        submit()
        awaitDialog()

        composeRule.onNodeWithText(string(R.string.trip_form_shrink_confirm)).performClick()
        composeRule.waitForIdle()

        // 상세로 돌아오고 서버의 기간이 실제로 줄었다.
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule
                .onAllNodesWithText(string(R.string.trip_detail_title))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertEquals("2026-09-02", storedEndDate)
        assertEquals(2, storedVersion)
        // 같은 수정이 동의 없이 한 번, 동의를 실어 한 번 나갔다.
        assertEquals(listOf(false, true), updateConfirmFlags)
        assertEquals(emptyList<Int>(), conflicts)
    }

    @Test
    fun 대화상자에서_취소하면_저장하지_않고_여행_기간이_그대로다() {
        setGraph()

        openEditor()
        shrinkPeriod()
        submit()
        awaitDialog()

        composeRule.onNodeWithText(string(R.string.trip_form_shrink_cancel)).performClick()
        composeRule.waitForIdle()

        // 대화상자만 닫히고 수정 화면에 남는다.
        composeRule.onNodeWithText(shrinkTitle(2)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.trip_form_edit_title)).assertIsDisplayed()
        assertEquals("2026-09-03", storedEndDate)
        assertEquals(1, storedVersion)
        // 취소는 요청을 만들지 않는다.
        assertEquals(listOf(false), updateConfirmFlags)
    }

    @Test
    fun 기간_밖_장소가_없으면_확인_없이_저장된다() {
        outOfRangeItemCount = 0
        setGraph()

        openEditor()
        shrinkPeriod()
        submit()

        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule
                .onAllNodesWithText(string(R.string.trip_detail_title))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText(shrinkTitle(0)).assertDoesNotExist()
        assertEquals("2026-09-02", storedEndDate)
        assertEquals(listOf(false), updateConfirmFlags)
    }

    /**
     * 종료일을 하루 줄인다.
     *
     * 달력(`DateRangePicker`)을 눌러 고르는 대신 view model에 직접 넣는다. 이 test가
     * 확인하려는 것은 날짜를 고르는 방법이 아니라 **줄인 기간을 저장할 때 서버의 확인
     * 요구를 화면이 어떻게 다루는가**이고, 달력 조작은 그 흐름과 무관하게 기기 locale과
     * 월 이동에 따라 흔들린다.
     */
    private fun shrinkPeriod() {
        composeRule.runOnIdle {
            formViewModel?.onPeriodChange(
                java.time.LocalDate.of(2026, 9, 1),
                java.time.LocalDate.of(2026, 9, 2),
            )
        }
        composeRule.waitForIdle()
    }

    /** 수정 화면의 저장 버튼을 누른다. */
    private fun submit() {
        composeRule.onNodeWithText(string(R.string.trip_form_edit_submit)).performClick()
        composeRule.waitForIdle()
    }

    /** 확인 대화상자가 뜰 때까지 기다린다. 서버 왕복이 끝나야 나타난다. */
    private fun awaitDialog() {
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule
                .onAllNodesWithText(string(R.string.trip_form_shrink_cancel))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    /** 삭제 확인 대화상자의 제목. */
    private fun shrinkTitle(count: Int) =
        context.getString(R.string.trip_form_shrink_title, count)

    /**
     * 현재 여행명이 화면에 그려질 때까지 기다린다.
     *
     * 상세와 수정 화면 모두 진입 시 서버에서 여행을 다시 받는다. 조회가 끝나기 전에는
     * 상세의 `더보기`가 아예 없고(`phase`가 `Content`일 때만 그린다) 수정 폼의 입력란도
     * 비어 있다. 기다리지 않으면 기기 성능에 따라 결과가 갈린다.
     */
    private fun awaitTrip() {
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(storedName).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * 저장한 뒤 상세로 돌아와 서버 값이 그려질 때까지 기다린다.
     *
     * 여기서는 [awaitTrip]으로 부족하다. `popBackStack()` 직후에는 NavHost 전환이 아직
     * 끝나지 않아 **나가는 중인 수정 화면의 이름 입력란**이 semantics tree에 남아 있고,
     * 그 입력란에는 방금 입력한 새 이름이 이미 들어 있다. 존재 여부만 보는 [awaitTrip]은
     * 그 노드로 곧장 통과해 버리고, 뒤따르는 단언은 아직 화면에 없는 그 노드를 집어
     * `is not displayed`로 실패한다(#174).
     *
     * 그래서 **수정 화면이 사라진 것**과 **상세가 이름을 그린 것**을 함께 기다린다.
     * 전환이 늦든 재조회가 늦든 어느 쪽이든 안전하다.
     */
    private fun awaitDetailAfterSave() {
        val editTitle = string(R.string.trip_form_edit_title)
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(editTitle).fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithText(storedName).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** 상세에서 더보기 메뉴를 열어 수정 화면으로 들어간다. */
    private fun openEditor() {
        awaitTrip()
        composeRule.onNodeWithContentDescription(string(R.string.trip_detail_more)).performClick()
        composeRule.onNodeWithText(string(R.string.trip_detail_edit)).performClick()
        composeRule.waitForIdle()
    }

    /** 이름 입력을 비우고 새 이름을 넣는다. */
    private fun rename(name: String) {
        awaitTrip()
        composeRule.onNodeWithText(storedName).performTextClearance()
        composeRule.onNodeWithText(string(R.string.trip_form_name_label)).performTextInput(name)
    }

    /** 상세와 수정을 실제 view model로 연결한 graph를 띄운다. */
    private fun setGraph() {
        composeRule.setContent {
            GilpickTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = DetailRoute(TRIP_ID)) {
                    composable<DetailRoute> { entry ->
                        val tripId = entry.toRoute<DetailRoute>().tripId
                        val viewModel = remember(tripId) {
                            TripDetailViewModel(
                                repository = repository,
                                itineraryRepository = itineraryRepository,
                                routeRepository = routeRepository,
                                progressRepository = progressRepository,
                                locationProvider = CurrentLocationProvider { null },
                                tripId = tripId,
                            )
                        }
                        val state by viewModel.state.collectAsStateWithLifecycle()

                        LaunchedEffect(Unit) { viewModel.load() }

                        TripDetailScreen(
                            state = state,
                            onBack = { navController.popBackStack() },
                            onRetry = viewModel::retry,
                            onEdit = { navController.navigate(EditRoute(tripId)) },
                            onDelete = {},
                            onDeleteErrorShown = {},
                            onRetryItinerary = viewModel::retryItinerary,
                            onEditItinerary = {},
                            onAddPlace = {},
                            onSelectPlace = {},
                        )
                    }
                    composable<EditRoute> { entry ->
                        val tripId = entry.toRoute<EditRoute>().tripId
                        val viewModel = remember(tripId) {
                            TripFormViewModel(repository).also { formViewModel = it }
                        }
                        val state by viewModel.state.collectAsStateWithLifecycle()

                        LaunchedEffect(tripId) {
                            viewModel.loadForEdit(tripId)
                        }
                        LaunchedEffect(state.savedTripId) {
                            if (state.savedTripId != null) {
                                viewModel.consumeSaved()
                                navController.popBackStack()
                            }
                        }

                        TripFormScreen(
                            state = state,
                            onNameChange = viewModel::onNameChange,
                            onPeriodChange = viewModel::onPeriodChange,
                            onSubmit = viewModel::submit,
                            onConfirmDeleteOutOfRangeItems =
                                viewModel::confirmDeleteOutOfRangeItems,
                            onCancelDeleteConfirmation = viewModel::cancelDeleteConfirmation,
                        )
                    }
                }
            }
        }
    }

    private fun string(id: Int) = context.getString(id)

    private fun json(body: String, code: Int = 200) = MockResponse(
        code = code,
        headers = Headers.headersOf("Content-Type", "application/json"),
        body = body,
    )

    private fun tripJson() = """
        {"success":true,
         "data":{"tripId":"$TRIP_ID","name":"$storedName","startDate":"2026-09-01",
                 "endDate":"$storedEndDate","status":"UPCOMING","dayCount":3,
                 "version":$storedVersion},
         "meta":{"requestId":"$REQUEST_ID"}}
    """.trimIndent()

    private fun itineraryJson() = """
        {"success":true,
         "data":{"tripId":"$TRIP_ID","days":[]},
         "meta":{"requestId":"$REQUEST_ID"}}
    """.trimIndent()

    private fun confirmationRequiredJson() = """
        {"success":false,
         "error":{"code":"${TripErrorCodes.CONFIRMATION_REQUIRED}","message":"진단용 설명",
                  "retryable":false,"details":{"deletedItemCount":$outOfRangeItemCount}},
         "meta":{"requestId":"$REQUEST_ID"}}
    """.trimIndent()

    private fun errorJson(code: String) = """
        {"success":false,
         "error":{"code":"$code","message":"진단용 설명","retryable":false},
         "meta":{"requestId":"$REQUEST_ID"}}
    """.trimIndent()

    @Serializable
    private data class DetailRoute(val tripId: String)

    @Serializable
    private data class EditRoute(val tripId: String)

    private companion object {
        const val TRIP_ID = "33333333-4444-4555-8666-777777777777"
        const val REQUEST_ID = "11111111-2222-4333-8444-555555555555"
        const val TIMEOUT_MILLIS = 5_000L
        val VERSION = """"version":(\d+)""".toRegex()
        val NAME = """"name":"([^"]*)"""".toRegex()
        val END_DATE = """"endDate":"([^"]*)"""".toRegex()
    }
}
