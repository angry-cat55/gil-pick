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
import org.junit.Assert.assertFalse
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

    // --- T036: 수정 ---

    @Test
    fun `수정 성공 응답을 여행으로 읽는다`() = withRepository { server, repository ->
        server.enqueue(MockResponse(code = 200, body = createdTripJson()))

        val result = repository.updateTrip(tripId = TRIP_ID, version = 1, name = "부산 여행")

        assertEquals(TRIP_ID, (result as AuthResult.Success).value.tripId)
    }

    @Test
    fun `수정은 계약대로 경로와 header, version을 보낸다`() = withRepository { server, repository ->
        server.enqueue(MockResponse(code = 200, body = createdTripJson()))

        repository.updateTrip(
            tripId = TRIP_ID,
            version = 7,
            name = "부산 여행",
            startDate = LocalDate.of(2026, 9, 1),
            endDate = LocalDate.of(2026, 9, 3),
        )

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/v1/trips/$TRIP_ID", request.url.encodedPath)
        assertEquals("Bearer $ACCESS_TOKEN", request.headers["Authorization"])

        val body = request.body?.utf8().orEmpty()
        // 계약이 version을 required로 정의한다. 빠지면 서버가 요청을 거절한다.
        assertTrue(body.contains(""""version":7"""))
        assertTrue(body.contains(""""name":"부산 여행""""))
        assertTrue(body.contains(""""startDate":"2026-09-01""""))
        assertTrue(body.contains(""""endDate":"2026-09-03""""))
    }

    @Test
    fun `이름만 바꿀 때는 기간을 보내지 않는다`() = withRepository { server, repository ->
        // 완료된 여행은 이름만 수정할 수 있다(FR-010a). 기간을 함께 보내면 서버가
        // TRIP_LOCKED로 거절한다.
        server.enqueue(MockResponse(code = 200, body = createdTripJson()))

        repository.updateTrip(tripId = TRIP_ID, version = 1, name = "이름만 변경")

        val body = server.takeRequest().body?.utf8().orEmpty()
        assertFalse(body.contains("startDate"))
        assertFalse(body.contains("endDate"))
    }

    @Test
    fun `수정도 여행명의 앞뒤 공백을 제거해 보낸다`() = withRepository { server, repository ->
        server.enqueue(MockResponse(code = 200, body = createdTripJson()))

        repository.updateTrip(tripId = TRIP_ID, version = 1, name = "   부산 여행   ")

        assertTrue(server.takeRequest().body?.utf8().orEmpty().contains(""""name":"부산 여행""""))
    }

    @Test
    fun `409 VERSION_CONFLICT를 재조회 안내로 좁힌다`() = withRepository { server, repository ->
        server.enqueue(
            MockResponse(code = 409, body = errorJson(TripErrorCodes.VERSION_CONFLICT)),
        )

        val result = repository.updateTrip(tripId = TRIP_ID, version = 1, name = "부산 여행")

        val error = (result as AuthResult.Failure).error
        assertEquals(TripFormSubmitError.VERSION_CONFLICT, error.toSubmitError())
    }

    @Test
    fun `409 TRIP_LOCKED를 기간 잠금 오류로 좁힌다`() = withRepository { server, repository ->
        server.enqueue(MockResponse(code = 409, body = errorJson(TripErrorCodes.TRIP_LOCKED)))

        val result = repository.updateTrip(
            tripId = TRIP_ID,
            version = 1,
            startDate = LocalDate.of(2026, 9, 1),
            endDate = LocalDate.of(2026, 9, 3),
        )

        val error = (result as AuthResult.Failure).error
        assertEquals(TripFormSubmitError.TRIP_LOCKED, error.toSubmitError())
    }

    @Test
    fun `409 CONFIRMATION_REQUIRED를 확인 필요 오류로 좁힌다`() = withRepository { server, repository ->
        // 같은 409라도 code가 다르면 사용자에게 할 말이 다르다. 상태 코드로는 갈라지지
        // 않으므로 code로 판정한다.
        server.enqueue(
            MockResponse(code = 409, body = errorJson(TripErrorCodes.CONFIRMATION_REQUIRED)),
        )

        val result = repository.updateTrip(tripId = TRIP_ID, version = 1, name = "부산 여행")

        val error = (result as AuthResult.Failure).error
        assertEquals(TripFormSubmitError.CONFIRMATION_REQUIRED, error.toSubmitError())
    }

    @Test
    fun `삭제 확인 동의는 요청에 담아 보낸다`() = withRepository { server, repository ->
        // F002에는 일정이 없어 실제로 쓰이지 않지만 계약이 정의한 값이다.
        server.enqueue(MockResponse(code = 200, body = createdTripJson()))

        repository.updateTrip(
            tripId = TRIP_ID,
            version = 1,
            startDate = LocalDate.of(2026, 9, 1),
            endDate = LocalDate.of(2026, 9, 2),
            confirmDeleteOutOfRangeItems = true,
        )

        assertTrue(
            server.takeRequest().body?.utf8().orEmpty()
                .contains(""""confirmDeleteOutOfRangeItems":true"""),
        )
    }

    @Test
    fun `수정 검증 실패 422는 입력 오류로 좁힌다`() = withRepository { server, repository ->
        server.enqueue(MockResponse(code = 422, body = errorJson(TripErrorCodes.VALIDATION_ERROR)))

        val result = repository.updateTrip(tripId = TRIP_ID, version = 1, name = "가")

        val error = (result as AuthResult.Failure).error
        assertEquals(TripFormSubmitError.INVALID_INPUT, error.toSubmitError())
    }

    @Test
    fun `수정의 통신 실패는 network로 좁힌다`() = withRepository { server, repository ->
        server.close()

        val result = repository.updateTrip(tripId = TRIP_ID, version = 1, name = "부산 여행")

        val error = (result as AuthResult.Failure).error
        assertEquals(TripFormSubmitError.NETWORK, error.toSubmitError())
    }

    // --- 삭제 (T043) ---

    @Test
    fun `삭제 성공 204를 성공으로 읽는다`() = withRepository { server, repository ->
        server.enqueue(MockResponse(code = 204))

        val result = repository.deleteTrip(TRIP_ID)

        assertEquals(204, (result as AuthResult.Success).httpStatus)
    }

    @Test
    fun `삭제는 계약대로 경로와 header를 보낸다`() = withRepository { server, repository ->
        server.enqueue(MockResponse(code = 204))

        repository.deleteTrip(TRIP_ID)

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/v1/trips/$TRIP_ID", request.url.encodedPath)
        assertEquals("Bearer $ACCESS_TOKEN", request.headers["Authorization"])
    }

    @Test
    fun `완료 상태 여행도 204로 삭제된다`() = withRepository { server, repository ->
        // 상태는 서버가 판정하므로 앱은 상태별로 다른 요청을 보내지 않는다. 완료 여행의
        // 기간 수정만 409 TRIP_LOCKED로 막히고 삭제는 막히지 않는다(spec.md FR-014).
        server.enqueue(MockResponse(code = 204))

        val result = repository.deleteTrip(TRIP_ID)

        assertTrue(result is AuthResult.Success)
    }

    @Test
    fun `삭제의 소유권 위반 403을 FORBIDDEN으로 좁힌다`() = withRepository { server, repository ->
        server.enqueue(MockResponse(code = 403, body = errorJson(TripErrorCodes.FORBIDDEN)))

        val result = repository.deleteTrip(TRIP_ID)

        val error = (result as AuthResult.Failure).error
        assertEquals(TripDeleteError.FORBIDDEN, error.toDeleteError())
    }

    @Test
    fun `삭제의 미존재 404를 NOT_FOUND로 좁힌다`() = withRepository { server, repository ->
        server.enqueue(MockResponse(code = 404, body = errorJson(TripErrorCodes.TRIP_NOT_FOUND)))

        val result = repository.deleteTrip(TRIP_ID)

        val error = (result as AuthResult.Failure).error
        assertEquals(TripDeleteError.NOT_FOUND, error.toDeleteError())
    }

    @Test
    fun `반복 삭제도 204면 같은 성공으로 읽는다`() = withRepository { server, repository ->
        // 계약은 이미 삭제된 여행의 재삭제도 204로 정의한다(spec.md FR-016 멱등).
        server.enqueue(MockResponse(code = 204))
        server.enqueue(MockResponse(code = 204))

        val first = repository.deleteTrip(TRIP_ID)
        val second = repository.deleteTrip(TRIP_ID)

        assertTrue(first is AuthResult.Success)
        assertTrue(second is AuthResult.Success)
    }

    @Test
    fun `삭제의 통신 실패는 NETWORK로 좁힌다`() = withRepository { server, repository ->
        server.close()

        val result = repository.deleteTrip(TRIP_ID)

        val error = (result as AuthResult.Failure).error
        assertEquals(TripDeleteError.NETWORK, error.toDeleteError())
    }

    @Test
    fun `삭제의 알 수 없는 서버 오류는 UNEXPECTED로 좁힌다`() = withRepository { server, repository ->
        server.enqueue(MockResponse(code = 500, body = errorJson("INTERNAL_ERROR")))

        val result = repository.deleteTrip(TRIP_ID)

        val error = (result as AuthResult.Failure).error
        assertEquals(TripDeleteError.UNEXPECTED, error.toDeleteError())
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
