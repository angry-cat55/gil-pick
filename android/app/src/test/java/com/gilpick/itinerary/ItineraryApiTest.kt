package com.gilpick.itinerary

import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthResult
import com.gilpick.auth.toAuthResult
import com.gilpick.place.PlaceCategory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T007: ITIN-001·002·003 요청 경로, `Idempotency-Key` header, DTO 직렬화 round-trip 검증.
 *
 * MockWebServer로 실제 Retrofit 왕복을 만들어 DTO가 Backend가 보낼 JSON을 그대로 받고
 * 계약대로 보내는지 확인한다. 계약 값은 `contracts/itinerary.openapi.yaml`을 따른다.
 */
class ItineraryApiTest {

    @Test
    fun `ITIN-003 개요를 계약 경로로 요청하고 날짜 목록을 받는다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = overviewJson()))

        val overview = api.getOverview(BEARER, TRIP_ID).body()!!.data

        assertEquals("/api/v1/trips/$TRIP_ID/itinerary", server.takeRequest().url.encodedPath)
        assertEquals(TRIP_ID, overview.tripId)
        assertEquals(listOf(1, 2), overview.days.map { it.dayNumber })
        // 저장된 적 없는 날짜는 version 0, 빈 items.
        assertEquals(0, overview.days[1].version)
        assertTrue(overview.days[1].items.isEmpty())
        assertEquals(RouteStatus.NOT_CALCULATED, overview.days[1].routeStatus)
    }

    @Test
    fun `ITIN-001 날짜별 조회의 enum과 nullable을 그대로 받는다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = dayEnvelopeJson(dayJson())))

        val day = api.getDayItinerary(BEARER, TRIP_ID, DATE).body()!!.data

        assertEquals("/api/v1/trips/$TRIP_ID/days/$DATE/itinerary", server.takeRequest().url.encodedPath)
        assertEquals(DATE, day.date)
        assertEquals(3, day.version)
        assertEquals(RouteStatus.READY, day.routeStatus)

        val first = day.items[0]
        assertEquals(ITEM_ID, first.itemId)
        assertEquals("tourapi:126508", first.place.placeId)
        assertEquals(PlaceCategory.HISTORY_CULTURE, first.place.category)
        assertEquals(StaySource.RECOMMENDED, first.staySource)
        assertEquals(TransportMode.TRANSIT, first.transportModeToNext)
        assertEquals(ItemStatus.COMPLETED, first.status)

        val last = day.items[1]
        assertNull(last.place.address)
        assertNull(last.place.imageUrl)
        assertNull(last.transportModeToNext)
        assertEquals(ItemStatus.PLANNED, last.status)
    }

    @Test
    fun `ITIN-002 저장은 PUT 경로에 Idempotency-Key와 계약 형식 body를 실어 보낸다`() =
        withService { server, api ->
            server.enqueue(MockResponse(code = 201, body = dayEnvelopeJson(dayJson(version = 0))))

            val response = api.saveDayItinerary(
                bearer = BEARER,
                idempotencyKey = IDEMPOTENCY_KEY,
                tripId = TRIP_ID,
                date = DATE,
                body = saveRequest(),
            )

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/api/v1/trips/$TRIP_ID/days/$DATE/itinerary", request.url.encodedPath)
            assertEquals(IDEMPOTENCY_KEY, request.headers["Idempotency-Key"])
            assertEquals(BEARER, request.headers["Authorization"])
            assertEquals(201, response.code())

            // 계약이 required로 정의한 nullable field는 생략하지 않고 null을 실어야 한다.
            val sent = Json.parseToJsonElement(request.body!!.utf8())
            val items = sent.jsonObject["items"]!!.jsonArray
            assertEquals(2, items.size)
            val existing = items[0].jsonObject
            assertEquals("\"$ITEM_ID\"", existing["itemId"].toString())
            assertEquals("null", existing["place"].toString())
            assertEquals("\"TRANSIT\"", existing["transportModeToNext"].toString())
            val created = items[1].jsonObject
            assertEquals("null", created["itemId"].toString())
            assertEquals("null", created["transportModeToNext"].toString())
            val snapshot = created["place"]!!.jsonObject
            assertEquals("\"CAFE\"", snapshot["category"].toString())
            assertEquals("null", snapshot["tourApiCategory"].toString())
            assertEquals("null", snapshot["imageUrl"].toString())
        }

    @Test
    fun `저장 요청 body는 직렬화 후 역직렬화하면 같은 값이다`() {
        val json = Json { encodeDefaults = true }
        val original = saveRequest()

        val roundTrip = json.decodeFromString<SaveDayItineraryRequest>(json.encodeToString(original))

        assertEquals(original, roundTrip)
    }

    @Test
    fun `INVALID_ITINERARY 응답은 violations를 details로 보존한다`() = withService { server, api ->
        server.enqueue(
            MockResponse(
                code = 422,
                body = errorJson(
                    ItineraryErrorCodes.INVALID_ITINERARY,
                    details = """{"violations":[{"field":"sequence","itemIndex":1,"reason":"duplicate"},""" +
                        """{"field":"items","itemIndex":null,"reason":"too many"}]}""",
                ),
            ),
        )

        val result = api.saveDayItinerary(BEARER, IDEMPOTENCY_KEY, TRIP_ID, DATE, saveRequest()).toAuthResult()

        val error = (result as AuthResult.Failure).error as AuthError.Server
        assertEquals(ItineraryErrorCodes.INVALID_ITINERARY, error.code)
        assertEquals(422, error.httpStatus)
        val violations = error.details!!.violations!!
        assertEquals(listOf("sequence", "items"), violations.map { it.field })
        assertEquals(listOf(1, null), violations.map { it.itemIndex })
    }

    @Test
    fun `ITINERARY_ITEM_LOCKED 응답은 itemId를 details로 보존한다`() = withService { server, api ->
        server.enqueue(
            MockResponse(
                code = 409,
                body = errorJson(ItineraryErrorCodes.ITINERARY_ITEM_LOCKED, details = """{"itemId":"$ITEM_ID"}"""),
            ),
        )

        val result = api.saveDayItinerary(BEARER, IDEMPOTENCY_KEY, TRIP_ID, DATE, saveRequest()).toAuthResult()

        val error = (result as AuthResult.Failure).error as AuthError.Server
        assertEquals(ItineraryErrorCodes.ITINERARY_ITEM_LOCKED, error.code)
        assertEquals(ITEM_ID, error.details?.itemId)
        assertNull(error.details?.violations)
    }

    @Test
    fun `details가 빈 object인 오류도 계약 code를 보존한다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 403, body = errorJson(ItineraryErrorCodes.TRIP_FORBIDDEN, details = "{}")))

        val result = api.getOverview(BEARER, TRIP_ID).toAuthResult()

        val error = (result as AuthResult.Failure).error as AuthError.Server
        assertEquals(ItineraryErrorCodes.TRIP_FORBIDDEN, error.code)
        assertNull(error.details?.itemId)
    }

    /** MockWebServer에 연결된 [ItineraryService]를 준비한다. */
    private fun withService(block: suspend (MockWebServer, ItineraryService) -> Unit) = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val api = createItineraryRetrofit(server.url("/api/v1/").toString())
                .create(ItineraryService::class.java)
            block(server, api)
        } finally {
            server.close()
        }
    }

    private companion object {
        const val BEARER = "Bearer access-token"
    }
}

internal const val TRIP_ID = "3f1d2c4b-5a6e-4f70-8a91-b2c3d4e5f607"
internal const val DATE = "2026-09-08"
internal const val ITEM_ID = "9c8b7a6f-5e4d-4c3b-8a29-18f7e6d5c4b3"
internal const val IDEMPOTENCY_KEY = "0a1b2c3d-4e5f-4061-8283-94a5b6c7d8e9"
internal const val ITINERARY_REQUEST_ID = "11111111-2222-4333-8444-555555555555"

/** 기존 항목 하나(처리됨)와 새 항목 하나(스냅샷 포함)를 담은 저장 요청. */
internal fun saveRequest() = SaveDayItineraryRequest(
    version = 3,
    items = listOf(
        SaveItemDto(
            itemId = ITEM_ID,
            placeId = "tourapi:126508",
            place = null,
            sequence = 1,
            plannedStayMinutes = 90,
            staySource = StaySource.RECOMMENDED,
            transportModeToNext = TransportMode.TRANSIT,
        ),
        SaveItemDto(
            itemId = null,
            placeId = "google:ChIJ_abc-123",
            place = PlaceSnapshotDto(
                name = "이름만 있는 카페",
                category = PlaceCategory.CAFE,
                tourApiCategory = null,
                address = null,
                latitude = 37.5,
                longitude = 127.0,
                imageUrl = null,
            ),
            sequence = 2,
            plannedStayMinutes = 60,
            staySource = StaySource.USER_ADJUSTED,
            transportModeToNext = null,
        ),
    ),
)

/** 값이 모두 채워진 처리된 항목과 nullable이 모두 null인 마지막 항목을 담은 날짜 JSON. */
internal fun dayJson(version: Int = 3, routeStatus: String = "READY") = """
    {
      "date": "$DATE",
      "dayNumber": 1,
      "version": $version,
      "routeStatus": "$routeStatus",
      "items": [
        {
          "itemId": "$ITEM_ID",
          "place": {
            "placeId": "tourapi:126508",
            "name": "불국사",
            "category": "HISTORY_CULTURE",
            "address": "경북 경주시",
            "imageUrl": "https://example.test/a.jpg"
          },
          "sequence": 1,
          "plannedStayMinutes": 90,
          "staySource": "RECOMMENDED",
          "transportModeToNext": "TRANSIT",
          "status": "COMPLETED"
        },
        {
          "itemId": "5d4c3b2a-1f0e-4d9c-8b7a-695847362514",
          "place": {
            "placeId": "google:ChIJ_abc-123",
            "name": "이름만 있는 카페",
            "category": "CAFE",
            "address": null,
            "imageUrl": null
          },
          "sequence": 2,
          "plannedStayMinutes": 60,
          "staySource": "USER_ADJUSTED",
          "transportModeToNext": null,
          "status": "PLANNED"
        }
      ],
      "route": {"routeId": "f5e4d3c2-b1a0-4998-8776-655443322110", "segments": []}
    }
""".trimIndent()

internal fun dayEnvelopeJson(day: String) =
    """{"success":true,"data":$day,"meta":{"requestId":"$ITINERARY_REQUEST_ID"}}"""

internal fun overviewJson() = """
    {
      "success": true,
      "data": {
        "tripId": "$TRIP_ID",
        "days": [
          ${dayJson()},
          {"date": "2026-09-09", "dayNumber": 2, "version": 0, "routeStatus": "NOT_CALCULATED", "items": [], "route": null}
        ]
      },
      "meta": {"requestId": "$ITINERARY_REQUEST_ID"}
    }
""".trimIndent()

/** 계약이 정한 error envelope. [details]는 code별 부가 정보 JSON object다. */
internal fun errorJson(code: String, retryable: Boolean = false, details: String = "{}") =
    """{"success":false,"error":{"code":"$code","message":"진단용 설명","retryable":$retryable,"details":$details},""" +
        """"meta":{"requestId":"$ITINERARY_REQUEST_ID"}}"""
