package com.gilpick.route

import com.gilpick.itinerary.ItemStatus

/**
 * 날짜별 경로 화면의 표시 상태(`plan.md` State & Interaction).
 *
 * `docs/design/ui-guidelines.md` 9절의 네 상태를 모두 쓴다. ViewModel은 Naver SDK 객체를 들지
 * 않고 계약 DTO만 담는다. 지도 overlay는 [RouteMap]이 [Content.route]에서 그때그때 만든다.
 */
sealed interface RouteUiState {
    /** 조회 중. 화면은 1초를 넘길 때만 대기 표시를 띄운다(UI-003). */
    data object Loading : RouteUiState

    /** 저장된 장소가 없어 경로가 없다(`NOT_CALCULATED`). 일정 추가 또는 돌아가기를 제공한다. */
    data object Empty : RouteUiState

    /** 경로를 보여줄 수 없다. 원인과 `다시 시도`를 제공한다. */
    data class Error(val problem: RouteProblem) : RouteUiState

    /**
     * 현재 일정 version의 계획 경로.
     *
     * 장소가 한 곳이면 [RouteDto.segments]가 비고 합계는 0이다(FR-020). 정상 경로에는
     * 재계산·후보 선택 행동이 없다(FR-019).
     */
    data class Content(val route: RouteDto, val marks: RouteMarks = RouteMarks.NONE) : RouteUiState
}

/**
 * 지도와 구간 목록에 겹치는 F006 진행 표시(F006 UI-011, T031).
 *
 * 시작 전 날짜와 F005 단독 조회는 [NONE]이라 계획만 그린다. 진행 현황 조회가 실패해도 [NONE]으로
 * 두어 경로 화면은 성립한다.
 *
 * @property start 시작 시 얻은 현재 위치(`[경도, 위도]`). 있으면 `현위치` marker를 그린다.
 * @property statuses 장소별 진행 상태. 없는 장소는 계획 표시 그대로다.
 */
data class RouteMarks(
    val start: Position? = null,
    val statuses: Map<String, ItemStatus> = emptyMap(),
) {
    companion object {
        val NONE = RouteMarks()
    }
}

/** 경로 화면이 `error` 상태가 된 이유. 문구는 [RouteLabels.kt]가 정한다. */
sealed interface RouteProblem {
    /** 조회 요청 자체가 실패했다(통신·인증·계약). `다시 시도`는 같은 조회를 다시 보낸다. */
    data class Request(val error: RouteError) : RouteProblem

    /**
     * 자동 경로 계산이 최종 실패했다(`FAILED`). 일정은 보존됐다(FR-009).
     *
     * @property scheduleVersion 실패한 계산의 일정 version. `다시 시도`가 같은 입력임을 서버에 알린다.
     */
    data class Calculation(val failure: RouteFailureDto, val scheduleVersion: Int) : RouteProblem

    /** 서버가 준 경로의 version이 현재 일정 version과 다르다. 이전 경로를 현재 경로로 보이지 않는다(FR-014). */
    data object Stale : RouteProblem
}
