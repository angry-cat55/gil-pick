package com.gilpick.place

import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthErrorCodes
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthResult

/**
 * 장소 데이터의 유일한 접근 지점.
 *
 * Access Token 주입과 만료 시 갱신·replay는 [AuthRepository.withAccessToken]이 소유하므로
 * 여기서 다시 구현하지 않는다. F002 [com.gilpick.trip.TripRepository]와 같은 구조다.
 *
 * #139(T023)는 상세 조회만 둔다. 검색(`searchPlaces`)은 #142의 T016이 이 파일에 추가한다.
 * 조회 결과는 어디에도 저장하지 않는다(`spec.md` FR-014).
 *
 * @property api 장소 endpoint 호출 계약.
 * @property auth Access Token을 주입하고 만료 시 갱신을 처리하는 인증 repository.
 */
class PlaceRepository(
    private val api: PlaceService,
    private val auth: AuthRepository,
) {

    /**
     * 장소 하나의 상세를 조회한다.
     *
     * @param placeId `tourapi:{contentId}` 또는 `google:{placeId}`. 검색 결과 값을 가공하지
     *   않고 그대로 넘긴다. server가 이 prefix로 조회 provider를 정한다.
     * @return 장소 상세 또는 좁혀진 실패 원인. 실패는 [AuthError.toPlaceError]로 화면이
     *   안내할 수 있는 [PlaceError]로 좁힌다.
     */
    suspend fun getPlace(placeId: String): AuthResult<PlaceDto> =
        auth.withAccessToken { accessToken ->
            api.getPlace(bearer = "Bearer $accessToken", placeId = placeId)
        }
}

/**
 * 장소 조회가 실패한 이유. 화면이 원인과 다음 행동을 안내하는 데 쓴다.
 *
 * 외부 provider 장애·시간 초과·호출 제한을 정상 응답과 구분해야 한다(`spec.md` FR-011).
 * 재시도 가능 여부는 code로 추론하지 않고 server의 `retryable`을 따른다.
 */
enum class PlaceErrorKind {
    /** 통신 실패. 같은 요청을 그대로 다시 보낼 수 있다. */
    NETWORK,

    /** provider가 제한 시간 안에 응답하지 않았다. */
    TIMEOUT,

    /** provider 호출 한도 초과. 즉시 재시도해도 같다. */
    RATE_LIMITED,

    /** provider 장애. `retryable`에 따라 재시도 가능 여부가 갈린다. */
    PROVIDER_FAILED,

    /** 요청이 계약에 맞지 않는다(식별자 형식 등). 재시도해도 같다. */
    INVALID_REQUEST,

    /** 없거나 더 이상 제공되지 않는 장소다. */
    NOT_FOUND,

    /** 로그인 상태가 확정적으로 무효다. F001 재인증 흐름으로 넘어간다. */
    SESSION_EXPIRED,

    /** 그 밖의 실패. 잠시 후 다시 시도한다. */
    UNEXPECTED,
}

/**
 * 화면이 안내할 수 있는 실패 원인.
 *
 * @property kind 원인 분류.
 * @property retryable 같은 요청을 다시 보내도 되는지 여부. server 값을 보존한다.
 */
data class PlaceError(
    val kind: PlaceErrorKind,
    val retryable: Boolean,
)

/**
 * 인증 계층의 실패를 장소 화면이 안내할 수 있는 원인으로 좁힌다.
 *
 * HTTP 상태 코드가 아니라 계약이 정한 error code로 판정한다. 같은 502라도 code가
 * provider를 가리키고, `retryable`은 server가 확정한 값이기 때문이다.
 */
internal fun AuthError.toPlaceError(): PlaceError = when (this) {
    is AuthError.Offline -> PlaceError(PlaceErrorKind.NETWORK, retryable = true)
    is AuthError.Malformed -> PlaceError(PlaceErrorKind.UNEXPECTED, retryable = true)
    is AuthError.Callback -> PlaceError(PlaceErrorKind.UNEXPECTED, retryable = retryable)
    is AuthError.Server -> when (code) {
        PlaceErrorCodes.TOUR_API_TIMEOUT,
        PlaceErrorCodes.GOOGLE_PLACES_TIMEOUT,
        -> PlaceError(PlaceErrorKind.TIMEOUT, retryable)

        PlaceErrorCodes.TOUR_API_RATE_LIMITED,
        PlaceErrorCodes.GOOGLE_PLACES_RATE_LIMITED,
        -> PlaceError(PlaceErrorKind.RATE_LIMITED, retryable = false)

        PlaceErrorCodes.TOUR_API_FAILED,
        PlaceErrorCodes.GOOGLE_PLACES_FAILED,
        -> PlaceError(PlaceErrorKind.PROVIDER_FAILED, retryable)

        PlaceErrorCodes.INVALID_REQUEST,
        PlaceErrorCodes.INVALID_CURSOR,
        -> PlaceError(PlaceErrorKind.INVALID_REQUEST, retryable = false)

        PlaceErrorCodes.PLACE_NOT_FOUND -> PlaceError(PlaceErrorKind.NOT_FOUND, retryable = false)

        // 갱신·replay 후에도 401이면 자격이 무효로 확정된 것이다. F001 계약의 refresh
        // 오류 code도 같은 뜻이다.
        PlaceErrorCodes.INVALID_ACCESS_TOKEN,
        AuthErrorCodes.INVALID_REFRESH_TOKEN,
        AuthErrorCodes.TOKEN_EXPIRED,
        AuthErrorCodes.DEVICE_MISMATCH,
        -> PlaceError(PlaceErrorKind.SESSION_EXPIRED, retryable = false)

        else -> if (httpStatus == 401) {
            PlaceError(PlaceErrorKind.SESSION_EXPIRED, retryable = false)
        } else {
            PlaceError(PlaceErrorKind.UNEXPECTED, retryable = true)
        }
    }
}
