package com.gilpick.settings

import android.content.Context
import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthService
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.CreateLoginTransactionRequest
import com.gilpick.auth.KeystoreSessionCipher
import com.gilpick.auth.LoginTicketExchangeRequest
import com.gilpick.auth.RefreshTokenRequest
import com.gilpick.auth.ResponseMeta
import com.gilpick.auth.SuccessEnvelope
import java.io.File
import retrofit2.Response

/**
 * network 없이 설정 API 응답을 정하는 [SettingsService]. 마지막으로 저장한 값을 다음 조회가 돌려줘
 * 서버처럼 행동한다. unit test의 `FakeSettingsService`와 같은 모양인데 androidTest는 그 소스를
 * 보지 못해 따로 둔다.
 */
class FakeSettingsService(initial: Boolean = true) : SettingsService {

    /** 서버가 마지막으로 성공 처리한 값. */
    var saved = initial
        private set

    /** 지금까지 도착한 조회 수. 탭을 다녀오면 다시 조회하는지 확인한다. */
    var getCalls = 0
        private set

    /** 지금까지 도착한 변경 요청의 값. */
    val updateCalls = mutableListOf<Boolean>()

    override suspend fun getPreferences(bearer: String): Response<SuccessEnvelope<PreferenceDto>> {
        getCalls++
        return ok(saved)
    }

    override suspend fun updatePreferences(
        bearer: String,
        body: UpdatePreferenceRequest,
    ): Response<SuccessEnvelope<PreferenceDto>> {
        val value = body.placeChangeSuggestionNotificationEnabled
        updateCalls += value
        saved = value
        return ok(value)
    }

    private fun ok(value: Boolean) = Response.success(
        SuccessEnvelope(success = true, data = PreferenceDto(value), meta = ResponseMeta(requestId = "test")),
    )
}

/**
 * 로그인된 session을 가진 [SettingsRepository]. F001 `AuthLogoutIntegrationTest`와 같은 조립이다.
 * 인증 endpoint는 부르지 않아야 하므로 부르면 실패한다.
 */
suspend fun signedInSettingsRepository(context: Context, service: SettingsService): SettingsRepository {
    val store = AuthSessionStore(
        AuthSessionStore.createDataStore(File(context.cacheDir, "settings-test-${System.nanoTime()}.pb")),
        KeystoreSessionCipher("gilpick.settings.test"),
    )
    val auth = AuthRepository(
        store = store,
        api = NoAuthService,
        appLinkHandler = AuthAppLinkHandler("app.gilpick.example"),
    )
    val now = System.currentTimeMillis() / 1_000
    auth.onSignedIn(
        sessionId = "session-1",
        userId = "user-1",
        nickname = "길픽",
        profileImageUrl = null,
        accessToken = "access-token",
        refreshToken = "session-1.refresh-token",
        accessExpiresAtEpochSeconds = now + 3_600,
        refreshExpiresAtEpochSeconds = now + 2_592_000,
    )
    return SettingsRepository(api = service, auth = auth)
}

private object NoAuthService : AuthService {
    override suspend fun createLoginTransaction(body: CreateLoginTransactionRequest) = error("설정 경로는 인증 endpoint를 호출하지 않는다")
    override suspend fun exchangeLoginTicket(body: LoginTicketExchangeRequest) = error("설정 경로는 인증 endpoint를 호출하지 않는다")
    override suspend fun refreshTokens(body: RefreshTokenRequest) = error("설정 경로는 인증 endpoint를 호출하지 않는다")
    override suspend fun logout(body: RefreshTokenRequest) = error("설정 경로는 인증 endpoint를 호출하지 않는다")
}
