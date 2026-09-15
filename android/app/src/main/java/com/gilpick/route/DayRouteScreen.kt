package com.gilpick.route

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gilpick.ui.component.TAG_HEADER_BACK
import com.gilpick.R
import com.gilpick.itinerary.ItemStatus
import com.gilpick.itinerary.iconRes
import com.gilpick.itinerary.labelRes
import com.gilpick.progress.progressIconRes
import com.gilpick.progress.progressLabelRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.unit.sp
import com.gilpick.ui.component.EmptyState
import com.gilpick.ui.component.ErrorState
import com.gilpick.ui.component.GradientButton
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSizing
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.gilpick.ui.theme.displayFont
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription

/**
 * 날짜별 경로 화면(`spec.md` US2, Figma `DayRouteScreen`).
 *
 * 모양은 Figma의 전체 화면 지도와 어두운 하단 sheet를 따르되, 지도는 도로·지명이 보이도록 밝은 기본
 * 지도로 둔다(#549). 시작된 날짜는 [RouteUiState.Content.marks]로 marker와 구간 목록에 `완료`·`이동 중`·
 * `건너뜀`을 문구+아이콘으로 겹친다(F006 UI-011, T031). `여행 중` 배지와
 * 도착 예정 시각은 진행 화면의 몫이라 그리지 않는다(UI-001·UI-010). 지도 위 정보는 지도를 못 보는 사용자를
 * 위해 같은 순서의 구간 목록으로도 제공한다(UI-005, 가이드라인 10절).
 *
 * @param state 현재 상태.
 * @param dayNumber 헤더 제목의 `N일차`.
 * @param date 헤더 부제의 날짜.
 * @param onBack 뒤로 가기.
 * @param onRetry `error`의 `다시 시도`.
 * @param onAddPlace `empty`의 `장소 추가`. 그 날짜의 일정 편집(장소 검색)으로 간다.
 * @param onReauthenticate 로그인 상태가 만료됐다. F001 재인증 흐름으로 넘어간다.
 * @param map 지도 영역. 세 번째 인자는 sheet가 덮는 높이 비율이다. 기본은 Naver [RouteMap]이며, UI test·
 *   screenshot은 SDK 인증 없이 그릴 수 있는 자리 표시로 바꿔 끼운다.
 */
@Composable
fun DayRouteScreen(
    state: RouteUiState,
    dayNumber: Int,
    date: LocalDate,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onAddPlace: () -> Unit,
    onReauthenticate: () -> Unit,
    modifier: Modifier = Modifier,
    map: @Composable (RouteDto, RouteMarks, Float, Modifier) -> Unit = { route, marks, sheetFraction, mapModifier ->
        RouteMap(route = route, marks = marks, modifier = mapModifier, sheetFraction = sheetFraction)
    },
) {
    val colors = LocalGilpickColors.current

    // 오류는 화면 전체를 대신한다(가이드라인 9절 오류 화면, #434 결정: 어두운 변형은 빈 상태만 둔다).
    if (state is RouteUiState.Error) {
        ErrorState(problem = state.problem, onRetry = onRetry, onBack = onBack, onReauthenticate = onReauthenticate, modifier = modifier.fillMaxSize().statusBarsPadding())
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.darkMap)
            .statusBarsPadding(),
    ) {
        Header(
            dayNumber = dayNumber,
            date = date,
            placeCount = (state as? RouteUiState.Content)?.route?.markers?.size,
            onBack = onBack,
        )
        Box(modifier = Modifier.weight(1f)) {
            when (state) {
                RouteUiState.Loading -> Loading()
                RouteUiState.Empty -> EmptyState(onAddPlace = onAddPlace, onBack = onBack)
                is RouteUiState.Error -> Unit

                is RouteUiState.Content -> Content(route = state.route, marks = state.marks, map = map)
            }
        }
    }
}

/** Figma 헤더: 36dp 흰 반투명 원형 뒤로 버튼(터치 48dp), `N일차 경로` 제목, `M월 D일 · K곳` 부제. */
@Composable
private fun Header(dayNumber: Int, date: LocalDate, placeCount: Int?, onBack: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val back = stringResource(R.string.route_back)
    val title = stringResource(R.string.route_title, dayNumber)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.space5 - (MIN_TOUCH - HEADER_BUTTON) / 2, end = spacing.space5, top = spacing.space1, bottom = spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space3 - (MIN_TOUCH - HEADER_BUTTON) / 2),
    ) {
        Box(
            modifier = Modifier
                .size(MIN_TOUCH)
                .clip(CircleShape)
                .clickable(onClick = onBack, role = Role.Button)
                .semantics { contentDescription = back }
                .testTag(TAG_HEADER_BACK),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(HEADER_BUTTON)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_arrow_left),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = title.displayFont(),
                color = Color.White,
            )
            Text(
                text = if (placeCount != null) {
                    stringResource(R.string.route_subtitle, date.routeDateLabel, placeCount)
                } else {
                    stringResource(R.string.route_subtitle_no_count, date.routeDateLabel)
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f),
            )
        }
    }
}

/** 조회 대기 표시. 1초를 넘길 때만 표시한다(UI-003, 가이드라인 9절). */
@Composable
private fun Loading() {
    var visible by remember { mutableStateOf(false) }
    val label = stringResource(R.string.route_loading)

    LaunchedEffect(Unit) {
        delay(LOADING_INDICATOR_DELAY_MILLIS)
        visible = true
    }

    if (visible) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.semantics { contentDescription = label },
            )
        }
    }
}

/** 빈 상태(가이드라인 9절 형식): 64dp 원각 사각 안 `faint` 지도 아이콘, 제목, 설명, `장소 추가`·`돌아가기`. */
/** 빈 상태: 공통 `EmptyState`의 어두운 배경 변형(#434), `장소 추가` 주버튼과 `돌아가기`. */
@Composable
private fun EmptyState(onAddPlace: () -> Unit, onBack: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    EmptyState(
        icon = R.drawable.ic_map,
        title = stringResource(R.string.route_empty_title),
        body = stringResource(R.string.route_empty_body),
        onDark = true,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        action = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.space2), horizontalAlignment = Alignment.CenterHorizontally) {
                GradientButton(label = stringResource(R.string.route_add_place), onClick = onAddPlace)
                BackButton(onBack)
            }
        },
    )
}

/** 오류 상태: 원인 문구와 `다시 시도`, `돌아가기`. 세션 만료는 `다시 로그인`으로 잇는다. */
/**
 * 오류 상태: 공통 오류 화면(가이드라인 9절). 원인 문구, `다시 시도`(세션 만료면 `다시 로그인`), 보조 `돌아가기`.
 * 발생 시각·마지막 동작은 이 화면이 모르는 값이라 원인 카드를 그리지 않는다.
 */
@Composable
private fun ErrorState(problem: RouteProblem, onRetry: () -> Unit, onBack: () -> Unit, onReauthenticate: () -> Unit, modifier: Modifier = Modifier) {
    val sessionExpired = (problem as? RouteProblem.Request)?.error == RouteError.SessionExpired

    ErrorState(
        title = stringResource(R.string.route_error_title),
        description = stringResource(problem.messageRes),
        primaryLabel = stringResource(if (sessionExpired) R.string.place_reauthenticate else R.string.route_retry),
        onPrimary = if (sessionExpired) onReauthenticate else onRetry,
        secondaryLabel = stringResource(R.string.route_go_back),
        onSecondary = onBack,
        modifier = modifier,
    )
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    OutlinedButton(
        onClick = onBack,
        modifier = Modifier.heightIn(min = MIN_TOUCH),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
    ) {
        Text(stringResource(R.string.route_go_back))
    }
}


/** sheet 높이 단계(#550). 접힘은 합계·출처까지, 기본은 화면의 [SHEET_DEFAULT_FRACTION], 펼침은 [SHEET_EXPANDED_FRACTION]까지다. */
private enum class SheetAnchor { COLLAPSED, DEFAULT, EXPANDED }

/**
 * 전체 화면 지도와 그 위에 겹치는 하단 sheet.
 *
 * sheet는 기본으로 화면 높이의 45%까지 차지해 지도 조작 영역을 남긴다(UI-009). 윗부분(손잡이·합계)을 끌면
 * 높이가 손가락을 따라가고, 놓으면 끈 방향의 다음 단계에 붙는다. 손잡이를 누르거나
 * 접근성 action으로도 단계를 바꾼다(#550). 구간이 많거나 글자가 크면 sheet 안에서 세로로 스크롤한다.
 * sheet가 멈추면 그 높이 비율을 [map]에 넘겨 지도 SDK의 로고·marker가 sheet에 가리지 않게 한다.
 */
@Composable
private fun Content(route: RouteDto, marks: RouteMarks, map: @Composable (RouteDto, RouteMarks, Float, Modifier) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxPx = constraints.maxHeight.toFloat()
        val flingPx = with(density) { SHEET_FLING_VELOCITY.toPx() }
        val slopPx = with(density) { SHEET_STEP_DISTANCE.toPx() }
        var anchor by rememberSaveable { mutableStateOf(SheetAnchor.DEFAULT) }
        // 접힘 높이는 합계 영역을 재서 정한다. 끄는 중에는 dragPx가 높이를 정한다.
        var collapsedPx by remember { mutableFloatStateOf(0f) }
        var dragPx by remember { mutableStateOf<Float?>(null) }
        var sheetPx by remember { mutableIntStateOf(0) }
        var dragStartPx by remember { mutableFloatStateOf(0f) }
        var mapFraction by remember { mutableFloatStateOf(SHEET_DEFAULT_FRACTION) }

        fun anchorPx(value: SheetAnchor) = when (value) {
            SheetAnchor.COLLAPSED -> collapsedPx
            SheetAnchor.DEFAULT -> maxPx * SHEET_DEFAULT_FRACTION
            SheetAnchor.EXPANDED -> maxPx * SHEET_EXPANDED_FRACTION
        }

        val targetPx = dragPx ?: anchorPx(anchor)
        val heightPx by animateFloatAsState(
            targetValue = targetPx,
            animationSpec = if (dragPx != null) snap() else spring(stiffness = Spring.StiffnessMediumLow),
            label = "routeSheetHeight",
        )
        // 끌기·애니메이션이 끝나 멈춘 높이만 지도에 넘긴다. 매 frame 넘기면 카메라가 계속 다시 맞춰진다.
        LaunchedEffect(maxPx) {
            snapshotFlow { if (dragPx == null && heightPx == anchorPx(anchor)) sheetPx else null }
                .filterNotNull()
                .collect { if (maxPx > 0) mapFraction = it / maxPx }
        }
        val dragState = rememberDraggableState { delta ->
            dragPx = ((dragPx ?: sheetPx.toFloat()) - delta).coerceIn(collapsedPx, anchorPx(SheetAnchor.EXPANDED))
        }

        map(route, marks, mapFraction, Modifier.fillMaxSize())
        RouteSheet(
            route = route,
            marks = marks,
            anchor = anchor,
            collapsed = anchor == SheetAnchor.COLLAPSED && dragPx == null,
            onAnchorChange = { anchor = it },
            onTopMeasured = { collapsedPx = it.toFloat() },
            topModifier = Modifier.draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStarted = { dragStartPx = sheetPx.toFloat() },
                onDragStopped = { velocity ->
                    // 목록이 짧으면 보이는 높이가 단계 높이보다 낮아 "가까운 단계"가 어긋난다. 그래서 시작 단계에서
                    // 끈 방향으로 한 단계 옮기고, 기본 높이를 넘겨 멀리 끌었으면 끝 단계까지 간다.
                    val current = dragPx ?: sheetPx.toFloat()
                    val moved = current - dragStartPx
                    val defaultPx = anchorPx(SheetAnchor.DEFAULT)
                    anchor = when {
                        // 화면 좌표는 아래가 +라 위로 밀면 velocity가 음수, 높이 변화(moved)는 양수다.
                        velocity < -flingPx || moved > slopPx -> when (anchor) {
                            SheetAnchor.COLLAPSED -> if (current > defaultPx + slopPx) SheetAnchor.EXPANDED else SheetAnchor.DEFAULT
                            else -> SheetAnchor.EXPANDED
                        }
                        velocity > flingPx || moved < -slopPx -> when (anchor) {
                            SheetAnchor.EXPANDED -> if (current < defaultPx - slopPx) SheetAnchor.COLLAPSED else SheetAnchor.DEFAULT
                            else -> SheetAnchor.COLLAPSED
                        }
                        else -> anchor
                    }
                    dragPx = null
                },
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .heightIn(max = with(density) { heightPx.toDp() })
                .onSizeChanged { sheetPx = it.height },
        )
    }
}

/**
 * Figma 하단 sheet: 손잡이, 합계·attribution, 범례, 구간 목록.
 *
 * @param collapsed 접힌 채 멈춰 있다. 가려진 범례·목록을 접근성 트리에서도 뺀다.
 * @param onTopMeasured 손잡이·합계 영역에 아래 여백·제스처 영역을 더한 접힘 높이(px).
 * @param topModifier 끌기 제스처를 받는 윗부분에 붙인다.
 */
@Composable
private fun RouteSheet(
    route: RouteDto,
    marks: RouteMarks,
    anchor: SheetAnchor,
    collapsed: Boolean,
    onAnchorChange: (SheetAnchor) -> Unit,
    onTopMeasured: (Int) -> Unit,
    topModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val density = LocalDensity.current
    val total = durationLabel(route.totalDurationSeconds)
    val distance = distanceLabel(route.totalDistanceMeters)
    val summary = if (route.segments.isEmpty()) {
        stringResource(R.string.route_summary_single, total, distance)
    } else {
        stringResource(R.string.route_summary_total, total, distance)
    }
    // 접힘 아래 여백은 범례 위 간격(space3)과 같게 해 범례가 조금도 비치지 않게 한다.
    val bottomExtraPx = with(density) { spacing.space3.roundToPx() } + WindowInsets.navigationBars.getBottom(density)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = radius.xl, topEnd = radius.xl))
            // 밝은 지도의 지명이 비치면 sheet 글자를 읽기 어려워 불투명하게 둔다(#549).
            .background(colors.darkMap)
            .padding(horizontal = spacing.space5)
            .navigationBarsPadding()
            .testTag(TAG_SHEET),
    ) {
        Column(
            modifier = topModifier
                .fillMaxWidth()
                // 접힌 sheet 안에서도 합계 영역의 원래 높이를 재도록 높이 제한 없이 잰다.
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .onSizeChanged { onTopMeasured(it.height + bottomExtraPx) },
        ) {
            SheetHandle(anchor = anchor, onAnchorChange = onAnchorChange)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                modifier = Modifier.testTag(TAG_SUMMARY),
            )
            if (route.providerAttributions.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.route_attribution_prefix,
                        route.providerAttributions.joinToString(stringResource(R.string.route_attribution_separator)),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onDarkMuted,
                    modifier = Modifier
                        .padding(top = spacing.space1)
                        .testTag(TAG_ATTRIBUTION),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .then(if (collapsed) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            // 상태 범례는 시작된 날짜에만 뜻이 있다. 시작 전에는 진행 상태를 지어내지 않고(UI-010) 지도 힌트만 둔다.
            Legend(showStatuses = marks.statuses.isNotEmpty(), modifier = Modifier.padding(top = spacing.space3))
            Column(
                modifier = Modifier
                    // sheet 높이가 모자라면 합계·범례가 아니라 목록이 줄어들며 스크롤한다.
                    .weight(1f, fill = false)
                    .padding(top = spacing.space3, bottom = spacing.space4)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.space2),
            ) {
                PlaceCards(markers = route.markers, statuses = marks.statuses)
                if (route.segments.isNotEmpty()) {
                    // 구간 정보(이동수단·시간·거리)는 카드에 없으므로 보조 목록으로 유지한다(UI-005).
                    val byId = route.markers.associateBy { it.itemId }
                    route.segments.forEach { segment ->
                        SegmentRow(
                            segment = segment,
                            from = byId.getValue(segment.fromItemId),
                            to = byId.getValue(segment.toItemId),
                            fromStatus = marks.statuses[segment.fromItemId],
                            toStatus = marks.statuses[segment.toItemId],
                        )
                    }
                }
            }
        }
    }
}

/**
 * 손잡이(40×4dp 막대, 터치 48dp). 누르면 접힘·기본은 한 단계 펼치고 펼침은 기본으로 돌린다. 끌기가 어려운
 * 사용자를 위해 현재 단계를 상태로 읽어 주고 `펼치기`·`접기` action을 둔다(가이드라인 10절, #550).
 */
@Composable
private fun SheetHandle(anchor: SheetAnchor, onAnchorChange: (SheetAnchor) -> Unit) {
    val label = stringResource(R.string.route_sheet_handle)
    val state = stringResource(
        when (anchor) {
            SheetAnchor.COLLAPSED -> R.string.route_sheet_collapsed
            SheetAnchor.DEFAULT -> R.string.route_sheet_default
            SheetAnchor.EXPANDED -> R.string.route_sheet_expanded
        },
    )
    val expandLabel = stringResource(R.string.route_sheet_expand)
    val collapseLabel = stringResource(R.string.route_sheet_collapse)
    val up = SheetAnchor.entries.getOrNull(anchor.ordinal + 1)
    val down = SheetAnchor.entries.getOrNull(anchor.ordinal - 1)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(MIN_TOUCH)
            .clickable(role = Role.Button) { onAnchorChange(up ?: SheetAnchor.DEFAULT) }
            .semantics {
                contentDescription = label
                stateDescription = state
                customActions = listOfNotNull(
                    up?.let { CustomAccessibilityAction(expandLabel) { onAnchorChange(it); true } },
                    down?.let { CustomAccessibilityAction(collapseLabel) { onAnchorChange(it); true } },
                )
            }
            .testTag(TAG_SHEET_HANDLE),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.2f)),
        )
    }
}

/** 범례(Figma): `완료`·`이동 중`·`예정` 12dp 점 + 흰 60% 글자, 오른쪽 `지도 이동 가능`(흰 40%, 14dp 돋보기). */
@Composable
private fun Legend(showStatuses: Boolean, modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space3),
    ) {
        if (showStatuses) {
            LegendItem(color = colors.success, label = stringResource(R.string.route_legend_done))
            LegendItem(color = MaterialTheme.colorScheme.primary, label = stringResource(R.string.route_legend_active))
            LegendItem(color = UPCOMING_DOT, label = stringResource(R.string.route_legend_upcoming))
        }
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            painter = painterResource(R.drawable.ic_lucide_search),
            contentDescription = null,
            tint = Color.White.copy(alpha = HINT_ALPHA),
            modifier = Modifier.size(LEGEND_HINT_ICON),
        )
        Text(
            text = stringResource(R.string.route_legend_pan_hint),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = HINT_ALPHA),
        )
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    val spacing = LocalGilpickSpacing.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.space1 + 2.dp)) {
        Box(modifier = Modifier.size(LEGEND_DOT).background(color, CircleShape))
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = LEGEND_ALPHA))
    }
}

/**
 * 장소별 카드 가로 배치(Figma `flex-1`). 카드는 폭을 n등분하되 [CARD_MIN_WIDTH]보다 좁아지면 그 폭을 유지하고
 * 가로로 스크롤한다 — 7곳 이상이거나 글자 2.0배에서도 잘리지 않는다(UI-008, PR 기록).
 * 지도의 번호·상태를 지도 밖에서도 같은 순서로 제공한다(UI-005·UI-011).
 */
@Composable
private fun PlaceCards(markers: List<RouteMarkerDto>, statuses: Map<String, ItemStatus>) {
    val spacing = LocalGilpickSpacing.current
    val gap = spacing.space2

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val share = (maxWidth - gap * (markers.size - 1)) / markers.size
        val cardWidth = if (share < CARD_MIN_WIDTH) CARD_MIN_WIDTH else share
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            markers.forEach { marker -> PlaceCard(marker = marker, status = statuses[marker.itemId], modifier = Modifier.width(cardWidth)) }
        }
    }
}

/** 카드 한 장: 6dp 상태 점 + 번호, 장소명 11sp, 상태 문구 9sp. 이동 중이면 `primary` 20% 배경, 아니면 흰 5%. */
@Composable
private fun PlaceCard(marker: RouteMarkerDto, status: ItemStatus?, modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val active = status == ItemStatus.EN_ROUTE
    val done = status == ItemStatus.COMPLETED || status == ItemStatus.ARRIVED
    val description = stringResource(R.string.route_marker_description, marker.sequence, statusName(marker.name, status))
    val dot = when {
        done -> colors.success
        active -> MaterialTheme.colorScheme.primary
        else -> Color.White.copy(alpha = LEGEND_ALPHA / 3)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(radius.md))
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = ACTIVE_CARD_ALPHA) else Color.White.copy(alpha = CARD_ALPHA))
            .padding(horizontal = spacing.space2 + 2.dp, vertical = spacing.space2)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag("$TAG_MARKER_PREFIX${marker.sequence}"),
        verticalArrangement = Arrangement.spacedBy(spacing.space1),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.space1)) {
            Box(modifier = Modifier.size(CARD_DOT).background(dot, CircleShape))
            Text(
                text = marker.sequence.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 0.sp),
                fontFamily = marker.sequence.toString().displayFont(),
                color = if (active) colors.primaryLight else Color.White.copy(alpha = HINT_ALPHA),
            )
        }
        Text(
            text = marker.name,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
            color = when {
                active -> Color.White
                done -> Color.White.copy(alpha = LEGEND_ALPHA)
                else -> Color.White.copy(alpha = HINT_ALPHA)
            },
        )
        // 도착 예정 시각은 F005 계약에 없어 지어내지 않고(UI-010) 진행 상태 문구만 둔다. 시작 전 날짜는 비운다.
        if (status != null) {
            Text(
                text = stringResource(status.progressLabelRes),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 0.sp),
                color = if (active) colors.primaryLight else Color.White.copy(alpha = HINT_ALPHA * 0.75f),
            )
        }
    }
}

/**
 * 구간 한 행: 출발·도착 순서 번호와 이름, 이동 수단 아이콘+문구, 이동시간·거리(UI-005).
 *
 * 이동 수단은 색이 아니라 아이콘과 문구로 구분한다(UI-007). 진행 상태가 있으면 장소명 옆에 문구로 더한다(T031).
 */
@Composable
private fun SegmentRow(
    segment: RouteSegmentDto,
    from: RouteMarkerDto,
    to: RouteMarkerDto,
    fromStatus: ItemStatus? = null,
    toStatus: ItemStatus? = null,
) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val mode = stringResource(segment.transportMode.labelRes)
    val duration = durationLabel(segment.durationSeconds)
    val distance = distanceLabel(segment.distanceMeters)
    val fromName = statusName(from.name, fromStatus)
    val toName = statusName(to.name, toStatus)
    val description = stringResource(R.string.route_segment_description, segment.sequence, fromName, toName, mode, duration, distance)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius.md))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = spacing.space3, vertical = spacing.space2)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag("$TAG_SEGMENT_PREFIX${segment.sequence}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space3),
    ) {
        Icon(
            painter = painterResource(segment.transportMode.iconRes),
            contentDescription = null,
            tint = colors.primaryLight,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.space1), verticalAlignment = Alignment.CenterVertically) {
                SequenceDot(from.sequence, fromStatus)
                Text(
                    text = from.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f, fill = false),
                )
                StatusText(fromStatus)
                Text(
                    text = stringResource(R.string.route_segment_arrow),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onDarkMuted,
                )
                SequenceDot(to.sequence, toStatus)
                Text(
                    text = to.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f, fill = false),
                )
                StatusText(toStatus)
            }
            Text(
                text = stringResource(R.string.route_segment_detail, mode, duration, distance),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onDarkMuted,
            )
        }
    }
}


/** 접근성 설명용 `장소명 상태`. 상태가 없으면 장소명만이다. */
@Composable
private fun statusName(name: String, status: ItemStatus?): String =
    if (status == null) name else stringResource(R.string.route_status_name, name, stringResource(status.progressLabelRes))

/** 장소명 옆 진행 상태 문구. 색 단독이 아니라 문구가 뜻을 전달한다(F006 UI-004). */
@Composable
private fun StatusText(status: ItemStatus?) {
    if (status == null) return
    val colors = LocalGilpickColors.current
    Text(
        text = stringResource(status.progressLabelRes),
        style = MaterialTheme.typography.labelSmall,
        color = when (status) {
            ItemStatus.COMPLETED, ItemStatus.ARRIVED -> colors.success
            ItemStatus.SKIPPED -> colors.onDarkMuted
            ItemStatus.EN_ROUTE, ItemStatus.PLANNED -> colors.primaryLight
        },
    )
}

/** 방문 순서 번호. 지도 마커의 번호와 같은 값이다(UI-006). */
@Composable
private fun SequenceDot(sequence: Int, status: ItemStatus? = null) {
    val colors = LocalGilpickColors.current
    val background = when (status) {
        ItemStatus.COMPLETED, ItemStatus.ARRIVED -> colors.success
        ItemStatus.SKIPPED, ItemStatus.PLANNED -> colors.faint
        ItemStatus.EN_ROUTE, null -> MaterialTheme.colorScheme.primary
    }
    val icon = status?.progressIconRes
    Box(
        modifier = Modifier
            .size(20.dp)
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
                color = Color.White,
            )
        }
    }
}

/** UI test가 찾는 tag. */
internal const val TAG_SHEET = "route_sheet"
internal const val TAG_SHEET_HANDLE = "route_sheet_handle"
internal const val TAG_SUMMARY = "route_summary"
internal const val TAG_ATTRIBUTION = "route_attribution"
internal const val TAG_SEGMENT_PREFIX = "route_segment_"
internal const val TAG_MARKER_PREFIX = "route_marker_"
internal const val TAG_MAP = "route_map"

private const val LOADING_INDICATOR_DELAY_MILLIS = 1_000L

/** 가이드라인 10절 최소 터치 영역. */
private val MIN_TOUCH: Dp = 48.dp

/** Figma 헤더 버튼의 보이는 크기. */
private val HEADER_BUTTON: Dp = 36.dp

/** sheet 기본 높이 비율. 나머지는 지도 조작 영역이다(UI-009). */
private const val SHEET_DEFAULT_FRACTION = 0.45f

/** sheet 펼침 높이 비율. 펼쳐도 지도 윗부분은 남긴다(UI-009). */
private const val SHEET_EXPANDED_FRACTION = 0.85f

/** 이보다 빠르게 밀거나 멀리 끌면 그 방향의 다음 단계로 간다. 못 미치면 원래 단계로 돌아간다. */
private val SHEET_FLING_VELOCITY: Dp = 400.dp
private val SHEET_STEP_DISTANCE: Dp = 48.dp

/** Figma 실측(범례 12dp 점, 힌트 14dp 아이콘, 카드 6dp 점, 흰 60%·40%·5%, `primary` 20%). 화면 전용이라 토큰이 아니다. */
private val LEGEND_DOT: Dp = 12.dp
private val LEGEND_HINT_ICON: Dp = 14.dp
private val CARD_DOT: Dp = 6.dp
/** 카드 최소 폭. 이보다 좁아지면 n등분을 포기하고 가로 스크롤한다. */
private val CARD_MIN_WIDTH: Dp = 88.dp
private const val LEGEND_ALPHA = 0.6f
private const val HINT_ALPHA = 0.4f
private const val CARD_ALPHA = 0.05f
private const val ACTIVE_CARD_ALPHA = 0.2f
/** 범례 `예정` 점. Figma `#1E3A5F`(3절 darkMap 행의 도로색, 이름 붙은 토큰 없음)는 흰 20%와 같은 뜻으로 쓴다(PR 기록). */
private val UPCOMING_DOT: Color = Color.White.copy(alpha = 0.2f)
