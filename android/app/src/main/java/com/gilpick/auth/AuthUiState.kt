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
