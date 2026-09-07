package com.gilpick.route

import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthErrorCodes
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthResult
import com.gilpick.auth.SuccessEnvelope
import com.gilpick.auth.toAuthResult
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import retrofit2.Response

/**
 * 경로 데이터의 유일한 접근 지점.
 *
 * Access Token 주입과 만료 시 갱신·replay는 [AuthRepository.withAuthorizedCall]이 소유한다.
 * F004 [com.gilpick.itinerary.ItineraryRepository]와 같은 구조다.
 *
 * 경로 계산의 최종 실패([RouteFailureDto])는 오류가 아니라 성공 응답의 상태이므로
 * [AuthResult.Success]로 그대로 돌려준다. 여기서 실패로 분류하는 것은 통신·인증·계약 오류뿐이다.
 */
class RouteRepository(
    private val api: RouteService,
    private val auth: AuthRepository,
) {

    /** 날짜의 현재 계획 경로를 조회한다(ROUTE-001). */
    suspend fun getDayRoute(tripId: String, date: LocalDate): AuthResult<DayRouteDto> =
        call { token -> api.getDayRoute(bearer = token, tripId = tripId, date = date.iso()) }

    /**
     * 실패한 날짜 경로를 같은 일정 입력으로 다시 계산한다(ROUTE-003).
     *
     * @param scheduleVersion 화면이 보고 있는 일정 version. 서버의 현재 version과 다르면
     *   [RouteError.VersionConflict]다.
     */
    suspend fun retryDayRoute(tripId: String, date: LocalDate, scheduleVersion: Int): AuthResult<DayRouteDto> =
        call { token ->
            api.retryDayRoute(
                bearer = token,
                tripId = tripId,
                date = date.iso(),
                body = RetryRouteRequest(scheduleVersion),
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
}

/**
 * 경로 조회·재시도 요청이 실패한 이유. 화면이 원인과 다음 행동을 안내하는 데 쓴다.
 *
 * 경로 계산 자체의 최종 실패는 여기 없다. 그것은 `200` 응답의 [DayRouteDto.failure]다.
 */
sealed interface RouteError {
    /** 통신 실패. 즉시 실패 이유와 재시도를 안내한다(`spec.md` Edge Case). */
    data object Network : RouteError

    /** `409 VERSION_CONFLICT`. 일정이 다른 곳에서 바뀌었다. 최신 일정을 다시 조회하도록 안내한다. */
    data object VersionConflict : RouteError

    /** `409 ROUTE_NOT_FAILED`. 현재 경로가 실패 상태가 아니라 재시도 대상이 아니다. 최신 경로를 다시 조회한다. */
    data object NotFailed : RouteError

    /** `403 TRIP_FORBIDDEN`. 다른 사용자의 여행이다. */
    data object Forbidden : RouteError

    /** `404 TRIP_NOT_FOUND`. 없거나 삭제된 여행 또는 기간 밖 날짜다. */
    data object NotFound : RouteError

    /** 갱신·replay 후에도 자격이 무효로 확정됐다. F001 재인증 흐름으로 넘어간다. */
    data object SessionExpired : RouteError

    /** 그 밖의 실패(`400 INVALID_REQUEST`, 5xx, 계약과 다른 응답). 잠시 후 다시 시도한다. */
    data object Unexpected : RouteError
}

/** 인증 계층의 실패를 경로 화면이 안내할 수 있는 원인으로 좁힌다. */
fun AuthError.toRouteError(): RouteError = when (this) {
    is AuthError.Offline -> RouteError.Network
    is AuthError.Malformed, is AuthError.Callback -> RouteError.Unexpected
    is AuthError.Server -> when (code) {
        RouteErrorCodes.VERSION_CONFLICT -> RouteError.VersionConflict
        RouteErrorCodes.ROUTE_NOT_FAILED -> RouteError.NotFailed
        RouteErrorCodes.TRIP_FORBIDDEN -> RouteError.Forbidden
        RouteErrorCodes.TRIP_NOT_FOUND -> RouteError.NotFound
        RouteErrorCodes.INVALID_ACCESS_TOKEN,
        AuthErrorCodes.INVALID_REFRESH_TOKEN,
        AuthErrorCodes.TOKEN_EXPIRED,
        AuthErrorCodes.DEVICE_MISMATCH,
        -> RouteError.SessionExpired

        else -> if (httpStatus == 401) RouteError.SessionExpired else RouteError.Unexpected
    }
}
