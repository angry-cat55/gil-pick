package com.gilpick.replacement

import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthResult
import com.gilpick.auth.toAuthResult
import com.gilpick.itinerary.RouteStatus
import com.gilpick.itinerary.TransportMode
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T007: REPL-001~004의 요청 경로·header와 계약 예시 JSON 역직렬화 검증.
 *
 * 값은 `contracts/replacements.openapi.yaml`을 따른다. 서버 구현(#342~#350)은 이미 병합됐지만
 * 이 test는 실서버를 쓰지 않고 계약 JSON만으로 앱 쪽 계약을 고정한다. 실서버 연동 확인은
 * T036·T037에서 한다.
 */
class ReplacementApiTest {

    @Test
    fun `REPL-001은 감지 결과 경로로 POST하고 Idempotency-Key를 보낸다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = routePreviewJson()))

        api.createPreview(BEARER, IDEMPOTENCY_KEY, REPL_DETECTION_ID, createRequest())

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/detections/$REPL_DETECTION_ID/route-previews", request.url.encodedPath)
        assertEquals(BEARER, request.headers["Authorization"])
        assertEquals(IDEMPOTENCY_KEY, request.headers["Idempotency-Key"])
    }

    @Test
    fun `직접 검색으로 고른 장소는 candidateId를 null로 실어 보낸다`() = withService { server, api ->
        // 계약이 required로 정의한 nullable field라 key를 빼지 않고 null을 그대로 보낸다(FR-004).
        server.enqueue(MockResponse(code = 200, body = routePreviewJson()))

        api.createPreview(BEARER, IDEMPOTENCY_KEY, REPL_DETECTION_ID, createRequest(candidateId = null))

        val body = server.takeRequest().body!!.utf8()
        assertTrue(body, body.contains("\"candidateId\":null"))
        assertTrue(body, body.contains("\"placeId\":\"$REPL_PLACE_ID\""))
        assertTrue(body, body.contains("\"scheduleVersion\":$REPL_SCHEDULE_VERSION"))
    }

    @Test
    fun `추천 후보로 고른 장소는 candidateId를 그대로 실어 보낸다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = routePreviewJson()))

        api.createPreview(BEARER, IDEMPOTENCY_KEY, REPL_DETECTION_ID, createRequest(candidateId = REPL_CANDIDATE_ID))

        assertTrue(server.takeRequest().body!!.utf8().contains("\"candidateId\":\"$REPL_CANDIDATE_ID\""))
    }

    @Test
    fun `REPL-001 응답은 비교 네 항목과 변경 경로를 읽는다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = routePreviewJson()))

        val data = api.createPreview(BEARER, IDEMPOTENCY_KEY, REPL_DETECTION_ID, createRequest()).body()!!.data

        assertEquals(REPL_PREVIEW_ID, data.previewId)
        assertEquals(REPL_ITEM_ID, data.itemId)
        assertEquals("경복궁", data.originalPlace.name)
        assertEquals("창덕궁", data.alternativePlace.name)
        assertEquals("오후 2시 이후 강한 비 + 매우 높은 혼잡", data.detectionReason)
        assertEquals(REPL_SCHEDULE_VERSION, data.scheduleVersion)
        assertEquals("2026-09-08T13:35:00+09:00", data.expiresAt)

        val comparison = data.comparison
        assertEquals(1800, comparison.totalDurationSeconds.before)
        assertEquals(1500, comparison.totalDurationSeconds.after)
        assertEquals(5000, comparison.totalDistanceMeters.before)
        assertEquals(4200, comparison.totalDistanceMeters.after)
        assertEquals("2026-09-08T14:00:00+09:00", comparison.estimatedArrivalAt.before)
        assertEquals("2026-09-08T14:20:00+09:00", comparison.estimatedArrivalAt.after)
        assertEquals("2026-09-08T18:00:00+09:00", comparison.closesAt.before)
        assertEquals("2026-09-08T20:00:00+09:00", comparison.closesAt.after)

        // 변경 경로는 F005 ROUTE-001의 route와 같은 형식이라 RouteDto를 그대로 쓴다(UI-001).
        assertEquals(1500, data.route.totalDurationSeconds)
        assertEquals(3, data.route.markers.size)
        assertEquals(TransportMode.WALK, data.route.segments[0].transportMode)
    }

    @Test
    fun `운영 마감 시각을 확보하지 못하면 그 항목만 null이고 나머지는 그대로다`() = withService { server, api ->
        // FR-002. 값을 지어내지 않고 그 항목만 비운다. 화면은 `정보 없음`으로 표시한다(UI-002).
        server.enqueue(MockResponse(code = 200, body = previewWithoutClosingTimeJson()))

        val comparison = api.createPreview(BEARER, IDEMPOTENCY_KEY, REPL_DETECTION_ID, createRequest()).body()!!.data.comparison

        assertNull(comparison.closesAt.before)
        assertNull(comparison.closesAt.after)
        assertEquals(1500, comparison.totalDurationSeconds.after)
        assertEquals("2026-09-08T14:20:00+09:00", comparison.estimatedArrivalAt.after)
    }

    @Test
    fun `REPL-002는 미리보기 경로로 POST하고 Idempotency-Key를 보낸다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = replacementJson()))

        val data = api.approvePreview(BEARER, IDEMPOTENCY_KEY, REPL_PREVIEW_ID).body()!!.data

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/route-previews/$REPL_PREVIEW_ID/approve", request.url.encodedPath)
        assertEquals(IDEMPOTENCY_KEY, request.headers["Idempotency-Key"])

        assertEquals(REPL_REPLACEMENT_ID, data.replacementId)
        assertEquals("창덕궁", data.newPlaceName)
        assertEquals("경복궁", data.originalPlaceName)
        assertEquals(REPL_SCHEDULE_VERSION + 1, data.scheduleVersion)
        // 미리보기에서 계산을 마쳤으므로 승인 응답의 routeStatus는 READY뿐이다(계약 Replacement).
        assertEquals(RouteStatus.READY, data.routeStatus)
        assertEquals("2026-09-08T13:30:30+09:00", data.undoExpiresAt)
    }

    @Test
    fun `REPL-003 폐기는 204이며 Idempotency-Key가 없다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 204))

        val response = api.rejectPreview(BEARER, REPL_PREVIEW_ID)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/route-previews/$REPL_PREVIEW_ID/reject", request.url.encodedPath)
        // 대상 상태로 결과가 정해지는 자연 멱등이라 header가 없다(계약 REPL-003).
        assertNull(request.headers["Idempotency-Key"])
        assertEquals(204, response.code())
    }

    @Test
    fun `REPL-004 되돌리기는 Idempotency-Key 없이 복원 결과를 읽는다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = undoResultJson()))

        val data = api.undoReplacement(BEARER, REPL_REPLACEMENT_ID).body()!!.data

        val request = server.takeRequest()
        assertEquals("/api/v1/replacements/$REPL_REPLACEMENT_ID/undo", request.url.encodedPath)
        assertNull(request.headers["Idempotency-Key"])

        assertTrue(data.restored)
        assertTrue(data.detectionRestored)
        assertEquals(RouteStatus.READY, data.routeStatus)
        assertEquals(REPL_SCHEDULE_VERSION + 2, data.scheduleVersion)
    }

    @Test
    fun `같은 항목에 더 새 감지 결과가 있으면 detectionRestored가 false로 온다`() = withService { server, api ->
        // FR-018. 되돌리기 자체는 성공하고 감지 결과만 복귀하지 못한 경우다(research 5절).
        server.enqueue(MockResponse(code = 200, body = undoResultJson(detectionRestored = false)))

        val data = api.undoReplacement(BEARER, REPL_REPLACEMENT_ID).body()!!.data

        assertTrue(data.restored)
        assertEquals(false, data.detectionRestored)
    }

    @Test
    fun `승인 전 경로가 실패 상태였으면 그 상태가 그대로 복원된다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = undoResultJson(routeStatus = "FAILED")))

        assertEquals(RouteStatus.FAILED, api.undoReplacement(BEARER, REPL_REPLACEMENT_ID).body()!!.data.routeStatus)
    }

    @Test
    fun `모르는 field가 와도 파싱된다`() = withService { server, api ->
        server.enqueue(
            MockResponse(
                code = 200,
                body = replacementJson().replace("\"routeStatus\": \"READY\"", "\"routeStatus\": \"READY\", \"extra\": {\"a\": 1}"),
            ),
        )

        assertEquals(REPL_REPLACEMENT_ID, api.approvePreview(BEARER, IDEMPOTENCY_KEY, REPL_PREVIEW_ID).body()!!.data.replacementId)
    }

    @Test
    fun `409 오류 code와 retryable이 보존된다`() = withService { server, api ->
        server.enqueue(
            MockResponse(
                code = 409,
                body = replacementErrorJson(ReplacementErrorCodes.VERSION_CONFLICT),
            ),
        )

        val result = api.approvePreview(BEARER, IDEMPOTENCY_KEY, REPL_PREVIEW_ID).toAuthResult()

        val error = (result as AuthResult.Failure).error as AuthError.Server
        assertEquals(ReplacementErrorCodes.VERSION_CONFLICT, error.code)
        assertEquals(409, error.httpStatus)
        assertEquals(false, error.retryable)
    }

    @Test
    fun `경로 계산 실패는 502·504 상태와 code를 함께 남긴다`() = withService { server, api ->
        // FR-007. 일정은 그대로이고 화면이 원인을 구분해 안내해야 한다(UI-004).
        server.enqueue(
            MockResponse(
                code = 504,
                body = replacementErrorJson(ReplacementErrorCodes.ROUTE_PROVIDER_TIMEOUT, retryable = true),
            ),
        )

        val result = api.createPreview(BEARER, IDEMPOTENCY_KEY, REPL_DETECTION_ID, createRequest()).toAuthResult()

        val error = (result as AuthResult.Failure).error as AuthError.Server
        assertEquals(ReplacementErrorCodes.ROUTE_PROVIDER_TIMEOUT, error.code)
        assertEquals(504, error.httpStatus)
        assertTrue(error.retryable)
    }

    private fun createRequest(candidateId: String? = REPL_CANDIDATE_ID) = CreatePreviewRequest(
        placeId = REPL_PLACE_ID,
        candidateId = candidateId,
        scheduleVersion = REPL_SCHEDULE_VERSION,
    )

    private fun withService(block: suspend (MockWebServer, ReplacementService) -> Unit) = runTest {
        val server = MockWebServer()
        server.start()
        try {
            block(
                server,
                createReplacementRetrofit(server.url("/api/v1/").toString()).create(ReplacementService::class.java),
            )
        } finally {
            server.close()
        }
    }

    private companion object {
        const val BEARER = "Bearer access-token"
        const val IDEMPOTENCY_KEY = "22222222-3333-4444-8555-666666666666"
    }
}
