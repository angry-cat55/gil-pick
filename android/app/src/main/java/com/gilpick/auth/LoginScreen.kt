package com.gilpick.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gilpick.R
import com.gilpick.ui.theme.GilpickTheme
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.gilpick.ui.theme.displayFont

/**
 * 로그인 화면. 밝은 배경 한 장에 로고·소개 문구(위)와 카카오 버튼·약관(아래)을 둔다(#597).
 *
 * Figma `LoginScreen`(#437)의 어두운 gradient·경로 일러스트·기능 칩·아래 흰 카드는 없앴다. 카드 위 손잡이는 끌 수 없는데도
 * 바텀시트처럼 보여 오해를 샀고, 확정 로고의 글자가 어두운 색이라 어두운 배경에서는 읽히지 않는다.
 *
 * 상태는 카카오 버튼 자리에서 알린다(가이드라인 9절 최저선): 로그인 중은 대기 표시와 문구, 실패는 원인 문구와
 * 다시 시도. 재시도는 언제나 새 Kakao 인증이며 앱이 같은 ticket이나 인가 코드를 다시 쓰지 않는다.
 * 글자 배율이 크면 화면 전체가 세로로 스크롤돼 버튼과 약관까지 닿는다.
 *
 * `Loading`은 이 화면에 오지 않는다. session 복원 중에는 `MainActivity`의 launch surface가 대신 뜬다(#616).
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

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = spacing.space6, vertical = spacing.space8),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            BrandArea()
            SignInArea(state = state, onKakaoLogin = onKakaoLogin, onRetry = onRetry)
        }
    }
}

/** 위쪽: 확정 로고와 서비스 소개 문구. 로고 이미지에 `Gilpick` 글자가 있어 앱 이름을 따로 두지 않는다. */
@Composable
private fun BrandArea() {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val headline = stringResource(R.string.login_headline)

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = BRAND_TOP_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.gilpick_logo),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier
                .widthIn(max = LOGO_MAX_WIDTH)
                .fillMaxWidth()
                .aspectRatio(LOGO_RATIO),
        )
        Text(
            text = headline,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = headline.displayFont(),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = BRAND_GAP),
        )
        Text(
            text = stringResource(R.string.login_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.space3),
        )
    }
}

/** 아래쪽: 카카오 버튼 자리의 상태 표시와 약관. */
@Composable
private fun SignInArea(state: AuthUiState, onKakaoLogin: () -> Unit, onRetry: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.space8),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            is AuthUiState.LoggingIn -> Row(
                modifier = Modifier.heightIn(min = KAKAO_HEIGHT),
                horizontalArrangement = Arrangement.spacedBy(spacing.space3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(PROGRESS_SIZE))
                Text(
                    text = stringResource(R.string.login_in_progress),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            is AuthUiState.LoginFailed -> {
                Text(
                    text = stringResource(if (state.retryable) R.string.login_error_retryable else R.string.login_error_restart),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = spacing.space3),
                )
                KakaoButton(label = R.string.login_retry, onClick = onRetry)
            }

            else -> KakaoButton(label = R.string.login_kakao, onClick = onKakaoLogin)
        }

        Text(
            text = stringResource(R.string.login_terms),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal, letterSpacing = TextStyle.Default.letterSpacing),
            color = colors.faint,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.space4),
        )
    }
}

/**
 * 카카오 로그인 버튼(가이드라인 7절 "카카오 로그인 버튼": `#FEE500`, 라벨 `#111827`, 56dp, 16dp, 말풍선 아이콘, 15sp 700).
 *
 * 배경과 라벨 색은 카카오 브랜드 가이드가 정한 고정값이라 앱 팔레트를 따르지 않는다.
 */
@Composable
private fun KakaoButton(label: Int, onClick: () -> Unit) {
    val colors = LocalGilpickColors.current
    val shape = RoundedCornerShape(LocalGilpickRadius.current.lg)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = KAKAO_HEIGHT)
            .clip(shape)
            .background(colors.kakao)
            .clickable(onClick = onClick, role = androidx.compose.ui.semantics.Role.Button)
            .padding(horizontal = LocalGilpickSpacing.current.space4),
        horizontalArrangement = Arrangement.spacedBy(LocalGilpickSpacing.current.space3, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_kakao_bubble),
            contentDescription = null,
            tint = colors.onKakao,
            modifier = Modifier.size(KAKAO_ICON),
        )
        Text(text = stringResource(label), style = MaterialTheme.typography.labelLarge, color = colors.onKakao)
    }
}

/** 확정 로고 `gilpick_logo.png` 801×311. 비율을 고정해 어느 폭에서도 늘어나거나 잘리지 않는다. */
private val LOGO_MAX_WIDTH = 240.dp
private const val LOGO_RATIO = 801f / 311f

/** 화면 위 여백과 로고~문구 간격. */
private val BRAND_TOP_PADDING = 32.dp
private val BRAND_GAP = 32.dp

/** 가이드라인 7절 카카오 버튼 56dp·말풍선 22, 로그인 중 대기 표시 24. */
private val KAKAO_HEIGHT = 56.dp
private val KAKAO_ICON = 22.dp
private val PROGRESS_SIZE = 24.dp

@Preview
@Composable
private fun LoginScreenSignedOutPreview() {
    GilpickTheme { LoginScreen(state = AuthUiState.SignedOut, onKakaoLogin = {}, onRetry = {}) }
}

@Preview
@Composable
private fun LoginScreenFailedPreview() {
    GilpickTheme { LoginScreen(state = AuthUiState.LoginFailed(code = "ERROR", retryable = true), onKakaoLogin = {}, onRetry = {}) }
}
