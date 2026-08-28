package com.gilpick.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing

/**
 * 여행 한 건을 나타내는 카드.
 *
 * `docs/design/ui-guidelines.md` 7절의 "여행 카드"다. 그림자 대신 `surface`와 화면
 * 바탕의 밝기 차이로 떠 있는 느낌을 만든다.
 *
 * 도메인 타입을 받지 않고 문자열만 받는다. `com.gilpick.ui.component`가 특정 feature에
 * 의존하면 다른 feature가 이 컴포넌트를 쓸 근거가 없어진다(가이드라인 11절).
 *
 * @param title 여행명.
 * @param period 여행 기간. 보조 정보 색으로 표시한다.
 * @param supporting 일수 같은 한 줄 부가 정보.
 * @param badgeLabel 상태 뱃지 문구.
 * @param badgeTone 상태 뱃지 강조 수준.
 * @param onClick 카드를 눌렀을 때 동작. `null`이면 누를 수 없다.
 */
@Composable
fun TripCard(
    title: String,
    period: String,
    supporting: String,
    badgeLabel: String,
    badgeTone: BadgeTone,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(radius.md),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TOUCH_TARGET)
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
    ) {
        Column(
            modifier = Modifier.padding(spacing.space4),
            verticalArrangement = Arrangement.spacedBy(spacing.space2),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                StatusBadge(label = badgeLabel, tone = badgeTone)
            }
            Text(
                text = period,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 가이드라인 10절: 누를 수 있는 영역은 48dp 이상. */
private val MIN_TOUCH_TARGET = Dp(48f)
