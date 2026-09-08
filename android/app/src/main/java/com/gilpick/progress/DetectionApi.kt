package com.gilpick.progress

import com.gilpick.auth.SuccessEnvelope
import com.gilpick.itinerary.ItemStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * 계약에 정의된 위치 감지 error code. 값은 `contracts/detection.openapi.yaml`을 따른다.
 *
 * `INVALID_REQUEST`·`INVALID_ACCESS_TOKEN`·`TRIP_FORBIDDEN`·`TRIP_NOT_FOUND`는 F006
 * [ProgressErrorCodes]와 같은 문자열이고, 나머지 다섯이 F007 전용이다.
 */
object DetectionErrorCodes {
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val INVALID_ACCESS_TOKEN = "INVALID_ACCESS_TOKEN"
    const val TRIP_FORBIDDEN = "TRIP_FORBIDDEN"
    const val TRIP_NOT_FOUND = "TRIP_NOT_FOUND"
    const val TRANSITION_NOT_PENDING = "TRANSITION_NOT_PENDING"
    const val INVALID_DECISION = "INVALID_DECISION"
    const val UNDO_WINDOW_EXPIRED = "UNDO_WINDOW_EXPIRED"
    const val TRANSITION_NOT_UNDOABLE = "TRANSITION_NOT_UNDOABLE"
    const val IDEMPOTENCY_KEY_CONFLICT = "IDEMPOTENCY_KEY_CONFLICT"
}

/** 지오펜스가 알린 전이 종류. 도착 판정은 [DWELL], 출발 판정은 [EXIT], 후보 취소는 [REENTER]다. */
@Serializable
enum class ProgressEventType { DWELL, EXIT, REENTER }

/** 감지 후보와 감지 대상의 종류. */
@Serializable
enum class DetectionKind { ARRIVAL, DEPARTURE }

/**
 * 되돌릴 수 있는 전환의 종류.
 *
 * [COMPOSITE]는 이전 장소를 벗어난 기록 없이 다음 장소 도착이 확정돼 두 변경이 함께 적용된
 * 경우다(FR-009). 후보·감지 대상에는 없고 확정된 전환에만 나타나므로 [DetectionKind]와 나눈다.
 */
@Serializable
enum class UndoableKind { ARRIVAL, DEPARTURE, COMPOSITE }

/**
 * 확인 시트에서 보낼 수 있는 응답.
 *
 * 후보 종류마다 받는 값이 다르다. 도착은 [CONFIRM]·[NOT_ARRIVED], 출발은 [CONFIRM]·[STILL_HERE]이며
 * 실제 허용 값은 서버가 [TransitionCandidateDto.allowedDecisions]로 알려 준다.
 */
@Serializable
enum class TransitionDecision { CONFIRM, NOT_ARRIVED, STILL_HERE }

/** 후보가 만들어진 뒤의 처리 결과. 확인 응답과 되돌리기 응답이 함께 쓴다. */
@Serializable
enum class TransitionStatus { PENDING_CONFIRMATION, CONFIRMED, AUTO_CONFIRMED, CANCELLED, UNDONE }

/**
 * 이벤트를 자동 판정에 쓰지 않은 이유.
 *
 * 기준 미충족은 오류가 아니라 정상 응답이다. 서버가 이벤트를 저장하되 판정에서 제외하고
 * 이 값으로 이유를 알려 준다(FR-002).
 */
@Serializable
enum class EventRejectionReason {
    LOW_ACCURACY,
    STALE,
    DAY_NOT_IN_PROGRESS,
    ITEM_NOT_ELIGIBLE,
    DETECTION_PAUSED,
    PROMPT_LIMIT_REACHED,
    DEPARTURE_DETECTION_STOPPED,
}

/** 위치 이벤트가 발생한 좌표와 정확도. */
@Serializable
data class EventLocationDto(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
)

/**
 * `POST .../progress/events` 요청(PROG-003).
 *
 * @property eventId 앱이 만든 중복 방지 ID. 지오펜스 broadcast는 OS가 중복 전달할 수 있고
 *   통신 실패 재시도도 있으므로, 같은 전이에는 같은 값을 다시 보내야 서버가 최초 결과를
 *   그대로 돌려준다(FR-004).
 * @property geofenceId 앱이 등록한 지오펜스 식별자. `{itemId}:{ARRIVAL|DEPARTURE}` 형식이다.
 * @property occurredAt 기기에서 전이가 발생한 시각. ISO-8601.
 */
@Serializable
data class ProgressEventRequest(
    val eventId: String,
    val eventType: ProgressEventType,
    val itemId: String,
    val geofenceId: String,
    val occurredAt: String,
    val location: EventLocationDto,
)

/**
 * 확인 시트가 보여 주는 감지 근거(UI-001).
 *
 * @property dwellMinutes 도착 후보에서 반경 안에 머문 시간. 출발 후보는 `null`이다.
 */
@Serializable
data class CandidateEvidenceDto(
    val occurredAt: String,
    val accuracyMeters: Double,
    val dwellMinutes: Int?,
)

/**
 * 답을 기다리는 도착·출발 후보 하나.
 *
 * @property autoFinalizeAt 응답이 없을 때 자동으로 확정되는 시각. 앱은 남은 시간 **표시에만**
 *   쓰고 만료 판정은 하지 않는다. 확정은 서버 시각으로 판정한다(FR-018).
 * @property allowedDecisions 이 후보가 받는 응답. 시트의 두 행동을 여기서 고른다.
 */
@Serializable
data class TransitionCandidateDto(
    val transitionId: String,
    val itemId: String,
    val type: DetectionKind,
    val status: TransitionStatus,
    val detectedAt: String,
    val autoFinalizeAt: String,
    val allowedDecisions: List<TransitionDecision>,
    val evidence: CandidateEvidenceDto,
)

/**
 * `POST .../progress/events` 응답 data.
 *
 * @property accepted 자동 판정에 사용했는지. `false`면 이벤트만 저장하고 상태를 바꾸지 않았다.
 * @property rejectionReason [accepted]가 `false`인 이유. `true`면 `null`이다.
 * @property candidate 이 이벤트로 새로 만들어진 후보. 만들지 않았으면 `null`이다.
 * @property cancelledTransitionId `REENTER`로 취소된 출발 후보. 취소가 없었으면 `null`이다.
 */
@Serializable
data class ProgressEventResultDto(
    val eventId: String,
    val accepted: Boolean,
    val rejectionReason: EventRejectionReason?,
    val candidate: TransitionCandidateDto?,
    val cancelledTransitionId: String?,
)

/** `POST /progress/transitions/{id}/decisions` 요청(PROG-004). */
@Serializable
data class DecisionRequest(
    val decision: TransitionDecision,
)

/** 하나의 전환으로 바뀐 장소와 그 전후 상태. */
@Serializable
data class AffectedItemDto(
    val itemId: String,
    val beforeStatus: ItemStatus,
    val afterStatus: ItemStatus,
)

/**
 * 확인 응답 결과(PROG-004).
 *
 * @property undoDeadline 자동 확정에만 있다. 사용자가 직접 확인한 전환에는 전용 되돌리기를
 *   제공하지 않으므로 `null`이다(FR-020).
 * @property nextPromptAt `NOT_ARRIVED`로 취소한 경우 다시 물을 수 있는 가장 이른 시각.
 *   질문 횟수 상한에 도달했으면 `null`이다(FR-008).
 */
@Serializable
data class TransitionResultDto(
    val transitionId: String,
    val status: TransitionStatus,
    val affectedItems: List<AffectedItemDto>,
    val dayStatus: DayStatus?,
    val undoDeadline: String?,
    val nextPromptAt: String?,
    val progressVersion: Int,
)

/**
 * 되돌리기 결과(PROG-005).
 *
 * @property dayStatus 되돌리기로 날짜 상태가 바뀌었으면 그 값. 당일 완료 해제가 여기 해당한다(FR-019).
 * @property detectionResumeAt 이 장소의 같은 종류 자동 감지를 다시 시작할 수 있는 시각(FR-017a).
 */
@Serializable
data class UndoResultDto(
    val transitionId: String,
    val status: TransitionStatus,
    val restoredItems: List<AffectedItemDto>,
    val dayStatus: DayStatus?,
    val detectionResumeAt: String,
    val progressVersion: Int,
)

/**
 * 지금 감지해야 할 대상 하나. F006 PROG-001 응답에 함께 실려 온다.
 *
 * 대상 판정 규칙은 모두 서버에 있다. 앱은 이 목록과 현재 등록 상태의 차이만 반영하고
 * 무엇을 감지할지 직접 계산하지 않는다(`research.md` 4절).
 *
 * @property geofenceId `{itemId}:{kind}`. 등록·해제에 그대로 쓴다.
 * @property dwellMinutes [DetectionKind.ARRIVAL]에만 있는 체류 판정 시간. 출발은 `null`이다.
 */
@Serializable
data class DetectionTargetDto(
    val itemId: String,
    val kind: DetectionKind,
    val geofenceId: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val dwellMinutes: Int?,
)

/**
 * 아직 되돌릴 수 있는 자동 확정. F006 PROG-001 응답에 함께 실려 온다.
 *
 * @property undoDeadline 되돌릴 수 있는 마지막 시각. 만료 판정은 서버가 한다(FR-018).
 */
@Serializable
data class UndoableTransitionDto(
    val transitionId: String,
    val itemId: String,
    val type: UndoableKind,
    val confirmedAt: String,
    val undoDeadline: String,
)

/**
 * 위치 감지 endpoint 전용 Json 설정.
 *
 * 서버가 field를 추가해도 앱이 깨지지 않도록 모르는 key를 무시한다. 계약이 required로 정의한
 * nullable field(`rejectionReason`·`candidate`·`undoDeadline` 등)는 `null`도 key째 주고받아야
 * 하므로 `explicitNulls`는 기본값(true)을 유지한다. F004 `ItineraryApi`와 같은 이유다.
 */
private val detectionJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** 위치 감지 endpoint 전용 Retrofit 인스턴스를 만든다. */
fun createDetectionRetrofit(
    baseUrl: String,
    client: OkHttpClient = OkHttpClient(),
): Retrofit = Retrofit.Builder()
    .baseUrl(baseUrl)
    .client(client)
    .addConverterFactory(detectionJson.asConverterFactory("application/json".toMediaType()))
    .build()

/**
 * 위치 감지 endpoint 호출 계약.
 *
 * Access Token은 [com.gilpick.auth.AuthRepository.withAuthorizedCall]이 넘겨주므로 각 함수가
 * `Authorization` header를 직접 받는다. F006 [ProgressService]와 같은 구조다.
 *
 * 감지 대상 목록(`detectionTargets`)과 답을 기다리는 후보(`pendingCandidate`), 되돌릴 수 있는
 * 전환(`undoable`)은 이 계약이 아니라 F006 PROG-001 응답으로 온다.
 */
interface DetectionService {

    /**
     * 지오펜스가 알린 위치 이벤트를 등록한다(PROG-003).
     *
     * 정확도·유효 시간 기준을 넘긴 이벤트도 오류가 아니라 `200`이며, 응답의
     * [ProgressEventResultDto.accepted]가 `false`로 온다. 앱이 재시도하지 않도록 하기 위해서다.
     *
     * 같은 [ProgressEventRequest.eventId]로 다시 보내면 최초 처리 결과를 그대로 받는다.
     */
    @POST("trips/{tripId}/days/{date}/progress/events")
    suspend fun registerEvent(
        @Header("Authorization") bearer: String,
        @Path("tripId") tripId: String,
        @Path("date") date: String,
        @Body body: ProgressEventRequest,
    ): Response<SuccessEnvelope<ProgressEventResultDto>>

    /**
     * 확인 시트의 응답을 보낸다(PROG-004).
     *
     * `409`는 이미 처리된 후보(`TRANSITION_NOT_PENDING`)이거나 후보 종류가 받지 않는
     * 응답(`INVALID_DECISION`)이다. 두 경우 모두 진행을 다시 조회한다.
     */
    @POST("progress/transitions/{transitionId}/decisions")
    suspend fun decide(
        @Header("Authorization") bearer: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Path("transitionId") transitionId: String,
        @Body body: DecisionRequest,
    ): Response<SuccessEnvelope<TransitionResultDto>>

    /**
     * 자동으로 확정된 전환을 되돌린다(PROG-005).
     *
     * `409`는 되돌릴 수 있는 시간이 지났거나(`UNDO_WINDOW_EXPIRED`) 자동 확정이 아닌
     * 전환(`TRANSITION_NOT_UNDOABLE`)이다.
     */
    @POST("progress/transitions/{transitionId}/undo")
    suspend fun undo(
        @Header("Authorization") bearer: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Path("transitionId") transitionId: String,
    ): Response<SuccessEnvelope<UndoResultDto>>
}
