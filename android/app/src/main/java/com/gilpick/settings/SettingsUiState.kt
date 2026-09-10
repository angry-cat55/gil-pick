package com.gilpick.settings

/**
 * 설정 화면의 알림 설정 영역 상태(data-model 3 `PreferencePhase`).
 *
 * 화면 전체가 아니라 **설정 영역만** 이 상태를 가진다. 설정 조회가 실패해도 정책 문서와
 * 로그아웃은 계속 쓸 수 있어야 하기 때문이다(UI-004).
 *
 * `empty`가 없다. 인증된 사용자에게는 항상 저장된 설정 값이 있어 "보여 줄 것이 없는" 상태가
 * 생기지 않는다(UI-003).
 */
sealed interface PreferencePhase {

    /**
     * 저장된 설정을 조회하는 중.
     *
     * 대기 표시는 1초를 넘길 때만 보인다. 그 판단은 화면이 하고 이 상태는 "조회 중"이라는
     * 사실만 갖는다(UI-003, ui-guidelines 9절).
     */
    data object Loading : PreferencePhase

    /**
     * 설정을 보여 줄 수 있다.
     *
     * @property value 지금 화면에 보일 값. 저장 중에는 사용자가 방금 고른 값이고, 저장이 끝나면
     *   서버가 돌려준 값이다(FR-003).
     * @property isSaving 저장 요청이 진행 중. 토글 중복 요청을 막고 처리 중임을 알린다(UI-004).
     */
    data class Content(
        val value: Boolean,
        val isSaving: Boolean = false,
    ) : PreferencePhase

    /**
     * 조회 또는 변경에 실패했다.
     *
     * @property lastConfirmedValue 마지막으로 **저장에 성공한** 값. 변경 실패면 그 값으로 되돌려
     *   보이고(FR-006), 첫 조회부터 실패해 아는 값이 없으면 `null`이다.
     * @property error 원인. 화면이 문구와 다음 행동을 고르는 데 쓴다.
     */
    data class Error(
        val lastConfirmedValue: Boolean?,
        val error: SettingsError,
    ) : PreferencePhase
}

/**
 * 설정 화면 상태(data-model 3 `SettingsUiState`).
 *
 * 계정·앱 정보와 정책 문서 열기 실패는 각각 #402·#400이 채운다. 이 Issue(#399)는 설정 영역만
 * 다루므로 [preference] 하나로 시작한다.
 *
 * @property preference 알림 설정 영역의 상태.
 */
data class SettingsUiState(
    val preference: PreferencePhase = PreferencePhase.Loading,
)
