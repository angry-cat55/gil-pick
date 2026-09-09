package com.gilpick.alternative

import com.gilpick.auth.SuccessEnvelope
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * network 없이 대체 장소 API 응답을 정하는 [AlternativeService].
 *
 * F007 `FakeDetectionService`와 같은 방식이다. 응답은 [AlternativeFixtures.kt]의 계약 JSON을 그대로
 * 역직렬화해 DTO와 계약이 어긋나면 여기서도 드러나게 한다.
 */
class FakeAlternativeService : AlternativeService {

    /** 지금까지 도착한 거절 요청의 detectionId. */
    val dismissCalls = mutableListOf<String>()

    var onGetDetection: suspend () -> Response<SuccessEnvelope<DetectionDetailDto>> = { ok(detectionDetailJson()) }
    var onListAlternatives: suspend () -> Response<SuccessEnvelope<AlternativeListDto>> = { ok(alternativesJson()) }
    var onDismiss: suspend () -> Response<SuccessEnvelope<DismissResultDto>> = { ok(dismissJson()) }

    override suspend fun listDetections(
        bearer: String,
        tripId: String,
        status: DetectionStatus?,
        cursor: String?,
        limit: Int?,
    ): Response<DetectionListEnvelope> = throw UnsupportedOperationException("이 test는 감지 목록을 쓰지 않는다")

    override suspend fun getDetection(bearer: String, detectionId: String) = onGetDetection()

    override suspend fun dismissDetection(bearer: String, detectionId: String): Response<SuccessEnvelope<DismissResultDto>> {
        dismissCalls += detectionId
        return onDismiss()
    }

    override suspend fun listAlternatives(bearer: String, detectionId: String) = onListAlternatives()

    override suspend fun searchAlternatives(
        bearer: String,
        detectionId: String,
        query: String,
        cursor: String?,
        limit: Int?,
    ): Response<AlternativeSearchEnvelope> = throw UnsupportedOperationException("이 test는 직접 검색을 쓰지 않는다")
}

private val json = Json { ignoreUnknownKeys = true }

/** 계약 JSON을 성공 응답으로 감싼다. */
internal inline fun <reified T> ok(body: String): Response<SuccessEnvelope<T>> =
    Response.success(json.decodeFromString<SuccessEnvelope<T>>(body))

/** 계약 오류 JSON을 HTTP 오류 응답으로 감싼다. */
internal fun <T> fail(httpStatus: Int, code: String, retryable: Boolean = false, details: String = "null"): Response<T> =
    Response.error(httpStatus, alternativeErrorJson(code, retryable, details).toResponseBody("application/json".toMediaType()))
