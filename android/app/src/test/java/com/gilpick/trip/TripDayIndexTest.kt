package com.gilpick.trip

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 진행 중인 여행 카드의 `며칠째` 계산.
 *
 * pen `02. 여행 목록 화면`의 `현재 진행 중인 여행` 그룹이 쓰는 값이다. 시작일 당일이
 * 1일째이며, 마지막 날도 예외 없이 같은 규칙으로 센다.
 */
class TripDayIndexTest {

    @Test
    fun `시작일 당일은 1일째다`() {
        assertEquals(1, tripDayIndex(startDate = "2026-09-02", today = LocalDate.of(2026, 9, 2)))
    }

    @Test
    fun `시작일 다음 날은 2일째다`() {
        assertEquals(2, tripDayIndex(startDate = "2026-09-02", today = LocalDate.of(2026, 9, 3)))
    }

    @Test
    fun `마지막 날도 예외 없이 이어서 센다`() {
        // 9월 2일 시작 4박 5일 여행의 마지막 날.
        assertEquals(5, tripDayIndex(startDate = "2026-09-02", today = LocalDate.of(2026, 9, 6)))
    }

    @Test
    fun `달을 넘겨도 실제 날짜 수로 센다`() {
        assertEquals(3, tripDayIndex(startDate = "2026-08-31", today = LocalDate.of(2026, 9, 2)))
    }
}
