package com.gilpick.route

import com.gilpick.auth.SuccessEnvelope
import com.gilpick.itinerary.RouteStatus
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
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * 계약에 정의된 경로 error code. 값은 `contracts/route.openapi.yaml`을 따른다.
 *
 * `TRIP_FORBIDDEN`·`TRIP_NOT_FOUND`·`VERSION_CONFLICT`는 F004와 같은 문자열이고
 * `ROUTE_NOT_FAILED`만 F005 전용이다.
 */
object RouteErrorCodes {
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val INVALID_ACCESS_TOKEN = "INVALID_ACCESS_TOKEN"
    const val TRIP_FORBIDDEN = "TRIP_FORBIDDEN"
    const val TRIP_NOT_FOUND = "TRIP_NOT_FOUND"
    const val VERSION_CONFLICT = "VERSION_CONFLICT"
    const val ROUTE_NOT_FAILED = "ROUTE_NOT_FAILED"
}

/**
 * 경로 계산이 최종 실패한 원인 code(`RouteFailure.code`).
 *
 * error envelope가 아니라 `200` 성공 envelope의 `failure`에 실린다. 서버가 code를 추가해도
 * 앱이 깨지지 않도록 enum 대신 문자열 상수로 둔다. 화면은 모르는 code를 일반 실패로 안내한다.
 */
object RouteFailureCodes {
    const val PROVIDER_TIMEOUT = "ROUTE_PROVIDER_TIMEOUT"
    const val PROVIDER_RATE_LIMITED = "ROUTE_PROVIDER_RATE_LIMITED"
    const val PROVIDER_UNAVAILABLE = "ROUTE_PROVIDER_UNAVAILABLE"
    const val NOT_FOUND = "ROUTE_NOT_FOUND"
    const val INVALID_RESULT = "ROUTE_INVALID_RESULT"
}

/** 구간을 계산한 경로 제공자. attribution 문구는 [RouteSegmentDto.providerAttribution]이 따로 준다. */
@Serializable
enum class RouteProvider { TMAP, ODSAY }

/** GeoJSON 좌표 한 점. `[경도, 위도]` 순서의 배열이라 data class로 풀지 않는다. */
typealias Position = List<Double>

/** GeoJSON 좌표의 경도. */
val Position.longitude: Double get() = this[0]

/** GeoJSON 좌표의 위도. */
val Position.latitude: Double get() = this[1]

/** 지도 표시용 구간 형상. GeoJSON `LineString`이며 좌표는 두 점 이상이다. */
@Serializable
data class RouteGeometryDto(
    val type: String,
    val coordinates: List<Position>,
)

/** 지도 마커 하나. [sequence]는 일정 순서와 같다. */
@Serializable
data class RouteMarkerDto(
    val itemId: String,
    val sequence: Int,
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

/** 연속한 두 일정 항목 사이의 이동 결과. */
@Serializable
data class RouteSegmentDto(
    val sequence: Int,
    val fromItemId: String,
    val toItemId: String,
    val transportMode: TransportMode,
    val provider: RouteProvider,
    val durationSeconds: Int,
    val distanceMeters: Int,
    val geometry: RouteGeometryDto,
    val providerAttribution: String,
)

/**
 * 현재 일정 version에 적용되는 계획 경로.
 *
 * @property scheduleVersion 계산 입력이 된 일정 version. 조회한 일정의 version과 다르면
 *   현재 경로로 표시하지 않는다(FR-014).
 * @property segments 장소가 한 곳이면 비어 있고 합계는 0이다(FR-020).
 * @property providerAttributions 구간 attribution을 순서대로 중복 제거한 표시 문구.
 * @property calculatedAt ISO-8601 문자열. 화면이 파싱하지 않으므로 문자열로 둔다.
 */
@Serializable
data class RouteDto(
    val routeId: String,
    val scheduleVersion: Int,
    val totalDurationSeconds: Int,
    val totalDistanceMeters: Int,
    val markers: List<RouteMarkerDto>,
    val segments: List<RouteSegmentDto>,
    val providerAttributions: List<String>,
    val calculatedAt: String,
)

/**
 * 경로 계산 최종 실패 원인.
 *
 * @property code [RouteFailureCodes] 중 하나. 모르는 값도 올 수 있다.
 * @property retryable 같은 입력으로 `다시 시도`할 만한 실패인지. 화면 문구에만 쓴다.
 */
@Serializable
data class RouteFailureDto(
    val code: String,
    val message: String,
    val retryable: Boolean,
)

/**
 * `GET /trips/{tripId}/days/{date}/route`와 retry의 응답 data.
 *
 * [routeStatus]가 [RouteStatus.READY]면 [route]가, [RouteStatus.FAILED]면 [failure]가 있고
 * [RouteStatus.NOT_CALCULATED]면 둘 다 `null`이다.
 *
 * @property scheduleVersion 현재 일정 version. 저장된 적 없는 날짜는 0.
 */
@Serializable
data class DayRouteDto(
    val tripId: String,
    val date: String,
    val scheduleVersion: Int,
    val routeStatus: RouteStatus,
    val route: RouteDto?,
    val failure: RouteFailureDto?,
)

/** `POST .../route/retry` 요청. 현재 일정 version과 다르면 `409 VERSION_CONFLICT`다. */
@Serializable
data class RetryRouteRequest(val scheduleVersion: Int)

/** 서버가 field를 추가해도 앱이 깨지지 않도록 모르는 key를 무시한다. */
private val routeJson = Json { ignoreUnknownKeys = true }

/** 경로 endpoint 전용 Retrofit 인스턴스를 만든다. */
fun createRouteRetrofit(
    baseUrl: String,
    client: OkHttpClient = OkHttpClient(),
): Retrofit = Retrofit.Builder()
    .baseUrl(baseUrl)
    .client(client)
    .addConverterFactory(routeJson.asConverterFactory("application/json".toMediaType()))
    .build()

/**
 * 경로 endpoint 호출 계약.
 *
 * Access Token은 [com.gilpick.auth.AuthRepository.withAuthorizedCall]이 넘겨주므로 각 함수가
 * `Authorization` header를 직접 받는다. F004 [com.gilpick.itinerary.ItineraryService]와 같은 구조다.
 */
interface RouteService {

    /** 날짜의 현재 계획 경로를 조회한다(ROUTE-001). */
    @GET("trips/{tripId}/days/{date}/route")
    suspend fun getDayRoute(
        @Header("Authorization") bearer: String,
        @Path("tripId") tripId: String,
        @Path("date") date: String,
    ): Response<SuccessEnvelope<DayRouteDto>>

    /**
     * 실패한 날짜 경로를 같은 일정 입력으로 다시 계산한다(ROUTE-003).
     *
     * 최종 실패도 `200`에 [RouteStatus.FAILED]로 온다. 현재 경로가 실패 상태가 아니면
     * `409 ROUTE_NOT_FAILED`, version이 다르면 `409 VERSION_CONFLICT`다. 같은 version의 중복
     * 요청은 서버가 한 경로 행으로 합치므로 멱등 키가 따로 없다.
     */
    @POST("trips/{tripId}/days/{date}/route/retry")
    suspend fun retryDayRoute(
        @Header("Authorization") bearer: String,
        @Path("tripId") tripId: String,
        @Path("date") date: String,
        @Body body: RetryRouteRequest,
    ): Response<SuccessEnvelope<DayRouteDto>>
}
