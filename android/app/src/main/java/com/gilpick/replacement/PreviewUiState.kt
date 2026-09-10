package com.gilpick.replacement

import com.gilpick.route.RouteDto
import java.time.Instant

/**
 * 변경 경로 미리보기 화면의 상태(data-model 4.1).
 *
 * `empty`가 없다. 후보가 없는 상황은 F009 대체 장소 화면이 처리하고 이 화면은 이미 고른 후보
 * 하나로만 열리므로 "보여 줄 것이 없는" 상태가 생기지 않는다(UI-004).
 */
sealed interface PreviewUiState {

    /**
     * 미리보기를 만드는 중.
     *
     * 대기 표시는 1초를 넘길 때만 보인다. 그 판단은 화면이 하고 이 상태는 "만드는 중"이라는
     * 사실만 갖는다(ui-guidelines 9절).
     */
    data object Loading : PreviewUiState

    /**
     * 미리보기를 만들지 못했다. 일정은 바뀌지 않았다(FR-007).
     *
     * @property error 원인. 화면이 원인 문구와 다음 행동을 고르는 데 쓴다(UI-004).
     */
    data class Error(val error: ReplacementError) : PreviewUiState

    /**
     * 비교를 볼 수 있다.
     *
     * @property preview REPL-001이 만든 비교와 변경 후 경로.
     * @property originalRoute 승인 전 그 날짜 경로. 지도에 기존 경로를 함께 그리는 데 쓴다(UI-001).
     *   **계약에 없어 F005 ROUTE-001로 따로 받는다.** 받지 못하면 `null`이고 지도는 변경 경로만
     *   그린다. 비교 표는 REPL-001의 값만 쓰므로 이 값이 없어도 화면은 성립한다(constitution IV).
     * @property now 남은 시간 계산 기준 시각. 만료 판정은 서버가 하고 화면은 표시만 한다(FR-005).
     * @property approving 승인 처리 중. 행동을 잠근다(UI-005). 승인 연결은 T023(#349)이 붙인다.
     * @property approveFailure 마지막 승인 실패 원인. 승인 연결과 함께 T023이 채운다.
     */
    data class Content(
        val preview: RoutePreviewDto,
        val originalRoute: RouteDto? = null,
        val now: Instant = Instant.EPOCH,
        val approving: Boolean = false,
        val approveFailure: ReplacementError? = null,
    ) : PreviewUiState
}
