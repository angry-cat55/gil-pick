package com.gilpick.trip

import com.gilpick.auth.SuccessEnvelope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 계약에 정의된 여행 error code. 화면 분기에서 문자열 오타를 막는다.
 *
 * 값은 `contracts/trips.openapi.yaml`과 Backend `api/app/api/errors.py`를 따른다.
 */
object TripErrorCodes {
    const val VALIDATION_ERROR = "VALIDATION_ERROR"
    const val INVALID_TRIP_PERIOD = "INVALID_TRIP_PERIOD"
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val TRIP_NOT_FOUND = "TRIP_NOT_FOUND"
    const val TRIP_LOCKED = "TRIP_LOCKED"
    const val VERSION_CONFLICT = "VERSION_CONFLICT"
    const val CONFIRMATION_REQUIRED = "CONFIRMATION_REQUIRED"
    const val FORBIDDEN = "FORBIDDEN"
}

/**
 * KST 현재 날짜와 여행 기간으로 서버가 계산하는 파생 상태.
 *
 * 저장된 값이 아니므로 앱도 직접 계산하지 않고 응답 값을 그대로 표시한다.
 */
@Serializable
enum class TripStatus {
    /** 시작일 전. */
    UPCOMING,

    /** 시작일부터 종료일까지. */
    IN_PROGRESS,

    /** 종료일 후. */
    COMPLETED,
}

/**
 * 여행 표현.
 *
 * 날짜는 `yyyy-MM-dd` 문자열로 주고받는다. kotlinx-serialization에 `LocalDate` 기본
 * 직렬화가 없어 DTO는 계약의 원문 형식을 유지하고, 변환은 화면 계층에서 한다.
 *
 * @property dayCount `endDate - startDate + 1`. 서버가 계산해 내려준다.
 * @property version 수정 요청에 그대로 실어 보내는 낙관적 동시성 버전.
 * @property createdAt 생성 응답에만 포함된다.
 */
@Serializable
data class TripDto(
    val tripId: String,
    val name: String,
    val startDate: String,
    val endDate: String,
    val status: TripStatus,
    val dayCount: Int,
    val version: Int,
    val createdAt: String? = null,
)

/**
 * `POST /trips` 요청.
 *
 * 앞뒤 공백 제거와 길이·기간 검증은 서버가 최종 판정하지만, 화면도 같은 규칙으로
 * 먼저 걸러 불필요한 왕복을 줄인다.
 */
@Serializable
data class CreateTripRequest(
    val name: String,
    val startDate: String,
    val endDate: String,
)

/**
 * `PATCH /trips/{tripId}` 요청.
 *
 * 바꾸지 않는 필드는 `null`로 두어 전송하지 않는다. [version]은 필수이며 서버가 저장된
 * 값과 다르면 `409 VERSION_CONFLICT`로 거절한다.
 *
 * @property confirmDeleteOutOfRangeItems 기간 축소로 삭제될 일정을 사용자가 확인했는지 여부.
 */
@Serializable
data class UpdateTripRequest(
    val name: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val version: Int,
    val confirmDeleteOutOfRangeItems: Boolean = false,
)

/** @property items 정렬된 여행 목록. 빈 목록도 정상 응답이다. */
@Serializable
data class TripListData(
    val items: List<TripDto>,
)

/**
 * 목록 응답의 cursor 상태.
 *
 * @property nextCursor 다음 페이지 요청에 그대로 전달한다. 마지막 페이지면 `null`이다.
 * @property hasNext 이어질 항목이 남았는지 여부.
 */
@Serializable
data class TripPagination(
    val nextCursor: String? = null,
    val hasNext: Boolean,
)

/**
 * 목록 전용 응답 metadata.
 *
 * 목록만 pagination을 포함하므로 공통 `ResponseMeta` 대신 별도로 둔다.
 */
@Serializable
data class TripListMeta(
    val requestId: String,
    val pagination: TripPagination,
)

/** 목록 응답 envelope. `data.items`와 `meta.pagination`을 함께 받는다. */
@Serializable
data class TripListEnvelope(
    val success: Boolean,
    val data: TripListData,
    val meta: TripListMeta,
)

/**
 * 여행 endpoint 전용 Json 설정.
 *
 * 서버가 나중에 필드를 추가해도 앱이 깨지지 않도록 모르는 키를 무시하고, `null`
 * 필드는 아예 보내지 않아 `PATCH`의 부분 수정 의미를 지킨다.
 */
private val tripJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** 여행 endpoint 전용 Retrofit 인스턴스를 만든다. */
fun createTripRetrofit(
    baseUrl: String,
    client: OkHttpClient = OkHttpClient(),
): Retrofit = Retrofit.Builder()
    .baseUrl(baseUrl)
    .client(client)
    .addConverterFactory(tripJson.asConverterFactory("application/json".toMediaType()))
    .build()

/**
 * 여행 endpoint 호출 계약.
 *
 * Access Token은 [com.gilpick.auth.AuthRepository.withAccessToken]이 넘겨주므로 각
 * 함수가 `Authorization` header를 직접 받는다. 만료 시 갱신과 replay도 그쪽이 처리한다.
 */
interface TripService {

    /** 소유한 여행을 검색어·상태·cursor로 조회한다. */
    @GET("trips")
    suspend fun listTrips(
        @Header("Authorization") bearer: String,
        @Query("query") query: String? = null,
        @Query("status") status: TripStatus? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
    ): Response<TripListEnvelope>

    /**
     * 여행을 생성한다.
     *
     * 같은 [idempotencyKey]로 재전송하면 서버가 새 여행을 만들지 않고 최초 결과를
     * 그대로 반환한다. 통신 실패 후 재시도할 때 키를 새로 만들지 않아야 한다.
     */
    @POST("trips")
    suspend fun createTrip(
        @Header("Authorization") bearer: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CreateTripRequest,
    ): Response<SuccessEnvelope<TripDto>>

    /** 소유한 여행 하나의 상세를 조회한다. */
    @GET("trips/{tripId}")
    suspend fun getTrip(
        @Header("Authorization") bearer: String,
        @Path("tripId") tripId: String,
    ): Response<SuccessEnvelope<TripDto>>

    /** 여행의 이름·기간을 수정한다. */
    @PATCH("trips/{tripId}")
    suspend fun updateTrip(
        @Header("Authorization") bearer: String,
        @Path("tripId") tripId: String,
        @Body body: UpdateTripRequest,
    ): Response<SuccessEnvelope<TripDto>>

    /** 여행을 논리 삭제한다. 계약상 성공은 body 없는 `204`다. */
    @DELETE("trips/{tripId}")
    suspend fun deleteTrip(
        @Header("Authorization") bearer: String,
        @Path("tripId") tripId: String,
    ): Response<Unit>
}
