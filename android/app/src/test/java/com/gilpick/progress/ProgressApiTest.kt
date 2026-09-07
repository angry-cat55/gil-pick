package com.gilpick.progress

import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthResult
import com.gilpick.auth.toAuthResult
import com.gilpick.itinerary.ItemStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T008: PROG-001·002·006 요청 경로·header·body와 [ProgressData] 직렬화 round-trip 검증.
 * 계약 값은 `contracts/progress.openapi.yaml`을 따른다.
 */
class ProgressApiTest {

    @Test
    fun `PROG-001 조회는 GET progress 경로이고 시작 전 응답은 모두 PLANNED·null이다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = notStartedJson()))

        val data = api.getDayProgress(BEARER, PROGRESS_TRIP_ID, PROGRESS_DATE).body()!!.data

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/trips/$PROGRESS_TRIP_ID/days/$PROGRESS_DATE/progress", request.url.encodedPath)
        assertEquals(BEARER, request.headers["Authorization"])
        assertEquals(DayStatus.NOT_STARTED, data.dayStatus)
        assertEquals(0, data.progressVersion)
        assertNull(data.actualStartedAt)
        assertNull(data.startLocation)
        assertEquals(P_ITEM_A, data.nextItemId)
        assertEquals(listOf(ItemStatus.PLANNED, ItemStatus.PLANNED), data.items.map { it.status })
        assertEquals(listOf(null, null), data.items.map { it.estimatedArrivalAt })
        assertEquals(listOf(null, null), data.items.map { it.inboundTravel })
    }

    @Test
    fun `PROG-002 시작은 POST start 경로에 Idempotency-Key와 progressVersion·currentLocation을 보낸다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = inProgressJson(progressVersion = 1)))
        val location = CurrentLocationDto(latitude = 37.57, longitude = 126.97, accuracyMeters = 12.5, occurredAt = "2026-09-08T00:59:30Z")

        val response = api.startDayProgress(BEARER, KEY, PROGRESS_TRIP_ID, PROGRESS_DATE, StartProgressRequest(0, location))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/trips/$PROGRESS_TRIP_ID/days/$PROGRESS_DATE/progress/start", request.url.encodedPath)
        assertEquals(KEY, request.headers["Idempotency-Key"])
        val sent = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(setOf("progressVersion", "currentLocation"), sent.keys)
        assertEquals("0", sent["progressVersion"].toString())
        assertEquals(
            setOf("latitude", "longitude", "accuracyMeters", "occurredAt"),
            sent["currentLocation"]!!.jsonObject.keys,
        )
        assertEquals(inProgress(progressVersion = 1), response.body()!!.data)
    }

    @Test
    fun `위치가 없으면 currentLocation을 null로 key째 보낸다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = inProgressJson(progressVersion = 1)))

        api.startDayProgress(BEARER, KEY, PROGRESS_TRIP_ID, PROGRESS_DATE, StartProgressRequest(0, null))

        val sent = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals(JsonNull, sent["currentLocation"])
    }

    @Test
    fun `PROG-006 전환은 PATCH status 경로에 목표 상태와 progressVersion만 보낸다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = inProgressJson(progressVersion = 3)))

        val response = api.updateItemStatus(BEARER, KEY, P_ITEM_B, UpdateProgressStatusRequest(ItemStatus.ARRIVED, 2))

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/v1/itinerary-items/$P_ITEM_B/status", request.url.encodedPath)
        assertEquals(KEY, request.headers["Idempotency-Key"])
        val sent = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(setOf("status", "progressVersion"), sent.keys)
        assertEquals("\"ARRIVED\"", sent["status"].toString())
        assertEquals("2", sent["progressVersion"].toString())
        assertEquals(3, response.body()!!.data.progressVersion)
    }

    @Test
    fun `진행 중 응답의 inboundTravel 출처·null ETA·시작 위치를 그대로 받는다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = inProgressJson()))

        val data = api.getDayProgress(BEARER, PROGRESS_TRIP_ID, PROGRESS_DATE).body()!!.data

        assertEquals(inProgress(), data)
        assertEquals(listOf(TravelSource.COMPUTED, TravelSource.PLANNED_ROUTE, null), data.items.map { it.inboundTravel?.source })
        assertNull(data.items[0].inboundTravel!!.fromItemId)
        assertNull(data.items[2].estimatedArrivalAt)
    }

    @Test
    fun `진행 DTO는 직렬화 후 역직렬화하면 같은 값이다`() {
        val json = Json
        val original = inProgress()
        assertEquals(original, json.decodeFromString<ProgressData>(json.encodeToString(original)))

        val start = StartProgressRequest(progressVersion = 0, currentLocation = null)
        assertEquals(start, json.decodeFromString<StartProgressRequest>(json.encodeToString(start)))
    }

    @Test
    fun `모르는 field가 와도 파싱된다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = inProgressJson().replace("\"scheduleVersion\": 3", "\"scheduleVersion\": 3, \"extra\": {\"a\": 1}")))

        assertEquals(3, api.getDayProgress(BEARER, PROGRESS_TRIP_ID, PROGRESS_DATE).body()!!.data.scheduleVersion)
    }

    @Test
    fun `409·422 오류 code가 보존된다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 409, body = progressErrorJson(ProgressErrorCodes.DAY_NOT_TODAY)))
        server.enqueue(MockResponse(code = 422, body = progressErrorJson(ProgressErrorCodes.INVALID_STATUS_TRANSITION)))

        val notToday = api.startDayProgress(BEARER, KEY, PROGRESS_TRIP_ID, PROGRESS_DATE, StartProgressRequest(0, null)).toAuthResult()
        val invalid = api.updateItemStatus(BEARER, KEY, P_ITEM_A, UpdateProgressStatusRequest(ItemStatus.COMPLETED, 2)).toAuthResult()

        assertEquals(ProgressErrorCodes.DAY_NOT_TODAY, ((notToday as AuthResult.Failure).error as AuthError.Server).code)
        assertEquals(ProgressErrorCodes.INVALID_STATUS_TRANSITION, ((invalid as AuthResult.Failure).error as AuthError.Server).code)
    }

    private fun withService(block: suspend (MockWebServer, ProgressService) -> Unit) = runTest {
        val server = MockWebServer()
        server.start()
        try {
            block(server, createProgressRetrofit(server.url("/api/v1/").toString()).create(ProgressService::class.java))
        } finally {
            server.close()
        }
    }

    private companion object {
        const val BEARER = "Bearer access-token"
        const val KEY = "7d6c5b4a-3f2e-4d1c-8b0a-9f8e7d6c5b4a"
    }
}
