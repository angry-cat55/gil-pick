package com.gilpick.settings

import com.gilpick.auth.SuccessEnvelope
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * F012 사용자 설정 test가 함께 쓰는 계약 응답 fixture.
 *
 * 값은 `specs/012-user-settings/contracts/preferences.openapi.yaml`을 따른다. 다른 feature와 같이
 * JSON 문자열을 두어 계약과 DTO가 어긋나면 파싱에서 드러나게 한다.
 */
internal const val SETTINGS_REQUEST_ID = "11111111-2222-4333-8444-555555555555"

/** PREF-001·PREF-002 성공 응답. 새 사용자의 기본값은 켜짐이다(FR-002). */
internal fun preferenceJson(enabled: Boolean = true) = """
    {
      "success": true,
      "data": {"placeChangeSuggestionNotificationEnabled": $enabled},
      "meta": {"requestId": "$SETTINGS_REQUEST_ID"}
    }
""".trimIndent()

/** 공통 오류 봉투. `docs/design/api-spec.md` 1절 형식이다. */
internal fun settingsErrorJson(code: String, retryable: Boolean = false) = """
    {
      "success": false,
      "error": {"code": "$code", "message": "테스트 오류", "retryable": $retryable, "details": {}},
      "meta": {"requestId": "$SETTINGS_REQUEST_ID"}
    }
""".trimIndent()

private val fakeJson = Json { ignoreUnknownKeys = true }

/**
 * network 없이 설정 API 응답을 정하는 [SettingsService].
 *
 * F007 `FakeDetectionService`·F009 `FakeAlternativeService`와 같은 방식이다. HTTP 왕복은
 * [SettingsApiTest]가 보므로 여기서는 어떤 값이 나갔는지만 기록한다.
 */
class FakeSettingsService : SettingsService {

    /** 지금까지 도착한 조회 수. 재시도가 실제로 다시 조회하는지 확인한다. */
    var getCalls = 0
        private set

    /** 지금까지 도착한 변경 요청의 값. 단일 in-flight와 마지막 희망값을 확인한다. */
    val updateCalls = mutableListOf<Boolean>()

    var onGet: suspend () -> Response<SuccessEnvelope<PreferenceDto>> = { preferenceOk() }

    var onUpdate: suspend (Boolean) -> Response<SuccessEnvelope<PreferenceDto>> = { preferenceOk(it) }

    override suspend fun getPreferences(bearer: String): Response<SuccessEnvelope<PreferenceDto>> {
        getCalls++
        return onGet()
    }

    override suspend fun updatePreferences(
        bearer: String,
        body: UpdatePreferenceRequest,
    ): Response<SuccessEnvelope<PreferenceDto>> {
        updateCalls += body.placeChangeSuggestionNotificationEnabled
        return onUpdate(body.placeChangeSuggestionNotificationEnabled)
    }
}

/** 계약 JSON을 성공 응답으로 만든다. DTO를 직접 조립하지 않아 계약과 어긋나면 파싱에서 드러난다. */
fun preferenceOk(enabled: Boolean = true): Response<SuccessEnvelope<PreferenceDto>> =
    Response.success(fakeJson.decodeFromString(preferenceJson(enabled)))

/** 계약이 정한 오류 응답. */
fun preferenceFailure(code: String, httpStatus: Int, retryable: Boolean = false): Response<SuccessEnvelope<PreferenceDto>> =
    Response.error(
        httpStatus,
        settingsErrorJson(code, retryable = retryable).toResponseBody("application/json".toMediaType()),
    )
