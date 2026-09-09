package com.gilpick.alternative

import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeAuthService
import com.gilpick.auth.FakeSessionCipher
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T032: 직접 검색 ViewModel 상태 전이 검증(spec US3 Scenario 1·4, FR-015).
 *
 * 2글자 검증은 요청 전에 끝나고, 다음 페이지는 cursor로 이어 받으며 `placeId`가 겹치면 버린다.
 * 선택은 `candidateId = null`인 [SelectedAlternative]다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlternativeSearchViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val service = FakeAlternativeService()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `공백을 뗀 검색어가 2글자 미만이면 요청하지 않고 안내한다`() = runTest {
        val viewModel = newViewModel()

        viewModel.onQueryChange(" 궁 ")
        viewModel.search()
        advanceUntilIdle()

        assertEquals(AlternativeSearchPhase.TooShort, viewModel.state.value.phase)
        assertEquals(emptyList<Pair<String, String?>>(), service.searchCalls)
    }

    @Test
    fun `검색은 trim한 검색어로 첫 페이지를 받고 다음 페이지는 cursor로 이어 붙이며 중복은 버린다`() = runTest {
        service.onSearch = { cursor -> search(if (cursor == null) searchJson() else secondPageJson()) }
        val viewModel = newViewModel()

        viewModel.onQueryChange(" 궁궐 ")
        viewModel.search()
        advanceUntilIdle()

        var state = viewModel.state.value
        assertEquals(AlternativeSearchPhase.Content, state.phase)
        assertEquals("궁궐", state.committedQuery)
        assertEquals(listOf("tourapi:126508", "google:ChIJ_abc-123", "tourapi:126001"), state.results.map { it.place.placeId })
        assertTrue(state.hasNext)

        viewModel.loadMore()
        advanceUntilIdle()

        state = viewModel.state.value
        assertEquals(listOf("궁궐" to null, "궁궐" to "c2Vjb25k"), service.searchCalls)
        // 두 번째 페이지의 창덕궁(126508)은 이미 있어 버리고 덕수궁만 붙는다.
        assertEquals(listOf("tourapi:126508", "google:ChIJ_abc-123", "tourapi:126001", "tourapi:126002"), state.results.map { it.place.placeId })
        assertFalse(state.hasNext)
        assertFalse(state.loadingMore)
        assertNull(state.loadMoreError)
    }

    @Test
    fun `추가 조회 실패는 기존 결과를 남기고 원인만 붙이며 재시도로만 다시 받는다`() = runTest {
        var fail = false
        service.onSearch = { cursor -> if (fail) throw IOException("끊김") else search(if (cursor == null) searchJson() else secondPageJson()) }
        val viewModel = newViewModel()
        viewModel.onQueryChange("궁궐")
        viewModel.search()
        advanceUntilIdle()

        fail = true
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(AlternativeError.Network, viewModel.state.value.loadMoreError)
        assertEquals(3, viewModel.state.value.results.size)

        // 목록 끝에 다시 닿아도 자동으로 재시도하지 않는다.
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(2, service.searchCalls.size)

        fail = false
        viewModel.retryLoadMore()
        advanceUntilIdle()
        assertNull(viewModel.state.value.loadMoreError)
        assertEquals(4, viewModel.state.value.results.size)
    }

    @Test
    fun `첫 페이지 실패는 failed이고 409는 처리된 감지로 알린다`() = runTest {
        service.onSearch = { fail(409, AlternativeErrorCodes.DETECTION_NOT_ACTIVE, details = """{"status": "DISMISSED"}""") }
        val viewModel = newViewModel()

        viewModel.onQueryChange("궁궐")
        viewModel.search()
        advanceUntilIdle()

        assertEquals(AlternativeSearchPhase.Failed(AlternativeError.NotActive(DetectionStatus.DISMISSED)), viewModel.state.value.phase)
    }

    @Test
    fun `선택은 candidateId와 점수가 없는 값이다`() = runTest {
        val viewModel = newViewModel()
        viewModel.onQueryChange("궁궐")
        viewModel.search()
        advanceUntilIdle()
        val first = viewModel.state.value.results[0]

        assertEquals(
            SelectedAlternative(
                detectionId = DETECTION_ID,
                placeId = "tourapi:126508",
                candidateId = null,
                name = "창덕궁",
                distanceMeters = 820,
                displayScore = null,
            ),
            viewModel.select(first),
        )
    }

    /** 두 번째 페이지: 첫 페이지의 창덕궁이 다시 오고 덕수궁이 새로 온다. 마지막 페이지다. */
    private fun secondPageJson() = """
        {"success": true,
         "data": {"items": [
           {"place": ${tourPlaceJson()},
            "distanceMeters": 820,
            "operatingStatus": "OPEN",
            "visitable": true,
            "inSchedule": false},
           {"place": ${tourPlaceJson(placeId = "tourapi:126002", name = "덕수궁")},
            "distanceMeters": 1500,
            "operatingStatus": "OPEN",
            "visitable": true,
            "inSchedule": false}
         ]},
         "meta": {"requestId": "$ALT_REQUEST_ID", "pagination": {"nextCursor": null, "hasNext": false}}}
    """.trimIndent()

    private suspend fun newViewModel(): AlternativeSearchViewModel =
        AlternativeSearchViewModel(repository = AlternativeRepository(api = service, auth = auth()), detectionId = DETECTION_ID)

    /** 로그인된 session을 가진 인증 계층. `AlternativeViewModelTest`와 같다. */
    private suspend fun auth(): AuthRepository {
        val store = AuthSessionStore(
            AuthSessionStore.createDataStore(
                File(tempFolder.newFolder(), AuthSessionStore.FILE_NAME),
                scope = CoroutineScope(dispatcher + SupervisorJob()),
            ),
            FakeSessionCipher(),
        )
        val auth = AuthRepository(
            store = store,
            api = FakeAuthService,
            appLinkHandler = AuthAppLinkHandler("app.gilpick.example"),
            scope = CoroutineScope(dispatcher + SupervisorJob()),
        )
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
        return auth
    }
}
