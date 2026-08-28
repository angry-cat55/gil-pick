package com.gilpick.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

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

    /**
     * Backend가 App Link redirect의 query error로 알린 provider 단계 실패.
     *
     * JSON 응답이 아니라 redirect로 도착하므로 HTTP 상태 코드가 없다.
     *
     * @property code `KAKAO_API_TIMEOUT`, `ACCESS_DENIED` 등 계약상의 code.
     * @property retryable 같은 인증 시도를 그대로 다시 요청해도 되는지 여부.
     */
    data class Callback(val code: String, val retryable: Boolean) : AuthError
}

/** 계약에 정의된 인증 error code. 화면·재시도 분기에서 문자열 오타를 막는다. */
object AuthErrorCodes {
    const val INVALID_LOGIN_TICKET = "INVALID_LOGIN_TICKET"
    const val LOGIN_TICKET_EXPIRED = "LOGIN_TICKET_EXPIRED"
    const val INVALID_REFRESH_TOKEN = "INVALID_REFRESH_TOKEN"
    const val TOKEN_EXPIRED = "TOKEN_EXPIRED"
    const val DEVICE_MISMATCH = "DEVICE_MISMATCH"

    // Backend가 App Link redirect의 query error로 전달하는 provider 단계 실패.
    const val ACCESS_DENIED = "ACCESS_DENIED"
    const val INVALID_AUTHORIZATION_CODE = "INVALID_AUTHORIZATION_CODE"
    const val KAKAO_AUTH_FAILED = "KAKAO_AUTH_FAILED"
    const val KAKAO_RATE_LIMITED = "KAKAO_RATE_LIMITED"
    const val KAKAO_API_FAILED = "KAKAO_API_FAILED"
    const val KAKAO_API_TIMEOUT = "KAKAO_API_TIMEOUT"
    const val LOGIN_TRANSACTION_EXPIRED = "LOGIN_TRANSACTION_EXPIRED"

    /**
     * callback error code가 같은 인증 시도를 그대로 다시 요청해도 되는 실패인지 알린다.
     *
     * provider 일시 장애만 재시도 대상이다. 동의 거절, 인가 코드 실패, transaction 만료는
     * 새 Kakao 인증이 필요하므로 자동 재시도하지 않는다.
     */
    private val RETRYABLE_CALLBACK_ERRORS = setOf(
        KAKAO_RATE_LIMITED,
        KAKAO_API_FAILED,
        KAKAO_API_TIMEOUT,
    )

    /** callback error code를 재시도 가능 여부와 함께 [AuthError.Callback]으로 옮긴다. */
    fun toCallbackError(code: String): AuthError.Callback =
        AuthError.Callback(code, retryable = code in RETRYABLE_CALLBACK_ERRORS)
}

/**
 * 인증 API 호출 결과.
 *
 * Retrofit 예외를 화면까지 전파하지 않고 [AuthError]로 좁혀 다음 행동을 명확히 한다.
 */
sealed interface AuthResult<out T> {

    /**
     * 계약을 만족하는 응답을 받았다.
     *
     * @property value endpoint별 payload.
     * @property httpStatus 신규 `201`과 기존 `200`을 구분해야 하므로 그대로 보존한다.
     */
    data class Success<T>(val value: T, val httpStatus: Int) : AuthResult<T>

    /**
     * 요청이 실패했다.
     *
     * @property error 다음 행동을 정하기 위해 좁힌 실패 원인.
     */
    data class Failure(val error: AuthError) : AuthResult<Nothing>
}

/** Token·ticket을 다루므로 응답을 캐시하지 않도록 서버가 `no-store`를 보낸다. */
private val authJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    // 계약이 required로 정의한 값은 Kotlin 기본값과 같아도 요청 body에 실어야 한다.
    encodeDefaults = true
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
fun <T> retrofit2.Response<SuccessEnvelope<T>>.toAuthResult(): AuthResult<T> =
    toAuthResult { it.data }

/**
 * 공통 envelope가 아닌 응답도 같은 오류 규칙으로 [AuthResult]에 담는다.
 *
 * 목록 endpoint처럼 `meta`에 pagination이 붙어 [SuccessEnvelope]로 표현할 수 없는
 * 응답이 있다. 성공 body에서 필요한 값을 꺼내는 방법만 [extract]로 받고, 실패 처리는
 * 모든 endpoint가 같은 규칙을 쓰도록 여기에 모아 둔다.
 *
 * @param extract 성공 body에서 화면·repository가 쓸 값을 꺼낸다.
 */
fun <B, T> retrofit2.Response<B>.toAuthResult(extract: (B) -> T): AuthResult<T> {
    val body = body()
    if (isSuccessful && body != null) {
        return AuthResult.Success(extract(body), code())
    }
    return toAuthFailure()
}

/**
 * body 없는 성공 응답을 [AuthResult]로 변환한다.
 *
 * logout은 계약상 `204`라 payload가 없으므로 상태 코드만 성공 여부로 쓴다.
 */
fun retrofit2.Response<Unit>.toEmptyAuthResult(): AuthResult<Unit> =
    if (isSuccessful) AuthResult.Success(Unit, code()) else toAuthFailure()

/** 오류 응답 body를 계약상의 code·`retryable`로 옮긴다. */
private fun retrofit2.Response<*>.toAuthFailure(): AuthResult.Failure {
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

// --- US1 로그인 계약 DTO ---

/**
 * `POST /auth/kakao/transactions` 요청.
 *
 * @property deviceId 이 설치의 기기 ID. 발급될 ticket과 session이 이 값에 결합된다.
 * @property platform 계약상 Android는 항상 `ANDROID`다.
 */
@Serializable
data class CreateLoginTransactionRequest(
    val deviceId: String,
    val platform: String = "ANDROID",
)

/**
 * 로그인 transaction 생성 결과.
 *
 * @property transactionId 서버가 부여한 transaction 식별자.
 * @property authorizationUrl Custom Tab으로 열 Kakao 인증 URL.
 * @property expiresIn transaction 유효 시간(초). 계약상 600이다.
 */
@Serializable
data class LoginTransactionData(
    val transactionId: String,
    val authorizationUrl: String,
    val expiresIn: Int,
)

/**
 * `POST /auth/kakao/exchange` 요청.
 *
 * @property loginTicket App Link URI fragment로 받은 일회용 ticket.
 * @property deviceId ticket 발급에 사용한 것과 같은 기기 ID.
 */
@Serializable
data class LoginTicketExchangeRequest(
    val loginTicket: String,
    val deviceId: String,
)

/**
 * 로그인한 사용자 요약.
 *
 * @property nickname 카카오 미동의 시 `null`이다.
 * @property profileImageUrl 카카오 미동의 시 `null`이다.
 */
@Serializable
data class UserSummary(
    val userId: String,
    val nickname: String? = null,
    val profileImageUrl: String? = null,
    val provider: String,
)

/**
 * 로그인 성공 시 발급되는 Token pair와 사용자 정보.
 *
 * @property expiresIn Access Token 유효 시간(초). 계약상 3600이다.
 * @property refreshExpiresIn Refresh Token 유효 시간(초). 계약상 2592000이다.
 */
@Serializable
data class AuthTokenData(
    val accessToken: String,
    val expiresIn: Int,
    val refreshToken: String,
    val refreshExpiresIn: Int,
    val user: UserSummary,
)

// --- US2·US3 갱신·로그아웃 계약 DTO ---

/**
 * `POST /auth/token/refresh` 요청. 계약상 `POST /auth/logout` 요청과 같은 형식이다.
 *
 * logout은 Bearer Access Token 없이 이 body만으로 durable retry할 수 있어야 하므로,
 * 폐기 대기 항목도 이 두 값만 보관한다.
 *
 * @property refreshToken 현재 기기의 Refresh Token.
 * @property deviceId Token을 발급받은 기기 ID. 서버가 기기 일치를 확인한다.
 */
@Serializable
data class RefreshTokenRequest(
    val refreshToken: String,
    val deviceId: String,
)

/**
 * 갱신 성공 시 발급되는 새 Token pair.
 *
 * 회전이므로 이전 Refresh Token은 더 이상 사용할 수 없다. 사용자 정보는 다시 내려오지
 * 않으므로 앱이 보관 중인 값을 유지한다.
 *
 * @property expiresIn Access Token 유효 시간(초). 계약상 3600이다.
 * @property refreshExpiresIn Refresh Token 유효 시간(초). 계약상 2592000이다.
 */
@Serializable
data class RefreshTokenData(
    val accessToken: String,
    val expiresIn: Int,
    val refreshToken: String,
    val refreshExpiresIn: Int,
)

/**
 * 인증 endpoint 호출 계약.
 *
 * 신규 사용자 `201`과 기존 사용자 `200`을 모두 성공으로 받기 위해 `Response`를 그대로
 * 반환하고 [toAuthResult]에서 상태 코드를 보존한다.
 */
interface AuthService {

    /** 기기에 결합된 로그인 transaction을 만들고 Kakao 인증 URL을 받는다. */
    @POST("auth/kakao/transactions")
    suspend fun createLoginTransaction(
        @Body body: CreateLoginTransactionRequest,
    ): Response<SuccessEnvelope<LoginTransactionData>>

    /** 일회용 login ticket을 Token pair와 사용자 정보로 교환한다. */
    @POST("auth/kakao/exchange")
    suspend fun exchangeLoginTicket(
        @Body body: LoginTicketExchangeRequest,
    ): Response<SuccessEnvelope<AuthTokenData>>

    /** 현재 기기의 Refresh Token을 새 Token pair로 회전한다. */
    @POST("auth/token/refresh")
    suspend fun refreshTokens(
        @Body body: RefreshTokenRequest,
    ): Response<SuccessEnvelope<RefreshTokenData>>

    /** 계약상 성공은 body 없는 `204`다. */
    @POST("auth/logout")
    suspend fun logout(
        @Body body: RefreshTokenRequest,
    ): Response<Unit>
}
