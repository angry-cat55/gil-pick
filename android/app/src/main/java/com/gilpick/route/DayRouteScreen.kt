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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.gilpick.R
import com.gilpick.itinerary.iconRes
import com.gilpick.itinerary.labelRes
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSizing
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.gilpick.ui.theme.displayFont
import java.time.LocalDate
import kotlinx.coroutines.delay

/**
 * 날짜별 경로 화면(`spec.md` US2, Figma `DayRouteScreen`).
 *
 * 모양은 Figma의 어두운 전체 화면 지도와 하단 sheet를 따르되, F006 전용 요소(`여행 중` 배지, 완료·이동 중
 * 마커 상태, 현재 위치, 도착 예정 시각)는 그리지 않는다(UI-001·UI-010). 지도 위 정보는 지도를 못 보는
 * 사용자를 위해 같은 순서의 구간 목록으로도 제공한다(UI-005, 가이드라인 10절).
 *
 * @param state 현재 상태.
 * @param dayNumber 헤더 제목의 `N일차`.
 * @param date 헤더 부제의 날짜.
 * @param onBack 뒤로 가기.
 * @param onRetry `error`의 `다시 시도`.
 * @param onAddPlace `empty`의 `장소 추가`. 그 날짜의 일정 편집(장소 검색)으로 간다.
 * @param onReauthenticate 로그인 상태가 만료됐다. F001 재인증 흐름으로 넘어간다.
 * @param map 지도 영역. 기본은 Naver [RouteMap]이며, UI test·screenshot은 SDK 인증 없이 그릴 수 있는
 *   자리 표시로 바꿔 끼운다.
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
    map: @Composable (RouteDto, Modifier) -> Unit = { route, mapModifier -> RouteMap(route = route, modifier = mapModifier) },
) {
    val colors = LocalGilpickColors.current

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
                is RouteUiState.Error -> ErrorState(
                    problem = state.problem,
                    onRetry = onRetry,
                    onBack = onBack,
                    onReauthenticate = onReauthenticate,
                )

                is RouteUiState.Content -> Content(route = state.route, map = map)
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
                .semantics { contentDescription = back },
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
@Composable
private fun EmptyState(onAddPlace: () -> Unit, onBack: () -> Unit) {
    DarkStateMessage(
        title = stringResource(R.string.route_empty_title),
        body = stringResource(R.string.route_empty_body),
        icon = R.drawable.ic_map,
    ) {
        Button(onClick = onAddPlace, modifier = Modifier.heightIn(min = MIN_TOUCH)) {
            Text(stringResource(R.string.route_add_place))
        }
        BackButton(onBack)
    }
}

/** 오류 상태: 원인 문구와 `다시 시도`, `돌아가기`. 세션 만료는 `다시 로그인`으로 잇는다. */
@Composable
private fun ErrorState(problem: RouteProblem, onRetry: () -> Unit, onBack: () -> Unit, onReauthenticate: () -> Unit) {
    val sessionExpired = (problem as? RouteProblem.Request)?.error == RouteError.SessionExpired

    DarkStateMessage(
        title = stringResource(R.string.route_error_title),
        body = stringResource(problem.messageRes),
        icon = R.drawable.ic_lucide_circle_x,
    ) {
        if (sessionExpired) {
            Button(onClick = onReauthenticate, modifier = Modifier.heightIn(min = MIN_TOUCH)) {
                Text(stringResource(R.string.place_reauthenticate))
            }
        } else {
            Button(onClick = onRetry, modifier = Modifier.heightIn(min = MIN_TOUCH)) {
                Text(stringResource(R.string.route_retry))
            }
        }
        BackButton(onBack)
    }
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

/** 어두운 배경 위 가운데 안내. 아이콘·제목·설명·행동 순서는 가이드라인 9절 빈 상태 형식이다. */
@Composable
private fun DarkStateMessage(title: String, body: String, icon: Int, actions: @Composable () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val sizing = LocalGilpickSizing.current
    val colors = LocalGilpickColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.space5 + spacing.space3, vertical = spacing.space6),
        verticalArrangement = Arrangement.spacedBy(spacing.space3, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(sizing.emptyIconCircle)
                .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(LocalGilpickRadius.current.lg)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = colors.faint,
                modifier = Modifier.size(sizing.emptyIcon),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onDarkMuted,
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
 * 전체 화면 지도와 그 위에 겹치는 하단 sheet.
 *
 * sheet는 화면 높이의 45%까지만 차지해 지도 조작 영역을 남긴다(UI-009). 구간이 많거나 글자가 크면
 * sheet 안에서 세로로 스크롤한다. 지도 SDK의 로고·attribution이 sheet에 가리지 않도록 [RouteMap]이
 * 같은 높이만큼 content padding을 둔다.
 */
@Composable
private fun Content(route: RouteDto, map: @Composable (RouteDto, Modifier) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val sheetMaxHeight = maxHeight * SHEET_MAX_FRACTION
        map(route, Modifier.fillMaxSize())
        RouteSheet(
            route = route,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .heightIn(max = sheetMaxHeight),
        )
    }
}

/** Figma 하단 sheet: 손잡이, 합계·attribution, 구간 목록. */
@Composable
private fun RouteSheet(route: RouteDto, modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val total = durationLabel(route.totalDurationSeconds)
    val distance = distanceLabel(route.totalDistanceMeters)
    val summary = if (route.segments.isEmpty()) {
        stringResource(R.string.route_summary_single, total, distance)
    } else {
        stringResource(R.string.route_summary_total, total, distance)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = radius.xl, topEnd = radius.xl))
            .background(colors.darkMap.copy(alpha = 0.92f))
            .padding(horizontal = spacing.space5)
            .padding(top = spacing.space4)
            .navigationBarsPadding()
            .testTag(TAG_SHEET),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(40.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.2f)),
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            modifier = Modifier
                .padding(top = spacing.space4)
                .testTag(TAG_SUMMARY),
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
        Column(
            modifier = Modifier
                // sheet 높이가 모자라면 합계·attribution이 아니라 목록이 줄어들며 스크롤한다.
                .weight(1f, fill = false)
                .padding(top = spacing.space3, bottom = spacing.space4)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.space2),
        ) {
            if (route.segments.isEmpty()) {
                route.markers.forEach { MarkerRow(it) }
            } else {
                val byId = route.markers.associateBy { it.itemId }
                route.segments.forEach { segment ->
                    SegmentRow(
                        segment = segment,
                        from = byId.getValue(segment.fromItemId),
                        to = byId.getValue(segment.toItemId),
                    )
                }
            }
        }
    }
}

/**
 * 구간 한 행: 출발·도착 순서 번호와 이름, 이동 수단 아이콘+문구, 이동시간·거리(UI-005).
 *
 * 이동 수단은 색이 아니라 아이콘과 문구로 구분한다(UI-007).
 */
@Composable
private fun SegmentRow(segment: RouteSegmentDto, from: RouteMarkerDto, to: RouteMarkerDto) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val mode = stringResource(segment.transportMode.labelRes)
    val duration = durationLabel(segment.durationSeconds)
    val distance = distanceLabel(segment.distanceMeters)
    val description = stringResource(R.string.route_segment_description, segment.sequence, from.name, to.name, mode, duration, distance)

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
                SequenceDot(from.sequence)
                Text(
                    text = from.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = stringResource(R.string.route_segment_arrow),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onDarkMuted,
                )
                SequenceDot(to.sequence)
                Text(
                    text = to.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                text = stringResource(R.string.route_segment_detail, mode, duration, distance),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onDarkMuted,
            )
        }
    }
}

/** 장소가 한 곳일 때의 유일한 행. 구간이 없으므로 순서 번호와 이름만 보인다. */
@Composable
private fun MarkerRow(marker: RouteMarkerDto) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val description = stringResource(R.string.route_marker_description, marker.sequence, marker.name)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius.md))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = spacing.space3, vertical = spacing.space2)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag("$TAG_MARKER_PREFIX${marker.sequence}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        SequenceDot(marker.sequence)
        Text(text = marker.name, style = MaterialTheme.typography.bodyMedium, color = Color.White)
    }
}

/** 방문 순서 번호. 지도 마커의 번호와 같은 값이다(UI-006). */
@Composable
private fun SequenceDot(sequence: Int) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = sequence.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = sequence.toString().displayFont(),
            color = Color.White,
        )
    }
}

/** UI test가 찾는 tag. */
internal const val TAG_SHEET = "route_sheet"
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

/** sheet가 차지할 수 있는 최대 화면 높이 비율. 나머지는 지도 조작 영역이다(UI-009). */
private const val SHEET_MAX_FRACTION = 0.45f
