package com.gilpick.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * `contracts/auth.openapi.yaml`의 공통 성공 envelope.
 *
 * @property success 항상 `true`.
 * @property data endpoint별 payload.
 * @property meta request ID를 담은 공통 metadata.
 */
@Serializable
data class SuccessEnvelope<T>(
    val success: Boolean,
    val data: T,
    val meta: ResponseMeta,
)

/**
 * 공통 오류 envelope.
 *
 * @property success 항상 `false`.
 * @property error 안정적인 error code와 재시도 가능 여부.
 * @property meta request ID를 담은 공통 metadata.
 */
@Serializable
data class ErrorEnvelope(
    val success: Boolean,
    val error: ErrorBody,
    val meta: ResponseMeta,
)

/**
 * @property code 화면 분기에 사용하는 안정적인 error code.
 * @property message 사용자에게 그대로 노출하지 않는 진단용 설명.
 * @property retryable 같은 요청을 재시도해도 되는지 여부.
 */
@Serializable
data class ErrorBody(
    val code: String,
    val message: String,
    val retryable: Boolean,
)

/** @property requestId 서버 log와 응답을 연결하는 ID. */
@Serializable
data class ResponseMeta(
    @SerialName("requestId") val requestId: String,
)

/**
 * 인증 흐름에서 앱이 취할 다음 행동.
 *
 * network 실패와 서버가 확정한 무효 자격을 구분해 오프라인에서 불필요한 로그아웃이
 * 일어나지 않게 한다.
 */
sealed interface AuthError {
    /** 통신 실패. session을 유지하고 재시도한다. */
    data class Offline(val cause: Throwable) : AuthError

    /**
     * 서버가 반환한 오류.
     *
     * @property code `INVALID_REFRESH_TOKEN`, `DEVICE_MISMATCH` 등 계약상의 code.
     * @property retryable 서버가 알린 재시도 가능 여부.
     * @property httpStatus 원래 HTTP 상태 코드.
     */
    data class Server(
        val code: String,
        val retryable: Boolean,
        val httpStatus: Int,
    ) : AuthError

    /** 계약과 다른 응답. 재시도해도 동일하므로 오류로 확정한다. */
    data class Malformed(val cause: Throwable) : AuthError
}

/** 계약에 정의된 인증 error code. 화면·재시도 분기에서 문자열 오타를 막는다. */
object AuthErrorCodes {
    const val INVALID_LOGIN_TICKET = "INVALID_LOGIN_TICKET"
    const val LOGIN_TICKET_EXPIRED = "LOGIN_TICKET_EXPIRED"
    const val INVALID_REFRESH_TOKEN = "INVALID_REFRESH_TOKEN"
    const val TOKEN_EXPIRED = "TOKEN_EXPIRED"
    const val DEVICE_MISMATCH = "DEVICE_MISMATCH"
}

/**
 * 인증 API 호출 결과.
 *
 * Retrofit 예외를 화면까지 전파하지 않고 [AuthError]로 좁혀 다음 행동을 명확히 한다.
 */
sealed interface AuthResult<out T> {
    data class Success<T>(val value: T, val httpStatus: Int) : AuthResult<T>
    data class Failure(val error: AuthError) : AuthResult<Nothing>
}

/** Token·ticket을 다루므로 응답을 캐시하지 않도록 서버가 `no-store`를 보낸다. */
private val authJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** 인증 endpoint 전용 Retrofit 인스턴스를 만든다. */
fun createAuthRetrofit(
    baseUrl: String,
    client: OkHttpClient = OkHttpClient(),
): Retrofit = Retrofit.Builder()
    .baseUrl(baseUrl)
    .client(client)
    .addConverterFactory(authJson.asConverterFactory("application/json".toMediaType()))
    .build()

/**
 * Retrofit 응답을 [AuthResult]로 변환한다.
 *
 * 오류 body가 계약을 따르면 code와 `retryable`을 보존하고, 그렇지 않으면
 * [AuthError.Malformed]로 확정한다. 어떤 경우에도 Token 원문을 message에 담지 않는다.
 */
fun <T> retrofit2.Response<SuccessEnvelope<T>>.toAuthResult(): AuthResult<T> {
    val body = body()
    if (isSuccessful && body != null) {
        return AuthResult.Success(body.data, code())
    }
    val raw = errorBody()?.string()
    if (raw.isNullOrBlank()) {
        return AuthResult.Failure(
            AuthError.Malformed(IllegalStateException("HTTP ${code()} 응답 body가 없습니다")),
        )
    }
    return try {
        val error = authJson.decodeFromString<ErrorEnvelope>(raw).error
        AuthResult.Failure(AuthError.Server(error.code, error.retryable, code()))
    } catch (e: kotlinx.serialization.SerializationException) {
        AuthResult.Failure(AuthError.Malformed(e))
    }
}
