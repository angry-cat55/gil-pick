package com.gilpick.progress

import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthErrorCodes
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthResult
import com.gilpick.auth.SuccessEnvelope
import com.gilpick.auth.toAuthResult
import com.gilpick.itinerary.ItemStatus
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import retrofit2.Response

/**
 * 진행 데이터의 유일한 접근 지점.
 *
 * Access Token 주입과 만료 시 갱신·replay는 [AuthRepository.withAuthorizedCall]이 소유한다.
 * F005 [com.gilpick.route.RouteRepository]와 같은 구조다.
 *
 * `Idempotency-Key`는 요청 내용(대상·목표 상태·`progressVersion`)에서 파생한 UUID다. 통신
 * 실패 후 같은 요청을 `다시 시도`하면 자동으로 같은 key가 나가 서버가 최초 결과를 돌려주고,
 * 전환이 적용돼 version이 바뀐 다음 요청은 새 key가 된다. 호출자가 key를 보관할 필요가 없다.
 */
class ProgressRepository(
    private val api: ProgressService,
    private val auth: AuthRepository,
) {

    /** 당일 진행 현황을 조회한다(PROG-001). */
    suspend fun getDayProgress(tripId: String, date: LocalDate): AuthResult<ProgressData> =
        call { token -> api.getDayProgress(bearer = token, tripId = tripId, date = date.iso()) }

    /**
     * 오늘 여행을 시작한다(PROG-002). 이미 시작됐으면 저장된 진행 현황을 그대로 받는다.
     *
     * @param progressVersion 조회한 진행 version. 시작 전 날짜는 0.
     * @param currentLocation 유효한 현재 위치. 권한이 없거나 얻지 못했으면 `null`(FR-004b).
     */
    suspend fun startDay(
        tripId: String,
        date: LocalDate,
        progressVersion: Int,
        currentLocation: CurrentLocationDto?,
    ): AuthResult<ProgressData> = call { token ->
        api.startDayProgress(
            bearer = token,
            idempotencyKey = idempotencyKey("start", tripId, date.iso(), progressVersion),
            tripId = tripId,
            date = date.iso(),
            body = StartProgressRequest(progressVersion = progressVersion, currentLocation = currentLocation),
        )
    }

    /**
     * 장소의 진행 상태를 목표 상태로 바꾼다(PROG-006).
     *
     * @param status `ARRIVED`·`COMPLETED`·`SKIPPED`·`PLANNED` 중 하나. `EN_ROUTE`는 서버가 파생한다.
     * @param progressVersion 화면이 보고 있는 진행 version. 다르면 [ProgressError.VersionConflict]다.
     */
    suspend fun updateStatus(
        itemId: String,
        status: ItemStatus,
        progressVersion: Int,
    ): AuthResult<ProgressData> = call { token ->
        api.updateItemStatus(
            bearer = token,
            idempotencyKey = idempotencyKey("status", itemId, status.name, progressVersion),
            itemId = itemId,
            body = UpdateProgressStatusRequest(status = status, progressVersion = progressVersion),
        )
    }

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

    /** 같은 요청 내용이면 같은 UUID(v3)를 돌려준다. 서버는 `(trip_day_id, key)` unique로 중복 적용을 막는다. */
    private fun idempotencyKey(vararg parts: Any): String =
        UUID.nameUUIDFromBytes(parts.joinToString("|").toByteArray()).toString()
}

/**
 * 진행 조회·시작·전환 요청이 실패한 이유. 화면이 원인과 다음 행동을 안내하는 데 쓴다.
 *
 * HTTP 상태 코드가 아니라 계약이 정한 error code로 판정한다. `409`·`422`는 여러 code가
 * 같이 쓰고 다음 행동(재조회 vs 안내)이 다르기 때문이다.
 */
sealed interface ProgressError {
    /** 통신 실패. 같은 요청을 그대로 다시 보내면 같은 `Idempotency-Key`가 나간다. */
    data object Network : ProgressError

    /** `409 VERSION_CONFLICT`. 진행 상태가 다른 곳에서 바뀌었다. 최신 진행 현황을 다시 조회해 안내한다. */
    data object VersionConflict : ProgressError

    /** `409 DAY_NOT_TODAY`. 오늘이 아닌 날짜는 시작할 수 없다. */
    data object DayNotToday : ProgressError

    /** `422 DAY_EMPTY`. 장소가 없는 날짜는 시작할 수 없다. `장소 추가`를 안내한다. */
    data object DayEmpty : ProgressError

    /** `409 DAY_NOT_STARTED`. 시작 전 날짜의 장소는 상태를 바꿀 수 없다. */
    data object DayNotStarted : ProgressError

    /** `422 INVALID_STATUS_TRANSITION`. 현재 상태에서 허용되지 않는 목표 상태다. 최신 진행 현황을 다시 조회한다. */
    data object InvalidTransition : ProgressError

    /** `403 TRIP_FORBIDDEN`. 다른 사용자의 여행이다. */
    data object Forbidden : ProgressError

    /** `404 TRIP_NOT_FOUND`·`ITINERARY_ITEM_NOT_FOUND`. 없거나 삭제된 여행·날짜·장소다. */
    data object NotFound : ProgressError

    /** 갱신·replay 후에도 자격이 무효로 확정됐다. F001 재인증 흐름으로 넘어간다. */
    data object SessionExpired : ProgressError

    /** 그 밖의 실패(`400 INVALID_REQUEST`, 5xx, 계약과 다른 응답). 잠시 후 다시 시도한다. */
    data object Unexpected : ProgressError
}

/** 인증 계층의 실패를 진행 화면이 안내할 수 있는 원인으로 좁힌다. */
fun AuthError.toProgressError(): ProgressError = when (this) {
    is AuthError.Offline -> ProgressError.Network
    is AuthError.Malformed, is AuthError.Callback -> ProgressError.Unexpected
    is AuthError.Server -> when (code) {
        ProgressErrorCodes.VERSION_CONFLICT -> ProgressError.VersionConflict
        ProgressErrorCodes.DAY_NOT_TODAY -> ProgressError.DayNotToday
        ProgressErrorCodes.DAY_EMPTY -> ProgressError.DayEmpty
        ProgressErrorCodes.DAY_NOT_STARTED -> ProgressError.DayNotStarted
        ProgressErrorCodes.INVALID_STATUS_TRANSITION -> ProgressError.InvalidTransition
        ProgressErrorCodes.TRIP_FORBIDDEN -> ProgressError.Forbidden
        ProgressErrorCodes.TRIP_NOT_FOUND, ProgressErrorCodes.ITINERARY_ITEM_NOT_FOUND -> ProgressError.NotFound
        ProgressErrorCodes.INVALID_ACCESS_TOKEN,
        AuthErrorCodes.INVALID_REFRESH_TOKEN,
        AuthErrorCodes.TOKEN_EXPIRED,
        AuthErrorCodes.DEVICE_MISMATCH,
        -> ProgressError.SessionExpired

        else -> if (httpStatus == 401) ProgressError.SessionExpired else ProgressError.Unexpected
    }
}
