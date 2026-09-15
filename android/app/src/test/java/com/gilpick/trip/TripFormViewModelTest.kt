package com.gilpick.trip

import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeAuthService
import com.gilpick.auth.FakeSessionCipher
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * #501: 겹치는 여행 기간 비활성 표시와 `409 TRIP_PERIOD_CONFLICT` 안내(F002 FR-002a·FR-002b, T053).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripFormViewModelTest {

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
    fun `모든 page의 내 여행 기간을 비활성 날짜로 모은다`() = runTest {
        service.onList = { call ->
            if (call.cursor == null) page(listOf(trip("t1", startDate = "2026-09-01", endDate = "2026-09-02")), nextCursor = "c2")
            else page(listOf(trip("t2", startDate = "2026-09-10", endDate = "2026-09-10")))
        }
        val viewModel = newViewModel()

        advanceUntilIdle()

        assertEquals(
            setOf(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 10)),
            viewModel.state.value.occupiedDates,
        )
        assertEquals(listOf(null, "c2"), service.listCalls.map { it.cursor })
    }

    @Test
    fun `수정 중인 자기 여행 기간은 비활성에서 뺀다`() = runTest {
        service.onList = { page(listOf(trip("t1", startDate = "2026-09-01", endDate = "2026-09-02"), trip("t2", startDate = "2026-09-05", endDate = "2026-09-05"))) }
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.startEditing(trip("t1", startDate = "2026-09-01", endDate = "2026-09-02"))

        assertEquals(setOf(LocalDate.of(2026, 9, 5)), viewModel.state.value.occupiedDates)
    }

    @Test
    fun `목록 조회가 실패하면 비활성 없이 폼을 그대로 쓴다`() = runTest {
        service.onList = { throw IOException("offline") }
        val viewModel = newViewModel()

        advanceUntilIdle()

        assertTrue(viewModel.state.value.occupiedDates.isEmpty())
        assertNull(viewModel.state.value.submitError)
    }

    @Test
    fun `기간 충돌은 입력을 유지하고 겹친 여행 이름과 함께 안내하며 다시 보낼 수 있다`() = runTest {
        service.onCreate = { periodConflict(name = "제주 여행") }
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.onNameChange("서울 여행")
        viewModel.onPeriodChange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3))

        viewModel.submit()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(TripFormSubmitError.PERIOD_CONFLICT, state.submitError)
        assertEquals("제주 여행", state.conflictTripName)
        assertEquals("서울 여행", state.name)
        assertEquals(LocalDate.of(2026, 9, 1) to LocalDate.of(2026, 9, 3), state.startDate to state.endDate)
        // 겹친 여행 기간이 달력에 반영되도록 목록을 다시 읽는다.
        assertEquals(2, service.listCalls.size)

        service.onCreate = { detail(trip("t3")) }
        viewModel.onPeriodChange(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 11))
        assertNull(viewModel.state.value.conflictTripName)
        viewModel.submit()
        advanceUntilIdle()

        assertEquals("t3", viewModel.state.value.savedTripId)
    }

    @Test
    fun `이름 없는 기간 충돌도 기간 충돌로 안내한다`() = runTest {
        service.onCreate = { periodConflict(name = null) }
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.onNameChange("서울 여행")
        viewModel.onPeriodChange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3))

        viewModel.submit()
        advanceUntilIdle()

        assertEquals(TripFormSubmitError.PERIOD_CONFLICT, viewModel.state.value.submitError)
        assertNull(viewModel.state.value.conflictTripName)
    }

    private suspend fun newViewModel(): TripFormViewModel {
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
        return TripFormViewModel(TripRepository(api = service, auth = auth))
    }
}
