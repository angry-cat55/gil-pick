package com.gilpick.trip

import androidx.navigation.NavController
import com.gilpick.itinerary.ItineraryEditRoute
import java.time.LocalDate

/**
 * 새 여행을 만든 직후 그 여행의 일정 편집으로 보낸다(#498).
 *
 * 백스택은 `목록 → 새 여행 상세 → 일정 편집`이 된다. 생성 폼은 [F] 까지 빼서 뒤로 가기로 폼에 돌아오지 않는다.
 * 일정 편집에서 뒤로 가면 방금 만든 여행 상세가 보이고(상세는 다시 조회하므로 저장한 일정이 반영된다), 한 번 더 가면 목록이다.
 * 상세에서 `일정 편집`에 들어간 기존 흐름과 같은 백스택이다. 하단 주버튼은 `여행 저장`이라 저장하면 바로 상세로 간다(#715).
 *
 * @param F 생성 폼 route 타입. 이 route까지 백스택에서 뺀다.
 * @param detailRoute 새 여행 상세 route. route 타입은 app graph가 소유하므로 호출부가 만든다.
 * @param startDate 일정 편집이 처음 여는 날짜. 여행 첫날이다.
 */
inline fun <reified F : Any> NavController.openNewTripItinerary(detailRoute: Any, tripId: String, startDate: LocalDate) {
    navigate(detailRoute) { popUpTo<F> { inclusive = true } }
    navigate(ItineraryEditRoute(tripId, startDate.toString(), newTrip = true))
}
