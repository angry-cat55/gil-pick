package com.gilpick.auth

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/** T011: OpenAPI 공통 success/error envelope parsing과 오류 mapping 검증. */
class AuthApiTest {

    @Serializable
    private data class Payload(val accessToken: String, val expiresIn: Int)

    @Test
    fun `성공 envelope의 data와 상태 코드를 그대로 전달한다`() {
        val body = SuccessEnvelope(
            success = true,
            data = Payload(accessToken = "access", expiresIn = 3_600),
            meta = ResponseMeta(requestId = REQUEST_ID),
        )

        val result = Response.success(201, body).toAuthResult()

        val success = result as AuthResult.Success
        assertEquals(201, success.httpStatus)
        assertEquals("access", success.value.accessToken)
        assertEquals(3_600, success.value.expiresIn)
    }

    @Test
    fun `오류 envelope의 code와 retryable을 보존한다`() {
        val result = errorResult(
            status = 401,
            json = errorJson(AuthErrorCodes.INVALID_REFRESH_TOKEN, retryable = false),
        )

        val error = (result as AuthResult.Failure).error as AuthError.Server
        assertEquals(AuthErrorCodes.INVALID_REFRESH_TOKEN, error.code)
        assertEquals(401, error.httpStatus)
        assertEquals(false, error.retryable)
    }

    @Test
    fun `기기 불일치는 403 DEVICE_MISMATCH로 전달된다`() {
        val result = errorResult(
            status = 403,
            json = errorJson(AuthErrorCodes.DEVICE_MISMATCH, retryable = false),
        )

        val error = (result as AuthResult.Failure).error as AuthError.Server
        assertEquals(AuthErrorCodes.DEVICE_MISMATCH, error.code)
        assertEquals(403, error.httpStatus)
    }

    @Test
    fun `재시도 가능한 서버 오류의 retryable을 보존한다`() {
        val result = errorResult(
            status = 500,
            json = errorJson("KAKAO_API_TIMEOUT", retryable = true),
        )

        val error = (result as AuthResult.Failure).error as AuthError.Server
        assertTrue(error.retryable)
    }

    @Test
    fun `계약과 다른 오류 body는 Malformed로 확정한다`() {
        val result = errorResult(status = 500, json = "<html>gateway error</html>")

        assertTrue((result as AuthResult.Failure).error is AuthError.Malformed)
    }

    @Test
    fun `body 없는 오류 응답도 Malformed로 확정한다`() {
        val result = errorResult(status = 500, json = "")

        assertTrue((result as AuthResult.Failure).error is AuthError.Malformed)
    }

    @Test
    fun `알 수 없는 필드가 있어도 성공 envelope를 읽는다`() {
        val json = """
            {"success":true,"data":{"accessToken":"access","expiresIn":3600,"unknown":1},
             "meta":{"requestId":"$REQUEST_ID"},"extra":"ignored"}
        """.trimIndent()

        val body = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<SuccessEnvelope<Payload>>(json)

        assertEquals("access", body.data.accessToken)
        assertEquals(REQUEST_ID, body.meta.requestId)
    }

    // --- T032: 실제 HTTP 왕복으로 확인하는 갱신·로그아웃 계약 ---

    @Test
    fun `갱신 요청은 계약대로 Refresh Token과 deviceId를 보낸다`() = withServer { server, service ->
        server.enqueue(
            MockResponse(
                code = 200,
                body = """
                    {"success":true,
                     "data":{"accessToken":"access-2","expiresIn":3600,
                             "refreshToken":"session-2.$SECRET","refreshExpiresIn":2592000},
                     "meta":{"requestId":"$REQUEST_ID"}}
                """.trimIndent(),
            ),
        )

        val result = service.refreshTokens(RefreshTokenRequest(REFRESH_TOKEN, DEVICE_ID)).toAuthResult()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/auth/token/refresh", request.url.encodedPath)
        assertEquals(
            """{"refreshToken":"$REFRESH_TOKEN","deviceId":"$DEVICE_ID"}""",
            request.body?.utf8(),
        )
        val data = (result as AuthResult.Success).value
        assertEquals("access-2", data.accessToken)
        assertEquals("session-2.$SECRET", data.refreshToken)
        assertEquals(3_600, data.expiresIn)
        assertEquals(2_592_000, data.refreshExpiresIn)
    }

    @Test
    fun `만료된 Refresh Token 응답을 TOKEN_EXPIRED로 읽는다`() = withServer { server, service ->
        server.enqueue(MockResponse(code = 401, body = errorJson(AuthErrorCodes.TOKEN_EXPIRED, false)))

        val result = service.refreshTokens(RefreshTokenRequest(REFRESH_TOKEN, DEVICE_ID)).toAuthResult()

        val error = (result as AuthResult.Failure).error as AuthError.Server
        assertEquals(AuthErrorCodes.TOKEN_EXPIRED, error.code)
        assertEquals(401, error.httpStatus)
    }

    @Test
    fun `무효한 Refresh Token 응답을 INVALID_REFRESH_TOKEN으로 읽는다`() = withServer { server, service ->
        server.enqueue(
            MockResponse(code = 401, body = errorJson(AuthErrorCodes.INVALID_REFRESH_TOKEN, false)),
        )

        val result = service.refreshTokens(RefreshTokenRequest(REFRESH_TOKEN, DEVICE_ID)).toAuthResult()

        val error = (result as AuthResult.Failure).error as AuthError.Server
        assertEquals(AuthErrorCodes.INVALID_REFRESH_TOKEN, error.code)
    }

    @Test
    fun `기기 불일치 응답을 DEVICE_MISMATCH로 읽는다`() = withServer { server, service ->
        server.enqueue(MockResponse(code = 403, body = errorJson(AuthErrorCodes.DEVICE_MISMATCH, false)))

        val result = service.refreshTokens(RefreshTokenRequest(REFRESH_TOKEN, DEVICE_ID)).toAuthResult()

        val error = (result as AuthResult.Failure).error as AuthError.Server
        assertEquals(AuthErrorCodes.DEVICE_MISMATCH, error.code)
        assertEquals(403, error.httpStatus)
    }

    @Test
    fun `로그아웃 204는 body 없이 성공으로 읽는다`() = withServer { server, service ->
        server.enqueue(MockResponse(code = 204))

        val result = service.logout(RefreshTokenRequest(REFRESH_TOKEN, DEVICE_ID)).toEmptyAuthResult()

        assertEquals("/api/v1/auth/logout", server.takeRequest().url.encodedPath)
        assertEquals(204, (result as AuthResult.Success).httpStatus)
    }

    @Test
    fun `로그아웃 실패 응답의 code를 보존한다`() = withServer { server, service ->
        server.enqueue(MockResponse(code = 403, body = errorJson(AuthErrorCodes.DEVICE_MISMATCH, false)))

        val result = service.logout(RefreshTokenRequest(REFRESH_TOKEN, DEVICE_ID)).toEmptyAuthResult()

        val error = (result as AuthResult.Failure).error as AuthError.Server
        assertEquals(AuthErrorCodes.DEVICE_MISMATCH, error.code)
    }

    /** MockWebServer와 실제 Retrofit 구현으로 한 왕복을 검증한다. */
    private fun withServer(block: suspend (MockWebServer, AuthService) -> Unit) = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val service = createAuthRetrofit(server.url("/api/v1/").toString())
                .create(AuthService::class.java)
            block(server, service)
        } finally {
            server.close()
        }
    }

    private fun errorResult(status: Int, json: String): AuthResult<Payload> =
        Response.error<SuccessEnvelope<Payload>>(
            status,
            json.toResponseBody("application/json".toMediaType()),
        ).toAuthResult()

    private fun errorJson(code: String, retryable: Boolean) = """
        {"success":false,
         "error":{"code":"$code","message":"진단용 설명","details":{},"retryable":$retryable},
         "meta":{"requestId":"$REQUEST_ID"}}
    """.trimIndent()

    private companion object {
        const val REQUEST_ID = "11111111-2222-3333-4444-555555555555"
        const val DEVICE_ID = "22222222-3333-4444-8555-666666666666"
        const val SECRET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"
        const val REFRESH_TOKEN = "session-1.$SECRET"
    }
}
