package com.gilpick.auth

/**
 * 인증 화면이 관찰하는 상태.
 *
 * 통신 장애([RefreshOffline])와 확정된 로그아웃([SignedOut])을 분리해, 오프라인에서
 * 로그인 상태를 잃지 않으면서 보호 기능만 잠시 차단한다.
 */
sealed interface AuthUiState {
    /** 저장된 session을 아직 복원하지 않은 초기 상태. */
    data object Loading : AuthUiState

    /** 로그인 자격이 없다. 로그인 화면을 표시한다. */
    data object SignedOut : AuthUiState

    /** Kakao 인증을 시작했고 App Link로 결과가 돌아오기를 기다린다. */
    data object LoggingIn : AuthUiState

    /**
     * 로그인이 실패했다. 로그인 화면에 오류와 다음 행동을 표시한다.
     *
     * 같은 인가 코드나 ticket을 앱이 자동으로 다시 쓰지 않는다. [retryable]은 사용자가
     * 같은 버튼을 다시 눌러도 되는지를 뜻할 뿐이고, 재시도는 항상 새 Kakao 인증이다.
     *
     * @property code 계약상의 error code.
     * @property retryable 잠시 후 다시 시도해볼 수 있는 일시적 실패인지 여부.
     */
    data class LoginFailed(val code: String, val retryable: Boolean) : AuthUiState

    /**
     * 유효한 session이 있다.
     *
     * @property userId 로그인한 사용자 ID.
     * @property nickname 표시 이름. 카카오 미동의 시 `null`이다.
     * @property profileImageUrl profile image URL. 카카오 미동의 시 `null`이다.
     */
    data class Authenticated(
        val userId: String,
        val nickname: String?,
        val profileImageUrl: String?,
    ) : AuthUiState

    /**
     * 갱신이 통신 장애로 실패했다. session은 유지하고 보호 기능만 차단한다.
     *
     * @property userId 유지 중인 session의 사용자 ID.
     */
    data class RefreshOffline(val userId: String) : AuthUiState
}
