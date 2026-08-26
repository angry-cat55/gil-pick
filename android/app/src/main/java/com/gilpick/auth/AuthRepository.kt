package com.gilpick.auth

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response

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
 * 로그인 상태 갱신 시도의 결과.
 *
 * 통신 실패([Offline])와 서버가 확정한 무효([SignedOut])를 분리해, 오프라인에서
 * 로그인 상태를 잃지 않게 한다.
 */
sealed interface RefreshOutcome {

    /**
     * 새 Token pair로 교체했다.
     *
     * @property accessToken 원 요청을 replay할 때 쓸 새 Access Token.
     */
    data class Refreshed(val accessToken: String) : RefreshOutcome

    /** 결과를 확인할 수 없다. session을 보존하고 재시도한다. */
    data object Offline : RefreshOutcome

    /** 자격이 무효로 확정되었다. local session을 제거하고 재로그인을 요구한다. */
    data object SignedOut : RefreshOutcome
}

/**
 * 로그인 상태를 바꾸는 유일한 진입점.
 *
 * 화면과 network 계층은 이 repository만 통해 session을 읽고 바꾼다. 상태 전이를 한
 * 곳에 모아 부분 로그인·중복 갱신이 생기지 않게 한다.
 *
 * @property store 암호화 session 저장소.
 * @property api 인증 endpoint 호출 계약.
 * @property appLinkHandler 인증 완료 App Link의 ticket 일회 수신 경계.
 * @property scope 동시 요청이 공유하는 refresh 작업의 실행 scope. 요청 coroutine이
 *   취소되어도 진행 중인 갱신이 함께 끊기지 않도록 화면 수명주기와 분리한다.
 * @property scheduleRevocation 오프라인 로그아웃의 서버 폐기를 예약한다. Context가
 *   필요한 WorkManager 호출을 밖으로 밀어내 repository를 JVM에서 검증할 수 있게 한다.
 */
class AuthRepository(
    private val store: AuthSessionStore,
    private val api: AuthService,
    private val appLinkHandler: AuthAppLinkHandler,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val scheduleRevocation: (String) -> Unit = {},
) {

    private val mutex = Mutex()
    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Loading)

    /** 갱신 작업의 시작·합류를 직렬화한다. session 전이용 [mutex]와 겹치지 않는다. */
    private val refreshMutex = Mutex()

    /** 진행 중인 갱신 작업. 뒤늦게 도착한 요청은 새로 시작하지 않고 여기에 합류한다. */
    private var inFlightRefresh: Deferred<RefreshOutcome>? = null

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
     * 자격이 무효로 확정되었을 때 local session을 즉시 제거한다.
     *
     * 서버가 이미 자격을 거절했으므로 폐기 대기 항목은 만들지 않는다. 사용자가 직접
     * 로그아웃한 경우는 [logout]이 처리한다.
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

    /**
     * 로그인 상태를 갱신한다. 동시에 도착한 요청은 갱신 한 건으로 합쳐진다.
     *
     * 서버는 같은 Refresh Token의 동시 갱신 중 정확히 한 건만 성공시키므로, 앱도 갱신을
     * single-flight로 직렬화해 나머지 요청이 이미 교체된 Token으로 거절되지 않게 한다.
     * 진행 중인 작업이 있으면 새로 호출하지 않고 그 결과를 함께 받는다.
     */
    suspend fun refresh(): RefreshOutcome {
        val job = refreshMutex.withLock {
            inFlightRefresh ?: scope.async {
                try {
                    performRefresh()
                } finally {
                    // 다음 요청이 새 갱신을 시작할 수 있도록 완료 즉시 자리를 비운다.
                    refreshMutex.withLock { inFlightRefresh = null }
                }
            }.also { inFlightRefresh = it }
        }
        return job.await()
    }

    /**
     * Access Token이 필요한 요청을 보내고, `401`이면 한 번만 갱신 후 replay한다.
     *
     * replay가 다시 `401`이면 갱신을 반복하지 않고 로그아웃으로 확정해 refresh loop를
     * 막는다. 갱신이 통신 실패로 끝나면 replay하지 않고 session을 보존한다.
     */
    suspend fun <T> withAccessToken(
        request: suspend (accessToken: String) -> Response<SuccessEnvelope<T>>,
    ): AuthResult<T> {
        val session = store.loadSession()
            ?: return AuthResult.Failure(AuthError.Server(AuthErrorCodes.INVALID_REFRESH_TOKEN, false, 401))

        val first = call { request(session.accessToken) }
        if (!first.isUnauthorized()) return first

        return when (val outcome = refresh()) {
            is RefreshOutcome.Refreshed -> {
                val replayed = call { request(outcome.accessToken) }
                if (replayed.isUnauthorized()) onSignedOut()
                replayed
            }

            RefreshOutcome.Offline -> AuthResult.Failure(
                AuthError.Offline(IOException("로그인 상태를 갱신하지 못했습니다")),
            )

            RefreshOutcome.SignedOut -> first
        }
    }

    /**
     * 현재 기기에서 즉시 로그아웃한다.
     *
     * network 상태와 무관하게 local session을 지우고 로그인 화면으로 전환한다. 서버
     * 폐기는 요청별 operation ID를 가진 대기 항목으로 격리해 예약하므로, 앱이 종료되거나
     * 기기가 재시작되어도 다음 연결에서 재시도할 수 있다. 대기 항목은 활성 session이
     * 아니므로 인증이나 갱신에 사용되지 않는다.
     */
    suspend fun logout(): AuthUiState {
        val operationId = mutex.withLock {
            val session = store.loadSession()
            _state.value = AuthUiState.SignedOut
            if (session == null) {
                null
            } else {
                // 폐기 자격을 먼저 격리한 뒤 활성 session을 지운다. 순서가 반대면 중간에
                // 종료됐을 때 서버 자격을 폐기할 방법이 사라진다.
                val id = store.enqueueRevocation(session.refreshToken, store.deviceId())
                store.clearSession()
                id
            }
        }
        operationId?.let(scheduleRevocation)
        return AuthUiState.SignedOut
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
     * 실제 갱신 한 건을 수행한다. [refresh]가 동시 요청을 합쳐 한 번만 호출한다.
     *
     * 사용자 정보는 갱신 응답에 없으므로 보관 중인 값을 그대로 유지하고, Token pair만
     * 한 번의 update로 교체한다.
     */
    private suspend fun performRefresh(): RefreshOutcome {
        val session = store.loadSession() ?: run {
            onSignedOut()
            return RefreshOutcome.SignedOut
        }
        val deviceId = store.deviceId()
        val result = call { api.refreshTokens(RefreshTokenRequest(session.refreshToken, deviceId)) }
        return when (result) {
            is AuthResult.Success -> {
                val data = result.value
                val now = System.currentTimeMillis() / 1_000
                onSignedIn(
                    sessionId = data.refreshToken.substringBefore('.'),
                    userId = session.userId,
                    nickname = session.nickname,
                    profileImageUrl = session.profileImageUrl,
                    accessToken = data.accessToken,
                    refreshToken = data.refreshToken,
                    accessExpiresAtEpochSeconds = now + data.expiresIn,
                    refreshExpiresAtEpochSeconds = now + data.refreshExpiresIn,
                )
                RefreshOutcome.Refreshed(data.accessToken)
            }

            is AuthResult.Failure ->
                if (result.error.isConfirmedInvalid()) {
                    onSignedOut()
                    RefreshOutcome.SignedOut
                } else {
                    // 통신 실패와 확인할 수 없는 서버 오류는 자격 무효의 증거가 아니다.
                    onRefreshOffline()
                    RefreshOutcome.Offline
                }
        }
    }

    /**
     * 인증 endpoint 호출의 통신 실패를 [AuthError.Offline]으로 좁힌다.
     *
     * Retrofit은 연결 실패를 예외로 알리므로 여기서 한 번만 잡아 화면까지 예외가
     * 전파되지 않게 한다.
     */
    private suspend fun <T> call(request: suspend () -> Response<SuccessEnvelope<T>>): AuthResult<T> =
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

/**
 * 서버가 자격의 무효를 확정한 오류인지 알린다.
 *
 * 이 세 code만 재로그인을 요구한다. 통신 실패, 5xx, 계약 위반 응답은 자격이 유효한지
 * 알 수 없으므로 로그인 상태를 유지한다.
 */
internal fun AuthError.isConfirmedInvalid(): Boolean =
    this is AuthError.Server && code in CONFIRMED_INVALID_CREDENTIAL_CODES

private val CONFIRMED_INVALID_CREDENTIAL_CODES = setOf(
    AuthErrorCodes.INVALID_REFRESH_TOKEN,
    AuthErrorCodes.TOKEN_EXPIRED,
    AuthErrorCodes.DEVICE_MISMATCH,
)

/** 만료된 Access Token 때문에 거절된 응답인지 확인한다. */
private fun AuthResult<*>.isUnauthorized(): Boolean {
    val error = (this as? AuthResult.Failure)?.error
    return error is AuthError.Server && error.httpStatus == 401
}

/** 서버 code가 아니라 앱이 붙이는 통신 실패 표시. */
const val LOGIN_NETWORK_ERROR = "LOGIN_NETWORK_ERROR"

/** 서버 code가 아니라 앱이 붙이는 계약 위반 응답 표시. */
const val LOGIN_UNEXPECTED_ERROR = "LOGIN_UNEXPECTED_ERROR"
