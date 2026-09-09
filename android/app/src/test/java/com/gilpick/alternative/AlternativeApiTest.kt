package com.gilpick.alternative

import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthResult
import com.gilpick.auth.toAuthResult
import com.gilpick.place.PlaceCategory
import com.gilpick.place.PlaceSource
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T010: DETECT-001/002/004·ALT-001/002 요청 경로·query와 계약 예시 JSON 역직렬화 검증.
 *
 * 계약 값은 `contracts/alternatives.openapi.yaml`을 따른다. 서버 구현(#322~#325) 전이므로
 * MockWebServer가 계약대로 응답한다고 보고 앱 쪽 계약만 확인한다.
 */
class AlternativeApiTest {

    @Test
    fun `DETECT-001은 status 필터를 query로 보내고 eta·reason을 읽는다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = detectionListJson()))

        val response = api.listDetections(BEARER, TRIP_ID, status = DetectionStatus.ACTIVE)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/trips/$TRIP_ID/detections", request.url.encodedPath)
        assertEquals("ACTIVE", request.url.queryParameter("status"))
        assertNull(request.url.queryParameter("cursor"))
        assertEquals(BEARER, request.headers["Authorization"])

        val body = response.body()!!
        val first = body.data.items.first()
        assertEquals(DETECTION_ID, first.detectionId)
        assertEquals(DetectionType.WEATHER, first.primaryType)
        assertEquals(DetectionStatus.ACTIVE, first.status)
        assertEquals("2026-09-09T14:00:00+09:00", first.eta)
        assertEquals("오후 2시 이후 강한 비 + 매우 높은 혼잡", first.reason)
        assertEquals(DetectionStatus.DISMISSED, body.data.items[1].status)
        assertFalse(body.meta.pagination.hasNext)
        assertNull(body.meta.pagination.nextCursor)
    }

    @Test
    fun `DETECT-001 status를 생략하면 query를 보내지 않는다`() = withService { server, api ->
        // 기존 F008 호출과 호환된다(계약 description).
        server.enqueue(MockResponse(code = 200, body = detectionListJson()))

        api.listDetections(BEARER, TRIP_ID)

        assertNull(server.takeRequest().url.queryParameter("status"))
    }

    @Test
    fun `DETECT-002 상세는 변수별 판정을 읽는다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = detectionDetailJson()))

        val data = api.getDetection(BEARER, DETECTION_ID).body()!!.data

        assertEquals("/api/v1/detections/$DETECTION_ID", server.takeRequest().url.encodedPath)
        assertEquals(TRIP_ID, data.tripId)
        assertEquals("경복궁", data.placeName)
        assertEquals(78, data.totalRiskScore)
        assertEquals(CongestionLevel.CROWDED, data.variables.congestion.level)
        assertEquals(true, data.variables.congestion.crowded)
        assertEquals(80, data.variables.weather.precipitationProbability)
        assertEquals(PrecipitationType.RAIN, data.variables.weather.precipitationType)
        assertFalse(data.variables.operatingHours.available)
        assertEquals(UnavailableReason.HOURS_UNKNOWN, data.variables.operatingHours.unavailableReason)
        assertNull(data.variables.operatingHours.closesAt)
    }

    @Test
    fun `ALT-001 후보는 place에 F003 PlaceDto를 그대로 담는다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = alternativesJson()))

        val data = api.listAlternatives(BEARER, DETECTION_ID).body()!!.data

        assertEquals("/api/v1/detections/$DETECTION_ID/alternatives", server.takeRequest().url.encodedPath)
        assertEquals(1000, data.searchRadiusMeters)
        assertEquals(CategoryMatchLevel.MIDDLE, data.categoryMatchLevel)
        assertEquals(2, data.items.size)

        val top = data.items[0]
        assertEquals(1, top.rank)
        assertEquals(CANDIDATE_ID, top.candidateId)
        assertEquals(PlaceSource.TOUR_API, top.place.source)
        assertEquals(PlaceCategory.HISTORY_CULTURE, top.place.category)
        assertEquals("창덕궁", top.place.name)
        assertEquals(4.6, top.place.rating!!, 0.0)
        assertEquals(820, top.distanceMeters)
        assertEquals(4.48, top.adjustedRating!!, 0.0)
        assertEquals(87, top.displayScore)
        assertEquals(1.0, top.scoreBreakdown.weather!!, 0.0)
        assertEquals(OperatingStatus.OPEN, top.operatingStatus)
        assertEquals("2026-09-09T18:00:00+09:00", top.closesAt)
        assertEquals(listOf("INDOOR", "NOT_CROWDED", "CLOSER"), top.reasons)
    }

    @Test
    fun `평점 없는 후보는 adjustedRating이 null이고 제외된 변수는 breakdown에 없다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = alternativesJson()))

        val second = api.listAlternatives(BEARER, DETECTION_ID).body()!!.data.items[1]

        assertEquals(PlaceSource.GOOGLE_PLACES, second.place.source)
        assertNull(second.place.rating)
        assertNull(second.adjustedRating)
        // 점수에 쓰인 변수만 key가 있다(계약 ScoreBreakdown).
        assertNull(second.scoreBreakdown.rating)
        assertNull(second.scoreBreakdown.weather)
        assertEquals(0.5, second.scoreBreakdown.congestion!!, 0.0)
        assertEquals(OperatingStatus.UNKNOWN, second.operatingStatus)
        assertNull(second.closesAt)
        assertTrue(second.reasons.isEmpty())
    }

    @Test
    fun `ALT-001 후보 없음은 빈 items와 반경 2000·NONE으로 온다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = alternativesEmptyJson()))

        val data = api.listAlternatives(BEARER, DETECTION_ID).body()!!.data

        assertTrue(data.items.isEmpty())
        assertEquals(2000, data.searchRadiusMeters)
        assertEquals(CategoryMatchLevel.NONE, data.categoryMatchLevel)
    }

    @Test
    fun `ALT-002 검색은 query·cursor를 보내고 방문 가능 여부를 읽는다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = searchJson()))

        val body = api.searchAlternatives(BEARER, DETECTION_ID, query = "궁궐", cursor = "Zmlyc3Q=").body()!!

        val request = server.takeRequest()
        assertEquals("/api/v1/detections/$DETECTION_ID/alternatives/search", request.url.encodedPath)
        assertEquals("궁궐", request.url.queryParameter("query"))
        assertEquals("Zmlyc3Q=", request.url.queryParameter("cursor"))

        val (open, closed, scheduled) = body.data.items
        assertTrue(open.visitable)
        assertEquals(820, open.distanceMeters)
        assertEquals(OperatingStatus.CLOSED, closed.operatingStatus)
        assertFalse(closed.visitable)
        assertNull(closed.distanceMeters)
        assertTrue(scheduled.inSchedule)
        assertFalse(scheduled.visitable)
        assertEquals("c2Vjb25k", body.meta.pagination.nextCursor)
        assertTrue(body.meta.pagination.hasNext)
    }

    @Test
    fun `DETECT-004 거절은 POST dismiss 경로이며 Idempotency-Key가 없다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = dismissJson()))

        val data = api.dismissDetection(BEARER, DETECTION_ID).body()!!.data

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/detections/$DETECTION_ID/dismiss", request.url.encodedPath)
        // 상태 기반 멱등이라 header가 없다(계약 DETECT-004).
        assertNull(request.headers["Idempotency-Key"])
        assertEquals(DetectionStatus.DISMISSED, data.status)
        assertEquals("2026-09-09T13:20:00+09:00", data.decidedAt)
    }

    @Test
    fun `이미 처리된 감지의 거절은 현재 상태와 null decidedAt으로 온다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = dismissJson(status = "INVALIDATED", decidedAt = null)))

        val data = api.dismissDetection(BEARER, DETECTION_ID).body()!!.data

        assertEquals(DetectionStatus.INVALIDATED, data.status)
        assertNull(data.decidedAt)
    }

    @Test
    fun `모르는 field가 와도 파싱된다`() = withService { server, api ->
        server.enqueue(
            MockResponse(
                code = 200,
                body = alternativesEmptyJson().replace("\"items\": []", "\"items\": [], \"extra\": {\"a\": 1}"),
            ),
        )

        assertEquals(DETECTION_ID, api.listAlternatives(BEARER, DETECTION_ID).body()!!.data.detectionId)
    }

    @Test
    fun `409 오류 code와 details status가 보존된다`() = withService { server, api ->
        server.enqueue(
            MockResponse(
                code = 409,
                body = alternativeErrorJson(AlternativeErrorCodes.DETECTION_NOT_ACTIVE, details = """{"status": "DISMISSED"}"""),
            ),
        )

        val result = api.listAlternatives(BEARER, DETECTION_ID).toAuthResult()

        val error = (result as AuthResult.Failure).error as AuthError.Server
        assertEquals(AlternativeErrorCodes.DETECTION_NOT_ACTIVE, error.code)
        assertEquals(409, error.httpStatus)
        assertEquals("DISMISSED", error.details?.status)
    }

    private fun withService(block: suspend (MockWebServer, AlternativeService) -> Unit) = runTest {
        val server = MockWebServer()
        server.start()
        try {
            block(server, createAlternativeRetrofit(server.url("/api/v1/").toString()).create(AlternativeService::class.java))
        } finally {
            server.close()
        }
    }

    private companion object {
        const val BEARER = "Bearer access-token"
    }
}
