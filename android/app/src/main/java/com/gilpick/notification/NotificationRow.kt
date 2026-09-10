package com.gilpick.notification

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gilpick.R
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSizing
import com.gilpick.ui.theme.LocalGilpickSpacing
import java.time.Instant

/**
 * 알림 목록 행(Figma `NotificationsScreen` 항목, T032).
 *
 * 앞은 유형 아이콘 상자, 뒤는 제목·본문·상대 시각이다. 안 읽음은 `surfaceTint` 배경과 제목 옆 `groupDot`
 * 점으로 함께 구분하고(UI-002, 색 단독 금지), 점에는 `안 읽음` 설명을 둬 스크린리더도 같은 정보를 받는다.
 * 행 전체가 48dp 이상 터치 영역이다(UI-006).
 *
 * @param now 상대 시각 기준. 화면이 한 번 정해 모든 행에 같은 값을 준다.
 */
@Composable
fun NotificationRow(item: NotifItemUi, now: Instant, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val radius = LocalGilpickRadius.current
    val sizing = LocalGilpickSizing.current
    val unreadLabel = stringResource(R.string.notification_unread)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (item.unread) colors.surfaceTint else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick, role = Role.Button)
            .sizeIn(minHeight = MIN_TOUCH)
            .padding(horizontal = spacing.space5, vertical = spacing.space4)
            .testTag(TAG_ROW_PREFIX + item.id),
        horizontalArrangement = Arrangement.spacedBy(spacing.space3),
    ) {
        Box(
            modifier = Modifier
                .size(ICON_BOX)
                .background(
                    if (item.unread) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
                    RoundedCornerShape(radius.lg),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(item.type.iconRes),
                contentDescription = null,
                tint = if (item.unread) MaterialTheme.colorScheme.primary else colors.muted,
                modifier = Modifier.size(ICON),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.space2)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (item.unread) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (item.unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
                if (item.unread) {
                    Box(
                        modifier = Modifier
                            .padding(top = spacing.space1 + 2.dp)
                            .size(sizing.groupDot)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .semantics { contentDescription = unreadLabel }
                            .testTag(TAG_UNREAD_DOT_PREFIX + item.id),
                    )
                }
            }
            Text(
                text = item.body,
                style = MaterialTheme.typography.bodySmall,
                color = if (item.unread) MaterialTheme.colorScheme.outline else colors.muted,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = relativeTimeLabel(item.createdAt, now),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = colors.faint,
                modifier = Modifier.padding(top = spacing.space1),
            )
        }
    }
}

/** 행 test tag 접두사. 뒤에 알림 ID가 붙는다. */
const val TAG_ROW_PREFIX = "notification_row_"

/** 안 읽음 점 test tag 접두사. 뒤에 알림 ID가 붙는다. */
const val TAG_UNREAD_DOT_PREFIX = "notification_unread_"

internal val MIN_TOUCH: Dp = 48.dp
private val ICON_BOX: Dp = 40.dp
private val ICON: Dp = 18.dp
