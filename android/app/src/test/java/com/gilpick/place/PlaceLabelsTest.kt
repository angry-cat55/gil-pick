package com.gilpick.place

import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceLabelsTest {

    private val week = listOf("월요일: 09:00~18:00", "화요일: 휴무", "수요일: 09:00~18:00", "목요일: 09:00~18:00", "금요일: 09:00~18:00", "토요일: 10:00~17:00", "일요일: 10:00~17:00")

    @Test
    fun 오늘_요일_줄에서_접두어를_뗀다() {
        assertEquals("휴무", todayHoursLabel(week, DayOfWeek.TUESDAY))
        assertEquals("10:00~17:00", todayHoursLabel(week, DayOfWeek.SUNDAY))
    }

    @Test
    fun 일곱_줄이_아니면_첫_줄을_쓰고_없으면_null이다() {
        assertEquals("10:00~20:00", todayHoursLabel(listOf("월요일: 오전 10:00~오후 8:00"), DayOfWeek.FRIDAY))
        assertEquals("00:30~12:00", todayHoursLabel(listOf("오전 12:30~오후 12:00"), DayOfWeek.FRIDAY))
        assertEquals("상시 개방", todayHoursLabel(listOf("상시 개방"), DayOfWeek.FRIDAY))
        assertNull(todayHoursLabel(emptyList(), DayOfWeek.FRIDAY))
        assertNull(todayHoursLabel(null, DayOfWeek.FRIDAY))
    }
}
