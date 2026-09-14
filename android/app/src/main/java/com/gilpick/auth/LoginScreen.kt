package com.gilpick.auth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gilpick.R
import com.gilpick.ui.theme.GilpickTheme
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.gilpick.ui.theme.displayFont
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 로그인 화면(Figma `LoginScreen`, #437). 2026-09-04 팀 결정의 정본인 Figma Make 기준이며, 폐기된 `.pen` 기준 구현을 대체한다.
 *
 * 어두운 gradient 위 브랜드 영역(경로 일러스트·로고·`길픽`·부제·기능 칩)과 아래 흰 카드(손잡이·안내·카카오 버튼·약관)다.
 * Figma에 없는 `길픽 로그인` 비활성 버튼은 두지 않는다(AGENTS.md 6절, 가이드라인 12절).
 *
 * 상태는 Figma 모양 안, 카드의 카카오 버튼 자리에서 알린다(가이드라인 9절 최저선): 세션 복원 중은 대기 표시, 로그인 중은
 * 대기 표시와 문구, 실패는 원인 문구와 다시 시도. 재시도는 언제나 새 Kakao 인증이며 앱이 같은 ticket이나 인가 코드를 다시
 * 쓰지 않는다. 글자 배율이 크면 화면 전체가 세로로 스크롤돼 카드까지 닿는다.
 *
 * @param state 현재 인증 상태. `Loading`, `SignedOut`, `LoggingIn`, `LoginFailed`만 의미가 있다.
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
    val colors = LocalGilpickColors.current
    val background = CssAngleGradient(listOf(colors.dark, colors.darkGradientMid, colors.darkGradientEnd), BACKGROUND_STOPS, BACKGROUND_ANGLE)

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(background)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            BrandArea()
            BottomCard(state = state, onKakaoLogin = onKakaoLogin, onRetry = onRetry)
        }
    }
}

/** 브랜드 영역: 경로 일러스트, 48dp 로고 상자 + Brand `길픽`, `onDarkMuted` 부제, 기능 칩 3개. */
@Composable
private fun BrandArea() {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val scheme = MaterialTheme.colorScheme
    val appName = stringResource(R.string.app_name)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = spacing.space8, end = spacing.space8, top = BRAND_TOP_PADDING, bottom = spacing.space6),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RouteIllustration(
            modifier = Modifier
                .widthIn(max = ILLUSTRATION_MAX_WIDTH)
                .fillMaxWidth()
                .aspectRatio(ILLUSTRATION_RATIO),
        )
        Row(
            modifier = Modifier.padding(top = BRAND_GAP, bottom = spacing.space4),
            horizontalArrangement = Arrangement.spacedBy(spacing.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(LOGO_BOX)
                    .background(scheme.primary, RoundedCornerShape(LocalGilpickRadius.current.md)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_navigation),
                    // 바로 옆 `길픽`이 뜻을 전달하는 장식이다(10절).
                    contentDescription = null,
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(LOGO_ICON),
                )
            }
            Text(
                text = appName,
                style = MaterialTheme.typography.displayMedium,
                fontFamily = appName.displayFont(),
                color = scheme.onPrimary,
            )
        }
        Text(
            text = stringResource(R.string.login_tagline),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            color = colors.onDarkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = spacing.space6),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.space2, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(spacing.space2),
            modifier = Modifier.padding(top = spacing.space2),
        ) {
            listOf(R.string.login_feature_congestion, R.string.login_feature_weather, R.string.login_feature_reroute).forEach {
                FeatureChip(stringResource(it))
            }
        }
    }
}

/** 기능 칩(Figma `px-3.5 py-1.5 rounded-full text-[12px] font-semibold`, primary 30% 테두리·8% 배경, `onDarkMuted` 글자). */
@Composable
private fun FeatureChip(label: String) {
    val primary = MaterialTheme.colorScheme.primary
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = LocalGilpickColors.current.onDarkMuted,
        modifier = Modifier
            .clip(CircleShape)
            .background(primary.copy(alpha = CHIP_FILL_ALPHA))
            .border(CHIP_BORDER, primary.copy(alpha = CHIP_BORDER_ALPHA), CircleShape)
            .padding(horizontal = CHIP_HORIZONTAL_PADDING, vertical = CHIP_VERTICAL_PADDING),
    )
}

/**
 * Figma 경로 일러스트(300×200 viewBox)를 Compose로 그린다.
 *
 * SVG의 격자 `pattern`과 `text`는 vector drawable이 지원하지 않아 drawable로 옮기지 못한다. 같은 좌표·색·투명도를 Canvas로
 * 옮겼고 색은 토큰(`primary`·`warning`·`muted`)에서 읽는다. 장식이라 스크린리더가 읽지 않는다. 확정 자산이 아니라 Figma 자리
 * 표시 그림이다(PR 기록).
 */
@Composable
private fun RouteIllustration(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val warning = LocalGilpickColors.current.warning
    val muted = LocalGilpickColors.current.muted
    val onBadge = MaterialTheme.colorScheme.onPrimary
    val measurer = rememberTextMeasurer()
    val congestion = stringResource(R.string.login_illustration_congestion)
    val reroute = stringResource(R.string.login_illustration_reroute)

    Canvas(modifier = modifier.clearAndSetSemantics {}) {
        val u = size.width / VIEW_WIDTH
        fun p(x: Float, y: Float) = Offset(x * u, y * u)

        // 24 단위 격자(stroke 0.3, 35%)
        var g = 0f
        while (g <= VIEW_WIDTH) {
            drawLine(primary.copy(alpha = 0.35f), p(g, 0f), p(g, VIEW_HEIGHT), strokeWidth = 0.3f * u)
            g += GRID
        }
        g = 0f
        while (g <= VIEW_HEIGHT) {
            drawLine(primary.copy(alpha = 0.35f), p(0f, g), p(VIEW_WIDTH, g), strokeWidth = 0.3f * u)
            g += GRID
        }

        dashedCurve(primary.copy(alpha = 0.45f), 2.5f * u, u, p(30f, 160f), p(80f, 140f), p(130f, 85f), p(185f, 65f))
        dashedCurve(warning.copy(alpha = 0.55f), 2f * u, u, p(30f, 160f), p(70f, 130f), p(150f, 105f), p(240f, 55f))

        drawCircle(primary.copy(alpha = 0.12f), 14f * u, p(30f, 160f))
        drawCircle(primary.copy(alpha = 0.9f), 7f * u, p(30f, 160f))
        drawCircle(muted.copy(alpha = 0.6f), 4f * u, p(115f, 110f))
        drawCircle(warning.copy(alpha = 0.12f), 16f * u, p(185f, 65f))
        drawCircle(warning.copy(alpha = 0.95f), 7f * u, p(185f, 65f))
        drawCircle(primary.copy(alpha = 0.7f), 5f * u, p(240f, 55f))

        val badgeStyle = TextStyle(color = onBadge, fontWeight = FontWeight.Bold, fontSize = (8f * u).toSp(), textAlign = TextAlign.Center)
        listOf(
            Triple(Offset(192f, 48f), Size(54f, 16f), warning to congestion),
            Triple(Offset(248f, 38f), Size(48f, 16f), primary to reroute),
        ).forEach { (topLeft, badge, pair) ->
            val (color, label) = pair
            drawRoundRect(color.copy(alpha = 0.9f), p(topLeft.x, topLeft.y), Size(badge.width * u, badge.height * u), CornerRadius(8f * u))
            val layout = measurer.measure(label, badgeStyle)
            drawText(
                layout,
                topLeft = Offset(
                    (topLeft.x + badge.width / 2) * u - layout.size.width / 2f,
                    (topLeft.y + badge.height / 2) * u - layout.size.height / 2f,
                ),
            )
        }
    }
}

/** SVG `C` 곡선 하나를 `strokeDasharray="6 3"`으로 그린다. */
private fun DrawScope.dashedCurve(color: Color, width: Float, unit: Float, start: Offset, c1: Offset, c2: Offset, end: Offset) {
    val path = Path().apply {
        moveTo(start.x, start.y)
        cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
    }
    drawPath(path, color, style = Stroke(width = width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * unit, 3f * unit))))
}

/**
 * 아래 흰 카드: 위쪽 곡률 36(가이드라인 6절 로그인 카드), 손잡이, 안내, 카카오 버튼 자리의 상태 표시, 약관.
 */
@Composable
private fun BottomCard(state: AuthUiState, onKakaoLogin: () -> Unit, onRetry: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = CARD_RADIUS, topEnd = CARD_RADIUS))
            .background(scheme.surface)
            .navigationBarsPadding()
            .padding(start = spacing.space6, end = spacing.space6, top = spacing.space8, bottom = CARD_BOTTOM_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = spacing.space6)
                .width(HANDLE_WIDTH)
                .height(HANDLE_HEIGHT)
                .background(scheme.outlineVariant, CircleShape),
        )
        Text(
            text = stringResource(R.string.login_card_title),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = spacing.space4),
        )

        when (state) {
            // 저장된 session을 복원하는 동안에는 로그인 수단을 잠깐 보였다 감추지 않는다.
            is AuthUiState.Loading -> Box(modifier = Modifier.heightIn(min = KAKAO_HEIGHT), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

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

/** CSS `linear-gradient(<angle>deg, …)`처럼 각도와 색 위치를 받아 그리는 배경 gradient. */
private data class CssAngleGradient(val colors: List<Color>, val stops: List<Float>, val degrees: Float) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val radians = Math.toRadians(degrees.toDouble())
        val dx = sin(radians).toFloat()
        val dy = -cos(radians).toFloat()
        val half = (abs(size.width * dx) + abs(size.height * dy)) / 2
        val cx = size.width / 2
        val cy = size.height / 2
        return LinearGradientShader(
            from = Offset(cx - dx * half, cy - dy * half),
            to = Offset(cx + dx * half, cy + dy * half),
            colors = colors,
            colorStops = stops,
        )
    }
}

/** Figma 배경 `linear-gradient(160deg, #0B1120 0%, #0E1A3A 55%, #0F2050 100%)`. */
private const val BACKGROUND_ANGLE = 160f
private val BACKGROUND_STOPS = listOf(0f, 0.55f, 1f)

/** Figma 일러스트 viewBox 300×200, 격자 24, 최대 너비 300. */
private const val VIEW_WIDTH = 300f
private const val VIEW_HEIGHT = 200f
private const val GRID = 24f
private const val ILLUSTRATION_RATIO = 1.5f
private val ILLUSTRATION_MAX_WIDTH = 300.dp

/** Figma 브랜드 영역: 위 48(`pt-12`), 일러스트 아래 40(`mb-10`), 로고 상자 48·아이콘 24. */
private val BRAND_TOP_PADDING = 48.dp
private val BRAND_GAP = 40.dp
private val LOGO_BOX = 48.dp
private val LOGO_ICON = 24.dp

/** Figma 기능 칩: 좌우 14, 위아래 6, 1px 테두리, primary 30%·8%. */
private val CHIP_HORIZONTAL_PADDING = 14.dp
private val CHIP_VERTICAL_PADDING = 6.dp
private val CHIP_BORDER = 1.dp
private const val CHIP_BORDER_ALPHA = 0.3f
private const val CHIP_FILL_ALPHA = 0.08f

/** Figma 흰 카드: 위쪽 곡률 36(`rounded-t-[36px]`, 가이드라인 6절 로그인 카드), 아래 40(`pb-10`), 손잡이 40×4. */
private val CARD_RADIUS = 36.dp
private val CARD_BOTTOM_PADDING = 40.dp
private val HANDLE_WIDTH = 40.dp
private val HANDLE_HEIGHT = 4.dp

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
