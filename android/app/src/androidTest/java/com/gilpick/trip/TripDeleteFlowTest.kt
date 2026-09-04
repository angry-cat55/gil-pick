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
 * T044: 목록 → 상세 → 삭제 → 목록 복귀 흐름 검증.
 *
 * 화면 상태를 test가 만들어 주는 다른 계측 test와 달리 실제 view model과 repository를
 * 그대로 쓰고 서버만 [MockWebServer]로 바꾼다. 이 test가 확인하려는 것은 화면 문구가
 * 아니라 **삭제한 여행이 목록에서 실제로 빠지는가**이고, 그건 서버 상태와 화면 재조회가
 * 맞물려야만 성립하기 때문이다.
 *
 * 특히 `TripListViewModel`에 삭제를 알리는 경로를 두지 않았다는 점을 검증한다. 상세에서
 * 목록으로 돌아갈 때 목록이 다시 조회하므로(`MainActivity`의 `LaunchedEffect(Unit)`)
 * 삭제된 여행은 돌아간 화면에서 이미 빠져 있다. 이 test의 graph도 같은 구조를 쓴다.
 *
 * 실제 백엔드를 붙인 종단간 확인은 #108이 다룬다.
 */
@RunWith(AndroidJUnit4::class)
class TripDeleteFlowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var server: MockWebServer
    private lateinit var repository: TripRepository

    /** 서버가 들고 있는 여행. 삭제되면 목록에서 빠지고 상세는 404가 된다. */
    private var deleted = false

    /** 서버가 받은 DELETE 요청 수. 멱등 검증과 중복 요청 방지 확인에 쓴다. */
    private var deleteCount = 0

    /** 삭제 요청에 대한 응답 코드. 실패 흐름 test가 바꾼다. */
    private var deleteResponse = 204

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                val path = request.url.encodedPath

                if (request.method == "DELETE") {
                    deleteCount += 1
                    if (deleteResponse != 204) {
                        return json(errorJson(TripErrorCodes.FORBIDDEN), code = deleteResponse)
                    }
                    deleted = true
                    return MockResponse(code = 204)
                }

                // 목록. 삭제된 뒤에는 빈 목록을 준다(FR-015).
                if (path.endsWith("/trips")) return json(listJson())

                // 상세. 삭제된 여행은 계약대로 404다.
                if (deleted) return json(errorJson(TripErrorCodes.TRIP_NOT_FOUND), code = 404)
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

    // --- 1. 삭제 성공 흐름 ---

    @Test
    fun 상세에서_삭제하면_목록으로_돌아가고_그_여행이_사라진다() {
        setGraph()

        // 목록에 여행이 하나 보인다. openDetail이 카드가 그려질 때까지 기다린다.
        openDetail()

        // 상세에 들어왔다.
        composeRule.onNodeWithText(string(R.string.trip_detail_title)).assertIsDisplayed()

        confirmDelete()

        // 목록으로 돌아왔고, 삭제한 여행은 목록에 없다. TripListViewModel에는 삭제를
        // 알리는 경로가 없다. 복귀 시 재조회만으로 빠진다.
        composeRule.onNodeWithText(string(R.string.trips_title)).assertIsDisplayed()
        composeRule.onNodeWithText(TRIP_NAME).assertDoesNotExist()
        assertEquals(1, deleteCount)
    }

    @Test
    fun 삭제한_뒤_목록은_빈_상태를_보여준다() {
        setGraph()

        openDetail()
        confirmDelete()

        // 마지막 여행을 지웠으므로 목록은 empty가 된다(가이드라인 9절).
        composeRule.onNodeWithText(string(R.string.trips_empty)).assertIsDisplayed()
    }

    // --- 2. 확인 절차 ---

    @Test
    fun 메뉴에서_삭제를_눌러도_확인_전에는_요청하지_않는다() {
        setGraph()

        openDetail()

        openMenu()
        composeRule.onNodeWithText(string(R.string.trip_detail_delete)).performClick()
        composeRule.waitForIdle()

        // 다이얼로그가 떴을 뿐 아직 아무것도 지워지지 않았다.
        composeRule.onNodeWithText(string(R.string.trip_delete_title)).assertIsDisplayed()
        assertEquals(0, deleteCount)
    }

    @Test
    fun 확인_다이얼로그에서_취소하면_여행이_남는다() {
        setGraph()

        openDetail()

        openMenu()
        composeRule.onNodeWithText(string(R.string.trip_detail_delete)).performClick()
        composeRule.onNodeWithText(string(R.string.trip_delete_cancel)).performClick()
        composeRule.waitForIdle()

        assertEquals(0, deleteCount)
        // 상세에 그대로 있다.
        composeRule.onNodeWithText(string(R.string.trip_detail_title)).assertIsDisplayed()
    }

    @Test
    fun 확인_다이얼로그는_삭제할_여행의_이름을_보여준다() {
        setGraph()

        openDetail()

        openMenu()
        composeRule.onNodeWithText(string(R.string.trip_detail_delete)).performClick()

        composeRule
            .onNodeWithText(context.getString(R.string.trip_delete_body, TRIP_NAME))
            .assertIsDisplayed()
    }

    // --- 3. 삭제 실패 ---

    @Test
    fun 삭제에_실패하면_다이얼로그에_안내가_남고_여행은_지워지지_않는다() {
        deleteResponse = 403
        setGraph()

        openDetail()
        confirmDelete()

        composeRule
            .onNodeWithText(string(R.string.trip_delete_error_forbidden))
            .assertIsDisplayed()
        // 상세에 머문다. 목록으로 돌아가지 않는다.
        composeRule.onNodeWithText(string(R.string.trip_detail_title)).assertIsDisplayed()
    }

    /**
     * 목록에서 여행 카드를 눌러 상세로 들어간다.
     *
     * 목록 조회는 `MockWebServer` 왕복이라 `waitForIdle`만으로는 끝났는지 알 수 없다.
     * compose는 idle 판정에 view model coroutine을 포함하지 않으므로, 카드가 실제로
     * 그려질 때까지 기다린 뒤 누른다.
     */
    private fun openDetail() {
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(TRIP_NAME).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(TRIP_NAME).performClick()
        composeRule.waitForIdle()
    }

    /** 상세에서 더보기 메뉴를 연다. */
    private fun openMenu() {
        composeRule.onNodeWithContentDescription(string(R.string.trip_detail_more)).performClick()
    }

    /** 메뉴에서 삭제를 고르고 다이얼로그에서 확정한다. */
    private fun confirmDelete() {
        openMenu()
        composeRule.onNodeWithText(string(R.string.trip_detail_delete)).performClick()
        composeRule.onNodeWithText(string(R.string.trip_delete_confirm)).performClick()
        composeRule.waitForIdle()
    }

    /**
     * 목록과 상세를 실제 view model로 연결한 graph를 띄운다.
     *
     * `MainActivity`와 같은 구조를 쓴다. 목록은 진입할 때마다 다시 조회하고, 상세는
     * 삭제가 끝나면 `popBackStack()`으로 돌아간다.
     */
    private fun setGraph() {
        composeRule.setContent {
            GilpickTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = ListRoute) {
                    composable<ListRoute> {
                        val viewModel = remember { TripListViewModel(repository) }
                        val state by viewModel.state.collectAsStateWithLifecycle()

                        LaunchedEffect(Unit) { viewModel.load() }

                        TripListScreen(
                            state = state,
                            onQueryChange = viewModel::onQueryChange,
                            onStatusFilterChange = viewModel::onStatusFilterChange,
                            onRetry = viewModel::retry,
                            onLoadMore = viewModel::loadMore,
                            onCreateTrip = {},
                            onTripClick = { navController.navigate(DetailRoute(it)) },
                        )
                    }
                    composable<DetailRoute> { entry ->
                        val tripId = entry.toRoute<DetailRoute>().tripId
                        val viewModel = remember(tripId) {
                            TripDetailViewModel(repository = repository, tripId = tripId)
                        }
                        val state by viewModel.state.collectAsStateWithLifecycle()

                        LaunchedEffect(Unit) { viewModel.load() }
                        LaunchedEffect(state.deletion) {
                            if (state.deletion is TripDeletePhase.Deleted) {
                                viewModel.consumeDeleted()
                                navController.popBackStack()
                            }
                        }

                        TripDetailScreen(
                            state = state,
                            onBack = { navController.popBackStack() },
                            onRetry = viewModel::retry,
                            onEdit = {},
                            onDelete = viewModel::delete,
                            onDeleteErrorShown = viewModel::clearDeleteError,
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

    private fun listJson(): String {
        val items = if (deleted) "" else tripObject()
        return """
            {"success":true,
             "data":{"items":[$items]},
             "meta":{"requestId":"$REQUEST_ID",
                     "pagination":{"nextCursor":null,"hasNext":false}}}
        """.trimIndent()
    }

    private fun tripJson() = """
        {"success":true,
         "data":${tripObject()},
         "meta":{"requestId":"$REQUEST_ID"}}
    """.trimIndent()

    private fun tripObject() = """
        {"tripId":"$TRIP_ID","name":"$TRIP_NAME","startDate":"2026-09-01",
         "endDate":"2026-09-03","status":"UPCOMING","dayCount":3,"version":1}
    """.trimIndent()

    private fun errorJson(code: String) = """
        {"success":false,
         "error":{"code":"$code","message":"진단용 설명","retryable":false},
         "meta":{"requestId":"$REQUEST_ID"}}
    """.trimIndent()

    @Serializable
    private object ListRoute

    @Serializable
    private data class DetailRoute(val tripId: String)

    private companion object {
        const val TRIP_ID = "33333333-4444-4555-8666-777777777777"
        const val TRIP_NAME = "서울 여행"
        const val REQUEST_ID = "11111111-2222-4333-8444-555555555555"
        const val TIMEOUT_MILLIS = 5_000L
    }
}
