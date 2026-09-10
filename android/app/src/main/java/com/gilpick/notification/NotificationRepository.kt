package com.gilpick.notification

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
import com.gilpick.auth.createAuthRetrofit
import com.gilpick.auth.toAuthResult
import com.gilpick.auth.toEmptyAuthResult
import java.io.IOException

/**
 * NOTI-001 한 페이지.
 *
 * @property nextCursor 다음 페이지 요청에 그대로 전달한다. 마지막 페이지면 `null`이다.
 */
data class NotificationPage(
    val items: List<NotificationItemDto>,
    val nextCursor: String?,
    val hasNext: Boolean,
)

/**
 * 알림·기기 토큰 데이터의 유일한 접근 지점.
 *
 * Access Token 주입과 만료 시 갱신·replay는 [AuthRepository.withAuthorizedCall]이 소유한다.
 * F009 `AlternativeRepository`와 같은 구조다. 어떤 요청도 감지·전환·일정·경로 상태를 바꾸지
 * 않는다(FR-025).
 */
class NotificationRepository(
    private val api: NotificationService,
    private val auth: AuthRepository,
) {

    /** 알림 목록을 조회한다(NOTI-001). */
    suspend fun listNotifications(
        cursor: String? = null,
        limit: Int? = null,
        read: Boolean? = null,
    ): AuthResult<NotificationPage> = call { token ->
        api.listNotifications(bearer = token, cursor = cursor, limit = limit, read = read).toAuthResult {
            NotificationPage(items = it.data.items, nextCursor = it.meta.pagination.nextCursor, hasNext = it.meta.pagination.hasNext)
        }
    }

    /** 알림 한 건을 읽음 처리한다(NOTI-002). 멱등이라 이미 읽음이어도 성공이다. */
    suspend fun markRead(notificationId: String): AuthResult<MarkReadResultDto> = call { token ->
        api.markRead(bearer = token, notificationId = notificationId).toAuthResult()
    }

    /** 안 읽은 알림을 모두 읽음 처리한다(NOTI-003). */
    suspend fun markAllRead(): AuthResult<MarkAllReadResultDto> = call { token ->
        api.markAllRead(bearer = token).toAuthResult()
    }

    /** 이 기기의 FCM 토큰을 등록·갱신한다(DEV-001). 기기 ID는 로그인에 쓴 값과 같다. */
    suspend fun registerFcmToken(fcmToken: String): AuthResult<FcmTokenRegisterResultDto> {
        val deviceId = auth.deviceId()
        return call { token ->
            api.registerFcmToken(bearer = token, body = FcmTokenRegisterRequest(deviceId = deviceId, fcmToken = fcmToken)).toAuthResult()
        }
    }

    /** 이 기기의 FCM 토큰을 비운다(DEV-002). 이미 비어 있어도 성공이다. */
    suspend fun unregisterFcmToken(): AuthResult<Unit> {
        val deviceId = auth.deviceId()
        return call { token -> api.unregisterFcmToken(bearer = token, deviceId = deviceId).toEmptyAuthResult() }
    }

    /** 다섯 endpoint가 같은 인증·통신 실패 규칙을 쓰도록 한곳에 모은다. */
    private suspend fun <T> call(
        request: suspend (bearer: String) -> AuthResult<T>,
    ): AuthResult<T> = auth.withAuthorizedCall { accessToken ->
        try {
            request("Bearer $accessToken")
        } catch (e: IOException) {
            AuthResult.Failure(AuthError.Offline(e))
        }
    }

    companion object {
        /** 실제 서버를 향한 repository. F009 `AlternativeRepository.default`와 같은 조립이다. */
        fun default(context: Context): NotificationRepository {
            val appContext = context.applicationContext
            val auth = AuthRepository(
                store = AuthSessionStore.create(appContext),
                api = createAuthRetrofit(BuildConfig.API_BASE_URL).create(AuthService::class.java),
                appLinkHandler = AuthAppLinkHandler(BuildConfig.APP_LINK_HOST),
                scheduleRevocation = SessionRevocationWorker.scheduler(appContext),
            )
            return NotificationRepository(
                api = createNotificationRetrofit(BuildConfig.API_BASE_URL).create(NotificationService::class.java),
                auth = auth,
            )
        }
    }
}

/** 알림·기기 요청이 실패한 이유. 화면이 원인과 다음 행동을 안내하는 데 쓴다. */
sealed interface NotificationError {
    /** 통신 실패. 같은 요청을 그대로 다시 보낸다. */
    data object Network : NotificationError

    /** `404 NOTIFICATION_NOT_FOUND`·`DEVICE_SESSION_NOT_FOUND`. 없거나 이미 정리된 대상이다. */
    data object NotFound : NotificationError

    /** `403 NOTIFICATION_FORBIDDEN`·`DEVICE_FORBIDDEN`. 다른 사용자의 알림·기기다. */
    data object Forbidden : NotificationError

    /** 갱신·replay 후에도 자격이 무효로 확정됐다. F001 재인증 흐름으로 넘어간다. */
    data object SessionExpired : NotificationError

    /** 그 밖의 실패(`400 INVALID_REQUEST`, 5xx, 계약과 다른 응답). 잠시 후 다시 시도한다. */
    data object Unexpected : NotificationError
}

/** 조회를 다시 보내면 결과가 달라질 수 있는 실패인지(다시 시도 버튼 표시 기준). */
val NotificationError.retryable: Boolean
    get() = when (this) {
        NotificationError.Network, NotificationError.Unexpected -> true
        NotificationError.NotFound, NotificationError.Forbidden, NotificationError.SessionExpired -> false
    }

/** 인증 계층의 실패를 알림 화면이 안내할 수 있는 원인으로 좁힌다. */
fun AuthError.toNotificationError(): NotificationError = when (this) {
    is AuthError.Offline -> NotificationError.Network
    is AuthError.Malformed, is AuthError.Callback -> NotificationError.Unexpected
    is AuthError.Server -> when (code) {
        NotificationErrorCodes.NOTIFICATION_NOT_FOUND,
        NotificationErrorCodes.DEVICE_SESSION_NOT_FOUND,
        -> NotificationError.NotFound

        NotificationErrorCodes.NOTIFICATION_FORBIDDEN,
        NotificationErrorCodes.DEVICE_FORBIDDEN,
        -> NotificationError.Forbidden

        // 갱신·replay 후에도 401이면 자격이 무효로 확정된 것이다. F001 계약의 refresh
        // 오류 code도 같은 뜻이다.
        NotificationErrorCodes.INVALID_ACCESS_TOKEN,
        AuthErrorCodes.INVALID_REFRESH_TOKEN,
        AuthErrorCodes.TOKEN_EXPIRED,
        AuthErrorCodes.DEVICE_MISMATCH,
        -> NotificationError.SessionExpired

        else -> when (httpStatus) {
            401 -> NotificationError.SessionExpired
            403 -> NotificationError.Forbidden
            404 -> NotificationError.NotFound
            else -> NotificationError.Unexpected
        }
    }
}
