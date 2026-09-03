package com.gilpick.place

import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthResult
import com.gilpick.auth.toAuthResult
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T008: 장소 계약의 enum, nullable, pagination, 오류 envelope parsing 검증.
 *
 * MockWebServer로 실제 Retrofit 왕복을 만들어 DTO가 Backend가 보낼 JSON을 그대로
 * 받는지 확인한다. 계약 값은 `contracts/places.openapi.yaml`을 따른다.
 */
class PlaceApiTest {

    @Test
    fun `검색 응답의 enum과 pagination을 그대로 받는다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = searchJson()))

        val response = api.searchPlaces(BEARER, query = "경주", category = PlaceCategory.NATURE)

        val body = response.body()!!
        assertEquals(2, body.data.items.size)
        assertEquals("next-cursor", body.meta.pagination.nextCursor)
        assertTrue(body.meta.pagination.hasNext)
        assertEquals(REQUEST_ID, body.meta.requestId)

        val tour = body.data.items[0]
        assertEquals("tourapi:126508", tour.placeId)
        assertEquals(PlaceSource.TOUR_API, tour.source)
        assertEquals(PlaceCategory.NATURE, tour.category)
        assertEquals(120, tour.recommendedStayMinutes)
        assertEquals("A01", tour.tourApiCategory?.large)
        assertNull(tour.tourApiCategory?.small)
        assertEquals(listOf("월요일 09:00~18:00"), tour.regularOpeningHours)
        assertEquals(listOf("Google 제공"), tour.googleAttributions)
        assertEquals(PlaceBusinessStatus.OPERATIONAL, tour.businessStatus)
    }

    @Test
    fun `nullable field가 모두 null인 항목도 받는다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = searchJson()))

        val sparse = api.searchPlaces(BEARER, query = "경주").body()!!.data.items[1]

        assertEquals("google:ChIJ_abc-123", sparse.placeId)
        assertEquals(PlaceSource.GOOGLE_PLACES, sparse.source)
        assertEquals(PlaceCategory.CAFE, sparse.category)
        assertNull(sparse.tourApiCategory)
        assertNull(sparse.address)
        assertNull(sparse.latitude)
        assertNull(sparse.imageUrl)
        assertNull(sparse.rating)
        assertNull(sparse.userRatingCount)
        assertNull(sparse.businessStatus)
        assertNull(sparse.regularOpeningHours)
        assertNull(sparse.googleAttributions)
    }

    @Test
    fun `마지막 페이지는 nextCursor가 null이고 hasNext가 false다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = lastPageJson()))

        val pagination = api.searchPlaces(BEARER, query = "경주").body()!!.meta.pagination

        assertNull(pagination.nextCursor)
        assertEquals(false, pagination.hasNext)
    }

    @Test
    fun `검색 조건을 계약대로 query parameter에 실어 보낸다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = lastPageJson()))

        api.searchPlaces(
            BEARER,
            query = "성산 일출봉",
            category = PlaceCategory.HISTORY_CULTURE,
            areaCode = "39",
            cursor = "prev-cursor",
            limit = 20,
        )

        val url = server.takeRequest().url
        assertEquals("성산 일출봉", url.queryParameter("query"))
        assertEquals("HISTORY_CULTURE", url.queryParameter("category"))
        assertEquals("39", url.queryParameter("areaCode"))
        assertEquals("prev-cursor", url.queryParameter("cursor"))
        assertEquals("20", url.queryParameter("limit"))
    }

    @Test
    fun `보내지 않은 검색 조건은 query parameter에서 생략된다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = lastPageJson()))

        api.searchPlaces(BEARER, category = PlaceCategory.FOOD)

        val url = server.takeRequest().url
        assertNull(url.queryParameter("query"))
        assertNull(url.queryParameter("areaCode"))
        assertNull(url.queryParameter("cursor"))
    }

    @Test
    fun `상세 응답의 상세 전용 field를 받는다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = detailJson()))

        val place = api.getPlace(BEARER, "tourapi:126508").body()!!.data

        assertEquals("신라의 왕경이 있던 곳이다.", place.description)
        assertEquals("054-000-0000", place.phone)
        assertEquals("연중 개방", place.operatingGuide)
        assertEquals(PlaceCategory.HISTORY_CULTURE, place.category)
    }

    @Test
    fun `검색 결과에는 상세 전용 field가 없어도 parsing된다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = lastPageJson()))

        val summary = api.searchPlaces(BEARER, query = "경주").body()!!.data.items[0]

        assertNull(summary.description)
        assertNull(summary.phone)
        assertNull(summary.operatingGuide)
    }

    /**
     * `placeId`의 `:`가 경로에 그대로 실려야 server가 provider를 판단할 수 있다.
     *
     * Retrofit이 이 문자를 percent-encode하면 `tourapi%3A126508`이 되어 계약의
     * `placeId` 패턴과 어긋난다.
     */
    @Test
    fun `placeId의 prefix 구분자를 경로에 그대로 전달한다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = detailJson()))

        api.getPlace(BEARER, "tourapi:126508")

        assertEquals("/api/v1/places/tourapi:126508", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `google 상세 실패는 GOOGLE_PLACES code와 retryable을 보존한다`() = withService { server, api ->
        server.enqueue(
            MockResponse(
                code = 502,
                body = errorJson(PlaceErrorCodes.GOOGLE_PLACES_FAILED, retryable = true),
            ),
        )

        val result = api.getPlace(BEARER, "google:ChIJ_abc-123").toAuthResult()

        val error = (result as AuthResult.Failure).error as AuthError.Server
        assertEquals(PlaceErrorCodes.GOOGLE_PLACES_FAILED, error.code)
        assertEquals(502, error.httpStatus)
        assertTrue(error.retryable)
    }

    @Test
    fun `호출 한도 초과는 retryable false로 전달된다`() = withService { server, api ->
        server.enqueue(
            MockResponse(
                code = 429,
                body = errorJson(PlaceErrorCodes.TOUR_API_RATE_LIMITED, retryable = false),
            ),
        )

        val result = api.searchPlaces(BEARER, query = "경주").toAuthResult { it.data.items }

        val error = (result as AuthResult.Failure).error as AuthError.Server
        assertEquals(PlaceErrorCodes.TOUR_API_RATE_LIMITED, error.code)
        assertEquals(429, error.httpStatus)
        assertEquals(false, error.retryable)
    }

    @Test
    fun `인증 실패는 F001 공통 401 code로 전달된다`() = withService { server, api ->
        server.enqueue(
            MockResponse(
                code = 401,
                body = errorJson(PlaceErrorCodes.INVALID_ACCESS_TOKEN, retryable = false),
            ),
        )

        val result = api.searchPlaces(BEARER, query = "경주").toAuthResult { it.data.items }

        val error = (result as AuthResult.Failure).error as AuthError.Server
        assertEquals(PlaceErrorCodes.INVALID_ACCESS_TOKEN, error.code)
        assertEquals(401, error.httpStatus)
    }

    @Test
    fun `계약과 다른 오류 body는 Malformed로 확정한다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 502, body = """{"detail":"Bad Gateway"}"""))

        val result = api.searchPlaces(BEARER, query = "경주").toAuthResult { it.data.items }

        assertTrue((result as AuthResult.Failure).error is AuthError.Malformed)
    }

    /** MockWebServer에 연결된 [PlaceService]를 준비한다. */
    private fun withService(block: suspend (MockWebServer, PlaceService) -> Unit) = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val api = createPlaceRetrofit(server.url("/api/v1/").toString())
                .create(PlaceService::class.java)
            block(server, api)
        } finally {
            server.close()
        }
    }

    private companion object {
        const val BEARER = "Bearer access-token"
        const val REQUEST_ID = "req-place-1"

        /** 값이 모두 채워진 TourAPI 항목과 nullable이 모두 null인 Google 항목. */
        fun searchJson() = """
            {
              "success": true,
              "data": {
                "items": [
                  {
                    "placeId": "tourapi:126508",
                    "source": "TOUR_API",
                    "sourcePlaceId": "126508",
                    "name": "불국사",
                    "category": "NATURE",
                    "tourApiCategory": {"large": "A01", "middle": "A0101", "small": null},
                    "address": "경북 경주시",
                    "latitude": 35.7900,
                    "longitude": 129.3320,
                    "imageUrl": "https://example.test/a.jpg",
                    "recommendedStayMinutes": 120,
                    "rating": 4.6,
                    "userRatingCount": 1200,
                    "businessStatus": "OPERATIONAL",
                    "regularOpeningHours": ["월요일 09:00~18:00"],
                    "currentOpeningHours": ["오늘 09:00~18:00"],
                    "googleAttributions": ["Google 제공"]
                  },
                  {
                    "placeId": "google:ChIJ_abc-123",
                    "source": "GOOGLE_PLACES",
                    "sourcePlaceId": "ChIJ_abc-123",
                    "name": "이름만 있는 카페",
                    "category": "CAFE",
                    "tourApiCategory": null,
                    "address": null,
                    "latitude": null,
                    "longitude": null,
                    "imageUrl": null,
                    "recommendedStayMinutes": 60,
                    "rating": null,
                    "userRatingCount": null,
                    "businessStatus": null,
                    "regularOpeningHours": null,
                    "currentOpeningHours": null,
                    "googleAttributions": null
                  }
                ]
              },
              "meta": {
                "requestId": "$REQUEST_ID",
                "pagination": {"nextCursor": "next-cursor", "hasNext": true}
              }
            }
        """.trimIndent()

        fun lastPageJson() = """
            {
              "success": true,
              "data": {
                "items": [
                  {
                    "placeId": "tourapi:1",
                    "source": "TOUR_API",
                    "sourcePlaceId": "1",
                    "name": "마지막 장소",
                    "category": "OTHER",
                    "tourApiCategory": null,
                    "address": null,
                    "latitude": null,
                    "longitude": null,
                    "imageUrl": null,
                    "recommendedStayMinutes": 60,
                    "rating": null,
                    "userRatingCount": null,
                    "businessStatus": null,
                    "regularOpeningHours": null,
                    "currentOpeningHours": null,
                    "googleAttributions": null
                  }
                ]
              },
              "meta": {
                "requestId": "$REQUEST_ID",
                "pagination": {"nextCursor": null, "hasNext": false}
              }
            }
        """.trimIndent()

        fun detailJson() = """
            {
              "success": true,
              "data": {
                "placeId": "tourapi:126508",
                "source": "TOUR_API",
                "sourcePlaceId": "126508",
                "name": "경주 역사유적지구",
                "category": "HISTORY_CULTURE",
                "tourApiCategory": {"large": "A02", "middle": null, "small": null},
                "address": "경북 경주시",
                "latitude": 35.8320,
                "longitude": 129.2250,
                "imageUrl": null,
                "recommendedStayMinutes": 90,
                "rating": null,
                "userRatingCount": null,
                "businessStatus": null,
                "regularOpeningHours": null,
                "currentOpeningHours": null,
                "googleAttributions": null,
                "description": "신라의 왕경이 있던 곳이다.",
                "phone": "054-000-0000",
                "operatingGuide": "연중 개방"
              },
              "meta": {"requestId": "$REQUEST_ID"}
            }
        """.trimIndent()

        fun errorJson(code: String, retryable: Boolean) = """
            {
              "success": false,
              "error": {"code": "$code", "message": "진단용 설명", "retryable": $retryable},
              "meta": {"requestId": "$REQUEST_ID"}
            }
        """.trimIndent()
    }
}
