package com.gilpick.notification

import com.gilpick.auth.SuccessEnvelope
import com.gilpick.place.PlaceListMeta
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 계약에 정의된 알림·기기 error code. 값은 `contracts/notifications.openapi.yaml`과 서버
 * `services/notification`을 따른다.
 */
object NotificationErrorCodes {
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val INVALID_ACCESS_TOKEN = "INVALID_ACCESS_TOKEN"
    const val NOTIFICATION_NOT_FOUND = "NOTIFICATION_NOT_FOUND"
    const val NOTIFICATION_FORBIDDEN = "NOTIFICATION_FORBIDDEN"
    const val DEVICE_SESSION_NOT_FOUND = "DEVICE_SESSION_NOT_FOUND"
    const val DEVICE_FORBIDDEN = "DEVICE_FORBIDDEN"
}

/**
 * 알림 유형(research R1). 앱은 이 값으로 아이콘과 탭 목적지를 고른다.
 *
 * 도착 재질문은 별도 유형이 아니라 [ARRIVAL_CHECK]가 한 번 더 온다.
 */
@Serializable
enum class NotificationType {
    PLACE_CHANGE_SUGGESTION,
    ARRIVAL_CHECK,
    DEPARTURE_CHECK,
    ARRIVAL_AUTO_CONFIRMED,
    DEPARTURE_AUTO_CONFIRMED,
}

/**
 * NOTI-001 목록 항목.
 *
 * @property detectionId [NotificationType.PLACE_CHANGE_SUGGESTION]에만 있다.
 * @property tripDayId 도착·출발·자동 처리·재질문에만 있다. [itemId]·[transitionId]도 같다.
 * @property title 서버가 만든 제목. 화면은 그대로 쓴다(research R14).
 */
@Serializable
data class NotificationItemDto(
    val notificationId: String,
    val type: NotificationType,
    val tripId: String,
    val tripDayId: String? = null,
    val itemId: String? = null,
    val detectionId: String? = null,
    val transitionId: String? = null,
    val title: String,
    val body: String,
    val read: Boolean,
    val createdAt: String,
)

/** NOTI-001 응답 data. */
@Serializable
data class NotificationListData(
    val items: List<NotificationItemDto>,
)

/** NOTI-001 응답 envelope. `meta.pagination`이 붙어 [SuccessEnvelope] 대신 쓴다(F003·F009와 같다). */
@Serializable
data class NotificationListEnvelope(
    val success: Boolean,
    val data: NotificationListData,
    val meta: PlaceListMeta,
)

/** NOTI-002 결과. 이미 읽음이어도 `read=true`다(멱등). */
@Serializable
data class MarkReadResultDto(
    val notificationId: String,
    val read: Boolean,
)

/** NOTI-003 결과. @property updated 이번에 읽음으로 바뀐 건수. 0이어도 성공이다. */
@Serializable
data class MarkAllReadResultDto(
    val updated: Int,
)

/**
 * DEV-001 요청. @property deviceId [com.gilpick.auth.AuthSessionStore.deviceId]와 같은 값이어야
 * 서버가 활성 세션을 찾는다.
 */
@Serializable
data class FcmTokenRegisterRequest(
    val deviceId: String,
    val fcmToken: String,
    val platform: String = "ANDROID",
)

/** DEV-001 결과. */
@Serializable
data class FcmTokenRegisterResultDto(
    val deviceId: String,
    val registered: Boolean,
)

/**
 * 알림·기기 endpoint 전용 Json 설정.
 *
 * 서버가 field를 추가해도 앱이 깨지지 않도록 모르는 key를 무시하고, DEV-001 body의
 * 기본값(`platform`)이 실제로 전송되도록 기본값을 encoding한다(F006과 같다).
 */
private val notificationJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** 알림·기기 endpoint 전용 Retrofit 인스턴스를 만든다. F009 `createAlternativeRetrofit`과 같은 구조다. */
fun createNotificationRetrofit(
    baseUrl: String,
    client: OkHttpClient = OkHttpClient(),
): Retrofit = Retrofit.Builder()
    .baseUrl(baseUrl)
    .client(client)
    .addConverterFactory(notificationJson.asConverterFactory("application/json".toMediaType()))
    .build()

/**
 * 알림·기기 endpoint 호출 계약.
 *
 * Access Token은 [com.gilpick.auth.AuthRepository.withAuthorizedCall]이 넘겨주므로 각 함수가
 * `Authorization` header를 직접 받는다. F009 `AlternativeService`와 같은 구조다.
 */
interface NotificationService {

    /**
     * 알림 목록을 최신순으로 조회한다(NOTI-001). 생성 후 90일이 지난 알림은 서버가 제외한다.
     *
     * @param read `true`면 읽은 알림만, `false`면 안 읽은 알림만, `null`이면 전체.
     */
    @GET("notifications")
    suspend fun listNotifications(
        @Header("Authorization") bearer: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("read") read: Boolean? = null,
    ): Response<NotificationListEnvelope>

    /** 알림 한 건을 읽음으로 만든다(NOTI-002). 이미 읽음이면 그대로 성공이다. */
    @PATCH("notifications/{notificationId}/read")
    suspend fun markRead(
        @Header("Authorization") bearer: String,
        @Path("notificationId") notificationId: String,
    ): Response<SuccessEnvelope<MarkReadResultDto>>

    /** 안 읽은 알림을 모두 읽음으로 만든다(NOTI-003). */
    @PATCH("notifications/read-all")
    suspend fun markAllRead(
        @Header("Authorization") bearer: String,
    ): Response<SuccessEnvelope<MarkAllReadResultDto>>

    /** 이 기기 세션에 FCM 토큰을 등록·갱신한다(DEV-001). PUT이라 멱등이다. */
    @PUT("devices/fcm-token")
    suspend fun registerFcmToken(
        @Header("Authorization") bearer: String,
        @Body body: FcmTokenRegisterRequest,
    ): Response<SuccessEnvelope<FcmTokenRegisterResultDto>>

    /** 이 기기 세션의 FCM 토큰을 비운다(DEV-002). 계약상 성공은 body 없는 `204`다. */
    @DELETE("devices/{deviceId}/fcm-token")
    suspend fun unregisterFcmToken(
        @Header("Authorization") bearer: String,
        @Path("deviceId") deviceId: String,
    ): Response<Unit>
}
