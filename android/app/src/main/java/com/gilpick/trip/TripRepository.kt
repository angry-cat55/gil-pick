package com.gilpick.trip

import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthResult
import com.gilpick.auth.toAuthResult
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
