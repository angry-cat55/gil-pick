package com.gilpick.auth

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.gilpick.ui.theme.GilpickTheme
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * #437: 로그인 화면 screenshot 기록(Figma `LoginScreen` 대조용).
 *
 * 검증이 아니라 기록이다. 저장 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/`.
 */
class LoginScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 로그아웃() = capture("login_signed_out") { Screen(AuthUiState.SignedOut) }

    @Test
    fun 로그인_중() = capture("login_logging_in") { Screen(AuthUiState.LoggingIn) }

    @Test
    fun 실패_재시도_가능() = capture("login_failed_retryable") { Screen(AuthUiState.LoginFailed(code = "PROVIDER", retryable = true)) }

    @Test
    fun 실패_재시작_필요() = capture("login_failed_restart") { Screen(AuthUiState.LoginFailed(code = "TICKET", retryable = false)) }

    @Test
    fun 로그아웃_360dp_최대_글자배율() = capture("login_signed_out_360dp_fontscale2") {
        Box(modifier = Modifier.width(360.dp)) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) { Screen(AuthUiState.SignedOut) }
        }
    }

    @Composable
    private fun Screen(state: AuthUiState) {
        LoginScreen(state = state, onKakaoLogin = {}, onRetry = {})
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent { GilpickTheme { content() } }
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
