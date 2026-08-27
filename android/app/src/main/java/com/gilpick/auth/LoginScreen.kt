package com.gilpick.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import com.gilpick.R
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing

/**
 * 로그인 화면.
 *
 * 위쪽 hero가 서비스가 무엇을 해주는지 알리고, 아래쪽 action 묶음이 로그인 수단을
 * 모은다. 시각 구조는 `gilpick-design-reference.pen`의 `01 로그인`을 따른다.
 *
 * 진행 중에는 진행 상태를, 실패 후에는 오류와 다음 행동을 함께 보여준다. 재시도는
 * 언제나 새 Kakao 인증이며 앱이 같은 ticket이나 인가 코드를 다시 쓰지 않는다.
 *
 * @param state 현재 인증 상태. `SignedOut`, `LoggingIn`, `LoginFailed`만 의미가 있다.
 * @param onKakaoLogin 카카오 로그인을 시작한다.
 * @param onRetry 실패 후 다시 시도한다.
 */
@Composable
fun LoginScreen(
    state: AuthUiState,
    onKakaoLogin: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalGilpickSpacing.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = spacing.space5)
            .padding(bottom = spacing.space6 + spacing.space2),
        verticalArrangement = Arrangement.spacedBy(spacing.space6),
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Hero()
        }
        Actions(state = state, onKakaoLogin = onKakaoLogin, onRetry = onRetry)
    }
}

/** 서비스 이름과 한 줄 설명. 로그인 화면에서만 Display 크기를 쓴다. */
@Composable
private fun Hero() {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current

    Column(
        verticalArrangement = Arrangement.spacedBy(spacing.space5),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 로고 자산이 확정되기 전까지 자리만 잡아둔다.
        Box(
            modifier = Modifier
                .size(LOGO_SIZE)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(radius.lg),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.login_logo_placeholder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.login_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 로그인 수단과 상태별 안내를 모은 묶음. */
@Composable
private fun Actions(
    state: AuthUiState,
    onKakaoLogin: () -> Unit,
    onRetry: () -> Unit,
) {
    val spacing = LocalGilpickSpacing.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.space3),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            // 저장된 session을 복원하는 동안에는 로그인 수단을 잠깐 보였다 감추지 않는다.
            is AuthUiState.Loading -> CircularProgressIndicator()

            is AuthUiState.LoggingIn -> {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.login_in_progress),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            is AuthUiState.LoginFailed -> {
                Text(
                    text = stringResource(
                        if (state.retryable) R.string.login_error_retryable
                        else R.string.login_error_restart,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                KakaoButton(label = R.string.login_retry, onClick = onRetry)
            }

            else -> KakaoButton(label = R.string.login_kakao, onClick = onKakaoLogin)
        }

        GilpickLoginButton()

        Text(
            text = stringResource(R.string.login_terms),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 카카오 로그인 버튼.
 *
 * 배경과 라벨 색은 카카오 브랜드 가이드가 정한 고정값이라 앱 팔레트를 따르지 않는다.
 */
@Composable
private fun KakaoButton(label: Int, onClick: () -> Unit) {
    val colors = LocalGilpickColors.current
    val radius = LocalGilpickRadius.current

    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PRIMARY_BUTTON_HEIGHT),
        shape = RoundedCornerShape(radius.md),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.kakao,
            contentColor = colors.onKakao,
        ),
    ) {
        Text(text = stringResource(label), style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * 길픽 자체 로그인 버튼.
 *
 * 아직 동작하지 않는다. F001 명세(FR-015)가 이메일 회원가입을 범위 밖으로 두고 있어
 * Backend에도 대응 endpoint가 없다. 누르면 아무 일도 일어나지 않는 버튼을 두는 대신
 * 비활성 상태와 준비 중 안내를 함께 노출한다.
 *
 * ponytail: 자리만 잡아둔 상태다. 자체 로그인을 실제로 도입하면 명세를 먼저 고치고
 * 이 버튼을 활성화한다.
 */
@Composable
private fun GilpickLoginButton() {
    val radius = LocalGilpickRadius.current
    val preparing = stringResource(R.string.login_gilpick_preparing)

    OutlinedButton(
        onClick = {},
        enabled = false,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SECONDARY_BUTTON_HEIGHT)
            .semantics { stateDescription = preparing },
        shape = RoundedCornerShape(radius.md),
    ) {
        Text(
            text = stringResource(R.string.login_gilpick),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** 로고 자리. 실제 자산이 정해지면 크기를 다시 확인한다. */
private val LOGO_SIZE = androidx.compose.ui.unit.Dp(88f)

/** 가이드라인 5절: 주요 CTA 52~56dp, 보조 버튼 44~48dp. */
private val PRIMARY_BUTTON_HEIGHT = androidx.compose.ui.unit.Dp(56f)
private val SECONDARY_BUTTON_HEIGHT = androidx.compose.ui.unit.Dp(48f)
