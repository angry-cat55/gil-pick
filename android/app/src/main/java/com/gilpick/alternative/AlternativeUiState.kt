package com.gilpick.alternative

/**
 * 대체 장소 화면의 표시 상태(data-model.md §3.1, spec UI-006).
 *
 * `docs/design/ui-guidelines.md` 9절의 상태 중 `empty`는 별도 값이 아니라 [Content]의 후보가
 * 비어 있는 것이다(후보 없음은 오류가 아닌 성공 응답, FR-021). 이미 처리된 감지([Closed])는
 * 오류와 구분해 진행 화면으로 돌아가는 행동만 제공한다.
 */
sealed interface AlternativeUiState {
    /** 조회 중. 화면은 1초를 넘길 때만 대기 표시를 띄운다(UI-006). */
    data object Loading : AlternativeUiState

    /**
     * 후보를 보여줄 수 없다. 기존 일정은 그대로다.
     *
     * @property retryable `다시 시도하기`를 보일지. 세션 만료·권한 없음·없는 감지는 다시 보내도 같다.
     */
    data class Error(val error: AlternativeError, val retryable: Boolean) : AlternativeUiState

    /**
     * 이미 처리된 감지다(`409 DETECTION_NOT_ACTIVE` 또는 거절 응답의 비-`DISMISSED` 상태).
     *
     * @property status 서버가 알린 현재 상태. 계약에 없는 값이면 `null`.
     */
    data class Closed(val status: DetectionStatus?) : AlternativeUiState

    /**
     * 감지 요약과 후보 목록.
     *
     * @property detection 감지 상세(DETECT-002). 상단 장소명·이유·변수 칩의 출처다(UI-002).
     * @property candidates 후보(ALT-001). `items`가 비어 있으면 `empty` 표현이다.
     * @property refreshing 재조회 중. 기존 내용을 그대로 두고 조용히 갱신한다.
     * @property dismissPending 거절(DETECT-004) 응답 대기 중. `기존 일정 그대로 진행`을 잠근다(UI-005).
     * @property dismissError 마지막 거절 실패. 화면은 그대로 두고 원인을 보인다.
     */
    data class Content(
        val detection: DetectionDetailDto,
        val candidates: AlternativeListDto,
        val refreshing: Boolean = false,
        val dismissPending: Boolean = false,
        val dismissError: AlternativeError? = null,
    ) : AlternativeUiState {
        /** 후보가 하나도 없다(Figma `alternativesEmpty`). */
        val isEmpty: Boolean get() = candidates.items.isEmpty()
    }
}

/** 조회를 다시 보내면 결과가 달라질 수 있는 실패인지(UI-006 `다시 시도하기`). */
val AlternativeError.retryable: Boolean
    get() = when (this) {
        AlternativeError.Network, AlternativeError.Unexpected -> true
        is AlternativeError.ProviderFailed -> retryable
        is AlternativeError.NotActive, AlternativeError.NotFound, AlternativeError.Forbidden, AlternativeError.SessionExpired -> false
    }
