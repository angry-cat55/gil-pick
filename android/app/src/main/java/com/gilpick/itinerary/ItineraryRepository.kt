package com.gilpick.itinerary

import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthErrorCodes
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthResult
import com.gilpick.auth.SuccessEnvelope
import com.gilpick.auth.Violation
import com.gilpick.auth.toAuthResult
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import retrofit2.Response

/**
 * 일정 데이터의 유일한 접근 지점.
 *
 * Access Token 주입과 만료 시 갱신·replay는 [AuthRepository.withAuthorizedCall]이 소유하므로
 * 여기서 다시 구현하지 않는다. F002 [com.gilpick.trip.TripRepository]와 같은 구조다.
 *
 * `409 VERSION_CONFLICT` 자동 재저장(`research.md` 4절)은 편집 초안을 아는 ViewModel이
 * 하고, 여기서는 저장 한 번을 그대로 보낸다.
 *
 * @property api 일정 endpoint 호출 계약.
 * @property auth Access Token을 주입하고 만료 시 갱신을 처리하는 인증 repository.
 */
class ItineraryRepository(
    private val api: ItineraryService,
    private val auth: AuthRepository,
) {

    /** 여행 전체의 날짜별 일정 개요를 조회한다(ITIN-003). */
    suspend fun getOverview(tripId: String): AuthResult<ItineraryOverviewDto> =
        call { token -> api.getOverview(bearer = token, tripId = tripId) }

    /** 한 날짜의 일정을 조회한다(ITIN-001). 저장된 적 없는 날짜는 version 0, 빈 items다. */
    suspend fun getDayItinerary(tripId: String, date: LocalDate): AuthResult<DayItineraryDto> =
        call { token -> api.getDayItinerary(bearer = token, tripId = tripId, date = date.iso()) }

    /**
     * 한 날짜의 일정 전체를 저장한다(ITIN-002).
     *
     * @param version 조회한 일정의 version. 아직 없는 날짜는 0.
     * @param items 그 날짜의 항목 전체. 새 항목은 `itemId = null`에 `place` 스냅샷을 싣는다.
     * @param idempotencyKey 이 저장 시도를 식별하는 UUID. 통신 실패로 재시도할 때는 같은
     *   값을 다시 보내야 서버가 같은 항목 ID를 만든다. 최신 version으로 다시 저장하는
     *   자동 재저장은 새 시도이므로 새 키를 쓴다.
     * @return 저장된 일정. 처음 저장이면 [AuthResult.Success.httpStatus]가 201이다.
     */
    suspend fun saveDayItinerary(
        tripId: String,
        date: LocalDate,
        version: Int,
        items: List<SaveItemDto>,
        idempotencyKey: String,
    ): AuthResult<DayItineraryDto> = call { token ->
        api.saveDayItinerary(
            bearer = token,
            idempotencyKey = idempotencyKey,
            tripId = tripId,
            date = date.iso(),
            body = SaveDayItineraryRequest(version = version, items = items),
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
}

/**
 * 일정 조회·저장이 실패한 이유. 화면이 원인과 다음 행동을 안내하는 데 쓴다.
 *
 * HTTP 상태 코드가 아니라 계약이 정한 error code로 판정한다. `409`는
 * [VersionConflict]와 [ItemLocked]가 같이 쓰고, 두 실패의 다음 행동(자동 재저장 vs 안내)이
 * 다르기 때문이다.
 */
sealed interface ItineraryError {
    /** 통신 실패. 같은 요청을 그대로 다시 보낼 수 있다. */
    data object Network : ItineraryError

    /** `409 VERSION_CONFLICT`. 최신 version으로 다시 조회해 같은 초안을 재저장한다. */
    data object VersionConflict : ItineraryError

    /**
     * `422 INVALID_ITINERARY`. 저장 규칙 위반.
     *
     * @property violations 위반 항목. `itemIndex`가 `null`이면 요청 전체 위반이다.
     */
    data class InvalidItinerary(val violations: List<Violation>) : ItineraryError

    /**
     * `409 ITINERARY_ITEM_LOCKED`. 처리된 항목의 장소·이동 수단·순서 변경 또는 삭제.
     *
     * @property itemId 거부된 항목. 서버가 details를 주지 않으면 `null`.
     */
    data class ItemLocked(val itemId: String?) : ItineraryError

    /** `403 TRIP_FORBIDDEN`. 다른 사용자의 여행이다. */
    data object Forbidden : ItineraryError

    /** `404 TRIP_NOT_FOUND`. 없거나 삭제된 여행 또는 기간 밖 날짜다. */
    data object NotFound : ItineraryError

    /** 갱신·replay 후에도 자격이 무효로 확정됐다. F001 재인증 흐름으로 넘어간다. */
    data object SessionExpired : ItineraryError

    /** 그 밖의 실패(`400 INVALID_REQUEST`, 5xx, 계약과 다른 응답). 잠시 후 다시 시도한다. */
    data object Unexpected : ItineraryError
}

/** 인증 계층의 실패를 일정 화면이 안내할 수 있는 원인으로 좁힌다. */
fun AuthError.toItineraryError(): ItineraryError = when (this) {
    is AuthError.Offline -> ItineraryError.Network
    is AuthError.Malformed, is AuthError.Callback -> ItineraryError.Unexpected
    is AuthError.Server -> when (code) {
        ItineraryErrorCodes.VERSION_CONFLICT -> ItineraryError.VersionConflict
        ItineraryErrorCodes.INVALID_ITINERARY ->
            ItineraryError.InvalidItinerary(details?.violations.orEmpty())
        ItineraryErrorCodes.ITINERARY_ITEM_LOCKED -> ItineraryError.ItemLocked(details?.itemId)
        ItineraryErrorCodes.TRIP_FORBIDDEN -> ItineraryError.Forbidden
        ItineraryErrorCodes.TRIP_NOT_FOUND -> ItineraryError.NotFound

        // 갱신·replay 후에도 401이면 자격이 무효로 확정된 것이다. F001 계약의 refresh
        // 오류 code도 같은 뜻이다.
        ItineraryErrorCodes.INVALID_ACCESS_TOKEN,
        AuthErrorCodes.INVALID_REFRESH_TOKEN,
        AuthErrorCodes.TOKEN_EXPIRED,
        AuthErrorCodes.DEVICE_MISMATCH,
        -> ItineraryError.SessionExpired

        else -> if (httpStatus == 401) ItineraryError.SessionExpired else ItineraryError.Unexpected
    }
}
