package com.gilpick.notification

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gilpick.R
import com.gilpick.alternative.AlternativeError
import com.gilpick.alternative.clockLabel
import com.gilpick.alternative.messageRes
import com.gilpick.progress.StateMessage
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.gilpick.ui.theme.displayFont
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * 감지 목록 화면(`spec.md` US6, Figma `VariableMonitorScreen`, T040).
 *
 * 헤더는 뒤로 가기·`변수 감지`. 본문은 경고 배너(`방문이 어려울 수 있어요`), `감지 N건`과 정렬 토글, 감지 카드
 * (장소명·요약·변수별 판정·제외 사유·방문 예정 시각·감지 경과·`대체 장소 보기`), 아래 `여행 진행 화면으로`다.
 * `empty`는 Figma `hasAlerts=false`(방패 아이콘·`모든 일정이 예정대로예요`), `error`는 F009 오류 형식이다.
 *
 * @param onBack 헤더 뒤로 가기·`여행 진행 화면으로`·`돌아가기`. 이 화면은 진행 화면에서 열리므로 모두 되돌아간다.
 * @param onRetry `error`의 `다시 시도하기`.
 * @param onOpenDetection 카드의 `대체 장소 보기`. 그 감지의 대체 장소 화면으로 간다.
 * @param onReauthenticate 로그인 상태가 만료됐다. F001 재인증 흐름으로 넘어간다.
 * @param now 감지 경과 문구 기준. screenshot test가 고정한다.
 */
@Composable
fun VariableMonitorScreen(
    state: VariableMonitorUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onToggleSort: () -> Unit,
    onOpenDetection: (detectionId: String) -> Unit,
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
        Header(onBack = onBack)
        Box(modifier = Modifier.weight(1f)) {
            when (state) {
                VariableMonitorUiState.Loading -> DelayedLoading()
                VariableMonitorUiState.Empty -> EmptyState(onBack = onBack)
                is VariableMonitorUiState.Error -> ErrorState(state, onRetry = onRetry, onBack = onBack, onReauthenticate = onReauthenticate)
                is VariableMonitorUiState.Content -> Content(state, now = now, onToggleSort = onToggleSort, onOpenDetection = onOpenDetection)
            }
        }
        if (state is VariableMonitorUiState.Content) ToProgressButton(onBack)
    }
}

/** Figma 헤더: 뒤로 가기 상자와 `변수 감지`. */
@Composable
private fun Header(onBack: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val title = stringResource(R.string.notification_monitor_title)

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
            modifier = Modifier.testTag(TAG_MONITOR_BACK),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = title.displayFont(),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 조회 대기 표시. 1초를 넘길 때만 표시한다(가이드라인 9절). */
@Composable
private fun DelayedLoading() {
    var visible by remember { mutableStateOf(false) }
    val label = stringResource(R.string.monitor_loading)

    LaunchedEffect(Unit) {
        delay(LOADING_INDICATOR_DELAY_MILLIS)
        visible = true
    }

    if (visible) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.clearAndSetSemantics { contentDescription = label }.testTag(TAG_MONITOR_LOADING))
        }
    }
}

/** Figma `hasAlerts=false`: 초록 방패 상자, `모든 일정이 예정대로예요`, 설명, `여행 진행 화면으로`. */
@Composable
private fun EmptyState(onBack: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.space6, vertical = spacing.space6),
            verticalArrangement = Arrangement.spacedBy(spacing.space2, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .padding(bottom = spacing.space4)
                    .size(EMPTY_ICON_BOX)
                    .background(colors.successContainer, RoundedCornerShape(radius.xl)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_shield_check),
                    contentDescription = null,
                    tint = colors.success,
                    modifier = Modifier.size(EMPTY_ICON),
                )
            }
            val title = stringResource(R.string.monitor_empty_title)
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = title.displayFont(),
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(TAG_MONITOR_EMPTY),
            )
            Text(
                text = stringResource(R.string.monitor_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
                textAlign = TextAlign.Center,
            )
        }
        ToProgressButton(onBack)
    }
}

/** 조회 실패: F009 오류 형식(원인, `다시 시도하기` 또는 `다시 로그인`, `돌아가기`). */
@Composable
private fun ErrorState(state: VariableMonitorUiState.Error, onRetry: () -> Unit, onBack: () -> Unit, onReauthenticate: () -> Unit) {
    StateMessage(
        title = stringResource(R.string.monitor_error_title),
        body = stringResource(state.error.messageRes),
        icon = R.drawable.ic_lucide_circle_x,
    ) {
        when {
            state.error == AlternativeError.SessionExpired -> Button(onClick = onReauthenticate, modifier = Modifier.heightIn(min = MIN_TOUCH)) {
                Text(stringResource(R.string.place_reauthenticate))
            }
            state.retryable -> Button(onClick = onRetry, modifier = Modifier.heightIn(min = MIN_TOUCH).testTag(TAG_MONITOR_RETRY)) {
                Text(stringResource(R.string.alternative_retry))
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = MIN_TOUCH)) {
            Text(stringResource(R.string.alternative_go_back))
        }
    }
}

/** 배너, `감지 N건` + 정렬 토글, 감지 카드 목록. */
@Composable
private fun Content(
    content: VariableMonitorUiState.Content,
    now: Instant,
    onToggleSort: () -> Unit,
    onOpenDetection: (detectionId: String) -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val items = content.sorted

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(TAG_MONITOR_LIST),
        contentPadding = PaddingValues(horizontal = spacing.space4, vertical = spacing.space4),
        verticalArrangement = Arrangement.spacedBy(spacing.space3),
    ) {
        item(key = "banner") { Banner(placeCount = items.distinctBy { it.item.itemId }.size) }
        item(key = "sort") { SortRow(count = items.size, sort = content.sort, onToggleSort = onToggleSort) }
        items(items, key = { it.item.detectionId }) { detection ->
            DetectionCard(detection = detection, now = now, onOpen = { onOpenDetection(detection.item.detectionId) })
        }
    }
}

/** Figma 상단 경고 배너: 주황 상자 안 경고 아이콘, `방문이 어려울 수 있어요`, `남은 일정 N곳에서 변수 감지`. */
@Composable
private fun Banner(placeCount: Int) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val title = stringResource(R.string.monitor_banner_title)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space3),
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.warningContainer, RoundedCornerShape(radius.lg))
            .padding(spacing.space4)
            .semantics(mergeDescendants = true) {},
    ) {
        Box(
            modifier = Modifier
                .size(BANNER_ICON_BOX)
                .background(colors.warning, RoundedCornerShape(radius.md)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_triangle_alert),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(BANNER_ICON),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = title.displayFont(),
                fontWeight = FontWeight.Black,
            )
            Text(
                text = stringResource(R.string.monitor_banner_body, placeCount),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onWarningContainer,
            )
        }
    }
}

/** `감지 N건`과 정렬 토글(`시간순`/`위험순` + 아래 화살표). 토글은 48dp 터치 영역이다. */
@Composable
private fun SortRow(count: Int, sort: DetectionSort, onToggleSort: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val current = stringResource(sort.labelRes)
    val next = stringResource(if (sort == DetectionSort.TIME) DetectionSort.RISK.labelRes else DetectionSort.TIME.labelRes)
    val toggleDescription = stringResource(R.string.monitor_sort_toggle, current, next)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.space1),
    ) {
        Text(
            text = stringResource(R.string.monitor_count, count),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).testTag(TAG_MONITOR_COUNT),
        )
        TextButton(
            onClick = onToggleSort,
            modifier = Modifier
                .heightIn(min = MIN_TOUCH)
                .semantics { contentDescription = toggleDescription }
                .testTag(TAG_MONITOR_SORT),
        ) {
            Text(text = current, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Icon(
                painter = painterResource(R.drawable.ic_lucide_chevron_down),
                contentDescription = null,
                modifier = Modifier.padding(start = spacing.space1).size(SMALL_ICON),
            )
        }
    }
}

/**
 * 감지 카드(Figma alert card): 경고 아이콘·장소명, 요약, 변수별 판정 줄, 제외 사유, 구분선,
 * `HH:mm 방문 예정 · N분 전 감지`와 `대체 장소 보기`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetectionCard(detection: DetectionUi, now: Instant, onOpen: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val item = detection.item
    val rows = detection.variables?.variableRows()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(radius.lg))
            .padding(spacing.space4)
            .testTag(TAG_MONITOR_CARD_PREFIX + item.detectionId),
        verticalArrangement = Arrangement.spacedBy(spacing.space3),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.space1), modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.space2)) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_triangle_alert),
                    contentDescription = null,
                    tint = colors.warning,
                    modifier = Modifier.size(CARD_ICON),
                )
                Text(
                    text = item.placeName,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = item.placeName.displayFont(),
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(text = item.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (rows == null) {
            Text(
                text = stringResource(R.string.monitor_variables_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.space2)) {
                rows.filter { it.value != null }.forEach { row -> VariableLine(row) }
            }
            rows.mapNotNull { it.note }.forEach { note -> ExcludedNote(note) }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.background)
        // 큰 글자 배율에서 시각 문구가 글자 단위로 꺾이지 않도록 버튼이 다음 줄로 내려간다.
        FlowRow(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.monitor_visit_at, clockLabel(item.eta)) +
                    stringResource(R.string.alternative_separator) +
                    stringResource(R.string.monitor_detected_ago, relativeTimeLabel(item.createdAt, now)),
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            TextButton(
                onClick = onOpen,
                modifier = Modifier.heightIn(min = MIN_TOUCH).testTag(TAG_MONITOR_OPEN_PREFIX + item.detectionId),
            ) {
                Text(text = stringResource(R.string.monitor_open_alternatives), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_chevron_right),
                    contentDescription = null,
                    modifier = Modifier.padding(start = spacing.space1).size(SMALL_ICON),
                )
            }
        }
    }
}

/** 변수 한 줄: 아이콘, 변수명, 판정 값. 위험이면 값이 `error` 색이되 문구가 뜻을 전달한다(가이드라인 10절). */
@Composable
private fun VariableLine(row: VariableRow) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        Icon(painter = painterResource(row.icon), contentDescription = null, tint = colors.muted, modifier = Modifier.size(SMALL_ICON))
        Text(text = row.label, style = MaterialTheme.typography.bodySmall, color = colors.muted)
        Text(
            text = row.value.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = if (row.risk) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 제외 사유 안내(Figma `infoNote`): 연한 파란 상자 안 정보 아이콘과 문구. */
@Composable
private fun ExcludedNote(note: String) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current

    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.primarySoft, RoundedCornerShape(radius.md))
            .padding(horizontal = spacing.space3, vertical = spacing.space2)
            .semantics(mergeDescendants = true) {},
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lucide_info),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp).size(SMALL_ICON),
        )
        Text(text = note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

/** 아래 `여행 진행 화면으로`(Figma: `background` 배경, 회색 글자). */
@Composable
private fun ToProgressButton(onBack: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current

    Button(
        onClick = onBack,
        shape = RoundedCornerShape(radius.lg),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = spacing.space4, end = spacing.space4, top = spacing.space3, bottom = spacing.space8)
            .heightIn(min = BOTTOM_BUTTON)
            .testTag(TAG_MONITOR_TO_PROGRESS),
    ) {
        Text(stringResource(R.string.monitor_to_progress))
    }
}

const val TAG_MONITOR_BACK = "monitor_back"
const val TAG_MONITOR_LOADING = "monitor_loading"
const val TAG_MONITOR_EMPTY = "monitor_empty"
const val TAG_MONITOR_RETRY = "monitor_retry"
const val TAG_MONITOR_LIST = "monitor_list"
const val TAG_MONITOR_COUNT = "monitor_count"
const val TAG_MONITOR_SORT = "monitor_sort"
const val TAG_MONITOR_TO_PROGRESS = "monitor_to_progress"

/** 감지 카드 test tag 접두사. 뒤에 `detectionId`가 붙는다. */
const val TAG_MONITOR_CARD_PREFIX = "monitor_card_"

/** `대체 장소 보기` test tag 접두사. 뒤에 `detectionId`가 붙는다. */
const val TAG_MONITOR_OPEN_PREFIX = "monitor_open_"

private val BOTTOM_BUTTON: Dp = 52.dp
private val EMPTY_ICON_BOX: Dp = 80.dp
private val EMPTY_ICON: Dp = 32.dp
private val BANNER_ICON_BOX: Dp = 40.dp
private val BANNER_ICON: Dp = 18.dp
private val CARD_ICON: Dp = 14.dp
private val SMALL_ICON: Dp = 13.dp
