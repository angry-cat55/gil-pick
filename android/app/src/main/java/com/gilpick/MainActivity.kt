package com.gilpick

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gilpick.auth.AuthUiState
import com.gilpick.auth.AuthViewModel
import com.gilpick.auth.AuthenticatedHomeScreen
import com.gilpick.auth.LoginScreen
import com.gilpick.auth.RefreshOfflineScreen

/**
 * 앱의 단일 Activity entrypoint.
 *
 * `singleTask`이므로 인증 완료 App Link는 새 Activity가 아니라 [onNewIntent]로 도착한다.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AuthViewModel by viewModels { AuthViewModel.factory(this) }

    /**
     * Custom Tab을 열어 두고 결과를 기다리는 중인지 여부.
     *
     * App Link 없이 화면으로 돌아오면 사용자가 인증을 취소한 것이다. 인증 완료 intent는
     * [onNewIntent]에서 이 표시를 먼저 내리므로 취소로 오인하지 않는다.
     */
    private var awaitingKakaoAuth = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.restore()
        handleAppLink(intent)

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            MaterialTheme {
                Surface {
                    GilpickApp(
                        state = state,
                        onKakaoLogin = ::startKakaoLogin,
                        onRetry = ::startKakaoLogin,
                        onRetryRefresh = viewModel::retryRefresh,
                        onLogout = viewModel::logout,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAppLink(intent)
    }

    override fun onResume() {
        super.onResume()
        if (awaitingKakaoAuth) {
            awaitingKakaoAuth = false
            viewModel.cancelLogin()
        }
    }

    /**
     * 인증 완료 App Link를 repository로 넘긴다.
     *
     * 같은 intent가 화면 재생성으로 다시 전달되어도 ticket은 한 번만 소비된다.
     */
    private fun handleAppLink(intent: Intent?) {
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        awaitingKakaoAuth = false
        viewModel.completeLogin(data.toString())
    }

    /** transaction을 만들고 Kakao 인증을 Custom Tab으로 연다. */
    private fun startKakaoLogin() {
        viewModel.startLogin { authorizationUrl ->
            awaitingKakaoAuth = true
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(this, authorizationUrl.toUri())
        }
    }
}

/**
 * 인증 상태에 따라 로그인 화면과 빈 여행 목록 shell을 고른다.
 *
 * @param state 현재 인증 상태.
 * @param onKakaoLogin 카카오 로그인을 시작한다.
 * @param onRetry 로그인 실패 후 새 카카오 인증을 시작한다.
 * @param onRetryRefresh 통신 장애로 중단된 로그인 상태 갱신을 다시 시도한다.
 * @param onLogout 현재 기기에서 로그아웃한다.
 */
@Composable
fun GilpickApp(
    state: AuthUiState,
    onKakaoLogin: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryRefresh: () -> Unit = {},
    onLogout: () -> Unit = {},
) {
    when (state) {
        is AuthUiState.Authenticated -> AuthenticatedHomeScreen(
            nickname = state.nickname,
            modifier = modifier,
            onLogout = onLogout,
        )

        // 통신 장애로 갱신이 중단된 상태다. session은 유지한 채 보호 기능만 막는다.
        is AuthUiState.RefreshOffline -> RefreshOfflineScreen(
            onRetry = onRetryRefresh,
            modifier = modifier,
            onLogout = onLogout,
        )

        else -> LoginScreen(
            state = state,
            onKakaoLogin = onKakaoLogin,
            onRetry = onRetry,
            modifier = modifier,
        )
    }
}
