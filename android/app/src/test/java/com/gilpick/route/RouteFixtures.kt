package com.gilpick.route

import com.gilpick.auth.ResponseMeta
import com.gilpick.auth.SuccessEnvelope
import com.gilpick.itinerary.RouteStatus
import com.gilpick.itinerary.TransportMode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/** route package test가 공유하는 계약 fixture. 값은 `contracts/route.openapi.yaml`의 예시를 따른다. */

internal const val ROUTE_TRIP_ID = "3f1d2c4b-5a6e-4f70-8a91-b2c3d4e5f607"
internal const val ROUTE_DATE = "2026-09-08"
internal const val ROUTE_ID = "7d6c5b4a-3f2e-4d1c-8b0a-9f8e7d6c5b4a"
internal const val ITEM_A = "9c8b7a6f-5e4d-4c3b-8a29-18f7e6d5c4b3"
internal const val ITEM_B = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
internal const val ITEM_C = "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e"
internal const val ROUTE_REQUEST_ID = "11111111-2222-4333-8444-555555555555"
internal const val TMAP_ATTRIBUTION = "경로 정보 제공: TMAP"
internal const val ODSAY_ATTRIBUTION = "대중교통 정보 제공: ODsay"

/** 도보(TMAP)·대중교통(ODsay) 두 구간, 세 장소의 READY 경로 JSON. */
internal fun readyRouteJson(scheduleVersion: Int = 3) = """
    {
      "routeId": "$ROUTE_ID",
      "scheduleVersion": $scheduleVersion,
      "totalDurationSeconds": 1500,
      "totalDistanceMeters": 4200,
      "markers": [
        {"itemId": "$ITEM_A", "sequence": 1, "name": "경복궁", "latitude": 37.5796, "longitude": 126.977},
        {"itemId": "$ITEM_B", "sequence": 2, "name": "북촌한옥마을", "latitude": 37.5826, "longitude": 126.9831},
        {"itemId": "$ITEM_C", "sequence": 3, "name": "인사동거리", "latitude": 37.5744, "longitude": 126.9857}
      ],
      "segments": [
        {
          "sequence": 1, "fromItemId": "$ITEM_A", "toItemId": "$ITEM_B",
          "transportMode": "WALK", "provider": "TMAP",
          "durationSeconds": 600, "distanceMeters": 800,
          "geometry": {"type": "LineString", "coordinates": [[126.977, 37.5796], [126.98, 37.581], [126.9831, 37.5826]]},
          "providerAttribution": "$TMAP_ATTRIBUTION"
        },
        {
          "sequence": 2, "fromItemId": "$ITEM_B", "toItemId": "$ITEM_C",
          "transportMode": "TRANSIT", "provider": "ODSAY",
          "durationSeconds": 900, "distanceMeters": 3400,
          "geometry": {"type": "LineString", "coordinates": [[126.9831, 37.5826], [126.9857, 37.5744]]},
          "providerAttribution": "$ODSAY_ATTRIBUTION"
        }
      ],
      "providerAttributions": ["$TMAP_ATTRIBUTION", "$ODSAY_ATTRIBUTION"],
      "calculatedAt": "2026-09-07T01:02:03Z"
    }
""".trimIndent()

/** `RouteEnvelope` JSON. [route]·[failure]는 JSON 조각이며 상태에 맞춰 `null`을 준다. */
internal fun routeEnvelopeJson(
    routeStatus: String,
    route: String = "null",
    failure: String = "null",
    scheduleVersion: Int = 3,
) = """
    {"success": true, "data": {
      "tripId": "$ROUTE_TRIP_ID", "date": "$ROUTE_DATE", "scheduleVersion": $scheduleVersion,
      "routeStatus": "$routeStatus", "route": $route, "failure": $failure
    }, "meta": {"requestId": "$ROUTE_REQUEST_ID"}}
""".trimIndent()

internal fun failureJson(code: String = RouteFailureCodes.PROVIDER_TIMEOUT, retryable: Boolean = true) =
    """{"code": "$code", "message": "provider timed out", "retryable": $retryable}"""

/** 계약이 정한 error envelope. */
internal fun routeErrorJson(code: String, retryable: Boolean = false) =
    """{"success":false,"error":{"code":"$code","message":"진단용 설명","retryable":$retryable,"details":{}},""" +
        """"meta":{"requestId":"$ROUTE_REQUEST_ID"}}"""

// --- ViewModel test용 DTO fixture ---

/** [readyRouteJson]과 같은 값의 DTO. */
internal fun readyRoute(scheduleVersion: Int = 3): RouteDto = RouteDto(
    routeId = ROUTE_ID,
    scheduleVersion = scheduleVersion,
    totalDurationSeconds = 1500,
    totalDistanceMeters = 4200,
    markers = listOf(
        RouteMarkerDto(ITEM_A, 1, "경복궁", 37.5796, 126.977),
        RouteMarkerDto(ITEM_B, 2, "북촌한옥마을", 37.5826, 126.9831),
        RouteMarkerDto(ITEM_C, 3, "인사동거리", 37.5744, 126.9857),
    ),
    segments = listOf(
        RouteSegmentDto(
            sequence = 1, fromItemId = ITEM_A, toItemId = ITEM_B,
            transportMode = TransportMode.WALK, provider = RouteProvider.TMAP,
            durationSeconds = 600, distanceMeters = 800,
            geometry = RouteGeometryDto("LineString", listOf(listOf(126.977, 37.5796), listOf(126.98, 37.581), listOf(126.9831, 37.5826))),
            providerAttribution = TMAP_ATTRIBUTION,
        ),
        RouteSegmentDto(
            sequence = 2, fromItemId = ITEM_B, toItemId = ITEM_C,
            transportMode = TransportMode.TRANSIT, provider = RouteProvider.ODSAY,
            durationSeconds = 900, distanceMeters = 3400,
            geometry = RouteGeometryDto("LineString", listOf(listOf(126.9831, 37.5826), listOf(126.9857, 37.5744))),
            providerAttribution = ODSAY_ATTRIBUTION,
        ),
    ),
    providerAttributions = listOf(TMAP_ATTRIBUTION, ODSAY_ATTRIBUTION),
    calculatedAt = "2026-09-07T01:02:03Z",
)

internal fun routeFailure(code: String = RouteFailureCodes.PROVIDER_TIMEOUT, retryable: Boolean = true) =
    RouteFailureDto(code = code, message = "provider timed out", retryable = retryable)

internal fun dayRoute(
    routeStatus: RouteStatus,
    route: RouteDto? = null,
    failure: RouteFailureDto? = null,
    scheduleVersion: Int = 3,
    date: String = ROUTE_DATE,
) = DayRouteDto(
    tripId = ROUTE_TRIP_ID,
    date = date,
    scheduleVersion = scheduleVersion,
    routeStatus = routeStatus,
    route = route,
    failure = failure,
)

/** 응답을 test가 직접 정하는 [RouteService]. */
class FakeRouteService : RouteService {

    /** 지금까지 도착한 조회의 날짜. */
    val getCalls = mutableListOf<String>()

    /** 지금까지 도착한 재시도의 (날짜, version). */
    val retryCalls = mutableListOf<Pair<String, Int>>()

    var onGet: suspend (String) -> Response<SuccessEnvelope<DayRouteDto>> =
        { error("이 test는 경로 조회 endpoint를 호출하지 않는다") }

    var onRetry: suspend (String, Int) -> Response<SuccessEnvelope<DayRouteDto>> =
        { _, _ -> error("이 test는 경로 재시도 endpoint를 호출하지 않는다") }

    override suspend fun getDayRoute(bearer: String, tripId: String, date: String): Response<SuccessEnvelope<DayRouteDto>> {
        getCalls += date
        return onGet(date)
    }

    override suspend fun retryDayRoute(
        bearer: String,
        tripId: String,
        date: String,
        body: RetryRouteRequest,
    ): Response<SuccessEnvelope<DayRouteDto>> {
        retryCalls += date to body.scheduleVersion
        return onRetry(date, body.scheduleVersion)
    }
}

/** 성공 envelope. */
internal fun <T> routeOk(value: T): Response<SuccessEnvelope<T>> =
    Response.success(200, SuccessEnvelope(success = true, data = value, meta = ResponseMeta(ROUTE_REQUEST_ID)))

/** 오류 envelope. */
internal fun <T> routeError(status: Int, code: String): Response<SuccessEnvelope<T>> =
    Response.error(status, routeErrorJson(code).toResponseBody("application/json".toMediaType()))
