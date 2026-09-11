package com.gilpick.settings

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.gilpick.BuildConfig

/**
 * 설정 화면에서 열 수 있는 정책 문서(FR-007).
 *
 * 두 문서는 서로 다른 위치를 가리키므로 구분해서 연다. 앱 안에 문서 본문을 넣거나 정책 전용
 * 화면을 만들지 않는다 — 승인된 문서의 현재 내용을 그대로 보여야 하기 때문이다.
 */
enum class PolicyDocument { PRIVACY_POLICY, TERMS_OF_SERVICE }

/**
 * 정책 문서를 열지 못한 이유(FR-008).
 *
 * **앱이 감지할 수 있는 실패만 여기 있다.** Custom Tab이 열린 뒤의 network·HTTP·문서 로딩
 * 오류는 브라우저가 표시하며 앱은 추적하지 않는다. 셋 다 설정 화면과 로그인 상태를 유지한 채
 * 다시 시도할 수 있다.
 */
sealed interface PolicyOpenFailure {
    /** 주입된 문서 위치가 비어 있다. 아직 승인된 URL이 build에 들어오지 않은 경우다. */
    data object UrlMissing : PolicyOpenFailure

    /** 문서 위치가 HTTPS가 아니다. 안전하지 않은 곳으로 사용자를 보내지 않는다. */
    data object UrlNotHttps : PolicyOpenFailure

    /** Custom Tab을 실행할 수 없다. 브라우저가 없거나 비활성화된 기기다. */
    data object LauncherUnavailable : PolicyOpenFailure
}

/**
 * 정책 문서를 앱 내 브라우저로 여는 진입점(T018).
 *
 * 여는 데까지만 책임진다. 실행 뒤에는 아무 상태도 추적하지 않는다(FR-008) — 그 뒤 화면은
 * 브라우저의 것이고 앱이 로딩 성공·실패를 알 방법도, 알아야 할 이유도 없다.
 *
 * **문서 위치와 사용자가 무엇을 열었는지는 log에 남기지 않는다**(FR-012). 그래서 실패를 던지지
 * 않고 [PolicyOpenFailure]로 돌려주며, 예외 메시지에 URL을 담지 않는다.
 *
 * @param urlOf 문서별 승인된 위치. 기본값은 build에 주입된 값이며 없으면 빈 문자열이다.
 * @param launch 실제로 여는 동작. test가 기기 없이 결과를 정할 수 있도록 분리했다.
 */
class PolicyDocumentLauncher(
    private val urlOf: (PolicyDocument) -> String,
    private val launch: (String) -> Unit,
) {

    /**
     * 문서를 연다.
     *
     * @return 열지 못한 이유. 성공했으면 `null`이다.
     */
    fun open(document: PolicyDocument): PolicyOpenFailure? {
        val url = urlOf(document)
        if (url.isBlank()) return PolicyOpenFailure.UrlMissing
        if (!url.startsWith(HTTPS_PREFIX, ignoreCase = true)) return PolicyOpenFailure.UrlNotHttps
        return try {
            launch(url)
            null
        } catch (e: ActivityNotFoundException) {
            // 브라우저가 없거나 꺼진 기기다. 예외를 그대로 올리면 앱이 죽고, 메시지에 URL이
            // 섞여 log로 새어 나갈 수 있다.
            PolicyOpenFailure.LauncherUnavailable
        }
    }

    companion object {
        private const val HTTPS_PREFIX = "https://"

        /**
         * 실제 Custom Tabs를 여는 launcher.
         *
         * 승인된 URL은 저장소에 두지 않고 build에 주입한다. 주입되지 않으면 빈 값이고
         * [PolicyOpenFailure.UrlMissing]으로 안내한다.
         */
        fun default(context: Context): PolicyDocumentLauncher = PolicyDocumentLauncher(
            urlOf = { document ->
                when (document) {
                    PolicyDocument.PRIVACY_POLICY -> BuildConfig.PRIVACY_POLICY_URL
                    PolicyDocument.TERMS_OF_SERVICE -> BuildConfig.TERMS_OF_SERVICE_URL
                }
            },
            launch = { url ->
                CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                    .launchUrl(context, url.toUri())
            },
        )
    }
}
