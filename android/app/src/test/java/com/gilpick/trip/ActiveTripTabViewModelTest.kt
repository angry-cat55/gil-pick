package com.gilpick.trip

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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #502: 하단 `여행 중` 탭이 진행 중 여행을 찾는 단계. */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveTripTabViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val service = FakeTripService()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `서버가 계산한 진행 중 상태로 한 건만 묻고 찾으면 그 여행이다`() = runTest {
        service.onList = { page(listOf(trip("t1", status = TripStatus.IN_PROGRESS))) }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        assertEquals(ActiveTripTabPhase.Found(trip("t1", status = TripStatus.IN_PROGRESS)), viewModel.phase.value)
        val call = service.listCalls.single()
        assertEquals(TripStatus.IN_PROGRESS, call.status)
        assertEquals(1, call.limit)
    }

    @Test
    fun `진행 중 여행이 없으면 빈 상태다`() = runTest {
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        assertEquals(ActiveTripTabPhase.Empty, viewModel.phase.value)
    }

    @Test
    fun `통신 실패는 network 오류고 다시 시도하면 다시 묻는다`() = runTest {
        service.onList = { throw IOException("offline") }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()
        assertEquals(ActiveTripTabPhase.Failed(TripListError.NETWORK), viewModel.phase.value)

        service.onList = { emptyPage() }
        viewModel.load()
        advanceUntilIdle()

        assertEquals(ActiveTripTabPhase.Empty, viewModel.phase.value)
        assertEquals(2, service.listCalls.size)
    }

    private suspend fun newViewModel(): ActiveTripTabViewModel {
        val store = AuthSessionStore(
            // DataStore 기본 scope는 Dispatchers.IO다. test scheduler 안에서 돌게 한다.
            AuthSessionStore.createDataStore(
                File(tempFolder.root, AuthSessionStore.FILE_NAME),
                scope = CoroutineScope(dispatcher + SupervisorJob()),
            ),
            FakeSessionCipher(),
        )
        val auth = AuthRepository(
            store = store,
            api = FakeAuthService,
            appLinkHandler = AuthAppLinkHandler("app.gilpick.example"),
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
        return ActiveTripTabViewModel(TripRepository(api = service, auth = auth))
    }
}
