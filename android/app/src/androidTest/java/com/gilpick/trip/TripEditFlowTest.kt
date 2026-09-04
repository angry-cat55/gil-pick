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

    /** 서버가 들고 있는 현재 여행. 수정 요청이 오면 version을 올린다. */
    private var storedName = "서울 여행"
    private var storedVersion = 1

    /** 서버가 거절한 요청. 비어 있어야 정상이다. */
    private val conflicts = mutableListOf<Int>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                if (request.method == "GET") return json(tripJson())

                // PATCH. 계약대로 version이 맞을 때만 수정한다(FR-011a).
                val body = request.body?.utf8().orEmpty()
                val sent = VERSION.find(body)?.groupValues?.get(1)?.toInt() ?: -1
                if (sent != storedVersion) {
                    conflicts += sent
                    return json(errorJson(TripErrorCodes.VERSION_CONFLICT), code = 409)
                }
                storedName = NAME.find(body)?.groupValues?.get(1) ?: storedName
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

        openEditor()
        rename("대구 여행")
        composeRule.onNodeWithText(string(R.string.trip_form_edit_submit)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.trip_detail_title)).assertIsDisplayed()
        composeRule.onNodeWithText("대구 여행").assertIsDisplayed()
        assertEquals(3, storedVersion)
        assertEquals(emptyList<Int>(), conflicts)
    }

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
                            TripDetailViewModel(repository = repository, tripId = tripId)
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
                        )
                    }
                    composable<EditRoute> { entry ->
                        val tripId = entry.toRoute<EditRoute>().tripId
                        val viewModel = remember(tripId) { TripFormViewModel(repository) }
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
                 "endDate":"2026-09-03","status":"UPCOMING","dayCount":3,
                 "version":$storedVersion},
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
    }
}
