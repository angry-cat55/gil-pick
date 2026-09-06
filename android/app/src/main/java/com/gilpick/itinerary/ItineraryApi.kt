package com.gilpick.itinerary

import com.gilpick.auth.SuccessEnvelope
import com.gilpick.place.PlaceCategory
import com.gilpick.place.TourApiCategoryDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * 계약에 정의된 일정 error code. 화면 분기에서 문자열 오타를 막는다.
 *
 * 값은 `contracts/itinerary.openapi.yaml`과 Backend `api/app/schemas/itinerary.py`를 따른다.
 * `TRIP_NOT_FOUND`·`VERSION_CONFLICT`·`CONFIRMATION_REQUIRED`는 F002와 같은 문자열이지만
 * 소유권 거부는 F002 `FORBIDDEN`이 아니라 `TRIP_FORBIDDEN`이다.
 */
object ItineraryErrorCodes {
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val INVALID_ITINERARY = "INVALID_ITINERARY"
    const val INVALID_ACCESS_TOKEN = "INVALID_ACCESS_TOKEN"
    const val TRIP_FORBIDDEN = "TRIP_FORBIDDEN"
    const val TRIP_NOT_FOUND = "TRIP_NOT_FOUND"
    const val VERSION_CONFLICT = "VERSION_CONFLICT"
    const val ITINERARY_ITEM_LOCKED = "ITINERARY_ITEM_LOCKED"
    const val CONFIRMATION_REQUIRED = "CONFIRMATION_REQUIRED"
}

/** 다음 장소까지의 이동 수단. 마지막 장소는 `null`이다. */
@Serializable
enum class TransportMode { WALK, TRANSIT, CAR }

/**
 * 일정 항목의 처리 상태. 서버가 정하며 앱은 저장 요청에 싣지 않는다.
 *
 * [PLANNED]가 아닌 항목은 장소·이동 수단·순서 변경과 삭제가 `ITINERARY_ITEM_LOCKED`로
 * 거부된다(`research.md` 8절).
 */
@Serializable
enum class ItemStatus { PLANNED, EN_ROUTE, ARRIVED, COMPLETED, SKIPPED }

/** 경로 계산 상태. F004는 항상 [NOT_CALCULATED]이고 [READY]·[FAILED]는 F005가 채운다. */
@Serializable
enum class RouteStatus { NOT_CALCULATED, READY, FAILED }

/** 체류 시간이 추천값인지 사용자가 조절한 값인지. */
@Serializable
enum class StaySource { RECOMMENDED, USER_ADJUSTED }

/**
 * 서버에 아직 없는 장소의 최소 참조 정보. F003 [com.gilpick.place.PlaceDto]에서 옮긴다.
 *
 * 새 항목(`itemId == null`)은 서버가 그 장소를 이미 알고 있어도 반드시 실어야 한다.
 * `tourApiCategory`·`address`·`imageUrl`은 값이 없어도 key를 `null`로 보낸다.
 */
@Serializable
data class PlaceSnapshotDto(
    val name: String,
    val category: PlaceCategory,
    val tourApiCategory: TourApiCategoryDto?,
    val address: String?,
    val latitude: Double,
    val longitude: Double,
    val imageUrl: String?,
)

/** 응답 항목의 표시용 장소 요약. */
@Serializable
data class ItineraryPlaceDto(
    val placeId: String,
    val name: String,
    val category: PlaceCategory,
    val address: String?,
    val imageUrl: String?,
)

/**
 * 저장된 일정 항목.
 *
 * @property sequence 1부터 시작하는 방문 순서.
 * @property plannedStayMinutes 30~360, 30분 단위.
 * @property transportModeToNext 마지막 항목은 `null`.
 */
@Serializable
data class ItineraryItemDto(
    val itemId: String,
    val place: ItineraryPlaceDto,
    val sequence: Int,
    val plannedStayMinutes: Int,
    val staySource: StaySource,
    val transportModeToNext: TransportMode?,
    val status: ItemStatus,
)

/**
 * 저장 요청의 항목 하나.
 *
 * @property itemId 기존 항목은 조회한 값, 새 항목은 `null`.
 * @property place 새 항목이면 필수, 기존 항목이면 `null`.
 */
@Serializable
data class SaveItemDto(
    val itemId: String?,
    val placeId: String,
    val place: PlaceSnapshotDto?,
    val sequence: Int,
    val plannedStayMinutes: Int,
    val staySource: StaySource,
    val transportModeToNext: TransportMode?,
)

/**
 * `PUT /trips/{tripId}/days/{date}/itinerary` 요청.
 *
 * @property version 조회한 version. 아직 없는 날짜는 0.
 * @property items 그 날짜의 항목 전체. 최대 10개.
 */
@Serializable
data class SaveDayItineraryRequest(
    val version: Int,
    val items: List<SaveItemDto>,
)

/**
 * 한 날짜의 일정.
 *
 * 날짜는 F002 [com.gilpick.trip.TripDto]와 같은 이유로 `yyyy-MM-dd` 문자열을 유지한다.
 * F005 `route` field는 F004가 읽지 않으므로 DTO에 두지 않는다(모르는 key는 무시한다).
 *
 * @property version 저장 요청에 그대로 실어 보내는 낙관적 동시성 버전.
 */
@Serializable
data class DayItineraryDto(
    val date: String,
    val dayNumber: Int,
    val version: Int,
    val routeStatus: RouteStatus,
    val items: List<ItineraryItemDto>,
)

/** @property days 여행 기간의 모든 날짜. 저장된 적 없는 날짜는 version 0, 빈 items. */
@Serializable
data class ItineraryOverviewDto(
    val tripId: String,
    val days: List<DayItineraryDto>,
)

/**
 * 일정 endpoint 전용 Json 설정.
 *
 * 서버가 field를 추가해도 앱이 깨지지 않도록 모르는 key를 무시한다. 계약이 required로
 * 정의한 nullable field(`itemId`, `place`, `transportModeToNext`)는 `null`도 key째 실어야
 * 하므로 `explicitNulls`는 기본값(true)을 유지한다.
 */
private val itineraryJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** 일정 endpoint 전용 Retrofit 인스턴스를 만든다. */
fun createItineraryRetrofit(
    baseUrl: String,
    client: OkHttpClient = OkHttpClient(),
): Retrofit = Retrofit.Builder()
    .baseUrl(baseUrl)
    .client(client)
    .addConverterFactory(itineraryJson.asConverterFactory("application/json".toMediaType()))
    .build()

/**
 * 일정 endpoint 호출 계약.
 *
 * Access Token은 [com.gilpick.auth.AuthRepository.withAuthorizedCall]이 넘겨주므로 각
 * 함수가 `Authorization` header를 직접 받는다. 만료 시 갱신과 replay도 그쪽이 처리한다.
 */
interface ItineraryService {

    /** 여행 전체의 날짜별 일정 개요를 조회한다(ITIN-003). */
    @GET("trips/{tripId}/itinerary")
    suspend fun getOverview(
        @Header("Authorization") bearer: String,
        @Path("tripId") tripId: String,
    ): Response<SuccessEnvelope<ItineraryOverviewDto>>

    /** 한 날짜의 일정을 조회한다(ITIN-001). 저장된 적 없는 날짜는 version 0, 빈 items다. */
    @GET("trips/{tripId}/days/{date}/itinerary")
    suspend fun getDayItinerary(
        @Header("Authorization") bearer: String,
        @Path("tripId") tripId: String,
        @Path("date") date: String,
    ): Response<SuccessEnvelope<DayItineraryDto>>

    /**
     * 한 날짜의 일정 전체를 저장한다(ITIN-002).
     *
     * 처음 저장이면 `201`, 갱신이면 `200`이다. 같은 [idempotencyKey]로 재전송하면 서버가
     * 같은 항목 ID를 만들어 중복 생성을 막으므로, 통신 실패 후 재시도할 때 키를 새로
     * 만들지 않아야 한다.
     */
    @PUT("trips/{tripId}/days/{date}/itinerary")
    suspend fun saveDayItinerary(
        @Header("Authorization") bearer: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Path("tripId") tripId: String,
        @Path("date") date: String,
        @Body body: SaveDayItineraryRequest,
    ): Response<SuccessEnvelope<DayItineraryDto>>
}
