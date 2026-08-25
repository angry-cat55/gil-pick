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
}
