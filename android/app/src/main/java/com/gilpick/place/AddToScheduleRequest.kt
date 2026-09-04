package com.gilpick.place

/** Figma `PlaceDetailScreen` 일정 추가 시트의 이동 수단 선택지. */
enum class PlaceTransport { WALK, TRANSIT, CAR }

/**
 * 상세 화면의 `일정에 추가` 시트에서 사용자가 확정한 값.
 *
 * F003은 이 값을 만들어 넘기기만 하고 저장하지 않는다(FR-014). 일정에 반영하는 쪽은 F004다.
 *
 * @property transport 선택한 이동 수단.
 * @property stayMinutes 체류 시간(분). 30~360, 30분 단위.
 */
data class AddToScheduleRequest(
    val transport: PlaceTransport,
    val stayMinutes: Int,
)
