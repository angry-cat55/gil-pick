package com.gilpick.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gilpick.R
import com.gilpick.itinerary.DayItineraryDto
import com.gilpick.itinerary.ItemStatus
import com.gilpick.itinerary.iconRes
import com.gilpick.itinerary.labelRes
import com.gilpick.route.RouteDto
import com.gilpick.route.RouteMap
import com.gilpick.route.RouteMarks
import com.gilpick.route.distanceLabel
import com.gilpick.route.durationLabel
import com.gilpick.route.routeDateLabel
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSizing
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.gilpick.ui.theme.displayFont
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.delay

/**
 * 진행 화면(`spec.md` US1, Figma `ActiveTravelScreen`, T020).
 *
 * F006은 헤더(`여행 중`·`N일차 · x/y 완료`·여행명), 날짜 진행 표시, 오늘의 다음 장소 카드(이동 중·도착·
 * 당일 완료), 지도 자리와 `경로 보기`, 일정 목록, `장소 추가`를 그린다(UI-001). Figma의 변수 경고 배너·
 * 날씨 안내·알림/변수/설정 버튼·도착 확인 시트·변경 토스트·데모 토글은 F007·F008·F010·F011 범위라
 * 그리지 않는다(plan.md). 장소 행 탭은 상태 수정 시트(UI-003, [StatusSheet])를 열고, 날짜 점은 다른 날짜의
 * 일정을 보여 준다(UI-005). 오늘이 아닌 날짜에는 카드·행동·시트가 없고 `오늘로 돌아가기`가 있다.
 *
 * @param state 현재 상태.
 * @param tripName 헤더의 여행명. 여행 상세가 이미 알고 있어 다시 조회하지 않는다.
 * @param onRetry `error`의 `다시 시도`.
 * @param onAddPlace `empty`·목록 아래의 `장소 추가`. 오늘 날짜의 일정 편집(장소 검색)으로 간다.
 * @param onOpenRoute 지도의 `경로 보기`. 오늘 날짜의 F005 경로 화면으로 간다.
 * @param onReauthenticate 로그인 상태가 만료됐다. F001 재인증 흐름으로 넘어간다.
 * @param onArrive 이동 중 카드의 `도착했어요`(US2).
 * @param onSkip 이동 중 카드의 `건너뛰기`(US2).
 * @param onDepart 도착 카드의 `다음 장소로 출발`(US2).
 * @param onRetryAction 전환 실패 안내의 `다시 시도`. 같은 요청을 다시 보낸다.
 * @param onDismissActionError 전환 실패 안내의 `닫기`.
 * @param onStatusAction 상태 수정 시트에서 고른 `(장소, 목표 상태)`(US3).
 * @param onSelectDate 날짜 진행 표시의 점을 눌러 그 날짜를 본다(US4).
 * @param onReturnToToday `오늘로 돌아가기`(UI-005).
 * @param map 지도 영역. 기본은 F005 Naver [RouteMap]이며, UI test·screenshot은 자리 표시로 바꿔 끼운다.
 */
@Composable
fun ActiveTravelScreen(
    state: ProgressUiState,
    tripName: String,
    onRetry: () -> Unit,
    onAddPlace: () -> Unit,
    onOpenRoute: (date: String, dayNumber: Int) -> Unit,
    onReauthenticate: () -> Unit,
    modifier: Modifier = Modifier,
    onArrive: () -> Unit = {},
    onSkip: () -> Unit = {},
    onDepart: () -> Unit = {},
    onRetryAction: () -> Unit = {},
    onDismissActionError: () -> Unit = {},
    onStatusAction: (itemId: String, status: ItemStatus) -> Unit = { _, _ -> },
    onSelectDate: (LocalDate) -> Unit = {},
    onReturnToToday: () -> Unit = {},
    onDecide: (TransitionDecision) -> Unit = {},
    onRetryDecision: () -> Unit = {},
    onDismissCandidate: () -> Unit = {},
    onUndo: () -> Unit = {},
    onEnableDetection: () -> Unit = {},
    onDismissDetectionNotice: () -> Unit = {},
    map: @Composable (RouteDto, RouteMarks, Modifier) -> Unit = { route, marks, mapModifier ->
        RouteMap(route = route, marks = marks, modifier = mapModifier, sheetFraction = 0f)
    },
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Header(state = state, tripName = tripName, onSelectDate = onSelectDate, onReturnToToday = onReturnToToday)
        Box(modifier = Modifier.weight(1f)) {
            when (state) {
                ProgressUiState.Loading -> Loading()
                ProgressUiState.Empty -> EmptyState(onAddPlace = onAddPlace)
                is ProgressUiState.Error -> ErrorState(error = state.error, onRetry = onRetry, onReauthenticate = onReauthenticate)
                is ProgressUiState.Content -> Content(
                    content = state,
                    onAddPlace = onAddPlace,
                    onOpenRoute = onOpenRoute,
                    onArrive = onArrive,
                    onSkip = onSkip,
                    onDepart = onDepart,
                    onRetryAction = onRetryAction,
                    onDismissActionError = onDismissActionError,
                    onStatusAction = onStatusAction,
                    onDecide = onDecide,
                    onRetryDecision = onRetryDecision,
                    onDismissCandidate = onDismissCandidate,
                    onUndo = onUndo,
                    onEnableDetection = onEnableDetection,
                    onDismissDetectionNotice = onDismissDetectionNotice,
                    map = map,
                )
            }
        }
    }
}

/**
 * Figma 헤더: `여행 중` 칩, `N일차 · x/y 완료`, 여행명, 날짜 진행 표시. 내용이 없으면 여행명만 보인다.
 * 오늘이 아닌 날짜를 보면 `N일차 · 지난/예정 일정`과 `오늘로 돌아가기`가 아래에 붙는다(UI-005).
 */
@Composable
private fun Header(state: ProgressUiState, tripName: String, onSelectDate: (LocalDate) -> Unit, onReturnToToday: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val radius = LocalGilpickRadius.current
    val content = state as? ProgressUiState.Content

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = spacing.space5, vertical = spacing.space3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.space2)) {
            Text(
                text = stringResource(R.string.progress_badge_in_progress),
                style = MaterialTheme.typography.labelSmall,
                color = colors.success,
                modifier = Modifier
                    .background(colors.successContainer, RoundedCornerShape(radius.sm))
                    .padding(horizontal = spacing.space2 + 2.dp, vertical = 2.dp),
            )
            val dayNumber = content?.todayItinerary?.dayNumber
            if (content != null && dayNumber != null) {
                Text(
                    text = stringResource(R.string.progress_day_summary, dayNumber, content.visitedCount, content.todayRows.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    modifier = Modifier.testTag(TAG_DAY_SUMMARY),
                )
            }
        }
        Text(
            text = tripName,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = tripName.displayFont(),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = spacing.space1),
        )
        if (content != null) {
            DayProgress(
                days = content.days,
                today = content.today,
                viewing = content.viewing,
                onSelectDate = onSelectDate,
                modifier = Modifier.padding(top = spacing.space3),
            )
            val viewingItinerary = content.viewingItinerary
            if (!content.isToday && viewingItinerary != null) {
                ViewingBanner(
                    dayNumber = viewingItinerary.dayNumber,
                    past = content.viewing < content.today,
                    onReturnToToday = onReturnToToday,
                    modifier = Modifier.padding(top = spacing.space2 + 2.dp),
                )
            }
        }
    }
}

/** Figma: 오늘이 아닌 날짜의 안내 `N일차 · 지난 일정`/`예정 일정`과 `오늘로 돌아가기`(UI-005). */
@Composable
private fun ViewingBanner(dayNumber: Int, past: Boolean, onReturnToToday: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius.md))
            .background(MaterialTheme.colorScheme.background)
            .padding(start = spacing.space3 + 2.dp, end = spacing.space1)
            .testTag(TAG_VIEWING_BANNER),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(if (past) R.string.progress_viewing_past else R.string.progress_viewing_future, dayNumber),
            // weight로 버튼이 먼저 제 너비를 갖게 한다. 360dp·글자 2.0에서 `오늘로 돌아가기`가 단어 중간에서 접히지 않는다(T039).
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.muted,
        )
        Box(
            modifier = Modifier
                .heightIn(min = MIN_TOUCH)
                .clip(RoundedCornerShape(radius.sm))
                .clickable(onClick = onReturnToToday, role = Role.Button),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.progress_return_today),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = spacing.space2),
            )
        }
    }
}

/**
 * 날짜 진행 표시: 지난·오늘 날짜만큼 채운 막대와 날짜별 점·`M/D`(Figma Day progress).
 *
 * 점을 누르면 그 날짜의 일정을 본다(US4). 보고 있는 날짜의 점은 테두리 고리와 파란 글자로 구분한다.
 * 점은 작지만 터치 영역은 48dp다(UI-009). 여행은 최대 7일이라 360dp 너비에 들어간다(F002 FR-001).
 */
@Composable
private fun DayProgress(
    days: List<DayItineraryDto>,
    today: LocalDate,
    viewing: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val todayIndex = days.indexOfFirst { it.date == today.toString() }
    val filled = if (days.isEmpty()) 0f else (todayIndex.coerceAtLeast(0)).toFloat() / days.size

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.space1)
                .height(PROGRESS_BAR_HEIGHT)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(filled)
                    .height(PROGRESS_BAR_HEIGHT)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.space1 + 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            days.forEachIndexed { index, day ->
                val date = LocalDate.parse(day.date)
                val reached = index <= todayIndex
                val isViewing = date == viewing
                val description = stringResource(R.string.progress_day_dot_description, day.dayNumber, date.monthValue, date.dayOfMonth)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing.space1),
                    modifier = Modifier
                        .sizeIn(minWidth = MIN_TOUCH, minHeight = MIN_TOUCH)
                        .clickable(onClick = { onSelectDate(date) }, role = Role.Button)
                        .semantics(mergeDescendants = true) { contentDescription = description },
                ) {
                    Box(
                        modifier = Modifier
                            .size(DAY_DOT_RING)
                            .border(2.dp, if (isViewing) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(DAY_DOT)
                                .clip(CircleShape)
                                .background(if (reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                                .border(2.dp, if (reached) MaterialTheme.colorScheme.primary else colors.faint, CircleShape),
                        )
                    }
                    Text(
                        text = stringResource(R.string.progress_day_dot, date.monthValue, date.dayOfMonth),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isViewing) MaterialTheme.colorScheme.primary else colors.faint,
                    )
                }
            }
        }
    }
}

/** 조회 대기 표시. 1초를 넘길 때만 표시한다(UI-008, 가이드라인 9절). */
@Composable
private fun Loading() {
    var visible by remember { mutableStateOf(false) }
    val label = stringResource(R.string.progress_loading)

    LaunchedEffect(Unit) {
        delay(LOADING_INDICATOR_DELAY_MILLIS)
        visible = true
    }

    if (visible) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.clearAndSetSemantics { contentDescription = label })
        }
    }
}

/** 빈 상태(가이드라인 9절): 오늘 날짜에 장소가 없다. `장소 추가`를 제공한다(UI-008). */
@Composable
private fun EmptyState(onAddPlace: () -> Unit) {
    StateMessage(
        title = stringResource(R.string.progress_empty_title),
        body = stringResource(R.string.progress_empty_body),
        icon = R.drawable.ic_map,
    ) {
        Button(onClick = onAddPlace, modifier = Modifier.heightIn(min = MIN_TOUCH)) {
            Text(stringResource(R.string.progress_add_place))
        }
    }
}

/** 오류 상태: 원인 문구와 `다시 시도`. 세션 만료는 `다시 로그인`으로 잇는다. */
@Composable
private fun ErrorState(error: ProgressError, onRetry: () -> Unit, onReauthenticate: () -> Unit) {
    StateMessage(
        title = stringResource(R.string.progress_error_title),
        body = stringResource(error.messageRes),
        icon = R.drawable.ic_lucide_circle_x,
    ) {
        if (error == ProgressError.SessionExpired) {
            Button(onClick = onReauthenticate, modifier = Modifier.heightIn(min = MIN_TOUCH)) {
                Text(stringResource(R.string.place_reauthenticate))
            }
        } else {
            Button(onClick = onRetry, modifier = Modifier.heightIn(min = MIN_TOUCH)) {
                Text(stringResource(R.string.progress_retry))
            }
        }
    }
}

/** 가운데 안내. 아이콘·제목·설명·행동 순서는 가이드라인 9절 빈 상태 형식이다. F009 대체 장소 화면도 쓴다. */
@Composable
internal fun StateMessage(title: String, body: String, icon: Int, actions: @Composable () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val sizing = LocalGilpickSizing.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.space8, vertical = spacing.space6),
        verticalArrangement = Arrangement.spacedBy(spacing.space3, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(sizing.emptyIconCircle)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(sizing.emptyIcon),
            )
        }
        Text(text = title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(spacing.space2),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = spacing.space2),
        ) { actions() }
    }
}

/**
 * 세로 스크롤 본문: 다음 장소 카드(오늘만), 지도, 일정 목록, `장소 추가`.
 *
 * 장소 행을 누르면 [StatusSheet]가 열린다. 오늘이면서 시작된 날짜에서만 열리고(UI-003), 행동을 고르면
 * 닫힌 뒤 전환을 요청한다. 시트에 보이는 장소는 여는 순간의 행이다.
 */
@Composable
private fun Content(
    content: ProgressUiState.Content,
    onAddPlace: () -> Unit,
    onOpenRoute: (date: String, dayNumber: Int) -> Unit,
    onArrive: () -> Unit,
    onSkip: () -> Unit,
    onDepart: () -> Unit,
    onRetryAction: () -> Unit,
    onDismissActionError: () -> Unit,
    onStatusAction: (itemId: String, status: ItemStatus) -> Unit,
    onDecide: (TransitionDecision) -> Unit,
    onRetryDecision: () -> Unit,
    onDismissCandidate: () -> Unit,
    onUndo: () -> Unit,
    onEnableDetection: () -> Unit,
    onDismissDetectionNotice: () -> Unit,
    map: @Composable (RouteDto, RouteMarks, Modifier) -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val itinerary = content.viewingItinerary
    var sheetRow by remember { mutableStateOf<ProgressRow?>(null) }
    val canEdit = content.isToday && content.viewingStarted

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.space4)
            .navigationBarsPadding(),
    ) {
        Spacer(modifier = Modifier.height(spacing.space3))
        // Figma `ActiveTravelScreen`은 이 안내를 스크롤 영역 맨 위, 다른 배너보다 앞에 둔다.
        content.visibleDetectionNotice?.let { reason ->
            DetectionOffBanner(
                reason = reason,
                onEnable = onEnableDetection,
                onDismiss = onDismissDetectionNotice,
                modifier = Modifier.padding(bottom = spacing.space2),
            )
        }
        if (content.isToday) {
            NextPlaceCard(content = content, onArrive = onArrive, onSkip = onSkip, onDepart = onDepart)
        }
        content.actionError?.let { failure ->
            ActionErrorBar(failure = failure, onRetry = onRetryAction, onDismiss = onDismissActionError, modifier = Modifier.padding(top = spacing.space2))
        }
        // 자동 확정 직후의 되돌리기(UI-003). 되돌릴 수 있는 동안만 보인다.
        content.visibleUndoable?.let { undoable ->
            UndoToast(
                undoable = undoable,
                placeName = content.undoablePlaceName,
                now = content.now,
                submitting = content.undoPending,
                error = content.undoError,
                onUndo = onUndo,
                modifier = Modifier.padding(top = spacing.space2),
            )
        }
        if (itinerary != null) {
            MapSlot(
                route = itinerary.route,
                marks = if (content.isToday) content.progress.toRouteMarks() else RouteMarks.NONE,
                onOpenRoute = { onOpenRoute(itinerary.date, itinerary.dayNumber) },
                map = map,
                modifier = Modifier.padding(top = spacing.space3),
            )
            ItemList(
                content = content,
                itinerary = itinerary,
                onRowClick = if (canEdit) { row -> sheetRow = row } else null,
                modifier = Modifier.padding(top = spacing.space3),
            )
        }
        AddPlaceButton(onAddPlace = onAddPlace, modifier = Modifier.padding(top = spacing.space3, bottom = spacing.space4))
    }

    sheetRow?.let { row ->
        StatusSheet(
            row = row,
            onAction = { status ->
                sheetRow = null
                onStatusAction(row.item.itemId, status)
            },
            onDismiss = { sheetRow = null },
        )
    }

    // 답을 기다리는 후보가 있으면 확인 시트를 띄운다. 닫아도 후보는 살아 있다(UI-007).
    content.visibleCandidate?.let { candidate ->
        ConfirmSheet(
            candidate = candidate,
            placeName = content.candidatePlaceName,
            now = content.now,
            submitting = content.decisionPending != null,
            error = content.decisionFailure?.error,
            onDecide = onDecide,
            onRetry = onRetryDecision,
            onDismiss = onDismissCandidate,
        )
    }
}

/**
 * 다음 장소 카드(UI-002·UI-006). 당일 상태에 따라 세 모양 중 하나다.
 *
 * - 당일 `COMPLETED`: 초록 체크, `오늘 일정을 모두 마쳤어요`, `N곳 방문 · 마지막 도착 시각`, 출발 행동 없음.
 * - `ARRIVED` 장소가 있음: `현재 장소`, 장소명, `도착 시각 · 체류 예정`, `다음 장소로 출발`.
 * - 다음 장소가 있음: `다음 장소`, 장소명, `예상 도착` 시각과 남은·지난 시간, 이전 장소에서의 이동, `도착했어요`·`건너뛰기`.
 *
 * 시작 전 날짜(`NOT_STARTED`)나 남은 장소가 없는 진행 중 날짜에는 카드를 그리지 않는다. 전환 요청 중에는
 * 내용을 그대로 두고 행동만 비활성화하며 요청한 버튼에 진행 표시를 겹친다(UI-008).
 */
@Composable
private fun NextPlaceCard(content: ProgressUiState.Content, onArrive: () -> Unit, onSkip: () -> Unit, onDepart: () -> Unit) {
    val progress = content.progress
    val current = content.currentRow
    val next = content.nextRow
    val pending = content.pendingAction
    when {
        progress.dayStatus == DayStatus.COMPLETED -> AllDoneCard(content)
        current != null -> ArrivedCard(row = current, hasNext = next != null, pending = pending, onDepart = onDepart)
        next != null && progress.dayStatus == DayStatus.IN_PROGRESS -> MovingCard(content = content, row = next, pending = pending, onArrive = onArrive, onSkip = onSkip)
    }
}

/** 전환 실패 안내(US2 시나리오 8): 원인 문구와 `다시 시도`(재전송이 뜻 있을 때)·`닫기`. */
@Composable
private fun ActionErrorBar(failure: ProgressActionFailure, onRetry: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius.lg))
            .background(colors.warningContainer)
            .padding(horizontal = spacing.space4, vertical = spacing.space3)
            .testTag(TAG_ACTION_ERROR),
    ) {
        Text(
            text = stringResource(failure.error.messageRes),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onWarningContainer,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.space2), modifier = Modifier.padding(top = spacing.space1)) {
            if (failure.retryable) {
                TextAction(label = stringResource(R.string.progress_retry), onClick = onRetry)
            }
            TextAction(label = stringResource(R.string.progress_dismiss), onClick = onDismiss)
        }
    }
}

/**
 * 자동 감지 꺼짐 안내(UI-005, Figma `ActiveTravelScreen` Location permission banner).
 *
 * 원인과 켜는 방법, 닫기를 함께 둔다. 안내를 따르지 않아도 진행은 계속되므로 닫을 수 있어야
 * 한다(FR-025). 색만으로 알리지 않고 문구로 원인을 적는다(가이드라인 10절).
 */
@Composable
private fun DetectionOffBanner(
    reason: DetectionOffReason,
    onEnable: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius.lg))
            .background(colors.warningContainer)
            .padding(horizontal = spacing.space4, vertical = spacing.space3)
            .testTag(TAG_DETECTION_OFF),
    ) {
        Text(
            text = stringResource(reason.causeRes),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onWarningContainer,
        )
        Text(
            text = stringResource(reason.howToRes),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onWarningContainer,
            modifier = Modifier.padding(top = spacing.space1),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.space2), modifier = Modifier.padding(top = spacing.space1)) {
            // 정확도 부족은 사용자가 권한으로 풀 수 있는 문제가 아니다. 켜는 행동을 주지 않는다.
            if (reason == DetectionOffReason.PermissionMissing) {
                TextAction(label = stringResource(R.string.detection_off_enable), onClick = onEnable)
            }
            TextAction(label = stringResource(R.string.detection_off_dismiss), onClick = onDismiss)
        }
    }
}

/** 꺼진 원인 문구. */
private val DetectionOffReason.causeRes: Int
    get() = when (this) {
        DetectionOffReason.PermissionMissing -> R.string.detection_off_permission
        DetectionOffReason.AccuracyLow -> R.string.detection_off_accuracy
    }

/** 켜는 방법과 켜지 않아도 된다는 안내. */
private val DetectionOffReason.howToRes: Int
    get() = when (this) {
        DetectionOffReason.PermissionMissing -> R.string.detection_off_permission_how
        DetectionOffReason.AccuracyLow -> R.string.detection_off_accuracy_how
    }

@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = MIN_TOUCH)
            .clip(RoundedCornerShape(LocalGilpickRadius.current.sm))
            .clickable(onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = LocalGilpickSpacing.current.space2),
        )
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius.xl))
            .background(MaterialTheme.colorScheme.surface)
            .padding(spacing.space5),
    ) { content() }
}

@Composable
private fun AllDoneCard(content: ProgressUiState.Content) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val radius = LocalGilpickRadius.current
    val visited = content.visitedCount
    val lastArrival = content.progress.items.mapNotNull { it.actualArrivedAt }.maxOrNull()

    Card(modifier = Modifier.testTag(TAG_CARD_ALL_DONE)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.space2),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(ALL_DONE_ICON_BOX)
                    .background(colors.successContainer, RoundedCornerShape(radius.lg)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_check),
                    contentDescription = null,
                    tint = colors.success,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                text = stringResource(R.string.progress_all_done_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = spacing.space3),
            )
            Text(
                text = if (lastArrival != null) {
                    stringResource(R.string.progress_all_done_body, visited, timeLabel(lastArrival))
                } else {
                    stringResource(R.string.progress_all_done_body_no_time, visited)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = spacing.space1),
            )
        }
    }
}

/**
 * 도착 카드. 뒤에 남은 장소가 없으면 `다음 장소로 출발`을 그리지 않는다(T030 "마지막이면 없음"). 마지막 장소에
 * 도착하면 서버가 당일 완료로 바꾸므로(FR-013) 보통은 [AllDoneCard]가 대신 보인다.
 */
@Composable
private fun ArrivedCard(row: ProgressRow, hasNext: Boolean, pending: ProgressAction?, onDepart: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val arrivedAt = row.progress.actualArrivedAt

    Card(modifier = Modifier.testTag(TAG_CARD_ARRIVED)) {
        CardLabel(stringResource(R.string.progress_card_current))
        PlaceName(row.item.place.name)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space2),
            modifier = Modifier.padding(top = spacing.space1, bottom = if (hasNext) spacing.space4 else 0.dp),
        ) {
            if (arrivedAt != null) {
                Text(
                    text = stringResource(R.string.progress_arrived_at, timeLabel(arrivedAt)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.success,
                )
                Dot()
            }
            Text(
                text = stringResource(R.string.progress_stay_planned, row.item.plannedStayMinutes),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
            )
        }
        if (hasNext) {
            PrimaryAction(
                label = stringResource(R.string.progress_action_depart),
                onClick = onDepart,
                enabled = pending == null,
                busy = pending?.status == ItemStatus.COMPLETED,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MovingCard(content: ProgressUiState.Content, row: ProgressRow, pending: ProgressAction?, onArrive: () -> Unit, onSkip: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val radius = LocalGilpickRadius.current
    val eta = row.progress.estimatedArrivalAt

    Card(modifier = Modifier.testTag(TAG_CARD_NEXT)) {
        CardLabel(stringResource(R.string.progress_card_next))
        PlaceName(row.item.place.name)
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(spacing.space2),
            modifier = Modifier.padding(top = spacing.space1),
        ) {
            Text(
                text = stringResource(R.string.progress_eta_prefix),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.muted,
                modifier = Modifier.padding(bottom = spacing.space1),
            )
            if (eta != null) {
                val etaLabel = timeLabel(eta)
                Text(
                    text = etaLabel,
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = etaLabel.displayFont(),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag(TAG_ETA),
                )
            } else {
                Text(
                    text = stringResource(R.string.progress_eta_unknown),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.muted,
                    modifier = Modifier.testTag(TAG_ETA),
                )
            }
        }
        if (eta != null) {
            val overdue = Instant.parse(eta) < content.now
            Text(
                text = remainingLabel(Instant.parse(eta), content.now),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (overdue) FontWeight.SemiBold else null,
                color = if (overdue) colors.warning else colors.muted,
                modifier = Modifier.testTag(TAG_REMAINING),
            )
        }
        Text(
            text = inboundLabel(content, row),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            modifier = Modifier.padding(top = spacing.space1, bottom = spacing.space4),
        )
        val enabled = pending == null
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.space2)) {
            PrimaryAction(
                label = stringResource(R.string.progress_action_arrive),
                onClick = onArrive,
                enabled = enabled,
                busy = pending?.status == ItemStatus.ARRIVED,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(radius.md),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = MIN_TOUCH)
                    .alpha(if (enabled) 1f else DISABLED_ALPHA)
                    .clip(RoundedCornerShape(radius.md))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = enabled, onClick = onSkip, role = Role.Button),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.progress_action_skip),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = spacing.space2, vertical = spacing.space3),
                )
                if (pending?.status == ItemStatus.SKIPPED) {
                    BusyIndicator(MaterialTheme.colorScheme.onSurfaceVariant, Modifier.align(Alignment.CenterEnd).padding(end = spacing.space3))
                }
            }
        }
    }
}

/** 요청 중인 버튼의 오른쪽 끝에 겹치는 작은 진행 표시(UI-008 "진행 중임을 표시"). 글자를 가리지 않는다. */
@Composable
private fun BusyIndicator(color: Color, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.progress_action_pending)
    CircularProgressIndicator(
        color = color,
        strokeWidth = 2.dp,
        modifier = modifier
            .size(BUSY_INDICATOR)
            .semantics { contentDescription = label }
            .testTag(TAG_BUSY),
    )
}

/** 이전 장소(또는 출발 위치)에서의 이동수단·시간·거리(UI-002). 계산되지 않았으면 `이동 정보 없음`. */
@Composable
private fun inboundLabel(content: ProgressUiState.Content, row: ProgressRow): String {
    val travel = row.progress.inboundTravel ?: return stringResource(R.string.progress_inbound_unknown)
    val mode = stringResource(travel.transportMode.labelRes)
    val duration = durationLabel(travel.durationSeconds)
    val distance = distanceLabel(travel.distanceMeters)
    val from = travel.fromItemId?.let { id -> content.rows.firstOrNull { it.item.itemId == id }?.item?.place?.name }
    return if (from != null) {
        stringResource(R.string.progress_inbound_from, from, mode, duration, distance)
    } else {
        stringResource(R.string.progress_inbound_from_start, mode, duration, distance)
    }
}

@Composable
private fun CardLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = LocalGilpickColors.current.muted,
        modifier = Modifier.padding(bottom = LocalGilpickSpacing.current.space1),
    )
}

@Composable
private fun PlaceName(name: String) {
    Text(
        text = name,
        style = MaterialTheme.typography.headlineSmall,
        fontFamily = name.displayFont(),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun Dot() {
    Box(
        modifier = Modifier
            .size(4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * Figma 주버튼: 135° `primary → primaryDark` gradient, 48dp, 흰 글자. F004 저장 버튼과 같은 조립이다.
 *
 * @param enabled 요청 중이면 `false`. 눌리지 않고 흐리게 보인다.
 * @param busy 이 버튼의 요청이 진행 중이다. 글자 옆에 진행 표시를 겹친다.
 */
@Composable
private fun PrimaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    shape: RoundedCornerShape = RoundedCornerShape(LocalGilpickRadius.current.lg),
) {
    Box(
        modifier = modifier
            .heightIn(min = MIN_TOUCH)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(shape)
            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, LocalGilpickColors.current.primaryDark)))
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = LocalGilpickSpacing.current.space2, vertical = LocalGilpickSpacing.current.space3),
        )
        if (busy) BusyIndicator(Color.White, Modifier.align(Alignment.CenterEnd).padding(end = LocalGilpickSpacing.current.space3))
    }
}

/**
 * 지도 자리(UI-011): F005 [RouteMap]을 150dp 높이 둥근 상자에 넣고 `경로 보기`를 겹친다.
 *
 * 경로가 아직 없으면(`NOT_CALCULATED`·`FAILED`) 지도 대신 안내 문구를 두되 `경로 보기`는 남긴다. 경로
 * 화면이 그 상태를 자세히 안내한다. 지도 정보는 아래 일정 목록이 같은 순서로 제공한다.
 */
@Composable
private fun MapSlot(
    route: RouteDto?,
    marks: RouteMarks,
    onOpenRoute: () -> Unit,
    map: @Composable (RouteDto, RouteMarks, Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(MAP_HEIGHT)
            .clip(RoundedCornerShape(radius.lg))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .testTag(TAG_MAP_SLOT),
    ) {
        if (route != null) {
            map(route, marks, Modifier.fillMaxSize())
        } else {
            Text(
                text = stringResource(R.string.progress_map_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = spacing.space4),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(spacing.space3)
                .heightIn(min = MIN_TOUCH)
                .clip(RoundedCornerShape(radius.sm))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onOpenRoute, role = Role.Button),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.progress_map_route),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = spacing.space3, vertical = spacing.space2),
            )
        }
    }
}

/**
 * 일정 목록(UI-004): `N일차 일정` 제목, `M월 D일 · K곳`, 장소 행.
 *
 * @param onRowClick 행 탭. `null`이면 행을 누를 수 없다(오늘 아님·시작 전).
 */
@Composable
private fun ItemList(
    content: ProgressUiState.Content,
    itinerary: DayItineraryDto,
    onRowClick: ((ProgressRow) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val rows = content.rows
    val date = LocalDate.parse(itinerary.date)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.space1, end = spacing.space1, bottom = spacing.space2),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.progress_list_title, itinerary.dayNumber),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.progress_list_subtitle, date.routeDateLabel, rows.size),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(radius.lg))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = spacing.space4, vertical = spacing.space3),
        ) {
            rows.forEachIndexed { index, row ->
                ItemRow(
                    row = row,
                    next = rows.getOrNull(index + 1),
                    last = index == rows.lastIndex,
                    started = content.viewingStarted,
                    showTime = content.isToday,
                    autoProcessed = content.isToday && row.item.itemId in content.autoProcessedItemIds,
                    onClick = onRowClick?.let { click -> { click(row) } },
                )
            }
        }
    }
}

/**
 * 장소 한 행: 상태별 순서 원(아이콘 또는 번호)과 세로선, 장소명과 상태 칩, 실제 시각 또는 ETA, 다음 장소로의 이동.
 *
 * 상태는 칩 문구와 아이콘으로 구분한다(UI-004, 색 단독 금지). [onClick]이 있으면 행 탭으로 상태 수정 시트를 연다.
 *
 * @param showTime 오늘이면 실제 시각·ETA를 보인다. 다른 날짜는 개요에 시각이 없어 상태만 보인다.
 */
@Composable
private fun ItemRow(row: ProgressRow, next: ProgressRow?, last: Boolean, started: Boolean, showTime: Boolean, autoProcessed: Boolean, onClick: (() -> Unit)?) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val status = row.progress.status
    val name = row.item.place.name
    val statusLabel = stringResource(status.progressLabelRes)
    val timeText = rowTimeLabel(row.progress)
    val description = when {
        showTime -> stringResource(R.string.progress_row_description, row.item.sequence, name, statusLabel, timeText)
        started -> stringResource(R.string.progress_row_description_no_time, row.item.sequence, name, statusLabel)
        else -> stringResource(R.string.progress_row_description_planned, row.item.sequence, name)
    }
    val planned = status == ItemStatus.PLANNED

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag("$TAG_ROW_PREFIX${row.item.sequence}"),
        horizontalArrangement = Arrangement.spacedBy(spacing.space3),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StatusCircle(status = status, sequence = row.item.sequence)
            if (!last) {
                Box(
                    modifier = Modifier
                        .padding(top = spacing.space1)
                        .width(1.dp)
                        .height(ROW_LINE_HEIGHT)
                        .background(if (status == ItemStatus.COMPLETED) colors.success else MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Column(modifier = Modifier.weight(1f).padding(bottom = if (last) 0.dp else spacing.space2)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.space2)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (planned && started) colors.muted else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (started) StatusChip(status = status, label = statusLabel)
                // 자동으로 바뀐 상태는 색이 아닌 문구로 알린다(UI-004).
                if (autoProcessed) AutoProcessedChip()
            }
            if (showTime) {
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (planned) colors.faint else colors.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            val toNext = row.item.transportModeToNext
            if (next != null && toNext != null) {
                val travel = next.progress.inboundTravel
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.space1),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Icon(
                        painter = painterResource(toNext.iconRes),
                        contentDescription = null,
                        tint = colors.faint,
                        modifier = Modifier.size(TRANSPORT_ICON),
                    )
                    Text(
                        text = if (travel != null) {
                            stringResource(R.string.progress_transport_to_next, stringResource(toNext.labelRes), durationLabel(travel.durationSeconds))
                        } else {
                            stringResource(toNext.labelRes)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.faint,
                    )
                }
            }
        }
    }
}

/** 행의 시각 문구(UI-004): 처리된 장소는 실제 시각, 남은 장소는 ETA 또는 `정보 없음`. */
@Composable
private fun rowTimeLabel(progress: ProgressItemDto): String = when (progress.status) {
    ItemStatus.COMPLETED -> progress.completedAt?.let { stringResource(R.string.progress_row_completed, timeLabel(it)) }
        ?: stringResource(R.string.itinerary_edit_status_completed)
    ItemStatus.ARRIVED -> progress.actualArrivedAt?.let { stringResource(R.string.progress_row_arrived, timeLabel(it)) }
        ?: stringResource(R.string.itinerary_edit_status_arrived)
    ItemStatus.SKIPPED -> stringResource(R.string.progress_row_skipped)
    ItemStatus.PLANNED, ItemStatus.EN_ROUTE -> progress.estimatedArrivalAt?.let { stringResource(R.string.progress_row_eta, timeLabel(it)) }
        ?: stringResource(R.string.progress_eta_unknown)
}

/** Figma 순서 원: 완료·도착 초록 체크, 건너뜀 회색 X, 이동 중 파란 화살표, 예정 연회색 번호. */
@Composable
private fun StatusCircle(status: ItemStatus, sequence: Int) {
    val colors = LocalGilpickColors.current
    val background = when (status) {
        ItemStatus.COMPLETED, ItemStatus.ARRIVED -> colors.success
        ItemStatus.SKIPPED -> colors.faint
        ItemStatus.EN_ROUTE -> MaterialTheme.colorScheme.primary
        ItemStatus.PLANNED -> MaterialTheme.colorScheme.outlineVariant
    }
    val icon = status.progressIconRes

    Box(
        modifier = Modifier
            .size(STATUS_CIRCLE)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
        } else {
            Text(
                text = sequence.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = sequence.toString().displayFont(),
                color = colors.muted,
            )
        }
    }
}

/**
 * 자동으로 처리된 장소 표시(UI-004, Figma `자동 처리` 칩).
 *
 * 사용자가 확인 질문을 보지 못한 채 상태가 바뀔 수 있으므로(FR-015a) 무엇이 자동으로
 * 처리됐는지 알 수 있어야 한다. 색이 아니라 문구로 알린다(가이드라인 10절).
 */
@Composable
private fun AutoProcessedChip() {
    val colors = LocalGilpickColors.current
    val radius = LocalGilpickRadius.current
    val spacing = LocalGilpickSpacing.current

    Text(
        text = stringResource(R.string.detection_auto_processed),
        style = MaterialTheme.typography.labelSmall,
        color = colors.muted,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(radius.sm))
            .padding(horizontal = spacing.space2, vertical = 2.dp),
    )
}

/** Figma `STATUS_CHIP`: 예정·이동 중 파랑, 도착·완료 초록, 건너뜀 회색. 문구가 뜻을 전달한다. 상태 수정 시트도 쓴다. */
@Composable
internal fun StatusChip(status: ItemStatus, label: String) {
    val colors = LocalGilpickColors.current
    val radius = LocalGilpickRadius.current
    val spacing = LocalGilpickSpacing.current
    val (background, foreground) = when (status) {
        ItemStatus.PLANNED, ItemStatus.EN_ROUTE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
        ItemStatus.ARRIVED, ItemStatus.COMPLETED -> colors.successContainer to colors.success
        ItemStatus.SKIPPED -> MaterialTheme.colorScheme.surfaceVariant to colors.muted
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = foreground,
        modifier = Modifier
            .background(background, RoundedCornerShape(radius.xs))
            .padding(horizontal = spacing.space2, vertical = 2.dp),
    )
}

/** Figma `장소 추가`: 파란 점선 테두리, 연한 파란 배경, `+`와 문구. */
@Composable
private fun AddPlaceButton(onAddPlace: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val shape = RoundedCornerShape(radius.md)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TOUCH)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), shape)
            .clickable(onClick = onAddPlace, role = Role.Button)
            .testTag(TAG_ADD_PLACE),
        horizontalArrangement = Arrangement.spacedBy(spacing.space2, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lucide_plus),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.progress_add_place),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** UI test가 찾는 tag. */
internal const val TAG_DAY_SUMMARY = "progress_day_summary"
internal const val TAG_CARD_NEXT = "progress_card_next"
internal const val TAG_CARD_ARRIVED = "progress_card_arrived"
internal const val TAG_CARD_ALL_DONE = "progress_card_all_done"
internal const val TAG_ETA = "progress_eta"
internal const val TAG_REMAINING = "progress_remaining"
internal const val TAG_MAP_SLOT = "progress_map_slot"
internal const val TAG_ROW_PREFIX = "progress_row_"
internal const val TAG_ADD_PLACE = "progress_add_place"
internal const val TAG_ACTION_ERROR = "progress_action_error"

/** 자동 감지 꺼짐 안내(UI-005). */
internal const val TAG_DETECTION_OFF = "progress_detection_off"
internal const val TAG_BUSY = "progress_busy"
internal const val TAG_VIEWING_BANNER = "progress_viewing_banner"

private const val LOADING_INDICATOR_DELAY_MILLIS = 1_000L

/** 가이드라인 10절 최소 터치 영역. */
private val MIN_TOUCH: Dp = 48.dp

/** 비활성 버튼의 투명도(F004 저장 버튼과 같다). */
private const val DISABLED_ALPHA = 0.5f

/** 요청 중 버튼 위 진행 표시 크기. */
private val BUSY_INDICATOR: Dp = 16.dp

/** Figma 지도 자리 높이(`h-[150px]`). */
private val MAP_HEIGHT: Dp = 150.dp

/** Figma 날짜 진행 막대(`h-1.5`)와 점(`w-2.5`). */
private val PROGRESS_BAR_HEIGHT: Dp = 6.dp
private val DAY_DOT: Dp = 10.dp

/** 보고 있는 날짜 점의 고리(Figma `ring-2 ring-offset-1`). */
private val DAY_DOT_RING: Dp = 18.dp

/** Figma 일정 행의 순서 원(`w-6`), 세로선(`h-7`), 이동수단 아이콘(11px). */
private val STATUS_CIRCLE: Dp = 24.dp
private val ROW_LINE_HEIGHT: Dp = 28.dp
private val TRANSPORT_ICON: Dp = 11.dp

/** Figma 당일 완료 카드의 체크 상자(`w-14`). */
private val ALL_DONE_ICON_BOX: Dp = 56.dp
