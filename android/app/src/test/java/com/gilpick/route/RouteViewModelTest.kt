package com.gilpick.route

import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeAuthService
import com.gilpick.auth.FakeSessionCipher
import com.gilpick.itinerary.RouteStatus
import java.io.File
import java.io.IOException
import java.time.LocalDate
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T022: 경로 화면 ViewModel 상태 전이 검증.
 *
 * `spec.md` US2 Acceptance 4~7, US3 Acceptance 6, FR-014·020, UI-003이 대상이다. HTTP 왕복은
 * `RouteRepositoryTest`가 보므로 여기서는 [FakeRouteService]로 응답만 정한다.
 *
 * 1초 대기 표시 지연은 다른 화면과 같이 composable(`DayRouteScreen`)이 맡는다. 여기서는 응답 전
 * 상태가 [RouteUiState.Loading]임을, 지연 규칙은 `DayRouteScreenTest`가 확인한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val service = FakeRouteService()
    private val date = LocalDate.of(2026, 9, 8)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `응답 전에는 loading이고 생성 시 한 번 조회한다`() = runTest {
        service.onGet = { routeOk(dayRoute(RouteStatus.READY, route = readyRoute())) }

        val viewModel = newViewModel()

        assertEquals(RouteUiState.Loading, viewModel.state.value)
        advanceUntilIdle()
        assertEquals(listOf(ROUTE_DATE), service.getCalls)
    }

    @Test
    fun `장소가 0곳이면 empty다`() = runTest {
        service.onGet = { routeOk(dayRoute(RouteStatus.NOT_CALCULATED, scheduleVersion = 0)) }

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(RouteUiState.Empty, viewModel.state.value)
    }

    @Test
    fun `장소가 1곳이면 구간 없이 합계 0인 content다`() = runTest {
        val single = readyRoute().copy(
            totalDurationSeconds = 0,
            totalDistanceMeters = 0,
            markers = listOf(RouteMarkerDto(ITEM_A, 1, "경복궁", 37.5796, 126.977)),
            segments = emptyList(),
            providerAttributions = emptyList(),
        )
        service.onGet = { routeOk(dayRoute(RouteStatus.READY, route = single)) }

        val viewModel = newViewModel()
        advanceUntilIdle()

        val content = viewModel.state.value as RouteUiState.Content
        assertTrue(content.route.segments.isEmpty())
        assertEquals(0, content.route.totalDurationSeconds)
        assertEquals(0, content.route.totalDistanceMeters)
        assertEquals(1, content.route.markers.size)
    }

    @Test
    fun `READY면 경로를 담은 content다`() = runTest {
        service.onGet = { routeOk(dayRoute(RouteStatus.READY, route = readyRoute())) }

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(RouteUiState.Content(readyRoute()), viewModel.state.value)
    }

    @Test
    fun `FAILED면 실패 원인과 version을 담은 error다`() = runTest {
        service.onGet = { routeOk(dayRoute(RouteStatus.FAILED, failure = routeFailure(RouteFailureCodes.NOT_FOUND, retryable = false))) }

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(
            RouteUiState.Error(RouteProblem.Calculation(routeFailure(RouteFailureCodes.NOT_FOUND, retryable = false), scheduleVersion = 3)),
            viewModel.state.value,
        )
    }

    @Test
    fun `통신 실패는 network error다`() = runTest {
        service.onGet = { throw IOException("끊김") }

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(RouteUiState.Error(RouteProblem.Request(RouteError.Network)), viewModel.state.value)
    }

    @Test
    fun `일정 version과 다른 이전 경로는 content로 보이지 않는다`() = runTest {
        service.onGet = { routeOk(dayRoute(RouteStatus.READY, route = readyRoute(scheduleVersion = 2), scheduleVersion = 3)) }

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(RouteUiState.Error(RouteProblem.Stale), viewModel.state.value)
    }

    @Test
    fun `다시 시도하면 loading을 거쳐 같은 화면이 content로 갱신된다`() = runTest {
        var fail = true
        service.onGet = { if (fail) throw IOException("끊김") else routeOk(dayRoute(RouteStatus.READY, route = readyRoute())) }
        val viewModel = newViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.state.value is RouteUiState.Error)

        fail = false
        viewModel.load()
        assertEquals(RouteUiState.Loading, viewModel.state.value)
        advanceUntilIdle()

        assertEquals(RouteUiState.Content(readyRoute()), viewModel.state.value)
        assertEquals(2, service.getCalls.size)
    }

    @Test
    fun `조회 중에 다시 시도해도 조회를 겹치지 않는다`() = runTest {
        service.onGet = { routeOk(dayRoute(RouteStatus.READY, route = readyRoute())) }
        val viewModel = newViewModel()

        viewModel.load()
        viewModel.load()
        advanceUntilIdle()

        assertEquals(1, service.getCalls.size)
    }

    private suspend fun newViewModel(): RouteViewModel =
        RouteViewModel(repository = repository(), tripId = ROUTE_TRIP_ID, date = date)

    /** 로그인된 session을 가진 repository를 만든다. F004 `ItineraryEditViewModelTest`와 같다. */
    private suspend fun repository(): RouteRepository {
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
        return RouteRepository(api = service, auth = auth)
    }
}
