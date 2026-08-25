package com.gilpick.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gilpick.BuildConfig
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 로그인 화면의 상태 보유자.
 *
 * 상태 전이는 모두 [AuthRepository]가 소유하므로 여기서는 화면 수명주기에 맞춰
 * 호출만 옮긴다. Custom Tab을 여는 것은 Context가 필요한 Activity의 일이라 URL을
 * [startLogin]의 callback으로 넘긴다.
 *
 * @property repository 로그인 상태의 유일한 변경 진입점.
 */
class AuthViewModel(private val repository: AuthRepository) : ViewModel() {

    /** 화면이 관찰하는 현재 인증 상태. */
    val state: StateFlow<AuthUiState> = repository.state

    /** 저장된 session을 복원해 첫 화면을 확정한다. */
    fun restore() {
        viewModelScope.launch { repository.restore() }
    }

    /**
     * 로그인 transaction을 만들고 성공하면 [openAuthorization]으로 Kakao 인증 URL을 넘긴다.
     *
     * 실패하면 상태만 `LoginFailed`로 바뀌고 [openAuthorization]은 호출되지 않는다.
     */
    fun startLogin(openAuthorization: (String) -> Unit) {
        viewModelScope.launch {
            when (val start = repository.startLogin()) {
                is LoginStart.Ready -> openAuthorization(start.authorizationUrl)
                is LoginStart.Failed -> Unit
            }
        }
    }

    /** 인증 완료 App Link를 처리한다. 신뢰할 수 없는 link는 무시된다. */
    fun completeLogin(appLinkUri: String?) {
        viewModelScope.launch { repository.completeLogin(appLinkUri) }
    }

    /** 사용자가 Kakao 인증을 끝내지 않고 돌아왔다. */
    fun cancelLogin() {
        viewModelScope.launch { repository.cancelLogin() }
    }

    companion object {

        /**
         * 앱이 실제로 사용하는 인증 의존성을 만든다.
         *
         * F001에는 인증 하나만 있으므로 DI 도구를 두지 않고 여기서 직접 조립한다.
         */
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    AuthViewModel(
                        AuthRepository(
                            store = AuthSessionStore.create(appContext),
                            api = createAuthRetrofit(BuildConfig.API_BASE_URL)
                                .create(AuthService::class.java),
                            appLinkHandler = AuthAppLinkHandler(BuildConfig.APP_LINK_HOST),
                        ),
                    )
                }
            }
        }
    }
}
