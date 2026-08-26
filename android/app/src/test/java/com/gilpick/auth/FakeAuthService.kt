package com.gilpick.auth

import retrofit2.Response

/**
 * network를 쓰지 않는 test용 [AuthService].
 *
 * 호출되면 실패시켜, 그 test가 실제로는 endpoint를 건드리지 않는다는 것을 드러낸다.
 * 실제 요청·응답을 검증하는 test는 MockWebServer와 Retrofit을 그대로 사용한다.
 */
object FakeAuthService : AuthService {

    override suspend fun createLoginTransaction(
        body: CreateLoginTransactionRequest,
    ): Response<SuccessEnvelope<LoginTransactionData>> = error("이 test는 인증 endpoint를 호출하지 않는다")

    override suspend fun exchangeLoginTicket(
        body: LoginTicketExchangeRequest,
    ): Response<SuccessEnvelope<AuthTokenData>> = error("이 test는 인증 endpoint를 호출하지 않는다")

    override suspend fun refreshTokens(
        body: RefreshTokenRequest,
    ): Response<SuccessEnvelope<RefreshTokenData>> = error("이 test는 인증 endpoint를 호출하지 않는다")

    override suspend fun logout(body: RefreshTokenRequest): Response<Unit> =
        error("이 test는 인증 endpoint를 호출하지 않는다")
}

/**
 * 응답을 test가 직접 정하는 [AuthService].
 *
 * 호출 횟수를 세어 single-flight 합류와 replay 횟수를 검증하고, 응답 lambda 안에서
 * 대기시켜 동시 요청을 만든다. 호출 순번은 lambda의 인자로 전달한다.
 */
class ProgrammableAuthService : AuthService {

    /** refresh endpoint 호출 횟수. 진입 시점에 증가한다. */
    @Volatile
    var refreshCount: Int = 0
        private set

    /** logout endpoint 호출 횟수. 진입 시점에 증가한다. */
    @Volatile
    var logoutCount: Int = 0
        private set

    /** 마지막 refresh 요청 body. 이전 Token으로 요청했는지 확인한다. */
    @Volatile
    var lastRefreshRequest: RefreshTokenRequest? = null
        private set

    /** 마지막 logout 요청 body. */
    @Volatile
    var lastLogoutRequest: RefreshTokenRequest? = null
        private set

    /** 호출 순번(0부터)을 받아 refresh 응답을 만든다. */
    var onRefresh: suspend (Int) -> Response<SuccessEnvelope<RefreshTokenData>> = {
        error("이 test는 refresh endpoint를 호출하지 않는다")
    }

    /** 호출 순번(0부터)을 받아 logout 응답을 만든다. */
    var onLogout: suspend (Int) -> Response<Unit> = {
        error("이 test는 logout endpoint를 호출하지 않는다")
    }

    override suspend fun createLoginTransaction(
        body: CreateLoginTransactionRequest,
    ): Response<SuccessEnvelope<LoginTransactionData>> = error("이 test는 login endpoint를 호출하지 않는다")

    override suspend fun exchangeLoginTicket(
        body: LoginTicketExchangeRequest,
    ): Response<SuccessEnvelope<AuthTokenData>> = error("이 test는 login endpoint를 호출하지 않는다")

    override suspend fun refreshTokens(
        body: RefreshTokenRequest,
    ): Response<SuccessEnvelope<RefreshTokenData>> {
        lastRefreshRequest = body
        return onRefresh(synchronized(this) { refreshCount++ })
    }

    override suspend fun logout(body: RefreshTokenRequest): Response<Unit> {
        lastLogoutRequest = body
        return onLogout(synchronized(this) { logoutCount++ })
    }
}
