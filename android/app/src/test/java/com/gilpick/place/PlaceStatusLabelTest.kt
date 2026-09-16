package com.gilpick.place

import com.gilpick.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 영업 상태 표시 규칙(#576).
 *
 * `openNow`는 Google이 준 실시간 값이고 `businessStatus`는 폐업·임시 휴업이다. 둘의 우선순위와
 * `openNow = null`일 때 현재 영업 여부를 지어내지 않는지 확인한다(F003 `spec.md` FR-007).
 */
class PlaceStatusLabelTest {

    @Test
    fun `openNow true면 영업 중, false면 영업 종료다`() {
        assertEquals(
            PlaceStatusLabel(R.string.place_open_now, closed = false),
            place("google:1", openNow = true).statusLabel(),
        )
        assertEquals(
            PlaceStatusLabel(R.string.place_closed_now, closed = true),
            place("google:1", openNow = false).statusLabel(),
        )
    }

    @Test
    fun `openNow가 null이면 현재 영업 여부를 표시하지 않는다`() {
        // 운영되는 곳이라는 사실만 남고 지금 문을 열었는지는 말하지 않는다.
        assertEquals(
            PlaceStatusLabel(R.string.place_business_operational, closed = false),
            place("tourapi:1", businessStatus = PlaceBusinessStatus.OPERATIONAL).statusLabel(),
        )
        assertNull(place("tourapi:1").statusLabel())
    }

    @Test
    fun `폐업과 임시 휴업은 실시간 상태보다 우선한다`() {
        assertEquals(
            PlaceStatusLabel(R.string.place_business_closed_permanently, closed = true),
            place("google:1", businessStatus = PlaceBusinessStatus.CLOSED_PERMANENTLY, openNow = true).statusLabel(),
        )
        assertEquals(
            PlaceStatusLabel(R.string.place_business_closed_temporarily, closed = true),
            place("google:1", businessStatus = PlaceBusinessStatus.CLOSED_TEMPORARILY, openNow = true).statusLabel(),
        )
    }

    @Test
    fun `openNow만 있어도 Google 출처 표기 대상이다`() {
        // 화면에 Google 값을 보이면 attribution도 함께 보여야 한다(FR-021).
        assertEquals(true, place("google:1", openNow = true).hasGoogleData)
        assertEquals(false, place("tourapi:1").hasGoogleData)
    }
}
