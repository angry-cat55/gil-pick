package com.gilpick.itinerary

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.gilpick.R
import com.gilpick.place.PlaceTransport
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

/** F003 시트의 이동 수단 카드를 재사용할 때 쓴다. [PlaceTransport.toTransportMode]의 반대다. */
fun TransportMode.toPlaceTransport(): PlaceTransport = when (this) {
    TransportMode.WALK -> PlaceTransport.WALK
    TransportMode.TRANSIT -> PlaceTransport.TRANSIT
    TransportMode.CAR -> PlaceTransport.CAR
}

/** 처리된 항목의 상태 문구(UI-002). 예정 항목은 순서 번호를 쓰므로 `null`이다. */
val ItemStatus.labelRes: Int?
    @StringRes get() = when (this) {
        ItemStatus.PLANNED -> null
        ItemStatus.EN_ROUTE -> R.string.itinerary_edit_status_en_route
        ItemStatus.ARRIVED -> R.string.itinerary_edit_status_arrived
        ItemStatus.COMPLETED -> R.string.itinerary_edit_status_completed
        ItemStatus.SKIPPED -> R.string.itinerary_edit_status_skipped
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
