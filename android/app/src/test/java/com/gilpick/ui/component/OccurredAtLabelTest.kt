package com.gilpick.ui.component

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** #434: 발생 시각은 한국어 오전·오후 + h:mm, KST(가이드라인 9절 원인 카드). */
class OccurredAtLabelTest {

    @Test
    fun UTC를_KST_오전_오후로_옮긴다() {
        assertEquals("오후 2:32", occurredAtLabel(Instant.parse("2026-09-14T05:32:00Z")))
        assertEquals("오전 9:05", occurredAtLabel(Instant.parse("2026-09-14T00:05:00Z")))
        assertEquals("오전 12:00", occurredAtLabel(Instant.parse("2026-09-13T15:00:00Z")))
    }
}
