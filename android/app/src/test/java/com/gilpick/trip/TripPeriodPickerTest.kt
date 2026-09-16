package com.gilpick.trip

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #594: 달력에서 누른 날짜를 기간에 반영하는 규칙.
 *
 * 종료일을 고치려다 시작일이 바뀌어 일정이 삭제되던 사고를 막는 규칙이라 화면 없이 규칙만 따로 검증한다.
 */
class TripPeriodPickerTest {

    private val sep1 = LocalDate.of(2026, 9, 1)
    private val sep2 = LocalDate.of(2026, 9, 2)
    private val sep3 = LocalDate.of(2026, 9, 3)
    private val sep5 = LocalDate.of(2026, 9, 5)

    private fun pick(
        start: LocalDate?,
        end: LocalDate?,
        picked: LocalDate,
        endpoint: TripPeriodEndpoint? = null,
        occupied: Set<LocalDate> = emptySet(),
    ) = TripPeriodPicker.pick(start, end, picked, occupied, endpoint)

    // --- 수정 화면: 누른 칸만 바뀐다 ---

    @Test
    fun 종료일_칸은_종료일만_당긴다() {
        assertEquals(TripPeriodPick.Applied(sep1, sep2), pick(sep1, sep3, sep2, TripPeriodEndpoint.END))
    }

    @Test
    fun 종료일_칸은_종료일만_늘린다() {
        assertEquals(TripPeriodPick.Applied(sep1, sep5), pick(sep1, sep3, sep5, TripPeriodEndpoint.END))
    }

    @Test
    fun 시작일_칸은_시작일만_바꾼다() {
        assertEquals(TripPeriodPick.Applied(sep2, sep3), pick(sep1, sep3, sep2, TripPeriodEndpoint.START))
    }

    @Test
    fun 종료일_칸에서_시작일보다_앞_날짜는_반영하지_않는다() {
        assertEquals(TripPeriodPick.Rejected(TripPeriodPickError.ORDER), pick(sep2, sep3, sep1, TripPeriodEndpoint.END))
    }

    @Test
    fun 시작일_칸에서_종료일보다_뒤_날짜는_반영하지_않는다() {
        assertEquals(TripPeriodPick.Rejected(TripPeriodPickError.ORDER), pick(sep1, sep2, sep3, TripPeriodEndpoint.START))
    }

    /** #501: 다른 여행 기간을 가로지르면 겹치는 기간이 된다. */
    @Test
    fun 다른_여행_기간을_가로지르면_반영하지_않는다() {
        assertEquals(
            TripPeriodPick.Rejected(TripPeriodPickError.OCCUPIED),
            pick(sep1, sep2, sep5, TripPeriodEndpoint.END, occupied = setOf(sep3)),
        )
    }

    /** 7일 초과는 여기서 막지 않는다. 반영한 뒤 [TripFormValidator]가 이유를 적고 저장을 막는다. */
    @Test
    fun 칠일을_넘겨도_반영한다() {
        val sep10 = LocalDate.of(2026, 9, 10)
        assertEquals(TripPeriodPick.Applied(sep1, sep10), pick(sep1, sep3, sep10, TripPeriodEndpoint.END))
    }

    // --- 만들기 화면: 칸이 없어 누른 날짜의 위치로 정한다 ---

    @Test
    fun 시작일만_고른_상태의_뒤_날짜는_종료일이_된다() {
        assertEquals(TripPeriodPick.Applied(sep1, sep3), pick(sep1, null, sep3))
    }

    @Test
    fun 시작일보다_앞_날짜는_새_시작일이_된다() {
        assertEquals(TripPeriodPick.Applied(sep1, null), pick(sep2, null, sep1))
    }

    @Test
    fun 기간을_다_고른_뒤_뒤_날짜는_종료일만_바꾼다() {
        assertEquals(TripPeriodPick.Applied(sep1, sep5), pick(sep1, sep3, sep5))
    }

    @Test
    fun 기간을_다_고른_뒤_앞_날짜는_시작일만_바꾼다() {
        assertEquals(TripPeriodPick.Applied(sep1, sep3), pick(sep2, sep3, sep1))
    }

    @Test
    fun 고른_종료일을_다시_누르면_종료일만_풀린다() {
        assertEquals(TripPeriodPick.Applied(sep1, null), pick(sep1, sep3, sep3))
    }

    @Test
    fun 고른_시작일을_다시_누르면_선택을_푼다() {
        assertEquals(TripPeriodPick.Applied(null, null), pick(sep1, sep3, sep1))
    }

    @Test
    fun 아무것도_고르지_않았으면_시작일이_된다() {
        assertEquals(TripPeriodPick.Applied(sep1, null), pick(null, null, sep1))
    }
}
