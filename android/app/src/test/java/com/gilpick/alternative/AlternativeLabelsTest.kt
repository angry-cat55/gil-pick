package com.gilpick.alternative

import com.gilpick.progress.timeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T022: 시각·근거 code 문구 규칙. Compose 자원이 필요한 문구는 UI test가 본다. */
class AlternativeLabelsTest {

    @Test
    fun `마감 시각은 offset과 무관하게 KST HH mm이다`() {
        assertEquals("18:00", clockLabel("2026-09-09T18:00:00+09:00"))
        assertEquals("18:00", clockLabel("2026-09-09T09:00:00Z"))
        assertEquals("오후 6:00", timeLabel("2026-09-09T18:00:00+09:00"))
    }

    @Test
    fun `모르는 근거 code는 문구가 없다`() {
        assertEquals(R_INDOOR, "INDOOR".reasonRes)
        assertNull("FASTER_ROUTE".reasonRes)
    }

    private companion object {
        val R_INDOOR = com.gilpick.R.string.alternative_reason_indoor
    }
}
