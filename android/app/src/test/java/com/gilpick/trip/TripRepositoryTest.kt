package com.gilpick.trip

import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthResult
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeAuthService
import com.gilpick.auth.FakeSessionCipher
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T013: 여행 생성 요청·응답 매핑 검증.
 *
 * MockWebServer로 실제 Retrofit 왕복을 만들어 계약대로 요청을 보내는지와 `201`·`422`
 * 응답을 어떻게 좁히는지 확인한다.
 */
class TripRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `생성 성공 응답을 여행으로 읽는다`() = withRepository { server, repository ->
        server.enqueue(MockResponse(code = 201, body = createdTripJson()))

        val result = repository.createTrip(
            name = "서울 여행",
            startDate = LocalDate.of(2026, 9, 1),
            endDate = LocalDate.of(2026, 9, 3),
            idempotencyKey = IDEMPOTENCY_KEY,
        )

        val trip = (result as AuthResult.Success).value
        assertEquals(TRIP_ID, trip.tripId)
        assertEquals("서울 여행", trip.name)
        assertEquals(TripStatus.UPCOMING, trip.status)
        assertEquals(3, trip.dayCount)
        assertEquals(1, trip.version)
    }

    @Test
    fun `계약대로 경로와 header, body를 보낸다`() = withRepository { server, repository ->
        server.enqueue(MockResponse(code = 201, body = createdTripJson()))

        repository.createTrip(
            name = "서울 여행",
            startDate = LocalDate.of(2026, 9, 1),
            endDate = LocalDate.of(2026, 9, 3),
            idempotencyKey = IDEMPOTENCY_KEY,
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/trips", request.url.encodedPath)
        assertEquals("Bearer $ACCESS_TOKEN", request.headers["Authorization"])
        assertEquals(IDEMPOTENCY_KEY, request.headers["Idempotency-Key"])
        assertEquals(
            """{"name":"서울 여행","startDate":"2026-09-01","endDate":"2026-09-03"}""",
            request.body?.utf8(),
        )
    }

    @Test
    fun `여행명의 앞뒤 공백을 제거해 보낸다`() = withRepository { server, repository ->
        server.enqueue(MockResponse(code = 201, body = createdTripJson()))

        repository.createTrip(
            name = "  제주  ",
            startDate = LocalDate.of(2026, 9, 1),
            endDate = LocalDate.of(2026, 9, 1),
            idempotencyKey = IDEMPOTENCY_KEY,
        )

        assertTrue(server.takeRequest().body?.utf8()?.contains(""""name":"제주"""") == true)
    }

    @Test
    fun `이름 검증 실패 422를 VALIDATION_ERROR로 읽는다`() = withRepository { server, repository ->
        server.enqueue(
            MockResponse(code = 422, body = errorJson(TripErrorCodes.VALIDATION_ERROR)),
        )

        val result = createTrip(repository)

        val error = (result as AuthResult.Failure).error as AuthError.Server
        assertEquals(TripErrorCodes.VALIDATION_ERROR, error.code)
        assertEquals(422, error.httpStatus)
    }

    @Test
    fun `기간 검증 실패 422를 INVALID_TRIP_PERIOD로 읽는다`() = withRepository { server, repository ->
        server.enqueue(
            MockResponse(code = 422, body = errorJson(TripErrorCodes.INVALID_TRIP_PERIOD)),
        )

        val result = createTrip(repository)

        val error = (result as AuthResult.Failure).error as AuthError.Server
        assertEquals(TripErrorCodes.INVALID_TRIP_PERIOD, error.code)
    }

    @Test
    fun `통신 실패는 Offline으로 좁힌다`() = withRepository { server, repository ->
        // 응답 없이 연결을 끊어 IOException을 만든다.
        server.close()

        val result = createTrip(repository)

        assertTrue((result as AuthResult.Failure).error is AuthError.Offline)
    }

    @Test
    fun `상세 성공 응답을 여행으로 읽는다`() = withRepository { server, repository ->
        server.enqueue(MockResponse(code = 200, body = createdTripJson()))

        val result = repository.getTrip(TRIP_ID)

        val trip = (result as AuthResult.Success).value
        assertEquals(TRIP_ID, trip.tripId)
        assertEquals("서울 여행", trip.name)
        assertEquals(TripStatus.UPCOMING, trip.status)
    }

    @Test
    fun `상세 조회는 계약대로 경로와 header를 보낸다`() = withRepository { server, repository ->
        server.enqueue(MockResponse(code = 200, body = createdTripJson()))

        repository.getTrip(TRIP_ID)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/trips/$TRIP_ID", request.url.encodedPath)
        assertEquals("Bearer $ACCESS_TOKEN", request.headers["Authorization"])
    }

    @Test
    fun `소유하지 않은 여행의 403을 FORBIDDEN으로 좁힌다`() = withRepository { server, repository ->
        server.enqueue(MockResponse(code = 403, body = errorJson(TripErrorCodes.FORBIDDEN)))

        val result = repository.getTrip(TRIP_ID)

        val error = (result as AuthResult.Failure).error
        assertEquals(TripDetailError.FORBIDDEN, error.toDetailError())
    }

    @Test
    fun `없거나 삭제된 여행의 404를 NOT_FOUND로 좁힌다`() = withRepository { server, repository ->
        server.enqueue(MockResponse(code = 404, body = errorJson(TripErrorCodes.TRIP_NOT_FOUND)))

        val result = repository.getTrip(TRIP_ID)

        val error = (result as AuthResult.Failure).error
        assertEquals(TripDetailError.NOT_FOUND, error.toDetailError())
    }

    @Test
    fun `상세 조회의 통신 실패는 NETWORK로 좁힌다`() = withRepository { server, repository ->
        server.close()

        val result = repository.getTrip(TRIP_ID)

        val error = (result as AuthResult.Failure).error
        assertTrue(error is AuthError.Offline)
        assertEquals(TripDetailError.NETWORK, error.toDetailError())
    }

    @Test
    fun `알 수 없는 서버 오류는 UNEXPECTED로 좁힌다`() = withRepository { server, repository ->
        // 계약에 없는 code가 오더라도 화면이 안내할 수 있는 원인으로 떨어져야 한다.
        server.enqueue(MockResponse(code = 500, body = errorJson("INTERNAL_ERROR")))

        val result = repository.getTrip(TRIP_ID)

        val error = (result as AuthResult.Failure).error
        assertEquals(TripDetailError.UNEXPECTED, error.toDetailError())
    }

    private suspend fun createTrip(repository: TripRepository) = repository.createTrip(
        name = "서울 여행",
        startDate = LocalDate.of(2026, 9, 1),
        endDate = LocalDate.of(2026, 9, 3),
        idempotencyKey = IDEMPOTENCY_KEY,
    )

    /** 로그인된 session을 가진 repository와 MockWebServer를 준비한다. */
    private fun withRepository(block: suspend (MockWebServer, TripRepository) -> Unit) = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val store = AuthSessionStore(
                AuthSessionStore.createDataStore(File(tempFolder.root, AuthSessionStore.FILE_NAME)),
                FakeSessionCipher(),
            )
            val auth = AuthRepository(
                store = store,
                api = FakeAuthService,
                appLinkHandler = AuthAppLinkHandler("app.gilpick.example"),
            )
            auth.onSignedIn(
                sessionId = "session-1",
                userId = "user-1",
                nickname = null,
                profileImageUrl = null,
                accessToken = ACCESS_TOKEN,
                refreshToken = "session-1.refresh-token",
                accessExpiresAtEpochSeconds = 3_600,
                refreshExpiresAtEpochSeconds = 2_592_000,
            )
            val api = createTripRetrofit(server.url("/api/v1/").toString())
                .create(TripService::class.java)
            block(server, TripRepository(api = api, auth = auth))
        } finally {
            server.close()
        }
    }

    private fun createdTripJson() = """
        {"success":true,
         "data":{"tripId":"$TRIP_ID","name":"서울 여행","startDate":"2026-09-01",
                 "endDate":"2026-09-03","status":"UPCOMING","dayCount":3,"version":1,
                 "createdAt":"2026-08-28T13:30:00+09:00"},
         "meta":{"requestId":"$REQUEST_ID"}}
    """.trimIndent()

    private fun errorJson(code: String) = """
        {"success":false,
         "error":{"code":"$code","message":"진단용 설명","retryable":false},
         "meta":{"requestId":"$REQUEST_ID"}}
    """.trimIndent()

    private companion object {
        const val TRIP_ID = "33333333-4444-4555-8666-777777777777"
        const val REQUEST_ID = "11111111-2222-4333-8444-555555555555"
        const val ACCESS_TOKEN = "access-token"
        const val IDEMPOTENCY_KEY = "44444444-5555-4666-8777-888888888888"
    }
}
