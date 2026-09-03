package com.gilpick.trip

import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeAuthService
import com.gilpick.auth.FakeSessionCipher
import java.io.File
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

/**
 * T024: 여행 상세 화면의 상태 매핑(로딩·성공·오류) 검증.
 *
 * `spec.md` US3 Acceptance Scenario 1~3과 `docs/design/ui-guidelines.md` 9절의 화면
 * 상태가 대상이다.
 *
 * **범위**: F002 계약의 `TripDto`는 이름·기간·상태·일수만 담는다. pen
 * `08. 여행 상세 화면`의 일정(`Day`/`Step`) 영역과 `ActionBar`는 F004·F005 데이터가
 * 있어야 하므로 이 test와 T028의 범위 밖이다. 여기서는 Summary(이름·기간·상태)와 화면
 * 상태 전이만 다룬다.
 *
 * **`empty`는 적용되지 않는다**: 9절의 네 상태 중 `empty`는 "보여줄 내용이 없지만
 * 조회는 성공한 경우"다. 상세 조회는 여행 하나를 특정해 요청하므로 성공하면 반드시
 * 내용이 있고, 없으면 계약상 `404`(→ `NOT_FOUND` 오류)다. 빈 성공 응답이 존재하지
 * 않으므로 `empty` 상태를 만들지 않고 그 이유를 여기에 남긴다.
 *
 * **상태 표시**: 여행 상태(`예정`/`여행 중`/`완료`)는 화면이
 * `com.gilpick.ui.component`의 `StatusBadge`로 그린다(T028 검증 기준, 10절 "색 단독
 * 의미 전달 금지"). view model은 서버가 KST로 계산해 준 `TripDto.status`를 그대로
 * 넘기기만 하고 다시 계산하지 않는다(`spec.md` FR-006).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripDetailViewModelTest {

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

    // --- loading ---

    @Test
    fun `첫 조회 전에는 loading이다`() = runTest {
        val viewModel = newViewModel()

        assertEquals(TripDetailPhase.Loading, viewModel.state.value.phase)
    }

    // --- content: US3 Acceptance Scenario 1 ---

    @Test
    fun `여행을 받으면 content가 된다`() = runTest {
        service.onGet = { detail(trip(TRIP_ID, name = "서울 여행")) }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        val phase = viewModel.state.value.phase as TripDetailPhase.Content
        assertEquals(TRIP_ID, phase.trip.tripId)
    }

    @Test
    fun `이름과 기간과 상태를 그대로 화면에 넘긴다`() = runTest {
        // 화면이 그려야 할 값은 US3 Acceptance 1이 정한 세 가지다. 상태는 서버가 KST로
        // 계산한 값이며(FR-006) view model이 다시 계산하지 않는다.
        service.onGet = {
            detail(
                trip(
                    TRIP_ID,
                    name = "부산 출장",
                    startDate = "2026-09-02",
                    endDate = "2026-09-06",
                    status = TripStatus.IN_PROGRESS,
                ),
            )
        }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        val trip = (viewModel.state.value.phase as TripDetailPhase.Content).trip
        assertEquals("부산 출장", trip.name)
        assertEquals("2026-09-02", trip.startDate)
        assertEquals("2026-09-06", trip.endDate)
        assertEquals(TripStatus.IN_PROGRESS, trip.status)
    }

    @Test
    fun `요청한 여행의 식별자로 조회한다`() = runTest {
        service.onGet = { detail(trip(TRIP_ID)) }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        assertEquals(listOf(TRIP_ID), service.getCalls)
    }

    // --- error: US3 Acceptance Scenario 2·3 ---

    @Test
    fun `소유하지 않은 여행은 forbidden 오류가 된다`() = runTest {
        service.onGet = { errorResponse(403, TripErrorCodes.FORBIDDEN) }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        assertEquals(
            TripDetailPhase.Failed(TripDetailError.FORBIDDEN),
            viewModel.state.value.phase,
        )
    }

    @Test
    fun `삭제되었거나 없는 여행은 not found 오류가 된다`() = runTest {
        service.onGet = { errorResponse(404, TripErrorCodes.TRIP_NOT_FOUND) }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        assertEquals(
            TripDetailPhase.Failed(TripDetailError.NOT_FOUND),
            viewModel.state.value.phase,
        )
    }

    @Test
    fun `통신 실패는 network 오류가 된다`() = runTest {
        service.onGet = { throw java.io.IOException("연결 실패") }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        assertEquals(
            TripDetailPhase.Failed(TripDetailError.NETWORK),
            viewModel.state.value.phase,
        )
    }

    @Test
    fun `계약에 없는 오류는 unexpected로 좁힌다`() = runTest {
        service.onGet = { errorResponse(500, "INTERNAL_ERROR") }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        assertEquals(
            TripDetailPhase.Failed(TripDetailError.UNEXPECTED),
            viewModel.state.value.phase,
        )
    }

    // --- 재시도: 9절 "error는 재시도 버튼을 둔다" ---

    @Test
    fun `재시도하면 다시 조회한다`() = runTest {
        service.onGet = { throw java.io.IOException("연결 실패") }
        val viewModel = newViewModel()
        viewModel.load()
        advanceUntilIdle()

        service.onGet = { detail(trip(TRIP_ID)) }
        viewModel.retry()
        advanceUntilIdle()

        val phase = viewModel.state.value.phase as TripDetailPhase.Content
        assertEquals(TRIP_ID, phase.trip.tripId)
    }

    @Test
    fun `재시도하는 동안 다시 loading이 된다`() = runTest {
        // 재시도를 눌렀는데 화면이 실패 상태로 남아 있으면 눌렀는지 알 수 없다.
        service.onGet = { throw java.io.IOException("연결 실패") }
        val viewModel = newViewModel()
        viewModel.load()
        advanceUntilIdle()

        service.onGet = { detail(trip(TRIP_ID)) }
        viewModel.retry()

        assertEquals(TripDetailPhase.Loading, viewModel.state.value.phase)
    }

    /** 로그인된 session을 가진 repository 위에 view model을 만든다. */
    private suspend fun newViewModel(): TripDetailViewModel {
        val store = AuthSessionStore(
            // DataStore 기본 scope는 Dispatchers.IO다. 그대로 두면 저장소 작업이 test
            // scheduler 밖에서 돌아 advanceUntilIdle()이 기다려 주지 않는다.
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
        return TripDetailViewModel(
            repository = TripRepository(api = service, auth = auth),
            tripId = TRIP_ID,
        )
    }

    private companion object {
        const val TRIP_ID = "33333333-4444-4555-8666-777777777777"
    }
}
