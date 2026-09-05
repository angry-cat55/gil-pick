package com.gilpick.trip

import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthResult
import com.gilpick.auth.toAuthResult
import com.gilpick.auth.toEmptyAuthResult
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 여행 목록 한 페이지.
 *
 * @property trips 이 페이지의 여행. 빈 목록도 정상 결과다.
 * @property nextCursor 다음 페이지 요청에 그대로 전달한다. 마지막 페이지면 `null`이다.
 * @property hasNext 이어질 항목이 남았는지 여부.
 */
data class TripPage(
    val trips: List<TripDto>,
    val nextCursor: String?,
    val hasNext: Boolean,
)

/**
 * 여행 데이터의 유일한 접근 지점.
 *
 * Access Token 주입과 만료 시 갱신·replay는 [AuthRepository.withAccessToken]이 이미
 * 소유하므로 여기서 다시 구현하지 않는다. 결과 타입도 인증 계층이 정의한
 * [AuthResult]를 그대로 쓴다.
 *
 * ponytail: [AuthResult]·`SuccessEnvelope`는 이름만 인증에 묶여 있을 뿐 실제로는 모든
 * API가 공유하는 계약이다. 세 번째 feature가 같은 것을 필요로 하면 그때
 * `com.gilpick.api` 같은 중립 패키지로 옮긴다. 지금 옮기면 F001 파일을 함께 고쳐야 해
 * 이 Issue 범위를 벗어난다.
 *
 * @property api 여행 endpoint 호출 계약.
 * @property auth Access Token을 주입하고 만료 시 갱신을 처리하는 인증 repository.
 */
class TripRepository(
    private val api: TripService,
    private val auth: AuthRepository,
) {

    /**
     * 여행을 생성한다.
     *
     * 여행명의 앞뒤 공백은 서버 검증 기준과 맞추기 위해 보내기 전에 제거한다.
     *
     * @param name 여행명. trim 후 2~30자여야 한다.
     * @param startDate 여행 시작일.
     * @param endDate 여행 종료일. [startDate] 이상이고 최대 7일 기간이어야 한다.
     * @param idempotencyKey 이 생성 시도를 식별하는 키. 통신 실패로 재시도할 때는 반드시
     *   같은 값을 다시 보내야 서버가 중복 생성을 막을 수 있다.
     * @return 생성된 여행 또는 좁혀진 실패 원인.
     */
    suspend fun createTrip(
        name: String,
        startDate: LocalDate,
        endDate: LocalDate,
        idempotencyKey: String,
    ): AuthResult<TripDto> = auth.withAccessToken { accessToken ->
        api.createTrip(
            bearer = "Bearer $accessToken",
            idempotencyKey = idempotencyKey,
            body = CreateTripRequest(
                name = name.trim(),
                startDate = startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                endDate = endDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
            ),
        )
    }

    /**
     * 여행 하나의 상세를 조회한다.
     *
     * 상태(`예정`/`여행 중`/`완료`)는 서버가 KST 기준으로 계산해 내려주므로(`spec.md`
     * FR-006) 앱은 응답 값을 그대로 쓴다.
     *
     * @param tripId 조회할 여행 식별자.
     * @return 여행 상세 또는 좁혀진 실패 원인. 실패 원인은 [AuthError.toDetailError]로
     *   화면이 안내할 수 있는 [TripDetailError]로 좁힌다.
     */
    suspend fun getTrip(tripId: String): AuthResult<TripDto> =
        auth.withAccessToken { accessToken ->
            api.getTrip(bearer = "Bearer $accessToken", tripId = tripId)
        }

    /**
     * 여행의 이름과 기간을 수정한다.
     *
     * 계약이 `version`을 required로 정의한다. 조회 시점의 버전을 그대로 실어 보내고,
     * 서버가 저장된 버전과 다르다고 판단하면 `409 VERSION_CONFLICT`로 거절한다
     * (`spec.md` FR-011a).
     *
     * 완료 상태 여행의 기간 수정은 서버가 `409 TRIP_LOCKED`로 거절한다(FR-010a).
     * 화면도 같은 규칙으로 기간 입력을 미리 잠그지만 최종 판정은 서버가 한다.
     *
     * @param tripId 수정할 여행 식별자.
     * @param version 조회했던 여행의 버전. 낙관적 동시성 제어에 쓴다.
     * @param name 새 여행명. 바꾸지 않으면 `null`이다.
     * @param startDate 새 시작일. 바꾸지 않으면 `null`이다.
     * @param endDate 새 종료일. 바꾸지 않으면 `null`이다.
     * @param confirmDeleteOutOfRangeItems 기간 축소로 삭제될 일정을 사용자가 확인했는지
     *   여부. F002 시점에는 일정이 없어 서버가 삭제 개수를 항상 0으로 보므로 실제로
     *   쓰이지 않는다. 계약이 정의한 값이라 자리만 열어 둔다(F004에서 사용).
     * @return 수정된 여행 또는 좁혀진 실패 원인. 실패 원인은 [AuthError.toSubmitError]로
     *   화면이 안내할 수 있는 [TripFormSubmitError]로 좁힌다.
     */
    suspend fun updateTrip(
        tripId: String,
        version: Int,
        name: String? = null,
        startDate: LocalDate? = null,
        endDate: LocalDate? = null,
        confirmDeleteOutOfRangeItems: Boolean = false,
    ): AuthResult<TripDto> = auth.withAccessToken { accessToken ->
        api.updateTrip(
            bearer = "Bearer $accessToken",
            tripId = tripId,
            body = UpdateTripRequest(
                // 생성과 같은 규칙으로 앞뒤 공백을 먼저 제거한다.
                name = name?.trim(),
                startDate = startDate?.format(DateTimeFormatter.ISO_LOCAL_DATE),
                endDate = endDate?.format(DateTimeFormatter.ISO_LOCAL_DATE),
                version = version,
                confirmDeleteOutOfRangeItems = confirmDeleteOutOfRangeItems,
            ),
        )
    }

    /**
     * 소유한 여행 목록을 한 페이지 조회한다.
     *
     * 검색어와 상태 필터는 cursor에 결합되어 있다. 조건을 바꾸면 [cursor]를 비워 첫
     * 페이지부터 다시 받아야 하며, 그렇지 않으면 서버가 cursor를 거절한다.
     *
     * @param query 여행명 부분 검색어. 비어 있으면 보내지 않는다.
     * @param status 상태 필터. `null`이면 전체를 조회한다.
     * @param cursor 이전 응답의 `nextCursor`. 첫 페이지는 `null`이다.
     * @param limit 한 페이지 최대 개수. `null`이면 서버 기본값(20)을 쓴다.
     * @return 여행 한 페이지와 다음 cursor, 또는 좁혀진 실패 원인.
     */
    /**
     * 여행을 논리 삭제한다.
     *
     * 계약(`contracts/trips.openapi.yaml`)이 정의한 성공은 body 없는 `204`다. 응답에
     * 담을 값이 없으므로 [AuthResult] 안에도 담지 않는다.
     *
     * 상태와 무관하게 삭제할 수 있다(`spec.md` FR-014). 완료 상태 여행의 기간 수정은
     * `409 TRIP_LOCKED`로 막히지만 삭제는 막히지 않으며, 계약도 이 endpoint에 `409`를
     * 정의하지 않는다. 그래서 [updateTrip]과 달리 잠금 오류를 다루지 않는다.
     *
     * @param tripId 삭제할 여행 식별자.
     * @return 성공 또는 좁혀진 실패 원인. 실패 원인은 [AuthError.toDeleteError]로 화면이
     *   안내할 수 있는 [TripDeleteError]로 좁힌다.
     */
    suspend fun deleteTrip(tripId: String): AuthResult<Unit> =
        auth.withAuthorizedCall { accessToken ->
            try {
                api.deleteTrip(
                    bearer = "Bearer $accessToken",
                    tripId = tripId,
                ).toEmptyAuthResult()
            } catch (e: IOException) {
                AuthResult.Failure(AuthError.Offline(e))
            }
        }

    /**
     * 내 여행 목록 한 page를 조회한다(`GET /trips`).
     *
     * 검색어·상태 필터·cursor를 그대로 전달하고, 빈 검색어는 보내지 않는다. 서버가 빈 문자열로
     * 필터링해 결과가 비는 것을 막기 위해서다. 인증 흐름은 [AuthRepository.withAuthorizedCall]을
     * 따르므로 `401`이면 갱신 후 최대 1회 replay한다.
     *
     * @param query 여행 이름 검색어. `null`이거나 공백이면 조건 없음.
     * @param status 상태 필터. `null`이면 전체.
     * @param cursor 이전 page의 `nextCursor`. 첫 page는 `null`.
     * @param limit page 크기. `null`이면 서버 기본값(20).
     * @return 여행 목록과 다음 page cursor. 실패는 [AuthError]로 원인을 구분한다.
     */
    suspend fun listTrips(
        query: String? = null,
        status: TripStatus? = null,
        cursor: String? = null,
        limit: Int? = null,
    ): AuthResult<TripPage> = auth.withAuthorizedCall { accessToken ->
        try {
            api.listTrips(
                bearer = "Bearer $accessToken",
                // 빈 검색어를 보내면 서버가 빈 문자열로 필터링한다. 아예 생략한다.
                query = query?.takeIf { it.isNotBlank() },
                status = status,
                cursor = cursor,
                limit = limit,
            ).toAuthResult { envelope ->
                TripPage(
                    trips = envelope.data.items,
                    nextCursor = envelope.meta.pagination.nextCursor,
                    hasNext = envelope.meta.pagination.hasNext,
                )
            }
        } catch (e: IOException) {
            AuthResult.Failure(AuthError.Offline(e))
        }
    }
}

/**
 * 상세 조회가 실패한 이유. 화면이 원인과 다음 행동을 안내하는 데 쓴다.
 *
 * 계약(`contracts/trips.openapi.yaml`)은 상세 조회에 `403`과 `404`를 함께 정의한다.
 * 둘 다 재시도해도 결과가 같지만 사용자에게 할 말이 다르므로 분리한다.
 */
enum class TripDetailError {
    /** 통신 실패. 같은 요청을 그대로 다시 보낼 수 있다. */
    NETWORK,

    /** `404 TRIP_NOT_FOUND`. 없거나 이미 삭제된 여행이다. `spec.md` FR-015. */
    NOT_FOUND,

    /** `403 FORBIDDEN`. 다른 사용자의 여행이다. `spec.md` FR-004·FR-017. */
    FORBIDDEN,

    /** 그 밖의 실패. 잠시 후 다시 시도한다. */
    UNEXPECTED,
}

/**
 * 상세 조회 실패를 화면이 안내할 수 있는 원인으로 좁힌다.
 *
 * HTTP 상태 코드가 아니라 계약이 정한 error code로 판정한다. 상태 코드는 같아도 code가
 * 다른 실패가 뒤에 생길 수 있고, code 쪽이 서버가 확정한 의미이기 때문이다.
 */
internal fun AuthError.toDetailError(): TripDetailError = when {
    this is AuthError.Offline -> TripDetailError.NETWORK
    this is AuthError.Server && code == TripErrorCodes.TRIP_NOT_FOUND -> TripDetailError.NOT_FOUND
    this is AuthError.Server && code == TripErrorCodes.FORBIDDEN -> TripDetailError.FORBIDDEN
    else -> TripDetailError.UNEXPECTED
}

/**
 * 삭제가 실패한 이유. 화면이 원인과 다음 행동을 안내하는 데 쓴다.
 *
 * 계약(`contracts/trips.openapi.yaml`)이 이 endpoint에 정의한 실패는 `403`과 `404`뿐이다.
 * 완료 상태 여행도 삭제할 수 있으므로(`spec.md` FR-014) `409 TRIP_LOCKED`는 오지 않는다.
 * 서버가 보내지 않는 code를 위한 자리를 만들면 검증할 수 없는 경로가 남는다.
 *
 * [TripDetailError]와 값이 같지만 합치지 않는다. 같은 원인이라도 조회 실패와 삭제 실패는
 * 사용자에게 할 말이 다르고, 한쪽 계약이 바뀔 때 다른 쪽이 따라 바뀔 이유가 없다.
 */
enum class TripDeleteError {
    /** 통신 실패. 같은 요청을 그대로 다시 보낼 수 있다. */
    NETWORK,

    /** `404 TRIP_NOT_FOUND`. 없거나 이미 삭제된 여행이다. `spec.md` FR-015·FR-016. */
    NOT_FOUND,

    /** `403 FORBIDDEN`. 다른 사용자의 여행이다. `spec.md` FR-017. */
    FORBIDDEN,

    /** 그 밖의 실패. 잠시 후 다시 시도한다. */
    UNEXPECTED,
}

/**
 * 삭제 실패를 화면이 안내할 수 있는 원인으로 좁힌다.
 *
 * [toDetailError]와 같은 규칙으로 HTTP 상태 코드가 아니라 계약이 정한 error code로
 * 판정한다. 상태 코드는 같아도 code가 다른 실패가 뒤에 생길 수 있다.
 */
internal fun AuthError.toDeleteError(): TripDeleteError = when {
    this is AuthError.Offline -> TripDeleteError.NETWORK
    this is AuthError.Server && code == TripErrorCodes.TRIP_NOT_FOUND -> TripDeleteError.NOT_FOUND
    this is AuthError.Server && code == TripErrorCodes.FORBIDDEN -> TripDeleteError.FORBIDDEN
    else -> TripDeleteError.UNEXPECTED
}
