package com.gilpick.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gilpick.R
import com.gilpick.ui.theme.GilpickTheme
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickShadows
import com.gilpick.ui.theme.LocalGilpickSpacing
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** gradient 계열(가이드라인 3절 "버튼 gradient"). 화면당 강조 하나 원칙(2절)은 세 계열 모두에 적용한다. */
enum class GradientTone {
    /** 모든 주 CTA. `primary → primaryDark`, 주버튼 그림자 있음. */
    Primary,

    /** 도착 확정 행동. `success → successDark`, 그림자 없음. */
    Success,

    /** 실패 뒤 복구 행동. `warning → warningDark`, 그림자 없음. */
    Warning,
}

/** 폭 기준 곡률(가이드라인 6절 "버튼 곡률 규칙", D4). 주·보조 여부나 놓인 자리는 곡률을 바꾸지 않는다. */
enum class GradientButtonWidth {
    /** R1 화면·dialog·sheet 폭을 채우는 버튼, R2 빈 상태 가운데 너비 자동 버튼. `radius.lg` 16dp. */
    Standalone,

    /** R3 한 줄을 가로로 나눈 행동 버튼(`flex-1`·`flex-[2]`). `radius.md` 12dp. */
    Split,
}

/** Figma 실측 크기. 버튼 전용 값이라 테마 토큰이 아니라 여기에 둔다. */
object GradientButtonDefaults {
    /** 하단 CTA 기본 높이(`RoutePreviewScreen`·`CreateTripScreen` 54). Figma 범위는 46~54dp다. */
    val Height: Dp = 54.dp

    /** 처리 중 spinner(`RoutePreviewScreen` `변경하는 중` 16px). */
    val SpinnerSize: Dp = 16.dp

    /** 처리 중 투명도(`disabled:opacity-80`, 가이드라인 7절 "버튼 비활성 상태"). */
    const val ProcessingAlpha: Float = 0.8f

    /** 비활성 투명도(`TripDetailScreen` `disabled:opacity-40`, 가이드라인 7절 D2). */
    const val DisabledAlpha: Float = 0.4f

    /** spinner 한 바퀴 시간. Tailwind `animate-spin`의 1s linear infinite. */
    const val SpinDurationMillis: Int = 1000
}

/**
 * gradient 주버튼(가이드라인 11절). 화면에서 gradient 버튼을 직접 조립하지 않고 이 컴포넌트를 쓴다.
 *
 * - 색·그림자·곡률은 테마 토큰에서만 읽는다. 그림자는 [GradientTone.Primary]에만 `primaryButton`을 준다.
 * - gradient는 CSS `linear-gradient(135deg, …)`와 같게 그린다. 버튼이 가로로 길어도 색 경계선이 45° 사선이고
 *   시작·끝 색이 왼쪽 위·오른쪽 아래 모서리에 닿는다.
 * - 높이는 최소값이다. 시스템 글자 배율이 커지면 라벨이 잘리지 않도록 버튼이 세로로 늘어난다(10절).
 *   [height]가 48dp보다 작아도 터치 영역은 48dp다.
 *
 * @param label 버튼 문구. 처리 중에는 호출부가 진행 문구(예: `변경하는 중`)를 넘긴다.
 * @param processing 처리 중. gradient를 유지한 채 80% 투명도와 라벨 앞 spinner를 보이고 클릭을 막는다.
 * @param enabled 조건이 맞지 않아 누를 수 없음(예: 여행 날짜 아님). `faint` 단색 + 40% 투명도, 그림자 없음, 흰 라벨 유지(7절 D2).
 *   흰 라벨 대비가 1.48:1이라 호출부가 비활성 이유 문장을 함께 보여야 한다. 요청을 보내는 중에 잠그는 경우는
 *   비활성이 아니라 [processing]을 쓴다. 둘 다 주면 [processing]이 이긴다.
 */
@Composable
fun GradientButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: GradientTone = GradientTone.Primary,
    width: GradientButtonWidth = GradientButtonWidth.Standalone,
    height: Dp = GradientButtonDefaults.Height,
    processing: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = LocalGilpickColors.current
    val radius = LocalGilpickRadius.current
    val spacing = LocalGilpickSpacing.current
    val primary = MaterialTheme.colorScheme.primary
    // 세 계열 모두 라벨이 흰색이다(3절). onPrimary가 흰색이라 그 값을 쓴다.
    val content = MaterialTheme.colorScheme.onPrimary

    val disabled = !enabled && !processing
    val gradient = if (disabled) SolidColor(colors.faint) else when (tone) {
        GradientTone.Primary -> CssLinearGradient(listOf(primary, colors.primaryDark))
        GradientTone.Success -> CssLinearGradient(listOf(colors.success, colors.successDark))
        GradientTone.Warning -> CssLinearGradient(listOf(colors.warning, colors.warningDark))
    }
    val shadows = if (tone == GradientTone.Primary && !disabled) LocalGilpickShadows.current.primaryButton else emptyList()
    val shape = RoundedCornerShape(
        when (width) {
            GradientButtonWidth.Standalone -> radius.lg
            GradientButtonWidth.Split -> radius.md
        },
    )

    val shadowed = shadows.fold(
        modifier
            .minimumInteractiveComponentSize()
            // Modifier.alpha는 경계 밖을 잘라 그림자가 사라진다. 그리기마다 투명도를 곱해 그림자까지 같은 투명도로 둔다.
            .graphicsLayer {
                alpha = when {
                    processing -> GradientButtonDefaults.ProcessingAlpha
                    disabled -> GradientButtonDefaults.DisabledAlpha
                    else -> 1f
                }
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) { acc, shadow -> acc.dropShadow(shape, shadow) }

    Row(
        modifier = shadowed
            .heightIn(min = height)
            .clip(shape)
            .background(gradient)
            .clickable(enabled = enabled && !processing, role = Role.Button, onClick = onClick)
            .padding(horizontal = spacing.space4, vertical = spacing.space2),
        horizontalArrangement = Arrangement.spacedBy(spacing.space2, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (processing) {
            Spinner(color = content)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            textAlign = TextAlign.Center,
        )
    }
}

/** Figma lucide `loader`를 Tailwind `animate-spin`처럼 1초에 한 바퀴 돌린다. 장식이라 설명 문구가 없다. */
@Composable
private fun Spinner(color: Color) {
    val rotation by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(GradientButtonDefaults.SpinDurationMillis, easing = LinearEasing), RepeatMode.Restart),
        label = "spinnerRotation",
    )
    Icon(
        painter = painterResource(R.drawable.ic_lucide_loader),
        contentDescription = null,
        tint = color,
        modifier = Modifier
            .size(GradientButtonDefaults.SpinnerSize)
            .rotate(rotation),
    )
}

/**
 * CSS `linear-gradient(135deg, …)`와 같은 gradient.
 *
 * CSS 각도는 위쪽이 0°이고 시계 방향이다. gradient 선은 중심을 지나고 길이는 `|w·sinθ| + |h·cosθ|`라서
 * 시작·끝 색이 모서리에 닿는다. `Brush.linearGradient` 기본값(왼쪽 위 → 오른쪽 아래 대각선)은 가로로 긴
 * 버튼에서 경계선이 거의 세로가 되어 Figma와 달라진다.
 */
private data class CssLinearGradient(val colors: List<Color>, val degrees: Float = 135f) : ShaderBrush() {
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
        )
    }
}

@Preview(widthDp = 360)
@Composable
private fun GradientButtonPrimaryPreview() = PreviewBox { GradientButton(label = "변경 승인", onClick = {}, modifier = Modifier.fillMaxWidth()) }

@Preview(widthDp = 360)
@Composable
private fun GradientButtonPrimaryProcessingPreview() = PreviewBox { GradientButton(label = "변경하는 중", onClick = {}, modifier = Modifier.fillMaxWidth(), processing = true) }

@Preview(widthDp = 360)
@Composable
private fun GradientButtonSuccessPreview() = PreviewBox {
    GradientButton(label = "도착했어요", onClick = {}, tone = GradientTone.Success, width = GradientButtonWidth.Split, height = 48.dp)
}

@Preview(widthDp = 360)
@Composable
private fun GradientButtonSuccessProcessingPreview() = PreviewBox {
    GradientButton(label = "도착했어요", onClick = {}, tone = GradientTone.Success, width = GradientButtonWidth.Split, height = 48.dp, processing = true)
}

@Preview(widthDp = 360)
@Composable
private fun GradientButtonWarningPreview() = PreviewBox { GradientButton(label = "다시 만들기", onClick = {}, modifier = Modifier.fillMaxWidth(), tone = GradientTone.Warning) }

@Preview(widthDp = 360)
@Composable
private fun GradientButtonWarningProcessingPreview() = PreviewBox {
    GradientButton(label = "다시 만드는 중", onClick = {}, modifier = Modifier.fillMaxWidth(), tone = GradientTone.Warning, processing = true)
}

@Preview(widthDp = 360)
@Composable
private fun GradientButtonDisabledPreview() = PreviewBox {
    GradientButton(label = "오늘 여행 시작", onClick = {}, modifier = Modifier.fillMaxWidth(), enabled = false)
}

@Composable
private fun PreviewBox(content: @Composable () -> Unit) {
    GilpickTheme {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(LocalGilpickSpacing.current.space4),
        ) { content() }
    }
}
