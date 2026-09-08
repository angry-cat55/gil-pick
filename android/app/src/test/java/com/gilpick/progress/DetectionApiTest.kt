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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T008: PROG-003·004·005 요청 경로·header·body와 응답 DTO 직렬화 검증.
 *
 * 계약 값은 `contracts/detection.openapi.yaml`을 따른다. 서버 구현(#259·#261) 전이므로
 * MockWebServer가 계약대로 응답한다고 보고 앱 쪽 계약만 확인한다.
 */
class DetectionApiTest {

    @Test
    fun `PROG-003 이벤트 등록은 POST events 경로에 eventId와 좌표를 보낸다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = acceptedWithCandidateJson()))

        val response = api.registerEvent(BEARER, TRIP_ID, DATE, dwellRequest())

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/trips/$TRIP_ID/days/$DATE/progress/events", request.url.encodedPath)
        assertEquals(BEARER, request.headers["Authorization"])
        // 이벤트는 Idempotency-Key 대신 앱이 만든 eventId로 중복을 막는다(계약 PROG-003).
        assertNull(request.headers["Idempotency-Key"])

        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(EVENT_ID, body["eventId"]!!.toString().trim('"'))
        assertEquals("DWELL", body["eventType"]!!.toString().trim('"'))
        assertEquals(GEOFENCE_ID, body["geofenceId"]!!.toString().trim('"'))
        assertEquals(200, response.code())
    }

    @Test
    fun `후보가 생기면 allowedDecisions와 evidence가 함께 온다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = acceptedWithCandidateJson()))

        val data = api.registerEvent(BEARER, TRIP_ID, DATE, dwellRequest()).body()!!.data

        assertTrue(data.accepted)
        assertNull(data.rejectionReason)
        val candidate = data.candidate!!
        assertEquals(DetectionKind.ARRIVAL, candidate.type)
        assertEquals(TransitionStatus.PENDING_CONFIRMATION, candidate.status)
        assertEquals(
            listOf(TransitionDecision.CONFIRM, TransitionDecision.NOT_ARRIVED),
            candidate.allowedDecisions,
        )
        assertEquals(6, candidate.evidence.dwellMinutes)
        assertNull(data.cancelledTransitionId)
    }

    @Test
    fun `기준 미충족 이벤트는 오류가 아니라 accepted false로 온다`() = withService { server, api ->
        // 정확도 초과·유효 시간 경과는 200이다. 앱이 재시도하지 않도록 하기 위해서다(FR-002).
        server.enqueue(MockResponse(code = 200, body = rejectedJson("LOW_ACCURACY")))

        val response = api.registerEvent(BEARER, TRIP_ID, DATE, dwellRequest())

        assertEquals(200, response.code())
        val data = response.body()!!.data
        assertEquals(false, data.accepted)
        assertEquals(EventRejectionReason.LOW_ACCURACY, data.rejectionReason)
        assertNull(data.candidate)
    }

    @Test
    fun `REENTER 응답은 취소된 출발 후보 id를 준다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = reenterCancelJson()))

        val data = api.registerEvent(
            BEARER,
            TRIP_ID,
            DATE,
            dwellRequest().copy(eventType = ProgressEventType.REENTER),
        ).body()!!.data

        assertNull(data.candidate)
        assertEquals(TRANSITION_ID, data.cancelledTransitionId)
    }

    @Test
    fun `PROG-004 확인 응답은 Idempotency-Key와 decision을 보낸다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = confirmedJson()))

        api.decide(BEARER, KEY, TRANSITION_ID, DecisionRequest(TransitionDecision.CONFIRM))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/progress/transitions/$TRANSITION_ID/decisions", request.url.encodedPath)
        assertEquals(KEY, request.headers["Idempotency-Key"])
        assertEquals("CONFIRM", Json.parseToJsonElement(request.body!!.utf8()).jsonObject["decision"]!!.toString().trim('"'))
    }

    @Test
    fun `사용자가 직접 확인한 전환은 undoDeadline이 null이다`() = withService { server, api ->
        // FR-020: 전용 되돌리기는 자동 확정에만 준다.
        server.enqueue(MockResponse(code = 200, body = confirmedJson()))

        val data = api.decide(BEARER, KEY, TRANSITION_ID, DecisionRequest(TransitionDecision.CONFIRM)).body()!!.data

        assertEquals(TransitionStatus.CONFIRMED, data.status)
        assertNull(data.undoDeadline)
        assertNull(data.nextPromptAt)
        assertEquals(
            listOf(ItemStatus.EN_ROUTE to ItemStatus.ARRIVED),
            data.affectedItems.map { it.beforeStatus to it.afterStatus },
        )
    }

    @Test
    fun `거절 응답은 다시 물을 수 있는 시각을 준다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = cancelledJson()))

        val data = api.decide(BEARER, KEY, TRANSITION_ID, DecisionRequest(TransitionDecision.NOT_ARRIVED)).body()!!.data

        assertEquals(TransitionStatus.CANCELLED, data.status)
        assertEquals(emptyList<AffectedItemDto>(), data.affectedItems)
        assertEquals("2026-09-08T12:20:00+09:00", data.nextPromptAt)
    }

    @Test
    fun `PROG-005 되돌리기는 복원 항목과 재개 시각을 준다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = undoneJson()))

        val data = api.undo(BEARER, KEY, TRANSITION_ID).body()!!.data

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/progress/transitions/$TRANSITION_ID/undo", request.url.encodedPath)
        assertEquals(KEY, request.headers["Idempotency-Key"])
        assertEquals(TransitionStatus.UNDONE, data.status)
        // 당일 완료가 함께 해제된다(FR-019).
        assertEquals(DayStatus.IN_PROGRESS, data.dayStatus)
        assertEquals("2026-09-08T12:20:00+09:00", data.detectionResumeAt)
        assertEquals(
            listOf(ItemStatus.ARRIVED to ItemStatus.EN_ROUTE),
            data.restoredItems.map { it.beforeStatus to it.afterStatus },
        )
    }

    @Test
    fun `required nullable field는 null도 key째 전송된다`() {
        // 계약이 required로 정의한 nullable field는 key가 사라지면 안 된다.
        val json = Json { encodeDefaults = true }
        val result = ProgressEventResultDto(
            eventId = EVENT_ID,
            accepted = true,
            rejectionReason = null,
            candidate = null,
            cancelledTransitionId = null,
        )

        val encoded = json.encodeToString(result).let { Json.parseToJsonElement(it).jsonObject }

        assertEquals(JsonNull, encoded["rejectionReason"])
        assertEquals(JsonNull, encoded["candidate"])
        assertEquals(JsonNull, encoded["cancelledTransitionId"])
        assertEquals(result, json.decodeFromString<ProgressEventResultDto>(json.encodeToString(result)))
    }

    @Test
    fun `모르는 field가 와도 파싱된다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = rejectedJson("STALE").replace("\"accepted\": false", "\"accepted\": false, \"extra\": {\"a\": 1}")))

        assertEquals(
            EventRejectionReason.STALE,
            api.registerEvent(BEARER, TRIP_ID, DATE, dwellRequest()).body()!!.data.rejectionReason,
        )
    }

    @Test
    fun `409 오류 code가 보존된다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 409, body = detectionErrorJson(DetectionErrorCodes.TRANSITION_NOT_PENDING)))
        server.enqueue(MockResponse(code = 409, body = detectionErrorJson(DetectionErrorCodes.UNDO_WINDOW_EXPIRED)))

        val decide = api.decide(BEARER, KEY, TRANSITION_ID, DecisionRequest(TransitionDecision.CONFIRM)).toAuthResult()
        val undo = api.undo(BEARER, KEY, TRANSITION_ID).toAuthResult()

        assertEquals(
            DetectionErrorCodes.TRANSITION_NOT_PENDING,
            ((decide as AuthResult.Failure).error as AuthError.Server).code,
        )
        assertEquals(
            DetectionErrorCodes.UNDO_WINDOW_EXPIRED,
            ((undo as AuthResult.Failure).error as AuthError.Server).code,
        )
    }

    private fun withService(block: suspend (MockWebServer, DetectionService) -> Unit) = runTest {
        val server = MockWebServer()
        server.start()
        try {
            block(server, createDetectionRetrofit(server.url("/api/v1/").toString()).create(DetectionService::class.java))
        } finally {
            server.close()
        }
    }

    private companion object {
        const val BEARER = "Bearer access-token"
        const val KEY = "7d6c5b4a-3f2e-4d1c-8b0a-9f8e7d6c5b4a"
    }
}
