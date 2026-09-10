package com.gilpick.replacement

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
import com.gilpick.auth.toEmptyAuthResult
import com.gilpick.notification.FcmTokenClearWorker
import com.gilpick.notification.FcmTokenSyncWorker
import java.io.IOException
import java.util.UUID
import retrofit2.Response

/**
 * 일정 변경 데이터의 유일한 접근 지점.
 *
 * Access Token 주입과 만료 시 갱신·replay는 [AuthRepository.withAuthorizedCall]이 소유한다.
 * F007 [com.gilpick.progress.DetectionRepository]와 같은 구조다.
 *
 * `Idempotency-Key`는 요청 내용에서 파생한 UUID다. 통신 실패 후 같은 요청을 다시 보내면
 * 자동으로 같은 key가 나가 서버가 최초 결과를 돌려준다(FR-011). 호출자가 key를 보관할 필요가
 * 없고, 화면의 `다시 시도`가 중복 승인을 만들지 않는다.
 */
class ReplacementRepository(
    private val api: ReplacementService,
    private val auth: AuthRepository,
) {

    /**
     * 감지 결과와 대체 장소로 변경 경로 미리보기를 만든다(REPL-001).
     *
     * 이 호출은 일정을 바꾸지 않는다(FR-001). 실패해도 기존 일정은 그대로이므로 화면은
     * `다시 시도`와 `다른 후보 보기`를 함께 제공한다(FR-007·UI-004).
     *
     * @param candidateId F009 추천 후보에서 왔으면 그 식별자, 직접 검색에서 왔으면 `null`.
     *   같은 장소라도 이 값이 다르면 다른 요청이므로 `Idempotency-Key`도 달라진다.
     * @param scheduleVersion 앱이 보고 있는 그 날짜의 일정 version.
     */
    suspend fun createPreview(
        detectionId: String,
        placeId: String,
        candidateId: String?,
        scheduleVersion: Int,
    ): AuthResult<RoutePreviewDto> = call { token ->
        api.createPreview(
            bearer = token,
            idempotencyKey = idempotencyKey("preview", detectionId, placeId, candidateId, scheduleVersion),
            detectionId = detectionId,
            body = CreatePreviewRequest(
                placeId = placeId,
                candidateId = candidateId,
                scheduleVersion = scheduleVersion,
            ),
        )
    }

    /**
     * 미리보기를 승인해 일정·경로·이력을 함께 바꾼다(REPL-002).
     *
     * 재검증에서 하나라도 어긋나면 아무것도 바뀌지 않고 원인별 오류가 온다(FR-010). 원인마다
     * 다음 행동이 달라 화면은 [ReplacementError]로 안내를 나눈다(UI-005).
     */
    suspend fun approvePreview(previewId: String): AuthResult<ReplacementDto> = call { token ->
        api.approvePreview(
            bearer = token,
            idempotencyKey = idempotencyKey("approve", previewId),
            previewId = previewId,
        )
    }

    /**
     * 미리보기만 폐기한다(REPL-003).
     *
     * 사용자가 승인하지 않고 후보 목록으로 돌아갈 때 호출한다(UI-003). 기존 일정·경로·감지
     * 결과는 그대로다.
     */
    suspend fun rejectPreview(previewId: String): AuthResult<Unit> = callEmpty { token ->
        api.rejectPreview(bearer = token, previewId = previewId)
    }

    /**
     * 승인으로 바뀐 장소를 승인 전으로 되돌린다(REPL-004).
     *
     * 되돌릴 수 있는지는 서버가 판정한다. 앱은 남은 시간을 표시만 하고 만료를 스스로
     * 결정하지 않는다(FR-015).
     */
    suspend fun undoReplacement(replacementId: String): AuthResult<ReplacementUndoResultDto> = call { token ->
        api.undoReplacement(bearer = token, replacementId = replacementId)
    }

    /** 네 endpoint가 같은 인증·통신 실패 규칙을 쓰도록 한곳에 모은다. */
    private suspend fun <T> call(
        request: suspend (bearer: String) -> Response<SuccessEnvelope<T>>,
    ): AuthResult<T> = auth.withAuthorizedCall { accessToken ->
        try {
            request("Bearer $accessToken").toAuthResult()
        } catch (e: IOException) {
            AuthResult.Failure(AuthError.Offline(e))
        }
    }

    /** REPL-003은 `204`라 body가 없다. 성공 판정만 [call]과 같은 규칙으로 처리한다. */
    private suspend fun callEmpty(
        request: suspend (bearer: String) -> Response<Unit>,
    ): AuthResult<Unit> = auth.withAuthorizedCall { accessToken ->
        try {
            request("Bearer $accessToken").toEmptyAuthResult()
        } catch (e: IOException) {
            AuthResult.Failure(AuthError.Offline(e))
        }
    }

    /**
     * 같은 요청 내용이면 같은 UUID(v3)를 돌려준다. F007 [com.gilpick.progress.DetectionRepository]와
     * 같은 방식이다.
     *
     * `null`도 값의 일부로 넣는다. 추천 후보로 고른 장소와 직접 검색으로 고른 같은 장소는
     * 서버가 다르게 검증하므로 같은 key를 쓰면 안 된다(FR-004).
     */
    private fun idempotencyKey(vararg parts: Any?): String =
        UUID.nameUUIDFromBytes(parts.joinToString("|").toByteArray()).toString()

    companion object {
        /** 실제 서버를 향한 repository. F009 `AlternativeRepository.default`와 같은 조립이다. */
        fun default(context: Context): ReplacementRepository {
            val appContext = context.applicationContext
            val auth = AuthRepository(
                store = AuthSessionStore.create(appContext),
                api = createAuthRetrofit(BuildConfig.API_BASE_URL).create(AuthService::class.java),
                appLinkHandler = AuthAppLinkHandler(BuildConfig.APP_LINK_HOST),
                scheduleRevocation = SessionRevocationWorker.scheduler(appContext),
                // F011. 이 경로로 만들어진 session에서도 토큰 갱신·로그아웃이 푸시 토큰 등록·해제를
                // 예약해야 한다(F011 FR-020). 다른 repository의 `default`와 같은 조립이다.
                syncPushToken = { FcmTokenSyncWorker.enqueue(appContext) },
                clearPushToken = { FcmTokenClearWorker.enqueue(appContext) },
            )
            return ReplacementRepository(
                api = createReplacementRetrofit(BuildConfig.API_BASE_URL).create(ReplacementService::class.java),
                auth = auth,
            )
        }
    }
}

/**
 * 일정 변경 요청이 실패한 이유. 화면이 원인과 다음 행동을 안내하는 데 쓴다.
 *
 * HTTP 상태 코드가 아니라 계약이 정한 error code로 판정한다. `409` 하나에 원인이 몰려 있고
 * 다음 행동(다시 만들기·후보 목록으로·다시 시도·일정 편집 안내)이 서로 다르기 때문이다.
 *
 * 승인 실패는 이 중 [ScheduleChanged]·[PreviewExpired]·[AlreadyVisited]·[AlternativeUnavailable]·
 * [Network] 다섯으로 나뉜다(data-model 4.1, SC-007). 경로 관련 실패는 승인에 없다. 승인은
 * 미리보기에서 계산을 마친 경로를 확정만 하므로 경로를 얻지 못하는 상황은 미리보기 생성
 * 단계의 [RouteUnavailable]로만 나타난다(research 2절).
 */
sealed interface ReplacementError {
    /** 통신 실패. 같은 요청을 그대로 다시 보내면 같은 `Idempotency-Key`가 나간다. */
    data object Network : ReplacementError

    /**
     * `409 VERSION_CONFLICT`. 미리보기를 만든 뒤 그 날짜 일정이 바뀌었다.
     *
     * 계산 근거가 낡았으므로 미리보기를 다시 만들어야 한다.
     */
    data object ScheduleChanged : ReplacementError

    /** `409 PREVIEW_EXPIRED`. 미리보기 유효 시간이 지났다. 다시 만든다. */
    data object PreviewExpired : ReplacementError

    /** `409 PREVIEW_SUPERSEDED`. 같은 감지 결과에 더 새 미리보기가 있다(FR-006). */
    data object PreviewSuperseded : ReplacementError

    /** `409 ALREADY_APPROVED`. 이미 승인한 미리보기다. 진행 화면을 다시 조회한다. */
    data object AlreadyApproved : ReplacementError

    /**
     * `409 ITEM_ALREADY_VISITED`. 바꾸려던 장소를 이미 방문하기 시작했다.
     *
     * 바꿀 대상이 사라졌으므로 후보 목록으로 돌아간다.
     */
    data object AlreadyVisited : ReplacementError

    /** `409 ALTERNATIVE_UNAVAILABLE`. 대체 장소를 더 이상 방문할 수 없다. 다른 후보를 고른다. */
    data object AlternativeUnavailable : ReplacementError

    /** `409 DETECTION_NOT_ACTIVE`. 감지 결과가 이미 사용자 결정을 마쳤다. 진행 화면으로 돌아간다. */
    data object DetectionNotActive : ReplacementError

    /** `409 UNDO_EXPIRED`. 되돌릴 수 있는 시간이 지났다. 일정 편집으로 안내한다(FR-019·UI-007). */
    data object UndoExpired : ReplacementError

    /** `409 FOLLOW_UP_CHANGE_EXISTS`. 승인 뒤 그 날짜 일정이 또 바뀌었다. 일정 편집으로 안내한다. */
    data object FollowUpChangeExists : ReplacementError

    /**
     * `502 ROUTE_PROVIDER_ERROR`·`504 ROUTE_PROVIDER_TIMEOUT`. 경로를 계산하지 못했다.
     *
     * 미리보기 생성에서만 일어나며 일정은 그대로다. 화면은 `다시 시도`와 `다른 후보 보기`를
     * 함께 제공한다(FR-007·UI-004·SC-007).
     *
     * @property retryable 서버가 알린 재시도 가능 여부. 문구에만 쓴다.
     */
    data class RouteUnavailable(val retryable: Boolean) : ReplacementError

    /** 갱신·replay 후에도 자격이 무효로 확정됐다. F001 재인증 흐름으로 넘어간다. */
    data object SessionExpired : ReplacementError

    /**
     * 그 밖의 실패. 잠시 후 다시 시도한다.
     *
     * `400 INVALID_REQUEST`·`INVALID_CANDIDATE`, `403 TRIP_FORBIDDEN`, `404`,
     * `409 PREVIEW_REJECTED`, `409 PLACE_ALREADY_IN_SCHEDULE`·`DAY_NOT_IN_PROGRESS`, 5xx,
     * 계약과 다른 응답이 여기 들어온다. 모두 화면이 같은 안내와 같은 다음 행동을 준다.
     */
    data object Unexpected : ReplacementError
}

/** 인증 계층의 실패를 일정 변경 화면이 안내할 수 있는 원인으로 좁힌다. */
fun AuthError.toReplacementError(): ReplacementError = when (this) {
    is AuthError.Offline -> ReplacementError.Network
    is AuthError.Malformed, is AuthError.Callback -> ReplacementError.Unexpected
    is AuthError.Server -> when (code) {
        ReplacementErrorCodes.VERSION_CONFLICT -> ReplacementError.ScheduleChanged
        ReplacementErrorCodes.PREVIEW_EXPIRED -> ReplacementError.PreviewExpired
        ReplacementErrorCodes.PREVIEW_SUPERSEDED -> ReplacementError.PreviewSuperseded
        ReplacementErrorCodes.ALREADY_APPROVED -> ReplacementError.AlreadyApproved
        ReplacementErrorCodes.ITEM_ALREADY_VISITED -> ReplacementError.AlreadyVisited
        ReplacementErrorCodes.ALTERNATIVE_UNAVAILABLE -> ReplacementError.AlternativeUnavailable
        ReplacementErrorCodes.DETECTION_NOT_ACTIVE -> ReplacementError.DetectionNotActive
        ReplacementErrorCodes.UNDO_EXPIRED -> ReplacementError.UndoExpired
        ReplacementErrorCodes.FOLLOW_UP_CHANGE_EXISTS -> ReplacementError.FollowUpChangeExists

        ReplacementErrorCodes.ROUTE_PROVIDER_ERROR,
        ReplacementErrorCodes.ROUTE_PROVIDER_TIMEOUT,
        -> ReplacementError.RouteUnavailable(retryable)

        // 갱신·replay 후에도 401이면 자격이 무효로 확정된 것이다. F001 계약의 refresh
        // 오류 code도 같은 뜻이다.
        ReplacementErrorCodes.INVALID_ACCESS_TOKEN,
        AuthErrorCodes.INVALID_REFRESH_TOKEN,
        AuthErrorCodes.TOKEN_EXPIRED,
        AuthErrorCodes.DEVICE_MISMATCH,
        -> ReplacementError.SessionExpired

        else -> when (httpStatus) {
            401 -> ReplacementError.SessionExpired
            502, 504 -> ReplacementError.RouteUnavailable(retryable)
            else -> ReplacementError.Unexpected
        }
    }
}
