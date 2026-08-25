package com.gilpick.auth

import kotlinx.serialization.Serializable
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
    }
}
