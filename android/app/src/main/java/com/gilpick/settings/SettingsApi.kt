package com.gilpick.settings

import com.gilpick.auth.SuccessEnvelope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH

/**
 * 계약에 정의된 사용자 설정 error code. 값은 `contracts/preferences.openapi.yaml`을 따른다.
 *
 * 계약이 정한 실패는 `400`·`401` 둘뿐이다. 설정은 현재 사용자 것만 다루므로 소유권 오류가 없다(FR-011).
 */
object SettingsErrorCodes {
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val INVALID_ACCESS_TOKEN = "INVALID_ACCESS_TOKEN"
}

/**
 * 저장된 사용자 설정(PREF-001·PREF-002 응답 data).
 *
 * F012가 다루는 사용자 변경 가능 설정은 이 하나뿐이다(FR-001). 혼잡도·강수·운영 종료별 토글,
 * 감지 기준, 설정 초기화는 제공하지 않는다.
 *
 * @property placeChangeSuggestionNotificationEnabled 장소 변경 제안 알림 전체 ON/OFF.
 *   새 사용자의 기본값은 켜짐이다(FR-002).
 */
@Serializable
data class PreferenceDto(
    val placeChangeSuggestionNotificationEnabled: Boolean,
)

/**
 * `PATCH /users/me/preferences` 요청(PREF-002).
 *
 * 부분 갱신이 아니라 **절대값**을 보낸다. 여러 기기가 겹쳐 바꿔도 서버가 마지막으로 성공 처리한
 * 값이 최종 상태가 된다(FR-003). 그래서 앱은 증분이 아니라 지금 원하는 값을 그대로 싣는다.
 */
@Serializable
data class UpdatePreferenceRequest(
    val placeChangeSuggestionNotificationEnabled: Boolean,
)

/**
 * 사용자 설정 endpoint 전용 Json 설정.
 *
 * 서버가 설정을 늘려도 앱이 깨지지 않도록 모르는 key를 무시한다. 계약에 nullable field가 없어
 * `explicitNulls`는 기본값을 유지한다.
 */
private val settingsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** 사용자 설정 endpoint 전용 Retrofit 인스턴스를 만든다. */
fun createSettingsRetrofit(
    baseUrl: String,
    client: OkHttpClient = OkHttpClient(),
): Retrofit = Retrofit.Builder()
    .baseUrl(baseUrl)
    .client(client)
    .addConverterFactory(settingsJson.asConverterFactory("application/json".toMediaType()))
    .build()

/**
 * 사용자 설정 endpoint 호출 계약(PREF-001·PREF-002).
 *
 * Access Token은 [com.gilpick.auth.AuthRepository.withAuthorizedCall]이 넘겨주므로 각 함수가
 * `Authorization` header를 직접 받는다. 다른 feature의 service와 같은 구조다.
 *
 * 경로에 사용자 식별자가 없다. 서버가 token의 주체로 대상을 정하므로 다른 사용자의 설정을
 * 조회·변경할 수 없다(FR-011).
 */
interface SettingsService {

    /** 현재 사용자의 저장된 설정을 조회한다(PREF-001). */
    @GET("users/me/preferences")
    suspend fun getPreferences(
        @Header("Authorization") bearer: String,
    ): Response<SuccessEnvelope<PreferenceDto>>

    /**
     * 현재 사용자의 설정을 바꾼다(PREF-002).
     *
     * 응답은 **서버가 저장한 값**이다. 앱이 보낸 값과 다를 수 있으므로(다른 기기의 나중 변경)
     * 화면은 응답값을 정본으로 삼는다(FR-003).
     */
    @PATCH("users/me/preferences")
    suspend fun updatePreferences(
        @Header("Authorization") bearer: String,
        @Body body: UpdatePreferenceRequest,
    ): Response<SuccessEnvelope<PreferenceDto>>
}
