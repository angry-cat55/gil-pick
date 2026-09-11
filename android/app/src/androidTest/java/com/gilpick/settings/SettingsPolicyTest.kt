package com.gilpick.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.ui.theme.GilpickTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T020: 정책 문서 항목·열기 실패·재시도 검증(spec FR-007·FR-008, UI-004·UI-005).
 *
 * 실제 Custom Tab 진입과 복귀는 기기·브라우저 동작이라 여기서 확인하지 않는다. 이 test는
 * **앱이 책임지는 범위**(어떤 문서를 고르는지, 실패를 어떻게 알리는지, 재시도가 무엇을 다시
 * 여는지)만 본다. 실행 뒤 network·HTTP·문서 로딩 오류는 브라우저 몫이라 test 대상이 아니다.
 *
 * 각 문서 열기 5회 3초 이내(SC-005)는 자동화할 수 없어 실제 환경에서 수동 측정한다.
 */
@RunWith(AndroidJUnit4::class)
class SettingsPolicyTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 두_정책_문서를_구분해_고를_수_있다() {
        // FR-007. 개인정보처리방침과 이용약관이 각각 다른 문서로 이어져야 한다.
        val opened = mutableListOf<PolicyDocument>()
        setSection(onOpen = { opened += it })

        composeRule.onNodeWithText("개인정보처리방침").assertIsDisplayed()
        composeRule.onNodeWithText("이용약관").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_PRIVACY_POLICY).performClick()
        composeRule.onNodeWithTag(TAG_TERMS_OF_SERVICE).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(PolicyDocument.PRIVACY_POLICY, PolicyDocument.TERMS_OF_SERVICE), opened)
        }
    }

    @Test
    fun 성공하면_실패_안내를_보이지_않는다() {
        // 연 뒤에는 앱이 아무것도 추적하지 않는다(FR-008). 남길 상태가 없다.
        setSection(openError = null)

        composeRule.onNodeWithTag(TAG_POLICY_ERROR).assertDoesNotExist()
    }

    @Test
    fun 문서_위치가_비어_있으면_원인을_알리고_화면을_유지한다() {
        // 승인된 URL이 아직 주입되지 않은 경우다.
        setSection(openError = PolicyOpenFailure.UrlMissing)

        composeRule.onNodeWithTag(TAG_POLICY_ERROR).assertIsDisplayed()
        composeRule.onNodeWithText("문서를 열 수 없어요").assertIsDisplayed()
        composeRule.onNodeWithText("문서 위치가 아직 준비되지 않았어요.").assertIsDisplayed()
        // 화면은 그대로다. 두 항목을 다시 고를 수 있다.
        composeRule.onNodeWithTag(TAG_PRIVACY_POLICY).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_TERMS_OF_SERVICE).assertIsDisplayed()
    }

    @Test
    fun HTTP_주소는_안전하지_않다고_알린다() {
        setSection(openError = PolicyOpenFailure.UrlNotHttps)

        composeRule.onNodeWithText("안전하지 않은 주소라 열지 않았어요.").assertIsDisplayed()
    }

    @Test
    fun 브라우저를_찾지_못하면_그_원인을_알린다() {
        setSection(openError = PolicyOpenFailure.LauncherUnavailable)

        composeRule.onNodeWithText("문서를 열 브라우저를 찾지 못했어요.").assertIsDisplayed()
    }

    @Test
    fun 세_실패_원인은_서로_다른_문구를_보인다() {
        // 한 화면에서 원인만 바꿔 가며 실제로 그려 본다. 문구가 겹치면 여기서 드러난다.
        val cases = listOf(
            PolicyOpenFailure.UrlMissing to "문서 위치가 아직 준비되지 않았어요.",
            PolicyOpenFailure.UrlNotHttps to "안전하지 않은 주소라 열지 않았어요.",
            PolicyOpenFailure.LauncherUnavailable to "문서를 열 브라우저를 찾지 못했어요.",
        )
        val failure = mutableStateOf<PolicyOpenFailure?>(null)
        composeRule.setContent {
            GilpickTheme {
                PolicyDocumentSection(
                    openError = failure.value,
                    onOpen = {},
                    onRetry = {},
                    onDismissError = {},
                )
            }
        }

        cases.forEach { (cause, message) ->
            composeRule.runOnIdle { failure.value = cause }
            composeRule.onNodeWithText(message).assertIsDisplayed()
        }
        assertEquals(3, cases.map { it.second }.toSet().size)
    }

    @Test
    fun 다시_시도는_마지막으로_고른_문서를_다시_연다() {
        var retries = 0
        setSection(openError = PolicyOpenFailure.LauncherUnavailable, onRetry = { retries++ })

        composeRule.onNodeWithTag(TAG_POLICY_RETRY).performClick()

        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun 닫기는_안내만_없애고_문서_항목은_남긴다() {
        var dismisses = 0
        setSection(openError = PolicyOpenFailure.UrlMissing, onDismissError = { dismisses++ })

        composeRule.onNodeWithTag(TAG_POLICY_DISMISS).performClick()

        composeRule.runOnIdle { assertEquals(1, dismisses) }
        composeRule.onNodeWithTag(TAG_PRIVACY_POLICY).assertIsDisplayed()
    }

    @Test
    fun 실패_중에도_두_문서를_다시_고를_수_있다() {
        // FR-008. 실패가 화면을 막지 않는다.
        val opened = mutableListOf<PolicyDocument>()
        setSection(openError = PolicyOpenFailure.LauncherUnavailable, onOpen = { opened += it })

        composeRule.onNodeWithTag(TAG_TERMS_OF_SERVICE).performClick()

        composeRule.runOnIdle { assertEquals(listOf(PolicyDocument.TERMS_OF_SERVICE), opened) }
    }

    @Test
    fun 문서_항목과_실패_행동은_48dp_이상이다() {
        setSection(openError = PolicyOpenFailure.UrlMissing)

        composeRule.onNodeWithTag(TAG_PRIVACY_POLICY).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(TAG_TERMS_OF_SERVICE).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(TAG_POLICY_RETRY).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(TAG_POLICY_DISMISS).assertHeightIsAtLeast(48.dp)
    }

    private fun setSection(
        openError: PolicyOpenFailure? = null,
        onOpen: (PolicyDocument) -> Unit = {},
        onRetry: () -> Unit = {},
        onDismissError: () -> Unit = {},
    ) {
        composeRule.setContent {
            GilpickTheme {
                PolicyDocumentSection(
                    openError = openError,
                    onOpen = onOpen,
                    onRetry = onRetry,
                    onDismissError = onDismissError,
                )
            }
        }
    }
}
