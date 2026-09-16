package com.gilpick.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gilpick.GilpickApp
import com.gilpick.TAG_NAV_SETTINGS
import com.gilpick.auth.AuthUiState
import com.gilpick.ui.theme.GilpickTheme
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

/**
 * #667: 설정 화면의 계정 탈퇴 진입·확인·결과 처리 검증.
 *
 * 다이얼로그 배치 규칙(취소가 기본 동작, 진행 중 잠금, 실패해도 닫지 않음)은 공용
 * `DestructiveConfirmDialog`가 소유하지만, **설정 화면이 그 규칙에 올바르게 연결되는지**를 여기서
 * 본다. 서버 왕복은 `AuthApiTest`, 상태 전이는 `SettingsViewModelTest`가 각각 맡는다.
 */
@RunWith(AndroidJUnit4::class)
class SettingsAccountDeleteTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 탈퇴_진입만으로는_요청이_나가지_않는다() {
        // 완료 조건 3. 확인 다이얼로그를 거치지 않고 계정이 사라지면 안 된다.
        var requests = 0
        setDestination(onDeleteAccount = { requests++ })

        composeRule.onNodeWithTag(TAG_ACCOUNT_DELETE).performClick()

        composeRule.onNodeWithText("계정을 탈퇴할까요?").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, requests) }
    }

    @Test
    fun 취소하면_요청_없이_다이얼로그가_닫힌다() {
        // 완료 조건 3. 취소가 기본 동작이고 계정·데이터는 그대로 남는다.
        var requests = 0
        setDestination(onDeleteAccount = { requests++ })
        composeRule.onNodeWithTag(TAG_ACCOUNT_DELETE).performClick()

        composeRule.onNodeWithText("취소").performClick()

        composeRule.onNodeWithText("계정을 탈퇴할까요?").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, requests) }
    }

    @Test
    fun 삭제_범위와_복구_불가를_먼저_알린다() {
        setDestination()

        composeRule.onNodeWithTag(TAG_ACCOUNT_DELETE).performClick()

        composeRule
            .onNodeWithText("여행·일정·방문 기록·알림이 모두 사라집니다. 탈퇴한 계정은 복구할 수 없습니다.")
            .assertIsDisplayed()
    }

    @Test
    fun 확정하면_탈퇴를_요청한다() {
        var requests = 0
        setDestination(onDeleteAccount = { requests++ })
        composeRule.onNodeWithTag(TAG_ACCOUNT_DELETE).performClick()

        composeRule.onNodeWithText("탈퇴하기").performClick()

        composeRule.runOnIdle { assertEquals(1, requests) }
    }

    @Test
    fun 처리_중에는_진행을_보이고_버튼을_잠근다() {
        // 응답을 기다리는 사이 다시 눌리면 같은 탈퇴가 두 번 나간다.
        setDestination(deletion = AccountDeletePhase.Deleting)
        composeRule.onNodeWithTag(TAG_ACCOUNT_DELETE).performClick()

        composeRule.onNodeWithText("탈퇴하는 중").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("취소").assertIsNotEnabled()
    }

    @Test
    fun 실패하면_안내가_남고_다시_시도할_수_있다() {
        // 완료 조건 2. 네트워크 실패를 성공처럼 표시하지 않고 같은 자리에서 재시도한다.
        var requests = 0
        setDestination(
            deletion = AccountDeletePhase.Failed(SettingsError.Network),
            onDeleteAccount = { requests++ },
        )
        composeRule.onNodeWithTag(TAG_ACCOUNT_DELETE).performClick()

        composeRule
            .onNodeWithText("연결을 확인한 뒤 다시 시도해 주세요. 계정은 그대로 있습니다.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("탈퇴하기").performClick()

        composeRule.runOnIdle { assertEquals(1, requests) }
    }

    @Test
    fun 탈퇴_진입은_48dp_이상이다() {
        setDestination()

        composeRule.onNodeWithTag(TAG_ACCOUNT_DELETE).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun 탈퇴에_성공하면_최상위가_로그인_화면으로_바뀐다() {
        // 완료 조건 1. 여행 목록 → 설정 → 탈퇴. 인증 상태가 SignedOut이 되는 순간 NavHost 전체가
        // 내려가므로 설정 화면이 백스택에 남지 않는다. 로그아웃과 같은 전환 경로다.
        val authService = DeleteAccountAuthService { Response.success(204, Unit) }
        val repository = runBlocking { signedInSettingsRepository(context, FakeSettingsService(), authService) }
        var state by mutableStateOf<AuthUiState>(
            AuthUiState.Authenticated("user-1", nickname = "길픽", profileImageUrl = null),
        )
        composeRule.setContent {
            GilpickApp(
                state = state,
                onKakaoLogin = {},
                onRetry = {},
                // 탈퇴 성공은 `MainActivity`에서 이 경로로 이어진다.
                onSessionExpired = { state = AuthUiState.SignedOut },
                settingsRepository = { repository },
            )
        }

        composeRule.onNodeWithTag(TAG_NAV_SETTINGS).performClick()
        composeRule.onNodeWithTag(TAG_ACCOUNT_DELETE).performClick()
        composeRule.onNodeWithText("탈퇴하기").performClick()

        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodesWithText("카카오로 시작하기").fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag(TAG_ACCOUNT_DELETE).assertDoesNotExist()
        composeRule.onAllNodesWithText("내 여행").assertCountEquals(0)
        composeRule.runOnIdle { assertEquals(1, authService.deleteCalls) }
    }

    @Test
    fun 탈퇴에_실패하면_로그인_상태가_유지된다() {
        // 완료 조건 2. 실패한 탈퇴가 로그인 화면으로 보내면 성공한 것처럼 보인다.
        val authService = DeleteAccountAuthService {
            Response.error(500, "".toResponseBody("application/json".toMediaType()))
        }
        val repository = runBlocking { signedInSettingsRepository(context, FakeSettingsService(), authService) }
        var state by mutableStateOf<AuthUiState>(
            AuthUiState.Authenticated("user-1", nickname = "길픽", profileImageUrl = null),
        )
        composeRule.setContent {
            GilpickApp(
                state = state,
                onKakaoLogin = {},
                onRetry = {},
                onSessionExpired = { state = AuthUiState.SignedOut },
                settingsRepository = { repository },
            )
        }

        composeRule.onNodeWithTag(TAG_NAV_SETTINGS).performClick()
        composeRule.onNodeWithTag(TAG_ACCOUNT_DELETE).performClick()
        composeRule.onNodeWithText("탈퇴하기").performClick()

        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule
                .onAllNodesWithText("지금은 탈퇴할 수 없습니다. 잠시 후 다시 시도해 주세요.")
                .fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText("카카오로 시작하기").assertDoesNotExist()
        composeRule.onNodeWithTag(TAG_ACCOUNT_DELETE).assertIsDisplayed()
    }

    private fun setDestination(
        deletion: AccountDeletePhase = AccountDeletePhase.Idle,
        onDeleteAccount: () -> Unit = {},
    ) {
        composeRule.setContent {
            GilpickTheme {
                SettingsDestination(
                    state = SettingsUiState(
                        preference = PreferencePhase.Content(value = true),
                        accountDeletion = deletion,
                    ),
                    onToggle = {},
                    onRetryLoad = {},
                    onRetrySave = {},
                    onReauthenticate = {},
                    onOpenPolicy = {},
                    onRetryPolicy = {},
                    onDismissPolicyError = {},
                    onLogout = {},
                    onDeleteAccount = onDeleteAccount,
                )
            }
        }
    }

    private companion object {
        /** 탈퇴 요청은 IO를 거치므로 상태 전환을 기다린다. */
        const val WAIT_MILLIS = 5_000L
    }
}
