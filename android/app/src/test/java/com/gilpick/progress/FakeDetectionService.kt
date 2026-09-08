package com.gilpick.progress

import com.gilpick.auth.ResponseMeta
import com.gilpick.auth.SuccessEnvelope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * network 없이 감지 API 응답을 정하는 [DetectionService].
 *
 * F006 [FakeProgressService]와 같은 방식이다. ViewModel test는 어떤 답을 보냈는지 확인해야
 * 하므로 요청 body를 그대로 기록한다.
 */
class FakeDetectionService : DetectionService {

    /** 지금까지 도착한 확인 응답의 `(transitionId, 보낸 답)`. */
    val decideCalls = mutableListOf<Pair<String, TransitionDecision>>()

    var onDecide: suspend (DecisionRequest) -> Response<SuccessEnvelope<TransitionResultDto>> = { decideOk() }

    override suspend fun registerEvent(
        bearer: String,
        tripId: String,
        date: String,
        body: ProgressEventRequest,
    ): Response<SuccessEnvelope<ProgressEventResultDto>> = throw UnsupportedOperationException("이 test는 이벤트 등록을 쓰지 않는다")

    override suspend fun decide(
        bearer: String,
        idempotencyKey: String,
        transitionId: String,
        body: DecisionRequest,
    ): Response<SuccessEnvelope<TransitionResultDto>> {
        decideCalls += transitionId to body.decision
        return onDecide(body)
    }

    override suspend fun undo(
        bearer: String,
        idempotencyKey: String,
        transitionId: String,
    ): Response<SuccessEnvelope<UndoResultDto>> = throw UnsupportedOperationException("이 test는 되돌리기를 쓰지 않는다")
}

fun decideOk(): Response<SuccessEnvelope<TransitionResultDto>> = Response.success(
    SuccessEnvelope(
        success = true,
        data = TransitionResultDto(
            transitionId = TRANSITION_ID,
            status = TransitionStatus.CONFIRMED,
            affectedItems = emptyList(),
            dayStatus = null,
            undoDeadline = null,
            nextPromptAt = null,
            progressVersion = 3,
        ),
        meta = ResponseMeta(requestId = DETECTION_REQUEST_ID),
    ),
)

/** 통신 실패를 흉내 낸다. `다시 시도`가 뜨는 유일한 경로다. */
fun decideServerError(): Response<SuccessEnvelope<TransitionResultDto>> = Response.error(
    500,
    """{"success": false, "error": {"code": "INTERNAL_ERROR", "message": "서버 오류"}}"""
        .toResponseBody("application/json".toMediaType()),
)
