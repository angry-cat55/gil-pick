package com.gilpick.auth

import java.net.URI
import java.net.URISyntaxException

/** 인증 App Link 수신 결과. */
sealed interface AppLinkResult {

    /**
     * 아직 교환하지 않은 login ticket을 받았다.
     *
     * @property loginTicket URI fragment에서 꺼낸 일회용 ticket.
     */
    data class Ticket(val loginTicket: String) : AppLinkResult

    /**
     * Backend가 provider 단계 실패를 알렸다.
     *
     * @property error 계약상의 callback error code와 재시도 가능 여부.
     */
    data class Failed(val error: AuthError.Callback) : AppLinkResult

    /** 인증 App Link가 아니거나, 신뢰할 수 없거나, 이미 소비한 link다. 아무 것도 하지 않는다. */
    data object Ignored : AppLinkResult
}

/**
 * verified App Link로 도착한 login ticket을 한 번만 받아들이는 경계.
 *
 * intent data는 앱 밖에서 오는 입력이므로 scheme·host·path를 모두 확인하고, ticket은
 * URI fragment에서만 받는다. fragment는 HTTP 요청에 실려 가지 않아 server log와
 * referrer에 남지 않으므로 계약이 ticket 전달 위치로 지정한 곳이다. query로 온 ticket은
 * 계약 위반이거나 위조이므로 수락하지 않는다.
 *
 * @property allowedHost claim한 App Link host. 보통 `BuildConfig.APP_LINK_HOST`다.
 */
class AuthAppLinkHandler(private val allowedHost: String) {

    /**
     * 마지막으로 소비한 link.
     *
     * `singleTask` activity는 화면 재생성이나 재진입에서 같은 intent를 다시 전달할 수
     * 있다. 같은 link를 두 번 교환하면 두 번째는 `INVALID_LOGIN_TICKET`이 되므로 여기서
     * 막는다.
     *
     * ponytail: 마지막 link 하나만 기억한다. 로그인 link는 한 번에 하나씩만 도착하므로
     * 충분하다. 여러 link가 교차 도착할 수 있게 되면 소비한 ticket 집합으로 바꾼다.
     */
    private var lastConsumed: String? = null

    /**
     * [uri]가 신뢰할 수 있는 인증 완료 link면 그 결과를 한 번만 반환한다.
     *
     * 같은 link를 다시 넣거나, 허용하지 않은 host·path·scheme이거나, ticket이 계약
     * 형식이 아니면 [AppLinkResult.Ignored]를 반환한다.
     */
    fun consume(uri: String?): AppLinkResult {
        if (uri.isNullOrBlank() || uri == lastConsumed) return AppLinkResult.Ignored

        val parsed = try {
            URI(uri)
        } catch (e: URISyntaxException) {
            return AppLinkResult.Ignored
        }

        if (!parsed.isAuthCompleteLink()) return AppLinkResult.Ignored

        // fragment의 ticket이 먼저다. 성공 link에는 error query가 없다.
        parsed.fragment?.let { fragment ->
            val ticket = fragment.paramOrNull(TICKET_PARAM)
            if (ticket != null && TICKET_PATTERN.matches(ticket)) {
                lastConsumed = uri
                return AppLinkResult.Ticket(ticket)
            }
        }

        parsed.query?.paramOrNull(ERROR_PARAM)?.let { code ->
            lastConsumed = uri
            return AppLinkResult.Failed(AuthErrorCodes.toCallbackError(code))
        }

        return AppLinkResult.Ignored
    }

    /** claim한 host의 인증 완료 path로 온 HTTPS link인지 확인한다. */
    private fun URI.isAuthCompleteLink(): Boolean =
        scheme.equals("https", ignoreCase = true) &&
            host.equals(allowedHost, ignoreCase = true) &&
            path == COMPLETE_PATH

    /** `a=1&b=2` 형식에서 [name]의 값을 꺼낸다. 없으면 `null`이다. */
    private fun String.paramOrNull(name: String): String? = split("&")
        .firstNotNullOfOrNull { pair ->
            val separator = pair.indexOf('=')
            if (separator > 0 && pair.substring(0, separator) == name) {
                pair.substring(separator + 1).takeIf { it.isNotEmpty() }
            } else {
                null
            }
        }

    private companion object {
        /** manifest가 `autoVerify`로 claim한 유일한 path. */
        const val COMPLETE_PATH = "/auth/kakao/complete"
        const val TICKET_PARAM = "loginTicket"
        const val ERROR_PARAM = "error"

        /** 계약의 `OpaqueSelectorToken`. UUID selector와 256-bit base64url secret이다. */
        val TICKET_PATTERN = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-" +
                "[0-9a-fA-F]{12}\\.[A-Za-z0-9_-]{43}$",
        )
    }
}
