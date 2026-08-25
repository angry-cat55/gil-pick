package com.gilpick.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 로그인 상태를 바꾸는 유일한 진입점.
 *
 * 화면과 network 계층은 이 repository만 통해 session을 읽고 바꾼다. 상태 전이를 한
 * 곳에 모아 부분 로그인·중복 갱신이 생기지 않게 한다. 로그인 흐름(T024)과 갱신·
 * 로그아웃(T033, T042)은 이 기반 위에 추가한다.
 *
 * @property store 암호화 session 저장소.
 */
class AuthRepository(private val store: AuthSessionStore) {

    private val mutex = Mutex()
    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Loading)

    /** 화면이 관찰하는 현재 인증 상태. */
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    /** 이 설치의 기기 ID. 최초 호출에서 생성한다. */
    suspend fun deviceId(): String = store.deviceId()

    /**
     * 저장된 session을 복원해 초기 상태를 확정한다.
     *
     * 복호화할 수 없는 session은 [AuthSessionStore]가 제거하므로 `SignedOut`이 된다.
     */
    suspend fun restore(): AuthUiState = mutex.withLock {
        val session = store.loadSession()
        val next = if (session == null) {
            AuthUiState.SignedOut
        } else {
            AuthUiState.Authenticated(session.userId, session.nickname, session.profileImageUrl)
        }
        _state.value = next
        next
    }

    /** 현재 복호화된 session을 반환한다. 없으면 `null`이다. */
    suspend fun currentSession(): ActiveSession? = store.loadSession()

    /**
     * 로그인 성공 결과를 저장하고 `Authenticated`로 전환한다.
     *
     * Token pair는 한 번의 update로 교체되므로 부분 저장이 남지 않는다.
     */
    suspend fun onSignedIn(
        sessionId: String,
        userId: String,
        nickname: String?,
        profileImageUrl: String?,
        accessToken: String,
        refreshToken: String,
        accessExpiresAtEpochSeconds: Long,
        refreshExpiresAtEpochSeconds: Long,
    ): AuthUiState = mutex.withLock {
        store.saveSession(
            sessionId = sessionId,
            userId = userId,
            nickname = nickname,
            profileImageUrl = profileImageUrl,
            accessToken = accessToken,
            refreshToken = refreshToken,
            accessExpiresAtEpochSeconds = accessExpiresAtEpochSeconds,
            refreshExpiresAtEpochSeconds = refreshExpiresAtEpochSeconds,
        )
        AuthUiState.Authenticated(userId, nickname, profileImageUrl).also { _state.value = it }
    }

    /**
     * 통신 장애로 갱신에 실패했음을 알린다.
     *
     * session은 지우지 않고 보호 기능만 차단하는 `RefreshOffline`로 전환한다. 이미
     * 로그아웃 상태면 전이하지 않는다.
     */
    suspend fun onRefreshOffline(): AuthUiState = mutex.withLock {
        val session = store.loadSession()
        val next = if (session == null) AuthUiState.SignedOut
        else AuthUiState.RefreshOffline(session.userId)
        _state.value = next
        next
    }

    /**
     * 자격이 무효로 확정되었거나 사용자가 로그아웃했을 때 local session을 즉시 제거한다.
     *
     * 서버 폐기 재시도는 T042에서 pending revocation queue로 연결한다.
     */
    suspend fun onSignedOut(): AuthUiState = mutex.withLock {
        store.clearSession()
        AuthUiState.SignedOut.also { _state.value = it }
    }
}
