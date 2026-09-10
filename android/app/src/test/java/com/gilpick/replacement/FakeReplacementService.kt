package com.gilpick.replacement

import com.gilpick.auth.SuccessEnvelope
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * network 없이 일정 변경 API 응답을 정하는 [ReplacementService].
 *
 * F007 `FakeDetectionService`·F009 `FakeAlternativeService`와 같은 방식이다. HTTP 왕복과
 * `Idempotency-Key` 생성 자체는 [ReplacementRepositoryTest]가 보므로, 여기서는 어떤 값이 나갔는지만
 * 기록해 ViewModel 흐름을 확인한다.
 */
class FakeReplacementService : ReplacementService {

    /** 지금까지 도착한 미리보기 생성 요청의 `(Idempotency-Key, 요청 body)`. */
    val previewCalls = mutableListOf<Pair<String, CreatePreviewRequest>>()

    /** 지금까지 도착한 폐기 요청의 previewId. */
    val rejectCalls = mutableListOf<String>()

    /** 지금까지 도착한 승인 요청의 `(Idempotency-Key, previewId)`. */
    val approveCalls = mutableListOf<Pair<String, String>>()

    var onCreatePreview: suspend () -> Response<SuccessEnvelope<RoutePreviewDto>> = { ok(routePreviewJson()) }

    var onApprove: suspend () -> Response<SuccessEnvelope<ReplacementDto>> = { approveOk(replacementJson()) }

    override suspend fun createPreview(
        bearer: String,
        idempotencyKey: String,
        detectionId: String,
        body: CreatePreviewRequest,
    ): Response<SuccessEnvelope<RoutePreviewDto>> {
        previewCalls += idempotencyKey to body
        return onCreatePreview()
    }

    override suspend fun approvePreview(
        bearer: String,
        idempotencyKey: String,
        previewId: String,
    ): Response<SuccessEnvelope<ReplacementDto>> {
        approveCalls += idempotencyKey to previewId
        return onApprove()
    }

    override suspend fun rejectPreview(bearer: String, previewId: String): Response<Unit> {
        rejectCalls += previewId
        return Response.success(204, Unit)
    }

    override suspend fun undoReplacement(
        bearer: String,
        replacementId: String,
    ): Response<SuccessEnvelope<ReplacementUndoResultDto>> =
        throw UnsupportedOperationException("되돌리기는 T031(#351)이 붙인다")
}

private val fakeJson = Json { ignoreUnknownKeys = true }

/** 계약 JSON을 성공 응답으로 만든다. DTO를 직접 조립하지 않아 계약과 어긋나면 파싱에서 드러난다. */
fun ok(body: String): Response<SuccessEnvelope<RoutePreviewDto>> = Response.success(fakeJson.decodeFromString(body))

/** 승인 성공 응답. 계약 JSON을 그대로 역직렬화한다. */
fun approveOk(body: String): Response<SuccessEnvelope<ReplacementDto>> =
    Response.success(fakeJson.decodeFromString(body))

/** 승인 실패 응답. */
fun approveFailure(code: String, httpStatus: Int, retryable: Boolean = false): Response<SuccessEnvelope<ReplacementDto>> =
    Response.error(
        httpStatus,
        replacementErrorJson(code, retryable = retryable).toResponseBody("application/json".toMediaType()),
    )

/** 계약이 정한 오류 응답. */
fun failure(code: String, httpStatus: Int, retryable: Boolean = false): Response<SuccessEnvelope<RoutePreviewDto>> =
    Response.error(
        httpStatus,
        replacementErrorJson(code, retryable = retryable).toResponseBody("application/json".toMediaType()),
    )
