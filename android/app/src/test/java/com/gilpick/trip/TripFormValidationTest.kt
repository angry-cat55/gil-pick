package com.gilpick.trip

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

    private fun validate(name: String, start: LocalDate?, end: LocalDate?) =
        TripFormValidator.validate(name = name, startDate = start, endDate = end)

    private companion object {
        val START: LocalDate = LocalDate.of(2026, 9, 1)
    }
}
