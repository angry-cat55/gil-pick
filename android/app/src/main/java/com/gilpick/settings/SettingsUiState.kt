package com.gilpick.settings

import com.gilpick.BuildConfig

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
 * 계정·앱 정보는 **상태 전이가 없다**. F001 session과 설치본에서 한 번 읽어 그대로 보이는
 * 읽기 전용 값이라 `loading`·`error`를 두지 않는다. 이 화면에서 상태를 갖는 것은 설정
 * 영역([preference])과 정책 열기 실패([policyOpenError])뿐이다.
 *
 * @property nickname F001 session의 표시 이름. 카카오 미동의면 `null`이고, 화면이 대체 표시로
 *   바꾼다. 없는 값을 지어내지 않기 위해 여기서 기본 문구로 채우지 않는다(FR-009).
 * @property profileImageUrl F001 session의 profile image 주소. 미동의면 `null`이다.
 * @property isKakaoConnected 카카오 연동 상태. MVP 인증 provider가 카카오 하나뿐이라 인증된
 *   session의 존재가 곧 연동이다. 이 표시를 위해 session에 provider field를 더하지 않는다
 *   (plan Account Display).
 * @property versionName 설치된 앱 버전. 사용자가 문의할 때 설치본을 식별하는 값이다.
 * @property preference 알림 설정 영역의 상태.
 * @property policyOpenError 정책 문서를 열지 못한 이유. 평상시는 `null`이다(FR-008).
 *   설정 조회·변경과 **독립**이라 설정이 실패한 상태에서도 정책 문서는 열 수 있고, 그 반대도 같다.
 */
data class SettingsUiState(
    val nickname: String? = null,
    val profileImageUrl: String? = null,
    val isKakaoConnected: Boolean = true,
    val versionName: String = BuildConfig.VERSION_NAME,
    val preference: PreferencePhase = PreferencePhase.Loading,
    val policyOpenError: PolicyOpenFailure? = null,
)
