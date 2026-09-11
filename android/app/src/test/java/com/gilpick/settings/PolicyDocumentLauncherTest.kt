package com.gilpick.settings

import android.content.ActivityNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T017: 정책 문서 위치 검증과 실행 결과 분류 검증(spec FR-007·FR-008·FR-012).
 *
 * 실행 자체는 기기 기능이라 [PolicyDocumentLauncher]의 `launch` 자리를 바꿔 끼워 결과만 정한다.
 * 실제 Custom Tab 진입·복귀는 `SettingsPolicyTest`와 수동 검증이 본다.
 */
class PolicyDocumentLauncherTest {

    /** 실제로 열린 위치. 어떤 문서를 열었는지 구분하는 데 쓴다. */
    private val opened = mutableListOf<String>()

    @Test
    fun `두 문서는 서로 다른 위치를 연다`() {
        // FR-007. 개인정보처리방침과 이용약관을 구분해 선택할 수 있어야 한다.
        val launcher = launcher(
            privacy = "https://example.test/privacy",
            terms = "https://example.test/terms",
        )

        assertNull(launcher.open(PolicyDocument.PRIVACY_POLICY))
        assertNull(launcher.open(PolicyDocument.TERMS_OF_SERVICE))

        assertEquals(listOf("https://example.test/privacy", "https://example.test/terms"), opened)
    }

    @Test
    fun `위치가 비어 있으면 열지 않고 UrlMissing이다`() {
        // 승인된 URL이 아직 build에 주입되지 않은 경우다. 지어낸 곳으로 보내지 않는다.
        val launcher = launcher(privacy = "", terms = "https://example.test/terms")

        assertEquals(PolicyOpenFailure.UrlMissing, launcher.open(PolicyDocument.PRIVACY_POLICY))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `공백만 있는 위치도 UrlMissing이다`() {
        val launcher = launcher(privacy = "   ", terms = "   ")

        assertEquals(PolicyOpenFailure.UrlMissing, launcher.open(PolicyDocument.PRIVACY_POLICY))
        assertEquals(PolicyOpenFailure.UrlMissing, launcher.open(PolicyDocument.TERMS_OF_SERVICE))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `HTTP 위치는 열지 않고 UrlNotHttps다`() {
        // FR-008. 안전하지 않은 곳으로 사용자를 보내지 않는다.
        val launcher = launcher(privacy = "http://example.test/privacy", terms = "https://example.test/terms")

        assertEquals(PolicyOpenFailure.UrlNotHttps, launcher.open(PolicyDocument.PRIVACY_POLICY))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `HTTPS가 아닌 다른 scheme도 UrlNotHttps다`() {
        val launcher = launcher(privacy = "file:///sdcard/privacy.html", terms = "javascript:alert(1)")

        assertEquals(PolicyOpenFailure.UrlNotHttps, launcher.open(PolicyDocument.PRIVACY_POLICY))
        assertEquals(PolicyOpenFailure.UrlNotHttps, launcher.open(PolicyDocument.TERMS_OF_SERVICE))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `대문자 HTTPS도 유효한 위치로 본다`() {
        val launcher = launcher(privacy = "HTTPS://example.test/privacy", terms = "https://example.test/terms")

        assertNull(launcher.open(PolicyDocument.PRIVACY_POLICY))
        assertEquals(listOf("HTTPS://example.test/privacy"), opened)
    }

    @Test
    fun `브라우저가 없으면 앱이 죽지 않고 LauncherUnavailable이다`() {
        val launcher = PolicyDocumentLauncher(
            urlOf = { "https://example.test/privacy" },
            launch = { throw ActivityNotFoundException("no browser") },
        )

        assertEquals(PolicyOpenFailure.LauncherUnavailable, launcher.open(PolicyDocument.PRIVACY_POLICY))
    }

    @Test
    fun `세 실패는 모두 다시 시도할 수 있는 결과로 돌아온다`() {
        // FR-008. 어느 실패에서도 예외를 던지지 않아 화면과 session이 유지된다.
        val missing = launcher(privacy = "", terms = "").open(PolicyDocument.PRIVACY_POLICY)
        val notHttps = launcher(privacy = "http://a", terms = "http://b").open(PolicyDocument.PRIVACY_POLICY)
        val unavailable = PolicyDocumentLauncher(
            urlOf = { "https://example.test/a" },
            launch = { throw ActivityNotFoundException("no browser") },
        ).open(PolicyDocument.PRIVACY_POLICY)

        assertEquals(
            setOf(PolicyOpenFailure.UrlMissing, PolicyOpenFailure.UrlNotHttps, PolicyOpenFailure.LauncherUnavailable),
            setOf(missing, notHttps, unavailable),
        )
    }

    private fun launcher(privacy: String, terms: String) = PolicyDocumentLauncher(
        urlOf = { document ->
            when (document) {
                PolicyDocument.PRIVACY_POLICY -> privacy
                PolicyDocument.TERMS_OF_SERVICE -> terms
            }
        },
        launch = { opened += it },
    )
}
