package com.gilpick.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing

/**
 * 상태 뱃지의 강조 수준.
 *
 * 색은 `docs/design/ui-guidelines.md` 3절이 대비를 검증한 조합만 쓴다. 상태를 색으로만
 * 구분하지 않으므로(10절) 뱃지 문구가 의미를 전달하고 색은 강조만 담당한다.
 */
enum class BadgeTone {
    /** 지금 주목해야 하는 상태. `primary` / `primaryContainer` 4.95:1. */
    ACCENT,

    /** 그 밖의 상태. `onSurfaceVariant` / `surfaceVariant` 4.65:1. */
    NEUTRAL,
}

/**
 * 상태를 나타내는 pill.
 *
 * @param label 상태를 그대로 읽을 수 있는 문구. 색 없이도 뜻이 통해야 한다.
 * @param tone 강조 수준.
 */
@Composable
fun StatusBadge(
    label: String,
    tone: BadgeTone,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = MaterialTheme.colorScheme

    val background = when (tone) {
        BadgeTone.ACCENT -> colors.primaryContainer
        BadgeTone.NEUTRAL -> colors.surfaceVariant
    }
    val content = when (tone) {
        BadgeTone.ACCENT -> colors.primary
        BadgeTone.NEUTRAL -> colors.onSurfaceVariant
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = content,
        modifier = modifier
            .background(color = background, shape = RoundedCornerShape(radius.xl))
            .padding(horizontal = spacing.space2, vertical = spacing.space1),
    )
}
