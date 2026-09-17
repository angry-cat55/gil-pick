package com.gilpick.progress

import android.content.Context
import com.gilpick.BuildConfig
import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthResult
import com.gilpick.auth.AuthService
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.LbsConsentData
import com.gilpick.auth.LbsConsentRequest
import com.gilpick.auth.SessionRevocationWorker
import com.gilpick.auth.createAuthRetrofit
import com.gilpick.auth.toAuthResult
import com.gilpick.notification.FcmTokenClearWorker
import com.gilpick.notification.FcmTokenSyncWorker
import java.io.IOException

/** 위치 권한을 요청하기 전에 서버에 LBS 약관 동의를 남기는 최소 repository. */
class LbsConsentRepository(
    private val api: AuthService,
    private val auth: AuthRepository,
) {
    suspend fun agree(): AuthResult<LbsConsentData> = auth.withAuthorizedCall { accessToken ->
        try {
            api.agreeToLbsTerms("Bearer $accessToken", LbsConsentRequest()).toAuthResult()
        } catch (error: IOException) {
            AuthResult.Failure(com.gilpick.auth.AuthError.Offline(error))
        }
    }

    companion object {
        fun default(context: Context): LbsConsentRepository {
            val appContext = context.applicationContext
            val api = createAuthRetrofit(BuildConfig.API_BASE_URL).create(AuthService::class.java)
            val auth = AuthRepository(
                store = AuthSessionStore.create(appContext),
                api = api,
                appLinkHandler = AuthAppLinkHandler(BuildConfig.APP_LINK_HOST),
                scheduleRevocation = SessionRevocationWorker.scheduler(appContext),
                syncPushToken = { FcmTokenSyncWorker.enqueue(appContext) },
                clearPushToken = { FcmTokenClearWorker.enqueue(appContext) },
            )
            return LbsConsentRepository(api, auth)
        }
    }
}
