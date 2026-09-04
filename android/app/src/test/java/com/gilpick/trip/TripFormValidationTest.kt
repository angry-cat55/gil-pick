package com.gilpick.trip

import com.gilpick.auth.AuthError
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T009: 여행 생성 폼 검증 규칙.
 *
 * 서버가 최종 판정하지만 화면도 같은 규칙으로 먼저 걸러 불필요한 왕복을 줄인다.
 * 규칙의 근거는 `spec.md`의 FR-001, FR-001a, FR-001b다.
 */
class TripFormValidationTest {

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
