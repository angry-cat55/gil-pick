package com.gilpick.route

import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthResult
import com.gilpick.auth.toAuthResult
import com.gilpick.itinerary.RouteStatus
import com.gilpick.itinerary.TransportMode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T009: ROUTE-001·003 요청 경로, `scheduleVersion` body, 세 상태와 geometry·failure 직렬화
 * round-trip 검증. 계약 값은 `contracts/route.openapi.yaml`을 따른다.
 */
class RouteApiTest {

    @Test
    fun `ROUTE-001 READY 응답의 마커·구간·geometry·attribution을 그대로 받는다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = routeEnvelopeJson("READY", route = readyRouteJson())))

        val data = api.getDayRoute(BEARER, ROUTE_TRIP_ID, ROUTE_DATE).body()!!.data

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/trips/$ROUTE_TRIP_ID/days/$ROUTE_DATE/route", request.url.encodedPath)
        assertEquals(BEARER, request.headers["Authorization"])
        assertEquals(RouteStatus.READY, data.routeStatus)
        assertEquals(3, data.scheduleVersion)
        assertNull(data.failure)

        val route = data.route!!
        assertEquals(readyRoute(), route)
        assertEquals(listOf(1, 2, 3), route.markers.map { it.sequence })
        assertEquals(listOf(TransportMode.WALK, TransportMode.TRANSIT), route.segments.map { it.transportMode })
        assertEquals(listOf(RouteProvider.TMAP, RouteProvider.ODSAY), route.segments.map { it.provider })
        // GeoJSON 좌표는 [경도, 위도] 순서다.
        val first = route.segments[0].geometry.coordinates[0]
        assertEquals(126.977, first.longitude, 0.0)
        assertEquals(37.5796, first.latitude, 0.0)
        assertEquals(listOf(TMAP_ATTRIBUTION, ODSAY_ATTRIBUTION), route.providerAttributions)
    }

    @Test
    fun `NOT_CALCULATED와 FAILED는 route와 failure가 상태에 맞게 null이다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = routeEnvelopeJson("NOT_CALCULATED", scheduleVersion = 0)))
        server.enqueue(MockResponse(code = 200, body = routeEnvelopeJson("FAILED", failure = failureJson())))

        val empty = api.getDayRoute(BEARER, ROUTE_TRIP_ID, ROUTE_DATE).body()!!.data
        assertEquals(RouteStatus.NOT_CALCULATED, empty.routeStatus)
        assertEquals(0, empty.scheduleVersion)
        assertNull(empty.route)
        assertNull(empty.failure)

        val failed = api.getDayRoute(BEARER, ROUTE_TRIP_ID, ROUTE_DATE).body()!!.data
        assertEquals(RouteStatus.FAILED, failed.routeStatus)
        assertNull(failed.route)
        assertEquals(routeFailure(), failed.failure)
    }

    @Test
    fun `ROUTE-003 재시도는 POST retry 경로에 scheduleVersion만 실어 보낸다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = routeEnvelopeJson("READY", route = readyRouteJson())))

        val response = api.retryDayRoute(BEARER, ROUTE_TRIP_ID, ROUTE_DATE, RetryRouteRequest(3))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/trips/$ROUTE_TRIP_ID/days/$ROUTE_DATE/route/retry", request.url.encodedPath)
        val sent = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(setOf("scheduleVersion"), sent.keys)
        assertEquals("3", sent["scheduleVersion"].toString())
        assertEquals(200, response.code())
        assertEquals(RouteStatus.READY, response.body()!!.data.routeStatus)
    }

    @Test
    fun `재시도의 최종 실패는 오류가 아니라 FAILED 성공 envelope다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = routeEnvelopeJson("FAILED", failure = failureJson(RouteFailureCodes.NOT_FOUND, retryable = false))))

        val result = api.retryDayRoute(BEARER, ROUTE_TRIP_ID, ROUTE_DATE, RetryRouteRequest(3)).toAuthResult()

        val data = (result as AuthResult.Success).value
        assertEquals(RouteStatus.FAILED, data.routeStatus)
        assertEquals(RouteFailureCodes.NOT_FOUND, data.failure!!.code)
        assertTrue(!data.failure!!.retryable)
    }

    @Test
    fun `모르는 failure code와 추가 field가 와도 파싱된다`() = withService { server, api ->
        server.enqueue(
            MockResponse(
                code = 200,
                body = routeEnvelopeJson("FAILED", failure = """{"code":"ROUTE_SOMETHING_NEW","message":"m","retryable":false,"extra":1}"""),
            ),
        )

        val data = api.getDayRoute(BEARER, ROUTE_TRIP_ID, ROUTE_DATE).body()!!.data

        assertEquals("ROUTE_SOMETHING_NEW", data.failure!!.code)
    }

    @Test
    fun `경로 DTO는 직렬화 후 역직렬화하면 같은 값이다`() {
        val json = Json
        val original = dayRoute(RouteStatus.READY, route = readyRoute())
        assertEquals(original, json.decodeFromString<DayRouteDto>(json.encodeToString(original)))

        val failed = dayRoute(RouteStatus.FAILED, failure = routeFailure())
        assertEquals(failed, json.decodeFromString<DayRouteDto>(json.encodeToString(failed)))
    }

    @Test
    fun `409 ROUTE_NOT_FAILED와 VERSION_CONFLICT는 code가 보존된다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 409, body = routeErrorJson(RouteErrorCodes.ROUTE_NOT_FAILED)))
        server.enqueue(MockResponse(code = 409, body = routeErrorJson(RouteErrorCodes.VERSION_CONFLICT)))

        val notFailed = api.retryDayRoute(BEARER, ROUTE_TRIP_ID, ROUTE_DATE, RetryRouteRequest(3)).toAuthResult()
        val conflict = api.retryDayRoute(BEARER, ROUTE_TRIP_ID, ROUTE_DATE, RetryRouteRequest(2)).toAuthResult()

        assertEquals(RouteErrorCodes.ROUTE_NOT_FAILED, ((notFailed as AuthResult.Failure).error as AuthError.Server).code)
        assertEquals(RouteErrorCodes.VERSION_CONFLICT, ((conflict as AuthResult.Failure).error as AuthError.Server).code)
    }

    private fun withService(block: suspend (MockWebServer, RouteService) -> Unit) = runTest {
        val server = MockWebServer()
        server.start()
        try {
            block(server, createRouteRetrofit(server.url("/api/v1/").toString()).create(RouteService::class.java))
        } finally {
            server.close()
        }
    }

    private companion object {
        const val BEARER = "Bearer access-token"
    }
}
