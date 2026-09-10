package com.gilpick.settings

import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthResult
import com.gilpick.auth.toAuthResult
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T003: PREF-001·PREF-002의 요청 경로·body와 계약 예시 JSON 역직렬화 검증.
 *
 * 값은 `specs/012-user-settings/contracts/preferences.openapi.yaml`을 따른다. Backend 구현(#398)
 * 없이도 계약 JSON만으로 앱 쪽 계약을 고정한다. 실서버 연동 확인은 통합 Issue(#403)에서 한다.
 */
class SettingsApiTest {

    @Test
    fun `설정 조회는 users me preferences로 GET한다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = preferenceJson()))

        api.getPreferences(BEARER)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/users/me/preferences", request.url.encodedPath)
        assertEquals(BEARER, request.headers["Authorization"])
    }

    @Test
    fun `설정 조회 응답은 단일 토글 값을 읽는다`() = withService { server, api ->
        // FR-001. 사용자가 바꿀 수 있는 값은 장소 변경 제안 알림 하나뿐이다.
        server.enqueue(MockResponse(code = 200, body = preferenceJson(enabled = false)))

        val data = api.getPreferences(BEARER).body()!!.data

        assertFalse(data.placeChangeSuggestionNotificationEnabled)
    }

    @Test
    fun `설정 변경은 같은 경로로 PATCH하고 절대값을 보낸다`() = withService { server, api ->
        // 계약이 부분 갱신이 아니라 절대값을 받는다. 서버가 마지막 성공값을 최종 상태로 삼는다(FR-003).
        server.enqueue(MockResponse(code = 200, body = preferenceJson(enabled = false)))

        api.updatePreferences(BEARER, UpdatePreferenceRequest(placeChangeSuggestionNotificationEnabled = false))

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/v1/users/me/preferences", request.url.encodedPath)
        assertEquals(
            """{"placeChangeSuggestionNotificationEnabled":false}""",
            request.body!!.utf8(),
        )
    }

    @Test
    fun `설정 변경 응답은 저장된 값을 그대로 돌려준다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 200, body = preferenceJson(enabled = true)))

        val data = api.updatePreferences(
            BEARER,
            UpdatePreferenceRequest(placeChangeSuggestionNotificationEnabled = true),
        ).body()!!.data

        assertTrue(data.placeChangeSuggestionNotificationEnabled)
    }

    @Test
    fun `모르는 field가 와도 파싱된다`() = withService { server, api ->
        // 서버가 설정을 늘려도 앱이 깨지지 않아야 한다.
        server.enqueue(
            MockResponse(
                code = 200,
                body = preferenceJson().replace("\"placeChangeSuggestionNotificationEnabled\": true", "\"placeChangeSuggestionNotificationEnabled\": true, \"extra\": 1"),
            ),
        )

        assertTrue(api.getPreferences(BEARER).body()!!.data.placeChangeSuggestionNotificationEnabled)
    }

    @Test
    fun `401은 계약 오류 code와 상태를 보존한다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 401, body = settingsErrorJson(SettingsErrorCodes.INVALID_ACCESS_TOKEN)))

        val result = api.getPreferences(BEARER).toAuthResult()

        val error = (result as AuthResult.Failure).error as AuthError.Server
        assertEquals(SettingsErrorCodes.INVALID_ACCESS_TOKEN, error.code)
        assertEquals(401, error.httpStatus)
    }

    @Test
    fun `400은 잘못된 요청 code로 온다`() = withService { server, api ->
        server.enqueue(MockResponse(code = 400, body = settingsErrorJson(SettingsErrorCodes.INVALID_REQUEST)))

        val result = api.updatePreferences(
            BEARER,
            UpdatePreferenceRequest(placeChangeSuggestionNotificationEnabled = true),
        ).toAuthResult()

        assertEquals(SettingsErrorCodes.INVALID_REQUEST, ((result as AuthResult.Failure).error as AuthError.Server).code)
    }

    @Test
    fun `계약과 다른 응답은 Malformed로 확정된다`() = withService { server, api ->
        // 필수 field가 빠진 응답을 성공으로 읽으면 저장되지 않은 값을 표시하게 된다(FR-006).
        server.enqueue(MockResponse(code = 200, body = """{"success": true, "data": {}, "meta": {"requestId": "$SETTINGS_REQUEST_ID"}}"""))

        val result = runCatching { api.getPreferences(BEARER).toAuthResult() }

        assertTrue(result.exceptionOrNull() is Exception || result.getOrNull() is AuthResult.Failure)
    }

    private fun withService(block: suspend (MockWebServer, SettingsService) -> Unit) = runTest {
        val server = MockWebServer()
        server.start()
        try {
            block(
                server,
                createSettingsRetrofit(server.url("/api/v1/").toString()).create(SettingsService::class.java),
            )
        } finally {
            server.close()
        }
    }

    private companion object {
        const val BEARER = "Bearer access-token"
    }
}
