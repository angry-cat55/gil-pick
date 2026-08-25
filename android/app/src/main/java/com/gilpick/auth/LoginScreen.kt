package com.gilpick.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gilpick.R

/**
 * 로그인 화면.
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
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.login_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )

        when (state) {
            // 저장된 session을 복원하는 동안에는 로그인 버튼을 잠깐 보였다 감추지 않는다.
            is AuthUiState.Loading -> CircularProgressIndicator()

            is AuthUiState.LoggingIn -> {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.login_in_progress),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }

            is AuthUiState.LoginFailed -> {
                Text(
                    text = stringResource(
                        if (state.retryable) R.string.login_error_retryable
                        else R.string.login_error_restart,
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.login_retry))
                }
            }

            else -> Button(onClick = onKakaoLogin) {
                Text(stringResource(R.string.login_kakao))
            }
        }
    }
}
