package com.gilpick.itinerary

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.gilpick.R
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 계약 값을 편집 화면 문구로 옮기는 규칙. F003 `PlaceLabels`와 같은 역할이다.
 *
 * 색으로만 구분하지 않고(가이드라인 10절) 문구가 뜻을 전달한다.
 */

/** 이동 수단 표시명. F003 시트와 같은 문자열을 쓴다. */
val TransportMode.labelRes: Int
    @StringRes get() = when (this) {
        TransportMode.WALK -> R.string.place_transport_walk
        TransportMode.TRANSIT -> R.string.place_transport_transit
        TransportMode.CAR -> R.string.place_transport_car
    }

/** 이동 수단 아이콘. F003 시트와 같은 lucide 아이콘이다. */
val TransportMode.iconRes: Int
    @DrawableRes get() = when (this) {
        TransportMode.WALK -> R.drawable.ic_lucide_walk
        TransportMode.TRANSIT -> R.drawable.ic_lucide_transit
        TransportMode.CAR -> R.drawable.ic_lucide_car
    }

/** 날짜 탭의 요일 한 글자. Figma `ScheduleEditScreen` 탭의 `목`·`금`과 같다. */
val LocalDate.shortDayOfWeek: String
    get() = when (dayOfWeek) {
        DayOfWeek.MONDAY -> "월"
        DayOfWeek.TUESDAY -> "화"
        DayOfWeek.WEDNESDAY -> "수"
        DayOfWeek.THURSDAY -> "목"
        DayOfWeek.FRIDAY -> "금"
        DayOfWeek.SATURDAY -> "토"
        DayOfWeek.SUNDAY -> "일"
    }

/** 조회·저장 실패 원인별 안내 문구. 원인을 뭉뚱그리지 않는다(가이드라인 9절). */
val ItineraryError.messageRes: Int
    @StringRes get() = when (this) {
        ItineraryError.Network -> R.string.itinerary_edit_error_network
        ItineraryError.NotFound -> R.string.itinerary_edit_error_not_found
        ItineraryError.Forbidden -> R.string.itinerary_edit_error_forbidden
        ItineraryError.SessionExpired -> R.string.itinerary_edit_error_session
        is ItineraryError.InvalidItinerary -> R.string.itinerary_edit_error_invalid
        is ItineraryError.ItemLocked -> R.string.itinerary_edit_error_locked
        ItineraryError.VersionConflict -> R.string.itinerary_edit_error_conflict
        ItineraryError.Unexpected -> R.string.itinerary_edit_error_unexpected
    }
