package com.gilpick.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing

/**
 * 상태 뱃지의 색 계열(Figma `MyTripsScreen` 카드 칩).
 *
 * 색은 Figma 값을 그대로 쓰고 대비는 가이드라인 3절 표에 기록한다. 상태를 색으로만 구분하지 않으므로(10절) 뱃지 문구가
 * 의미를 전달하고 색은 강조만 담당한다.
 */
enum class BadgeTone {
    /** 예정·D-day. `primary` / `primaryContainer` 3.60:1(문구가 뜻을 전달). */
    ACCENT,

    /** 완료처럼 지난 상태. `muted` / `background`(문구가 뜻을 전달). */
    NEUTRAL,

    /** 여행 중. 흰 글자 / `success` 채움 2.54:1(3절 `surface` / `success`, 문구가 뜻을 전달, #441 Figma). */
    SUCCESS,
}

/**
 * 상태를 나타내는 작은 칩(Figma `px-2 py-0.5 rounded-md text-[11px] font-bold`).
 *
 * @param label 상태를 그대로 읽을 수 있는 문구. 색 없이도 뜻이 통해야 한다.
 * @param tone 색 계열.
 */
@Composable
fun StatusBadge(
    label: String,
    tone: BadgeTone,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val scheme = MaterialTheme.colorScheme

    val (background, content) = when (tone) {
        BadgeTone.ACCENT -> scheme.primaryContainer to scheme.primary
        BadgeTone.NEUTRAL -> scheme.background to colors.muted
        BadgeTone.SUCCESS -> colors.success to scheme.onPrimary
    }

    Text(
        text = label,
        // Caption 11sp 700. 칩 문구라 대문자 자간을 두지 않는다(Figma).
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
        fontWeight = FontWeight.Bold,
        color = content,
        maxLines = 1,
        modifier = modifier
            .background(color = background, shape = RoundedCornerShape(LocalGilpickRadius.current.xs))
            .padding(horizontal = spacing.space2, vertical = BADGE_VERTICAL_PADDING),
    )
}

/** Figma `py-0.5`. 간격 스케일에 없는 칩 안쪽 여백이다. */
private val BADGE_VERTICAL_PADDING = 2.dp
