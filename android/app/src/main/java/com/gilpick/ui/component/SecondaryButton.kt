package com.gilpick.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing

/**
 * `background` 채움 보조 버튼(가이드라인 3절 `background` 행, 9절 오류 화면 보조 버튼).
 * Figma `ErrorScreen` 돌아가기(48), `VariableMonitorScreen` 여행 진행 화면으로(52), `RoutePreviewScreen` 다른 후보 보기(50).
 *
 * 폭을 채우는 버튼이라 곡률은 `radiusLg`다(6절 D4). 라벨은 14sp 600 `onSurfaceVariant`.
 * [height]는 최소 높이이며 글자 배율이 커지면 늘어난다. 터치 영역은 48dp 이상이다.
 * 비활성이면 Figma `disabled:opacity-40`대로 40% 투명이다(`RoutePreviewScreen` 승인 중 `다른 후보 보기`).
 */
@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    enabled: Boolean = true,
) {
    val spacing = LocalGilpickSpacing.current
    val shape = RoundedCornerShape(LocalGilpickRadius.current.lg)

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .heightIn(min = height)
            .clip(shape)
            .background(MaterialTheme.colorScheme.background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = spacing.space4, vertical = spacing.space2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private const val DISABLED_ALPHA = 0.4f
