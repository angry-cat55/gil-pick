package com.gilpick.progress

import com.gilpick.auth.SuccessEnvelope
import com.gilpick.itinerary.ItemStatus
import com.gilpick.itinerary.TransportMode
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
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * 계약에 정의된 진행 error code. 값은 `contracts/progress.openapi.yaml`을 따른다.
 *
 * `TRIP_FORBIDDEN`·`TRIP_NOT_FOUND`·`VERSION_CONFLICT`·`ITINERARY_ITEM_NOT_FOUND`는 F004·F005와
 * 같은 문자열이고 `DAY_*`·`INVALID_STATUS_TRANSITION`이 F006 전용이다.
 */
object ProgressErrorCodes {
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val INVALID_ACCESS_TOKEN = "INVALID_ACCESS_TOKEN"
    const val TRIP_FORBIDDEN = "TRIP_FORBIDDEN"
    const val TRIP_NOT_FOUND = "TRIP_NOT_FOUND"
    const val ITINERARY_ITEM_NOT_FOUND = "ITINERARY_ITEM_NOT_FOUND"
    const val VERSION_CONFLICT = "VERSION_CONFLICT"
    const val DAY_NOT_TODAY = "DAY_NOT_TODAY"
    const val DAY_EMPTY = "DAY_EMPTY"
    const val DAY_NOT_STARTED = "DAY_NOT_STARTED"
    const val INVALID_STATUS_TRANSITION = "INVALID_STATUS_TRANSITION"
}

/** 날짜 진행 상태. 시작 전은 [NOT_STARTED]이고 모든 항목이 `PLANNED`·ETA `null`이다. */
@Serializable
enum class DayStatus { NOT_STARTED, IN_PROGRESS, COMPLETED }

/** 구간 이동시간의 출처. [PLANNED_ROUTE]는 F005 활성 경로 구간, [COMPUTED]는 진행 중 계산한 구간이다. */
@Serializable
enum class TravelSource { PLANNED_ROUTE, COMPUTED }

/**
 * 직전 처리 장소(또는 시작 위치)에서 이 장소까지의 이동 정보.
 *
 * @property fromItemId `null`이면 시작 위치에서 출발한다.
 */
@Serializable
data class InboundTravelDto(
    val fromItemId: String?,
    val transportMode: TransportMode,
    val durationSeconds: Int,
    val distanceMeters: Int,
    val source: TravelSource,
)

/**
 * 장소 하나의 진행 상태와 시각.
 *
 * 시각은 모두 ISO-8601 문자열이며 화면이 표시할 때만 파싱한다. ETA·실제 시각은 상태에 따라
 * `null`이고, [inboundTravel]은 이동시간을 계산할 수 없으면 `null`이다(FR-005).
 */
@Serializable
data class ProgressItemDto(
    val itemId: String,
    val sequence: Int,
    val status: ItemStatus,
    val estimatedArrivalAt: String?,
    val estimatedDepartureAt: String?,
    val actualArrivedAt: String?,
    val completedAt: String?,
    val inboundTravel: InboundTravelDto?,
)

/** 시작 요청에 실린 유효한 현재 위치. 없으면 `null`이다. */
@Serializable
data class StartLocationDto(
    val latitude: Double,
    val longitude: Double,
)

/**
 * `PROG-001`·`PROG-002`·`PROG-006` 응답 data. 한 날짜의 진행 현황 전체다.
 *
 * @property progressVersion 진행 상태 version. 일정 version([scheduleVersion])과 별개로 전환마다 1 증가한다.
 * @property currentItemId `ARRIVED` 장소. 없으면 `null`.
 * @property nextItemId `EN_ROUTE` 장소, 없으면 순서상 첫 `PLANNED` 장소. 남은 장소가 없으면 `null`.
 */
@Serializable
data class ProgressData(
    val tripId: String,
    val date: String,
    val dayStatus: DayStatus,
    val progressVersion: Int,
    val scheduleVersion: Int,
    val actualStartedAt: String?,
    val completedAt: String?,
    val startLocation: StartLocationDto?,
    val currentItemId: String?,
    val nextItemId: String?,
    val items: List<ProgressItemDto>,
)

/**
 * 시작 요청에 싣는 현재 위치. 정확도 100m 초과·발생 2분 경과 위치는 앱이 보내지 않는다(FR-004).
 *
 * @property occurredAt 위치를 얻은 시각. ISO-8601.
 */
@Serializable
data class CurrentLocationDto(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val occurredAt: String,
)

/** `POST .../progress/start` 요청. [currentLocation]이 없으면 `null`을 key째 보낸다. */
@Serializable
data class StartProgressRequest(
    val progressVersion: Int,
    val currentLocation: CurrentLocationDto?,
)

/**
 * `PATCH /itinerary-items/{itemId}/status` 요청. 목표 상태만 보내고 파생 전환은 서버가 적용한다.
 *
 * 허용 값은 `ARRIVED`·`COMPLETED`·`SKIPPED`·`PLANNED`다. `EN_ROUTE`는 서버가 파생하는 상태라
 * 목표로 보낼 수 없다.
 */
@Serializable
data class UpdateProgressStatusRequest(
    val status: ItemStatus,
    val progressVersion: Int,
)

/**
 * 진행 endpoint 전용 Json 설정. 모르는 key는 무시하고, 계약이 required nullable로 정의한
 * field(`currentLocation` 등)는 `null`도 key째 싣도록 `explicitNulls` 기본값을 유지한다.
 */
private val progressJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** 진행 endpoint 전용 Retrofit 인스턴스를 만든다. */
fun createProgressRetrofit(
    baseUrl: String,
    client: OkHttpClient = OkHttpClient(),
): Retrofit = Retrofit.Builder()
    .baseUrl(baseUrl)
    .client(client)
    .addConverterFactory(progressJson.asConverterFactory("application/json".toMediaType()))
    .build()

/**
 * 진행 endpoint 호출 계약.
 *
 * Access Token은 [com.gilpick.auth.AuthRepository.withAuthorizedCall]이 넘겨주므로 각 함수가
 * `Authorization` header를 직접 받는다. F005 [com.gilpick.route.RouteService]와 같은 구조다.
 */
interface ProgressService {

    /** 당일 진행 현황을 조회한다(PROG-001). */
    @GET("trips/{tripId}/days/{date}/progress")
    suspend fun getDayProgress(
        @Header("Authorization") bearer: String,
        @Path("tripId") tripId: String,
        @Path("date") date: String,
    ): Response<SuccessEnvelope<ProgressData>>

    /**
     * 오늘 여행을 시작한다(PROG-002). 이미 시작됐으면 저장된 값을 그대로 `200`으로 돌려준다.
     *
     * `409`는 `VERSION_CONFLICT`·`DAY_NOT_TODAY`, `422`는 `DAY_EMPTY`·`INVALID_REQUEST`다.
     */
    @POST("trips/{tripId}/days/{date}/progress/start")
    suspend fun startDayProgress(
        @Header("Authorization") bearer: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Path("tripId") tripId: String,
        @Path("date") date: String,
        @Body body: StartProgressRequest,
    ): Response<SuccessEnvelope<ProgressData>>

    /**
     * 장소의 진행 상태를 수동으로 바꾼다(PROG-006). 응답은 날짜 전체 진행 현황이다.
     *
     * `409`는 `VERSION_CONFLICT`·`DAY_NOT_STARTED`, `422`는 `INVALID_STATUS_TRANSITION`이다.
     */
    @PATCH("itinerary-items/{itemId}/status")
    suspend fun updateItemStatus(
        @Header("Authorization") bearer: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Path("itemId") itemId: String,
        @Body body: UpdateProgressStatusRequest,
    ): Response<SuccessEnvelope<ProgressData>>
}
