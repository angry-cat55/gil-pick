package com.gilpick.progress

import android.content.Context
import com.gilpick.BuildConfig
import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthErrorCodes
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthService
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.SessionRevocationWorker
import com.gilpick.auth.createAuthRetrofit
import com.gilpick.auth.AuthResult
import com.gilpick.auth.SuccessEnvelope
import com.gilpick.auth.toAuthResult
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import retrofit2.Response

/**
 * 위치 감지 데이터의 유일한 접근 지점.
 *
 * Access Token 주입과 만료 시 갱신·replay는 [AuthRepository.withAuthorizedCall]이 소유한다.
 * F006 [ProgressRepository]와 같은 구조다.
 *
 * `Idempotency-Key`는 요청 내용에서 파생한 UUID다. 통신 실패 후 같은 요청을 다시 보내면
 * 자동으로 같은 key가 나가 서버가 최초 결과를 돌려준다. 호출자가 key를 보관할 필요가 없다.
 * 위치 이벤트는 이 header 대신 앱이 만든 `eventId`로 중복을 막는다(계약 PROG-003).
 */
class DetectionRepository(
    private val api: DetectionService,
    private val auth: AuthRepository,
) {

    /**
     * 지오펜스가 알린 위치 이벤트를 서버에 올린다(PROG-003).
     *
     * 기준 미충족 이벤트도 성공 응답이다. 판정에 쓰였는지는
     * [ProgressEventResultDto.accepted]로 확인한다.
     *
     * @param eventId 이 전이를 식별하는 UUID. 같은 전이의 재전송에는 반드시 같은 값을 쓴다.
     * @param occurredAt 기기에서 전이가 발생한 시각. ISO-8601 문자열.
     */
    suspend fun registerEvent(
        tripId: String,
        date: LocalDate,
        eventId: String,
        eventType: ProgressEventType,
        itemId: String,
        geofenceId: String,
        occurredAt: String,
        location: EventLocationDto,
    ): AuthResult<ProgressEventResultDto> = call { token ->
        api.registerEvent(
            bearer = token,
            tripId = tripId,
            date = date.iso(),
            body = ProgressEventRequest(
                eventId = eventId,
                eventType = eventType,
                itemId = itemId,
                geofenceId = geofenceId,
                occurredAt = occurredAt,
                location = location,
            ),
        )
    }

    /**
     * 확인 시트의 응답을 보낸다(PROG-004).
     *
     * @param decision 후보의 [TransitionCandidateDto.allowedDecisions]에 있는 값만 보낸다.
     *   그렇지 않으면 [DetectionError.InvalidDecision]이다.
     */
    suspend fun decide(
        transitionId: String,
        decision: TransitionDecision,
    ): AuthResult<TransitionResultDto> = call { token ->
        api.decide(
            bearer = token,
            idempotencyKey = idempotencyKey("decision", transitionId, decision.name),
            transitionId = transitionId,
            body = DecisionRequest(decision = decision),
        )
    }

    /**
     * 자동으로 확정된 전환을 되돌린다(PROG-005).
     *
     * 되돌릴 수 있는 시간이 지났는지는 서버가 판정한다. 앱은 남은 시간을 표시만 하고
     * 만료를 스스로 결정하지 않는다(FR-018).
     */
    suspend fun undo(transitionId: String): AuthResult<UndoResultDto> = call { token ->
        api.undo(
            bearer = token,
            idempotencyKey = idempotencyKey("undo", transitionId),
            transitionId = transitionId,
        )
    }

    /** 세 endpoint가 같은 인증·통신 실패 규칙을 쓰도록 한곳에 모은다. */
    private suspend fun <T> call(
        request: suspend (bearer: String) -> Response<SuccessEnvelope<T>>,
    ): AuthResult<T> = auth.withAuthorizedCall { accessToken ->
        try {
            request("Bearer $accessToken").toAuthResult()
        } catch (e: IOException) {
            AuthResult.Failure(AuthError.Offline(e))
        }
    }

    private fun LocalDate.iso(): String = format(DateTimeFormatter.ISO_LOCAL_DATE)

    /** 같은 요청 내용이면 같은 UUID(v3)를 돌려준다. F006 [ProgressRepository]와 같은 방식이다. */
    private fun idempotencyKey(vararg parts: Any): String =
        UUID.nameUUIDFromBytes(parts.joinToString("|").toByteArray()).toString()

    companion object {

        /**
         * 실제 서버를 향한 repository를 조립한다.
         *
         * 지오펜스 broadcast는 화면·ViewModel 없이 도착하므로 receiver가 직접 만들어 쓸 곳이
         * 필요하다. F006 `ProgressViewModel.defaultRepository`와 같은 조립이다.
         */
        fun default(context: Context): DetectionRepository {
            val appContext = context.applicationContext
            val auth = AuthRepository(
                store = AuthSessionStore.create(appContext),
                api = createAuthRetrofit(BuildConfig.API_BASE_URL).create(AuthService::class.java),
                appLinkHandler = AuthAppLinkHandler(BuildConfig.APP_LINK_HOST),
                scheduleRevocation = SessionRevocationWorker.scheduler(appContext),
            )
            return DetectionRepository(
                api = createDetectionRetrofit(BuildConfig.API_BASE_URL).create(DetectionService::class.java),
                auth = auth,
            )
        }
    }
}

/**
 * 위치 감지 요청이 실패한 이유. 화면이 원인과 다음 행동을 안내하는 데 쓴다.
 *
 * HTTP 상태 코드가 아니라 계약이 정한 error code로 판정한다. `409` 하나에 네 가지 원인이
 * 몰려 있고 다음 행동(재조회 vs 안내 vs 무시)이 서로 다르기 때문이다.
 *
 * 기준 미충족 이벤트는 여기 없다. 오류가 아니라 성공 응답의
 * [ProgressEventResultDto.rejectionReason]으로 온다.
 */
sealed interface DetectionError {
    /** 통신 실패. 같은 요청을 그대로 다시 보내면 같은 `eventId`·`Idempotency-Key`가 나간다. */
    data object Network : DetectionError

    /**
     * `409 TRANSITION_NOT_PENDING`. 이미 확정·취소된 후보다.
     *
     * 답을 보내는 사이 자동 확정이 일어났거나 다른 기기가 먼저 답한 경우다. 화면은 오류를
     * 띄우기보다 진행을 다시 조회해 최신 상태를 보여 준다.
     */
    data object TransitionNotPending : DetectionError

    /** `409 INVALID_DECISION`. 후보 종류가 받지 않는 응답을 보냈다. 앱 결함이므로 진행을 재조회한다. */
    data object InvalidDecision : DetectionError

    /** `409 UNDO_WINDOW_EXPIRED`. 되돌릴 수 있는 시간이 지났다. F006 상태 수정으로 안내한다. */
    data object UndoWindowExpired : DetectionError

    /** `409 TRANSITION_NOT_UNDOABLE`. 자동 확정이 아니거나 이미 되돌린 전환이다. */
    data object TransitionNotUndoable : DetectionError

    /** `409 IDEMPOTENCY_KEY_CONFLICT`. 같은 key로 다른 내용을 보냈다. 진행을 다시 조회한다. */
    data object IdempotencyKeyConflict : DetectionError

    /** `403 TRIP_FORBIDDEN`. 다른 사용자의 여행이다. */
    data object Forbidden : DetectionError

    /** `404 TRIP_NOT_FOUND`. 없거나 삭제된 여행·날짜다. */
    data object NotFound : DetectionError

    /** 갱신·replay 후에도 자격이 무효로 확정됐다. F001 재인증 흐름으로 넘어간다. */
    data object SessionExpired : DetectionError

    /** 그 밖의 실패(`400 INVALID_REQUEST`, 5xx, 계약과 다른 응답). 잠시 후 다시 시도한다. */
    data object Unexpected : DetectionError
}

/** 인증 계층의 실패를 감지 화면이 안내할 수 있는 원인으로 좁힌다. */
fun AuthError.toDetectionError(): DetectionError = when (this) {
    is AuthError.Offline -> DetectionError.Network
    is AuthError.Malformed, is AuthError.Callback -> DetectionError.Unexpected
    is AuthError.Server -> when (code) {
        DetectionErrorCodes.TRANSITION_NOT_PENDING -> DetectionError.TransitionNotPending
        DetectionErrorCodes.INVALID_DECISION -> DetectionError.InvalidDecision
        DetectionErrorCodes.UNDO_WINDOW_EXPIRED -> DetectionError.UndoWindowExpired
        DetectionErrorCodes.TRANSITION_NOT_UNDOABLE -> DetectionError.TransitionNotUndoable
        DetectionErrorCodes.IDEMPOTENCY_KEY_CONFLICT -> DetectionError.IdempotencyKeyConflict
        DetectionErrorCodes.TRIP_FORBIDDEN -> DetectionError.Forbidden
        DetectionErrorCodes.TRIP_NOT_FOUND -> DetectionError.NotFound

        // 갱신·replay 후에도 401이면 자격이 무효로 확정된 것이다. F001 계약의 refresh
        // 오류 code도 같은 뜻이다.
        DetectionErrorCodes.INVALID_ACCESS_TOKEN,
        AuthErrorCodes.INVALID_REFRESH_TOKEN,
        AuthErrorCodes.TOKEN_EXPIRED,
        AuthErrorCodes.DEVICE_MISMATCH,
        -> DetectionError.SessionExpired

        else -> if (httpStatus == 401) DetectionError.SessionExpired else DetectionError.Unexpected
    }
}
