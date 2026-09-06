package com.gilpick.trip

import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeAuthService
import com.gilpick.auth.FakeSessionCipher
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * T009: 여행 생성 폼 검증 규칙.
 *
 * 서버가 최종 판정하지만 화면도 같은 규칙으로 먼저 걸러 불필요한 왕복을 줄인다.
 * 규칙의 근거는 `spec.md`의 FR-001, FR-001a, FR-001b다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripFormValidationTest {

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
    fun `2자 이상 30자 이하 이름과 7일 이하 기간은 통과한다`() {
        val result = validate(name = "서울 여행", start = START, end = START.plusDays(2))

        assertTrue(result.isValid)
        assertNull(result.nameError)
        assertNull(result.periodError)
    }

    @Test
    fun `앞뒤 공백을 제외한 길이로 이름을 검증한다`() {
        // trim 후 3자라 통과해야 한다. 원문 길이로 세면 잘못 통과하거나 잘못 막힌다.
        val result = validate(name = "   제주   ", start = START, end = START)

        assertTrue(result.isValid)
    }

    @Test
    fun `공백만 입력한 이름은 거부한다`() {
        val result = validate(name = "    ", start = START, end = START)

        assertEquals(TripNameError.TOO_SHORT, result.nameError)
        assertFalse(result.isValid)
    }

    @Test
    fun `1자 이름은 거부한다`() {
        val result = validate(name = "제", start = START, end = START)

        assertEquals(TripNameError.TOO_SHORT, result.nameError)
    }

    @Test
    fun `31자 이름은 거부한다`() {
        val result = validate(name = "가".repeat(31), start = START, end = START)

        assertEquals(TripNameError.TOO_LONG, result.nameError)
    }

    @Test
    fun `경계값인 2자와 30자는 통과한다`() {
        assertNull(validate(name = "제주", start = START, end = START).nameError)
        assertNull(validate(name = "가".repeat(30), start = START, end = START).nameError)
    }

    @Test
    fun `날짜를 고르지 않으면 거부한다`() {
        assertEquals(
            TripPeriodError.NOT_SELECTED,
            validate(name = "서울 여행", start = null, end = null).periodError,
        )
        assertEquals(
            TripPeriodError.NOT_SELECTED,
            validate(name = "서울 여행", start = START, end = null).periodError,
        )
    }

    @Test
    fun `종료일이 시작일보다 빠르면 거부한다`() {
        val result = validate(name = "서울 여행", start = START, end = START.minusDays(1))

        assertEquals(TripPeriodError.END_BEFORE_START, result.periodError)
    }

    @Test
    fun `같은 날 당일 여행은 통과한다`() {
        assertNull(validate(name = "서울 여행", start = START, end = START).periodError)
    }

    @Test
    fun `기간이 8일이면 거부한다`() {
        // 시작일 포함 8일이므로 차이는 7일이다.
        val result = validate(name = "서울 여행", start = START, end = START.plusDays(7))

        assertEquals(TripPeriodError.TOO_LONG, result.periodError)
    }

    @Test
    fun `경계값인 7일 여행은 통과한다`() {
        assertNull(validate(name = "서울 여행", start = START, end = START.plusDays(6)).periodError)
    }

    @Test
    fun `이름과 기간 오류를 함께 알린다`() {
        // 한 번에 하나만 알리면 사용자가 고친 뒤 다시 막히는 경험을 반복한다.
        val result = validate(name = "", start = START, end = START.plusDays(7))

        assertEquals(TripNameError.TOO_SHORT, result.nameError)
        assertEquals(TripPeriodError.TOO_LONG, result.periodError)
    }

    // --- T033: 수정 모드 ---

    // 여행 생성과 수정은 검증 규칙이 같고 화면도 공용이지만, 제출 경로와 잠금 상태가
    // 다르다. 어느 쪽인지는 [FormMode]가 들고 있고 화면과 view model은 이 값으로만
    // 갈라진다. `tasks.md` T037이 하나의 화면을 재사용하도록 정한다.

    @Test
    fun `생성 모드에서는 기간 입력이 잠기지 않는다`() {
        val state = TripFormUiState(mode = FormMode.Create)

        assertFalse(state.periodLocked)
    }

    @Test
    fun `완료된 여행을 수정할 때는 기간 입력이 잠긴다`() {
        // FR-010a: 완료 상태 여행은 이름만 수정할 수 있다.
        val state = TripFormUiState(mode = edit(TripStatus.COMPLETED))

        assertTrue(state.periodLocked)
    }

    @Test
    fun `예정과 여행 중인 여행은 기간을 수정할 수 있다`() {
        // FR-010a는 완료 상태만 잠근다. 나머지 두 상태는 기간 수정이 허용된다.
        assertFalse(TripFormUiState(mode = edit(TripStatus.UPCOMING)).periodLocked)
        assertFalse(TripFormUiState(mode = edit(TripStatus.IN_PROGRESS)).periodLocked)
    }

    @Test
    fun `수정 모드는 조회했던 버전을 들고 있는다`() {
        // FR-011a: 수정 요청에 조회 시점의 version을 실어 보내야 서버가 낙관적
        // 동시성 제어를 할 수 있다.
        val mode = edit(TripStatus.UPCOMING, version = 7)

        assertEquals(7, mode.version)
        assertEquals(TRIP_ID, mode.tripId)
    }

    // --- T033: 서버 오류를 사용자 메시지로 매핑 ---

    @Test
    fun `버전 충돌은 재조회 안내로 매핑한다`() {
        // US4 Acceptance 8: 최신 정보를 다시 조회한 뒤 재시도하라고 안내해야 한다.
        assertEquals(
            TripFormSubmitError.VERSION_CONFLICT,
            serverError(TripErrorCodes.VERSION_CONFLICT).toSubmitError(),
        )
    }

    @Test
    fun `완료 여행 기간 잠금은 별도 오류로 매핑한다`() {
        // US4 Acceptance 7: 완료된 여행은 기간을 수정할 수 없다는 이유를 안내한다.
        // 입력을 고쳐서 해결되는 문제가 아니므로 INVALID_INPUT과 구분한다.
        assertEquals(
            TripFormSubmitError.TRIP_LOCKED,
            serverError(TripErrorCodes.TRIP_LOCKED).toSubmitError(),
        )
    }

    @Test
    fun `기간 축소 확인 요구는 별도 오류로 매핑한다`() {
        // FR-012. F002 시점에는 일정이 없어 서버가 삭제 개수를 항상 0으로 보므로 이
        // 오류는 실제로 발동하지 않는다. 화면 표현은 F004로 미루고 매핑만 둔다.
        assertEquals(
            TripFormSubmitError.CONFIRMATION_REQUIRED,
            serverError(TripErrorCodes.CONFIRMATION_REQUIRED).toSubmitError(),
        )
    }

    @Test
    fun `이름과 기간 검증 실패는 입력 오류로 매핑한다`() {
        // 기존 생성 흐름의 매핑이 수정 모드에서도 그대로 유지되어야 한다.
        assertEquals(
            TripFormSubmitError.INVALID_INPUT,
            serverError(TripErrorCodes.VALIDATION_ERROR).toSubmitError(),
        )
        assertEquals(
            TripFormSubmitError.INVALID_INPUT,
            serverError(TripErrorCodes.INVALID_TRIP_PERIOD).toSubmitError(),
        )
    }

    @Test
    fun `계약에 없는 서버 오류는 unexpected로 좁힌다`() {
        assertEquals(
            TripFormSubmitError.UNEXPECTED,
            serverError("INTERNAL_ERROR").toSubmitError(),
        )
    }

    @Test
    fun `통신 실패는 network로 좁힌다`() {
        assertEquals(
            TripFormSubmitError.NETWORK,
            AuthError.Offline(java.io.IOException("연결 실패")).toSubmitError(),
        )
    }

    @Test
    fun `수정 모드는 조회한 여행 값으로 폼을 채운다`() {
        val state = TripFormUiState(
            name = "서울 여행",
            startDate = LocalDate.of(2026, 9, 1),
            endDate = LocalDate.of(2026, 9, 3),
            mode = edit(TripStatus.UPCOMING, version = 4),
        )

        assertEquals("서울 여행", state.name)
        assertEquals(4, (state.mode as FormMode.Edit).version)
        assertFalse(state.periodLocked)
    }

    // --- T030: 기간 축소 삭제 확인 대화상자 ---

    @Test
    fun `삭제될 장소 수를 받으면 확인 대화상자를 연다`() = runTest {
        // FR-013: 확인 없는 기간 축소는 거부되고 삭제될 장소 수가 안내된다.
        service.onGet = { detail(trip(TRIP_ID)) }
        service.onUpdate = { confirmationRequired(deletedItemCount = 2) }
        val viewModel = newFormViewModel()
        loadEdit(viewModel)

        viewModel.onPeriodChange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2))
        viewModel.submit()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(2, state.deleteConfirmation)
        // 대화상자가 뜨므로 같은 뜻의 오류 문구를 겹쳐 보여 주지 않는다.
        assertNull(state.submitError)
        assertFalse(state.submitting)
    }

    @Test
    fun `확인을 요구받은 저장은 아직 서버에 반영되지 않는다`() = runTest {
        service.onGet = { detail(trip(TRIP_ID)) }
        service.onUpdate = { confirmationRequired(deletedItemCount = 3) }
        val viewModel = newFormViewModel()
        loadEdit(viewModel)

        viewModel.onPeriodChange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2))
        viewModel.submit()
        advanceUntilIdle()

        assertNull(viewModel.state.value.savedTripId)
        // 첫 요청은 동의 없이 나갔다.
        assertEquals(listOf(false), service.updateCalls.map { it.confirmDeleteOutOfRangeItems })
    }

    @Test
    fun `동의하면 같은 수정을 confirm true로 다시 보낸다`() = runTest {
        service.onGet = { detail(trip(TRIP_ID)) }
        service.onUpdate = { confirmationRequired(deletedItemCount = 2) }
        val viewModel = newFormViewModel()
        loadEdit(viewModel)
        viewModel.onNameChange("짧아진 여행")
        viewModel.onPeriodChange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2))
        viewModel.submit()
        advanceUntilIdle()

        service.onUpdate = { detail(trip(TRIP_ID)) }
        viewModel.confirmDeleteOutOfRangeItems()
        advanceUntilIdle()

        val calls = service.updateCalls
        assertEquals(2, calls.size)
        assertEquals(listOf(false, true), calls.map { it.confirmDeleteOutOfRangeItems })
        // 동의는 같은 입력을 그대로 다시 보내는 것이다. 값이 달라지면 안 된다.
        assertEquals("짧아진 여행", calls[1].name)
        assertEquals("2026-09-01", calls[1].startDate)
        assertEquals("2026-09-02", calls[1].endDate)
    }

    @Test
    fun `동의 후 저장에 성공하면 대화상자를 닫고 화면을 넘긴다`() = runTest {
        service.onGet = { detail(trip(TRIP_ID)) }
        service.onUpdate = { confirmationRequired(deletedItemCount = 2) }
        val viewModel = newFormViewModel()
        loadEdit(viewModel)
        viewModel.onPeriodChange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2))
        viewModel.submit()
        advanceUntilIdle()

        service.onUpdate = { detail(trip(TRIP_ID)) }
        viewModel.confirmDeleteOutOfRangeItems()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertNull(state.deleteConfirmation)
        assertEquals(TRIP_ID, state.savedTripId)
    }

    @Test
    fun `취소하면 저장하지 않고 고른 기간도 그대로 남는다`() = runTest {
        // US4 Acceptance 4: 취소하면 기간은 바뀌지 않는다. 서버 여행은 손대지 않았고
        // 폼에 고른 값은 남아 사용자가 다시 줄이거나 되돌릴 수 있다.
        service.onGet = { detail(trip(TRIP_ID)) }
        service.onUpdate = { confirmationRequired(deletedItemCount = 2) }
        val viewModel = newFormViewModel()
        loadEdit(viewModel)
        viewModel.onPeriodChange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2))
        viewModel.submit()
        advanceUntilIdle()

        viewModel.cancelDeleteConfirmation()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertNull(state.deleteConfirmation)
        assertNull(state.savedTripId)
        assertEquals(LocalDate.of(2026, 9, 2), state.endDate)
        // 취소는 요청을 만들지 않는다.
        assertEquals(1, service.updateCalls.size)
    }

    @Test
    fun `대화상자가 없으면 동의 요청을 보내지 않는다`() = runTest {
        service.onGet = { detail(trip(TRIP_ID)) }
        val viewModel = newFormViewModel()
        loadEdit(viewModel)

        viewModel.confirmDeleteOutOfRangeItems()
        advanceUntilIdle()

        assertEquals(0, service.updateCalls.size)
    }

    @Test
    fun `입력을 고치면 확인 대화상자가 닫힌다`() = runTest {
        // 대화상자가 말하는 삭제 개수는 방금 보낸 기간에 대한 값이다. 기간을 바꾸면
        // 그 수는 더 이상 맞지 않으므로 다시 물어야 한다.
        service.onGet = { detail(trip(TRIP_ID)) }
        service.onUpdate = { confirmationRequired(deletedItemCount = 2) }
        val viewModel = newFormViewModel()
        loadEdit(viewModel)
        viewModel.onPeriodChange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2))
        viewModel.submit()
        advanceUntilIdle()

        viewModel.onPeriodChange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3))

        assertNull(viewModel.state.value.deleteConfirmation)
    }

    @Test
    fun `삭제될 장소 수가 없으면 대화상자 대신 안내 문구를 남긴다`() = runTest {
        // 계약은 이 code에 deletedItemCount를 함께 주도록 정한다. 없으면 무엇에
        // 동의하는지 말할 수 없으므로 대화상자를 열지 않는다.
        service.onGet = { detail(trip(TRIP_ID)) }
        service.onUpdate = {
            errorResponse(409, TripErrorCodes.CONFIRMATION_REQUIRED)
        }
        val viewModel = newFormViewModel()
        loadEdit(viewModel)
        viewModel.onPeriodChange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2))
        viewModel.submit()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertNull(state.deleteConfirmation)
        assertEquals(TripFormSubmitError.CONFIRMATION_REQUIRED, state.submitError)
    }

    /** 로그인된 session을 가진 repository 위에 폼 view model을 만든다. */
    private suspend fun newFormViewModel(): TripFormViewModel {
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
        return TripFormViewModel(TripRepository(api = service, auth = auth))
    }

    /** 수정할 여행을 조회해 폼을 채운다. `advanceUntilIdle`을 쓰므로 test scope 확장이다. */
    private fun TestScope.loadEdit(viewModel: TripFormViewModel) {
        viewModel.loadForEdit(TRIP_ID)
        advanceUntilIdle()
    }

    private fun validate(name: String, start: LocalDate?, end: LocalDate?) =
        TripFormValidator.validate(name = name, startDate = start, endDate = end)

    /** 수정 모드 하나. 상태와 버전만 test마다 바꾼다. */
    private fun edit(status: TripStatus, version: Int = 1) =
        FormMode.Edit(tripId = TRIP_ID, version = version, status = status)

    /** 계약이 정한 code를 가진 서버 오류. 재시도 가능 여부는 매핑에 쓰지 않는다. */
    private fun serverError(code: String) =
        AuthError.Server(code = code, retryable = false, httpStatus = 409)

    private companion object {
        val START: LocalDate = LocalDate.of(2026, 9, 1)
        const val TRIP_ID = "33333333-4444-4555-8666-777777777777"
    }
}
