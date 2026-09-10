package com.gilpick.settings

import android.content.Context
import com.gilpick.BuildConfig
import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthErrorCodes
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthResult
import com.gilpick.auth.AuthService
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.SessionRevocationWorker
import com.gilpick.auth.SuccessEnvelope
import com.gilpick.auth.createAuthRetrofit
import com.gilpick.auth.toAuthResult
import com.gilpick.notification.FcmTokenClearWorker
import com.gilpick.notification.FcmTokenSyncWorker
import java.io.IOException
import retrofit2.Response

/**
 * 사용자 설정 데이터의 유일한 접근 지점.
 *
 * Access Token 주입과 만료 시 갱신·replay는 [AuthRepository.withAuthorizedCall]이 소유한다.
 * 다른 feature의 repository와 같은 구조다.
 *
 * **서버 응답이 정본이다.** 앱이 보낸 값과 저장된 값이 다를 수 있어(다른 기기의 나중 변경)
 * 호출자는 응답값을 표시한다(FR-003). 저장에 성공하지 않은 값을 성공으로 다루지 않는다(FR-006).
 */
class SettingsRepository(
    private val api: SettingsService,
    private val auth: AuthRepository,
) {

    /** 현재 사용자의 저장된 설정을 조회한다(PREF-001). */
    suspend fun getPreferences(): AuthResult<PreferenceDto> = call { token ->
        api.getPreferences(bearer = token)
    }

    /**
     * 현재 사용자의 설정을 바꾼다(PREF-002).
     *
     * @param enabled 지금 원하는 절대값. 증분이 아니라 이 값이 그대로 나간다.
     */
    suspend fun updatePreferences(enabled: Boolean): AuthResult<PreferenceDto> = call { token ->
        api.updatePreferences(
            bearer = token,
            body = UpdatePreferenceRequest(placeChangeSuggestionNotificationEnabled = enabled),
        )
    }

    /** 두 endpoint가 같은 인증·통신 실패 규칙을 쓰도록 한곳에 모은다. */
    private suspend fun <T> call(
        request: suspend (bearer: String) -> Response<SuccessEnvelope<T>>,
    ): AuthResult<T> = auth.withAuthorizedCall { accessToken ->
        try {
            request("Bearer $accessToken").toAuthResult()
        } catch (e: IOException) {
            AuthResult.Failure(AuthError.Offline(e))
        }
    }

    companion object {
        /** 실제 서버를 향한 repository. F009 `AlternativeRepository.default`와 같은 조립이다. */
        fun default(context: Context): SettingsRepository {
            val appContext = context.applicationContext
            val auth = AuthRepository(
                store = AuthSessionStore.create(appContext),
                api = createAuthRetrofit(BuildConfig.API_BASE_URL).create(AuthService::class.java),
                appLinkHandler = AuthAppLinkHandler(BuildConfig.APP_LINK_HOST),
                scheduleRevocation = SessionRevocationWorker.scheduler(appContext),
                syncPushToken = { FcmTokenSyncWorker.enqueue(appContext) },
                clearPushToken = { FcmTokenClearWorker.enqueue(appContext) },
            )
            return SettingsRepository(
                api = createSettingsRetrofit(BuildConfig.API_BASE_URL).create(SettingsService::class.java),
                auth = auth,
            )
        }
    }
}

/**
 * 설정 조회·변경이 실패한 이유. 화면이 원인과 재시도 행동을 안내하는 데 쓴다(UI-003).
 *
 * 계약이 정한 실패는 `400`·`401`뿐이라 원인이 적다. 설정은 현재 사용자 것만 다뤄 소유권 오류가
 * 없고(FR-011), 값이 항상 존재해 `404`도 없다.
 */
sealed interface SettingsError {
    /** 통신 실패. 같은 요청을 그대로 다시 보낸다. */
    data object Network : SettingsError

    /** 갱신·replay 후에도 자격이 무효로 확정됐다. F001 재인증 흐름으로 넘어간다. */
    data object SessionExpired : SettingsError

    /** 그 밖의 실패(`400 INVALID_REQUEST`, 5xx, 계약과 다른 응답). 잠시 후 다시 시도한다. */
    data object Unexpected : SettingsError
}

/** 인증 계층의 실패를 설정 화면이 안내할 수 있는 원인으로 좁힌다. */
fun AuthError.toSettingsError(): SettingsError = when (this) {
    is AuthError.Offline -> SettingsError.Network
    is AuthError.Malformed, is AuthError.Callback -> SettingsError.Unexpected
    is AuthError.Server -> when (code) {
        // 갱신·replay 후에도 401이면 자격이 무효로 확정된 것이다. F001 계약의 refresh
        // 오류 code도 같은 뜻이다.
        SettingsErrorCodes.INVALID_ACCESS_TOKEN,
        AuthErrorCodes.INVALID_REFRESH_TOKEN,
        AuthErrorCodes.TOKEN_EXPIRED,
        AuthErrorCodes.DEVICE_MISMATCH,
        -> SettingsError.SessionExpired

        else -> if (httpStatus == 401) SettingsError.SessionExpired else SettingsError.Unexpected
    }
}
