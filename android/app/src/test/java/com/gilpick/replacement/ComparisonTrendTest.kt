package com.gilpick.replacement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #593: 경로 비교의 나아짐·나빠짐은 화면에 보이는 값이 다를 때만 표시한다. */
class ComparisonTrendTest {

    @Test
    fun `원값이 달라도 화면 표시값이 같으면 강조하지 않는다`() {
        // 11,400m와 11,430m는 둘 다 `11.4km`로 보인다.
        assertNull(comparisonTrend(before = 11_430, after = 11_400, beforeLabel = "11.4km", afterLabel = "11.4km"))
    }

    @Test
    fun `표시값이 줄면 나아짐이고 늘면 나빠짐이다`() {
        assertEquals(true, comparisonTrend(before = 1_980, after = 1_500, beforeLabel = "33분", afterLabel = "25분"))
        assertEquals(false, comparisonTrend(before = 1_500, after = 1_980, beforeLabel = "25분", afterLabel = "33분"))
    }

    @Test
    fun `값이 없거나 같으면 강조하지 않는다`() {
        assertNull(comparisonTrend(before = null, after = 1_500, beforeLabel = null, afterLabel = "25분"))
        assertNull(comparisonTrend(before = 1_500, after = 1_500, beforeLabel = "25분", afterLabel = "25분"))
    }
}
