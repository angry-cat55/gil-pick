package com.gilpick.place

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceLabelsTest {

    private val week = listOf("월요일: 09:00~18:00", "화요일: 휴무", "수요일: 09:00~18:00", "목요일: 09:00~18:00", "금요일: 09:00~18:00", "토요일: 10:00~17:00", "일요일: 10:00~17:00")

    @Test
    fun 운영시간_행은_Google_현재_정규_TourAPI_안내_순으로_합친다() {
        val google = place("tourapi:1", currentOpeningHours = week, regularOpeningHours = listOf("월요일: 09:00~10:00"), operatingGuide = "매주 화요일 휴무")
        assertEquals(week.joinToString("\n") + "\n매주 화요일 휴무", google.openingHoursDetail)

        val regularOnly = place("tourapi:1", currentOpeningHours = emptyList(), regularOpeningHours = listOf("오전 9:00~오후 6:00"))
        assertEquals("오전 9:00~오후 6:00", regularOnly.openingHoursDetail)

        val guideOnly = place("tourapi:1", operatingGuide = "09:00~18:00, 매주 월요일 휴무")
        assertEquals("09:00~18:00, 매주 월요일 휴무", guideOnly.openingHoursDetail)

        assertNull(place("tourapi:1", operatingGuide = " ").openingHoursDetail)
    }
}
