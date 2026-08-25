package com.gilpick.auth

import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 로그인 시작 결과.
 *
 * 화면이 Custom Tab을 열지, 오류를 표시할지 결정한다.
 */
sealed interface LoginStart {

    /**
     * Kakao 인증을 열 수 있다.
     *
     * @property authorizationUrl Custom Tab으로 열 URL.
     */
    data class Ready(val authorizationUrl: String) : LoginStart

    /**
     * transaction을 만들지 못했다.
     *
     * @property error 화면에 표시할 실패 원인.
     */
    data class Failed(val error: AuthError) : LoginStart
}

/**
 * 로그인 상태를 바꾸는 유일한 진입점.
 *
 * 화면과 network 계층은 이 repository만 통해 session을 읽고 바꾼다. 상태 전이를 한
 * 곳에 모아 부분 로그인·중복 갱신이 생기지 않게 한다. 갱신·로그아웃(T033, T042)은 이
 * 기반 위에 추가한다.
 *
 * @property store 암호화 session 저장소.
 * @property api 인증 endpoint 호출 계약.
 * @property appLinkHandler 인증 완료 App Link의 ticket 일회 수신 경계.
 */
class AuthRepository(
    private val store: AuthSessionStore,
    private val api: AuthService,
    private val appLinkHandler: AuthAppLinkHandler,
) {

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

    /**
     * 로그인 transaction을 만들고 Custom Tab으로 열 Kakao 인증 URL을 반환한다.
     *
     * 성공하면 App Link 결과를 기다리는 `LoggingIn` 상태가 되고, 실패하면 화면에
     * 표시할 `LoginFailed`가 된다. 어느 경우에도 session은 만들지 않는다.
     */
    suspend fun startLogin(): LoginStart {
        _state.value = AuthUiState.LoggingIn
        val deviceId = store.deviceId()
        val result = call { api.createLoginTransaction(CreateLoginTransactionRequest(deviceId)) }
        return when (result) {
            is AuthResult.Success -> LoginStart.Ready(result.value.authorizationUrl)
            is AuthResult.Failure -> {
                _state.value = result.error.toLoginFailed()
                LoginStart.Failed(result.error)
            }
        }
    }

    /**
     * 인증 완료 App Link를 처리해 로그인을 마친다.
     *
     * ticket 교환이 성공한 경우에만 Token pair를 한 번의 update로 저장하므로, 오류나
     * 통신 실패로 끝나면 부분 session이 남지 않는다. 신뢰할 수 없거나 이미 소비한
     * link는 상태를 바꾸지 않는다.
     */
    suspend fun completeLogin(appLinkUri: String?): AuthUiState =
        when (val link = appLinkHandler.consume(appLinkUri)) {
            is AppLinkResult.Ignored -> state.value
            is AppLinkResult.Failed -> link.error.toLoginFailed().also { _state.value = it }
            is AppLinkResult.Ticket -> exchange(link.loginTicket)
        }

    /** 사용자가 Kakao 인증을 취소했다. 로그인 화면으로 돌아간다. */
    suspend fun cancelLogin(): AuthUiState = mutex.withLock {
        AuthUiState.SignedOut.also { _state.value = it }
    }

    /** ticket을 교환하고 성공한 경우에만 session을 저장한다. */
    private suspend fun exchange(loginTicket: String): AuthUiState {
        val deviceId = store.deviceId()
        val result = call { api.exchangeLoginTicket(LoginTicketExchangeRequest(loginTicket, deviceId)) }
        return when (result) {
            is AuthResult.Failure -> result.error.toLoginFailed().also { _state.value = it }
            is AuthResult.Success -> {
                val data = result.value
                val now = System.currentTimeMillis() / 1_000
                onSignedIn(
                    // Refresh Token의 selector가 곧 서버 session 식별자다. 점 뒤 secret은
                    // 여기서 쓰지 않는다.
                    sessionId = data.refreshToken.substringBefore('.'),
                    userId = data.user.userId,
                    nickname = data.user.nickname,
                    profileImageUrl = data.user.profileImageUrl,
                    accessToken = data.accessToken,
                    refreshToken = data.refreshToken,
                    accessExpiresAtEpochSeconds = now + data.expiresIn,
                    refreshExpiresAtEpochSeconds = now + data.refreshExpiresIn,
                )
            }
        }
    }

    /**
     * 인증 endpoint 호출의 통신 실패를 [AuthError.Offline]으로 좁힌다.
     *
     * Retrofit은 연결 실패를 예외로 알리므로 여기서 한 번만 잡아 화면까지 예외가
     * 전파되지 않게 한다.
     */
    private suspend fun <T> call(request: suspend () -> retrofit2.Response<SuccessEnvelope<T>>): AuthResult<T> =
        try {
            request().toAuthResult()
        } catch (e: IOException) {
            AuthResult.Failure(AuthError.Offline(e))
        }
}

/** 화면이 표시할 로그인 실패 상태로 옮긴다. */
private fun AuthError.toLoginFailed(): AuthUiState.LoginFailed = when (this) {
    is AuthError.Server -> AuthUiState.LoginFailed(code, retryable)
    is AuthError.Callback -> AuthUiState.LoginFailed(code, retryable)
    // 통신 실패는 같은 요청을 그대로 다시 시도할 수 있다.
    is AuthError.Offline -> AuthUiState.LoginFailed(LOGIN_NETWORK_ERROR, retryable = true)
    // 계약과 다른 응답은 재시도해도 같으므로 새 인증을 안내한다.
    is AuthError.Malformed -> AuthUiState.LoginFailed(LOGIN_UNEXPECTED_ERROR, retryable = false)
}

/** 서버 code가 아니라 앱이 붙이는 통신 실패 표시. */
const val LOGIN_NETWORK_ERROR = "LOGIN_NETWORK_ERROR"

/** 서버 code가 아니라 앱이 붙이는 계약 위반 응답 표시. */
const val LOGIN_UNEXPECTED_ERROR = "LOGIN_UNEXPECTED_ERROR"
