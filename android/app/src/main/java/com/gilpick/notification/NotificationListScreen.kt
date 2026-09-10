package com.gilpick.notification

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gilpick.R
import com.gilpick.progress.StateMessage
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.gilpick.ui.theme.displayFont
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * 알림 목록 화면(`spec.md` US4, Figma `NotificationsScreen`, T032).
 *
 * 헤더는 뒤로 가기·`알림`·`모두 읽음` 아이콘 버튼이고, 본문은 날짜 구간 라벨(`오늘`/`어제`/`그 이전`) 아래
 * 흰 블록에 [NotificationRow]를 구분선으로 잇는다(UI-001). Figma에 없는 `loading`·`empty`·`error`는
 * ui-guidelines 9절 형식이다(UI-004): 대기 표시는 1초를 넘길 때만, 빈 상태는 아이콘 상자·제목·설명·`돌아가기`,
 * 오류는 원인·`다시 시도하기`·`돌아가기`. 재조회 중에는 기존 목록을 그대로 둔다.
 *
 * @param onBack 헤더 뒤로 가기·`돌아가기`.
 * @param onRetry `error`의 `다시 시도하기`. 같은 조회를 다시 보낸다.
 * @param onOpen 행 탭. 읽음 처리 뒤 유형별 화면으로 간다(FR-018).
 * @param onMarkAllRead 헤더 `모두 읽음`(FR-014).
 * @param onReauthenticate 로그인 상태가 만료됐다. F001 재인증 흐름으로 넘어간다.
 * @param now 상대 시각 기준. screenshot test가 고정한다.
 */
@Composable
fun NotificationListScreen(
    state: NotificationUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (NotifItemUi) -> Unit,
    onMarkAllRead: () -> Unit,
    onReauthenticate: () -> Unit,
    modifier: Modifier = Modifier,
    now: Instant = remember { Instant.now() },
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Header(onBack = onBack, onMarkAllRead = onMarkAllRead, hasUnread = state.hasUnread)
        Box(modifier = Modifier.weight(1f)) {
            when (state) {
                NotificationUiState.Loading -> DelayedLoading()
                NotificationUiState.Empty -> EmptyState(onBack = onBack)
                is NotificationUiState.Error -> ErrorState(state, onRetry = onRetry, onBack = onBack, onReauthenticate = onReauthenticate)
                is NotificationUiState.Content -> Groups(groups = state.groups, now = now, onOpen = onOpen)
            }
        }
    }
}

/** 안 읽은 알림이 하나라도 있는지. `모두 읽음` 버튼 활성 여부다. */
private val NotificationUiState.hasUnread: Boolean
    get() = this is NotificationUiState.Content && groups.any { group -> group.items.any { it.unread } }

/** Figma 헤더: 뒤로 가기 상자, `알림`, 오른쪽 `모두 읽음` 상자. 두 아이콘 버튼 모두 48dp 터치 영역이다. */
@Composable
private fun Header(onBack: () -> Unit, onMarkAllRead: () -> Unit, hasUnread: Boolean) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val title = stringResource(R.string.notification_list_title)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = spacing.space3, end = spacing.space3, top = spacing.space1, bottom = spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space1),
    ) {
        IconBoxButton(
            icon = R.drawable.ic_lucide_arrow_left,
            contentDescription = stringResource(R.string.notification_back),
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = onBack,
            modifier = Modifier.testTag(TAG_BACK),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = title.displayFont(),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconBoxButton(
            icon = R.drawable.ic_lucide_check_check,
            contentDescription = stringResource(R.string.notification_mark_all_read),
            tint = if (hasUnread) MaterialTheme.colorScheme.primary else colors.muted,
            onClick = onMarkAllRead,
            enabled = hasUnread,
            modifier = Modifier.testTag(TAG_MARK_ALL_READ),
        )
    }
}

/**
 * Figma 헤더의 둥근 아이콘 상자(`rounded-xl`, `background` 배경)를 48dp 터치 영역 안에 둔다(UI-006).
 * 여행 목록·진행 화면 헤더의 알림 벨(T036)도 같은 모양이라 함께 쓴다.
 *
 * @param box 상자 한 변. Figma는 알림 목록·진행 화면 36dp, 여행 목록 40dp다.
 */
@Composable
fun IconBoxButton(
    icon: Int,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    box: Dp = ICON_BOX,
) {
    val radius = LocalGilpickRadius.current
    Box(
        modifier = modifier
            .size(MIN_TOUCH)
            .clip(RoundedCornerShape(radius.md))
            .clickable(onClick = onClick, enabled = enabled, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(box)
                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(radius.md)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(ICON),
            )
        }
    }
}

/** 조회 대기 표시. 1초를 넘길 때만 표시한다(UI-004, 가이드라인 9절). */
@Composable
private fun DelayedLoading() {
    var visible by remember { mutableStateOf(false) }
    val label = stringResource(R.string.notification_loading)

    LaunchedEffect(Unit) {
        delay(LOADING_INDICATOR_DELAY_MILLIS)
        visible = true
    }

    if (visible) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.clearAndSetSemantics { contentDescription = label }.testTag(TAG_LOADING))
        }
    }
}

/** 받은 알림 0(UI-004): 아이콘 상자·제목·설명·`돌아가기`. */
@Composable
private fun EmptyState(onBack: () -> Unit) {
    StateMessage(
        title = stringResource(R.string.notification_empty_title),
        body = stringResource(R.string.notification_empty_body),
        icon = R.drawable.ic_lucide_bell,
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = MIN_TOUCH).testTag(TAG_EMPTY)) {
            Text(stringResource(R.string.notification_go_back))
        }
    }
}

/** 조회 실패(UI-004): 원인, `다시 시도하기`(가능할 때) 또는 `다시 로그인`, `돌아가기`. */
@Composable
private fun ErrorState(state: NotificationUiState.Error, onRetry: () -> Unit, onBack: () -> Unit, onReauthenticate: () -> Unit) {
    StateMessage(
        title = stringResource(R.string.notification_error_title),
        body = stringResource(state.error.messageRes),
        icon = R.drawable.ic_lucide_circle_x,
    ) {
        when {
            state.error == NotificationError.SessionExpired -> Button(onClick = onReauthenticate, modifier = Modifier.heightIn(min = MIN_TOUCH)) {
                Text(stringResource(R.string.place_reauthenticate))
            }
            state.retryable -> Button(onClick = onRetry, modifier = Modifier.heightIn(min = MIN_TOUCH).testTag(TAG_RETRY)) {
                Text(stringResource(R.string.notification_retry))
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = MIN_TOUCH)) {
            Text(stringResource(R.string.notification_go_back))
        }
    }
}

/** 날짜 구간 라벨 + 흰 블록 안 행 목록. 블록 안 행은 좌우 여백을 둔 구분선으로 잇는다. */
@Composable
private fun Groups(groups: List<NotifGroup>, now: Instant, onOpen: (NotifItemUi) -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current

    LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding().testTag(TAG_LIST)) {
        groups.forEachIndexed { groupIndex, group ->
            item(key = "header_${group.bucket}") {
                Text(
                    text = stringResource(group.bucket.labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    color = colors.muted,
                    modifier = Modifier
                        .padding(
                            start = spacing.space5,
                            end = spacing.space5,
                            top = if (groupIndex == 0) spacing.space4 else spacing.space5,
                            bottom = spacing.space2,
                        )
                        .testTag(TAG_GROUP_PREFIX + group.bucket.name),
                )
            }
            itemsIndexed(group.items, key = { _, item -> item.id }) { index, item ->
                Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                    NotificationRow(item = item, now = now, onClick = { onOpen(item) })
                    if (index < group.items.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = spacing.space5),
                            color = MaterialTheme.colorScheme.background,
                        )
                    }
                }
            }
        }
        item { Box(modifier = Modifier.size(spacing.space8)) }
    }
}

const val TAG_BACK = "notification_back"
const val TAG_MARK_ALL_READ = "notification_mark_all_read"
const val TAG_LOADING = "notification_loading"
const val TAG_EMPTY = "notification_empty"
const val TAG_RETRY = "notification_retry"
const val TAG_LIST = "notification_list"

/** 날짜 구간 라벨 test tag 접두사. 뒤에 [DateBucket] 이름이 붙는다. */
const val TAG_GROUP_PREFIX = "notification_group_"

/** 대기 표시를 띄우기 전까지 기다리는 시간(가이드라인 9절). */
internal const val LOADING_INDICATOR_DELAY_MILLIS = 1_000L

private val ICON_BOX: Dp = 36.dp
private val ICON: Dp = 18.dp
