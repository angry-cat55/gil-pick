package com.gilpick.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.gilpick.R
import com.gilpick.ui.component.DestructiveConfirmDialog
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing
import kotlinx.serialization.Serializable

/** 설정 화면 route(F012). 진입점은 `MainActivity`의 하단 최상위 탭 `설정`이다(T026). */
@Serializable
data object SettingsRoute

/**
 * 설정 destination을 app navigation graph에 등록한다(T022).
 *
 * 로그아웃은 F001 `AuthViewModel.logout` 하나에 연결한다. 설정 화면은 token을 직접 지우거나
 * 별도 logout 상태를 갖지 않고, 서버 폐기 재시도·다른 기기 session 유지는 F001 규칙이 그대로
 * 적용된다(FR-010).
 *
 * @param onLogout 현재 기기 로그아웃. 앱 전체 인증 상태를 가진 `AuthViewModel`로 이어진다.
 * @param onAccountDeleted 서버가 계정 탈퇴를 확정했다(#667). 로그아웃과 달리 서버 session이 이미
 *   사라졌으므로 폐기를 예약하지 않고 local session만 지우는 F001 경로로 이어진다. 그 전환으로
 *   `NavHost` 전체가 내려가 설정 화면이 백스택에 남지 않는다.
 * @param onSessionExpired 자격이 무효로 확정됐다. F001 재인증 흐름으로 넘긴다.
 * @param nickname·profileImageUrl 인증된 F001 session의 표시 정보(#402). 설정 화면은 이 값을
 *   읽기만 하고 프로필 조회 API를 부르지 않는다(FR-013). 카카오 미동의면 비어 있다.
 * @param repository 설정 데이터 접근. UI test가 바꿔 끼운다.
 * @param launcher 정책 문서 Custom Tabs launcher. UI test가 바꿔 끼운다.
 */
fun NavGraphBuilder.settingsGraph(
    onLogout: () -> Unit,
    onAccountDeleted: () -> Unit,
    onSessionExpired: () -> Unit,
    nickname: String? = null,
    profileImageUrl: String? = null,
    repository: (Context) -> SettingsRepository = SettingsRepository::default,
    launcher: (Context) -> PolicyDocumentLauncher = PolicyDocumentLauncher::default,
) {
    composable<SettingsRoute> { entry ->
        val context = LocalContext.current
        val factory = remember(entry) {
            SettingsViewModel.factory(
                repository = repository(context),
                nickname = nickname,
                profileImageUrl = profileImageUrl,
            )
        }
        val viewModel: SettingsViewModel = viewModel(factory = factory)
        val state by viewModel.state.collectAsStateWithLifecycle()
        val policyLauncher = remember { launcher(context) }

        LaunchedEffect(Unit) { viewModel.load() }

        SettingsDestination(
            state = state,
            onToggle = viewModel::setPlaceChangeSuggestionEnabled,
            onRetryLoad = viewModel::load,
            onRetrySave = viewModel::retrySave,
            onReauthenticate = onSessionExpired,
            onOpenPolicy = { viewModel.openPolicy(it, policyLauncher::open) },
            onRetryPolicy = { viewModel.retryPolicy(policyLauncher::open) },
            onDismissPolicyError = viewModel::dismissPolicyError,
            onLogout = onLogout,
            onDeleteAccount = { viewModel.deleteAccount(onAccountDeleted) },
            onDismissDeleteError = viewModel::clearAccountDeleteError,
        )
    }
}

/**
 * 설정 화면 본문(Figma `SettingsScreen`). 계정 헤더 → 알림 설정 → 앱 정보(버전·정책 문서) →
 * 로그아웃 → 계정 탈퇴 순서다.
 *
 * 전체가 세로 scroll이라 작은 화면과 가로 방향에서도 로그아웃과 탈퇴까지 닿는다(UI-006).
 *
 * @param onLogout 로그아웃 선택. 연속 선택은 첫 요청만 전달한다(Edge Cases).
 * @param onDeleteAccount 탈퇴 확인 다이얼로그에서 확정을 골랐다(#667).
 * @param onDismissDeleteError 탈퇴 실패 안내를 지운다. 다이얼로그를 닫을 때 부른다.
 */
@Composable
internal fun SettingsDestination(
    state: SettingsUiState,
    onToggle: (Boolean) -> Unit,
    onRetryLoad: () -> Unit,
    onRetrySave: () -> Unit,
    onReauthenticate: () -> Unit,
    onOpenPolicy: (PolicyDocument) -> Unit,
    onRetryPolicy: () -> Unit,
    onDismissPolicyError: () -> Unit,
    onLogout: () -> Unit,
    onDeleteAccount: () -> Unit = {},
    onDismissDeleteError: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val spacing = LocalGilpickSpacing.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        AccountSection(
            nickname = state.nickname,
            profileImageUrl = state.profileImageUrl,
            isKakaoConnected = state.isKakaoConnected,
        )
        Spacer(Modifier.height(spacing.space3))
        NotificationPreferenceSection(
            phase = state.preference,
            onToggle = onToggle,
            onRetryLoad = onRetryLoad,
            onRetrySave = onRetrySave,
            onReauthenticate = onReauthenticate,
        )
        Spacer(Modifier.height(spacing.space3))
        PolicyDocumentSection(
            versionName = state.versionName,
            openError = state.policyOpenError,
            onOpen = onOpenPolicy,
            onRetry = onRetryPolicy,
            onDismissError = onDismissPolicyError,
        )
        Spacer(Modifier.height(spacing.space3))
        LogoutSection(onLogout = onLogout)
        AccountDeleteSection(
            deletion = state.accountDeletion,
            onDelete = onDeleteAccount,
            onDismissError = onDismissDeleteError,
        )
        Spacer(Modifier.height(spacing.space8))
    }
}

/**
 * 로그아웃 영역(US3, Figma `SettingsScreen` 로그아웃).
 *
 * 확인 단계 없이 즉시 실행한다(Assumptions). 한 번 요청하면 버튼을 잠가 연속 선택이 두 번째
 * 로그아웃을 만들지 않게 한다. 로그아웃되면 인증 상태 전환으로 이 화면 자체가 내려가므로
 * 잠금을 풀 필요가 없다.
 */
@Composable
private fun LogoutSection(onLogout: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    var requested by remember { mutableStateOf(false) }

    Button(
        onClick = {
            if (requested) return@Button
            requested = true
            onLogout()
        },
        enabled = !requested,
        // 폭을 채우는 버튼이라 16dp(가이드라인 6절 R1, D4).
        shape = RoundedCornerShape(LocalGilpickRadius.current.lg),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            disabledContainerColor = MaterialTheme.colorScheme.errorContainer,
            disabledContentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = spacing.space5, vertical = spacing.space4)
            .heightIn(min = MIN_TOUCH)
            .testTag(TAG_LOGOUT),
    ) {
        Text(
            text = stringResource(R.string.logout),
            // Figma `text-[14px] font-bold`.
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 계정 탈퇴 영역(#667).
 *
 * 로그아웃과 달리 되돌릴 수 없으므로 두 가지로 구분한다. 첫째, 채운 버튼이 아니라 글자 버튼이라
 * 실수로 먼저 눌리지 않는다. 둘째, 확인 다이얼로그를 거치고 그 기본 동작은 취소다 — 취소하면
 * 아무 요청도 나가지 않는다.
 *
 * 다이얼로그를 열었는지는 화면 안에서만 쓰이는 표시 상태라 여기서 들고, 요청 결과는
 * [AccountDeletePhase]로 받는다. 실패하면 다이얼로그를 닫지 않고 같은 자리에서 다시 시도한다.
 */
@Composable
private fun AccountDeleteSection(
    deletion: AccountDeletePhase,
    onDelete: () -> Unit,
    onDismissError: () -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    var confirming by remember { mutableStateOf(false) }

    TextButton(
        onClick = { confirming = true },
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = spacing.space5)
            .heightIn(min = MIN_TOUCH)
            .testTag(TAG_ACCOUNT_DELETE),
    ) {
        Text(
            text = stringResource(R.string.settings_account_delete),
            style = MaterialTheme.typography.labelMedium,
        )
    }

    if (confirming) {
        DestructiveConfirmDialog(
            title = stringResource(R.string.settings_account_delete_title),
            body = stringResource(R.string.settings_account_delete_body),
            confirmLabel = stringResource(R.string.settings_account_delete_confirm),
            progressLabel = stringResource(R.string.settings_account_delete_progress),
            cancelLabel = stringResource(R.string.settings_account_delete_cancel),
            onConfirm = onDelete,
            onDismiss = {
                confirming = false
                onDismissError()
            },
            busy = deletion is AccountDeletePhase.Deleting,
            errorMessage = (deletion as? AccountDeletePhase.Failed)?.let { stringResource(it.error.deleteMessageRes) },
        )
    }
}

/** 탈퇴 실패 안내 문구. 설정 조회 실패와 달리 계정이 그대로임을 함께 알린다. */
private val SettingsError.deleteMessageRes: Int
    get() = when (this) {
        SettingsError.Network -> R.string.settings_account_delete_error_network
        SettingsError.SessionExpired -> R.string.settings_account_delete_error_session
        SettingsError.Unexpected -> R.string.settings_account_delete_error_unexpected
    }

internal const val TAG_LOGOUT = "settings_logout"
internal const val TAG_ACCOUNT_DELETE = "settings_account_delete"

private val MIN_TOUCH: Dp = 48.dp
