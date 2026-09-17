package com.gilpick.route

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalContext
import com.gilpick.progress.DeviceLocationProvider
import com.gilpick.progress.progressIconRes
import com.gilpick.progress.progressLabelRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.unit.sp
import com.gilpick.ui.component.EmptyState
import com.gilpick.ui.component.ErrorState
import com.gilpick.ui.component.GradientButton
import com.gilpick.ui.component.LightSystemBarIcons
import com.gilpick.ui.component.SheetAnchor
import com.gilpick.ui.component.SheetHandle
import com.gilpick.ui.component.dragModifier
import com.gilpick.ui.component.measureSheet
import com.gilpick.ui.component.rememberSheetDragState
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSizing
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.gilpick.ui.theme.displayFont
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics

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
 * @param hasLocationPermission 위치 권한이 있는지. 기본은 실제 기기 권한이며, UI test는 권한 상태를 바꿔 끼운다.
 * @param currentLocation `내 위치로 이동`이 누를 때마다 확인하는 현재 위치(`[경도, 위도]`). 못 얻으면 `null`이고
 *   화면은 지도를 옮기는 대신 안내를 띄운다(#651). 기본은 실제 기기 위치이며, UI test가 바꿔 끼운다.
 * @param map 지도 영역. 세 번째 인자는 sheet가 덮는 높이 비율, 네 번째는 장소 카드로 고른 지도 이동 대상이다.
 *   기본은 Naver [RouteMap]이며, UI test·screenshot은 SDK 인증 없이 그릴 수 있는 자리 표시로 바꿔 끼운다.
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
    hasLocationPermission: (Context) -> Boolean = { DeviceLocationProvider.hasLocationPermission(it) },
    currentLocation: suspend (Context) -> Position? = { context ->
        DeviceLocationProvider.forMap(context)?.let { listOf(it.longitude, it.latitude) }
    },
    map: @Composable (RouteDto, RouteMarks, Float, RouteFocus?, Modifier) -> Unit = { route, marks, sheetFraction, focus, mapModifier ->
        RouteMap(route = route, marks = marks, modifier = mapModifier, sheetFraction = sheetFraction, focus = focus, myLocation = true)
    },
) {
    val colors = LocalGilpickColors.current

    // 오류는 화면 전체를 대신한다(가이드라인 9절 오류 화면, #434 결정: 어두운 변형은 빈 상태만 둔다).
    if (state is RouteUiState.Error) {
        ErrorState(problem = state.problem, onRetry = onRetry, onBack = onBack, onReauthenticate = onReauthenticate, modifier = modifier.fillMaxSize().statusBarsPadding())
        return
    }

    // 어두운 지도 배경이 system bar 뒤까지 이어지므로 그 위 아이콘도 밝게 바꾼다(#590).
    LightSystemBarIcons()

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

                is RouteUiState.Content -> Content(
                    route = state.route,
                    marks = state.marks,
                    hasLocationPermission = hasLocationPermission,
                    currentLocation = currentLocation,
                    map = map,
                )
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
        title = stringResource(problem.titleRes),
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


/**
 * 전체 화면 지도와 그 위에 겹치는 하단 sheet.
 *
 * sheet는 기본으로 화면 높이의 45%까지 차지해 지도 조작 영역을 남긴다(UI-009). 높이 단계·끌기는 공용
 * `SheetDragState`가 맡는다(#550, #660). 구간이 많거나 글자가 크면 sheet 안에서 세로로 스크롤한다.
 * sheet가 멈추면 그 높이 비율을 [map]에 넘겨 지도 SDK의 로고·marker가 sheet에 가리지 않게 한다.
 */
@Composable
private fun Content(
    route: RouteDto,
    marks: RouteMarks,
    hasLocationPermission: (Context) -> Boolean,
    currentLocation: suspend (Context) -> Position?,
    map: @Composable (RouteDto, RouteMarks, Float, RouteFocus?, Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val sheet = rememberSheetDragState(
            maxPx = constraints.maxHeight.toFloat(),
            defaultFraction = SHEET_DEFAULT_FRACTION,
            expandedFraction = SHEET_EXPANDED_FRACTION,
        )
        // 카드 선택·내 위치는 지도 카메라만 옮긴다. 같은 대상을 다시 눌러도 옮기도록 누를 때마다 tick을 올린다(#618, #614).
        var focus by remember { mutableStateOf<RouteFocus?>(null) }
        var notice by remember { mutableStateOf<Int?>(null) }
        var locating by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        fun moveTo(next: (Int) -> RouteFocus) { focus = next((focus?.tick ?: 0) + 1) }
        // 누를 때마다 위치를 새로 확인한다. 화면에 들어온 시점에 위치 서비스가 꺼져 있었더라도 이 누름에서
        // 다시 시도하고, 끝내 못 얻으면 아무 일도 없는 대신 이유를 알린다(#651).
        fun locate() {
            if (locating) return
            locating = true
            notice = null
            scope.launch {
                val position = currentLocation(context)
                locating = false
                if (position == null) notice = R.string.route_my_location_unavailable else moveTo { tick -> RouteFocus.MyLocation(position, tick) }
            }
        }
        // 권한을 받은 뒤에야 현재 위치를 알 수 있으므로, 허용된 뒤에 확인한다(F003 PlaceNavigation과 같은 방식).
        val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.any { it }) locate() else notice = R.string.route_my_location_denied
        }

        map(route, marks, sheet.fraction, focus, Modifier.fillMaxSize())
        MyLocationButton(
            notice = notice,
            onClick = {
                notice = null
                if (hasLocationPermission(context)) {
                    locate()
                } else {
                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(LocalGilpickSpacing.current.space4),
        )
        RouteSheet(
            route = route,
            marks = marks,
            onSelectPlace = { marker -> moveTo { tick -> RouteFocus.Place(marker.itemId, tick) } },
            anchor = sheet.anchor,
            collapsed = sheet.collapsed,
            onAnchorChange = { sheet.anchor = it },
            onTopMeasured = sheet::onTopMeasured,
            topModifier = sheet.dragModifier(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .heightIn(max = sheet.height)
                .measureSheet(sheet),
        )
    }
}

/**
 * Figma 하단 sheet: 손잡이, 합계·attribution, 범례, 구간 목록.
 *
 * @param collapsed 접힌 채 멈춰 있다. 가려진 범례·목록을 접근성 트리에서도 뺀다.
 * @param onSelectPlace 장소 카드를 눌렀다. 지도를 그 장소로 옮긴다(#618).
 * @param onTopMeasured 손잡이·합계 영역에 아래 여백·제스처 영역을 더한 접힘 높이(px).
 * @param topModifier 끌기 제스처를 받는 윗부분에 붙인다.
 */
@Composable
private fun RouteSheet(
    route: RouteDto,
    marks: RouteMarks,
    onSelectPlace: (RouteMarkerDto) -> Unit,
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
            SheetHandle(anchor = anchor, onAnchorChange = onAnchorChange, color = Color.White.copy(alpha = HANDLE_ALPHA))
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
                PlaceCards(markers = route.markers, statuses = marks.statuses, onSelect = onSelectPlace)
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
 * 지도 오른쪽 위 `내 위치로 이동` 버튼(#614, Figma에 없는 새 control). UI test가 두 상태를 직접 그린다.
 *
 * 지도 위 control과 겹치지 않는 자리다: Naver SDK는 로고를 왼쪽 아래, 축척을 오른쪽 아래, 확대/축소를
 * 오른쪽 가운데(content padding 기준)에 둔다. 경로 정보 sheet는 아래쪽에 있다.
 *
 * @param notice 지도를 옮기지 못한 이유 문구의 resource. `null`이면 버튼만 보인다. 권한 거부와 위치 확인
 *   실패를 같은 자리에서 알린다(#651).
 */
@Composable
internal fun MyLocationButton(@StringRes notice: Int?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val label = stringResource(R.string.route_my_location)

    Column(modifier = modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(spacing.space2)) {
        Box(
            modifier = Modifier
                .size(MIN_TOUCH)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onClick, role = Role.Button)
                .semantics { contentDescription = label }
                .testTag(TAG_MY_LOCATION),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_locate_fixed),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        if (notice != null) {
            Text(
                text = stringResource(notice),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                modifier = Modifier
                    // 화면 폭을 다 쓰면 지도 위 marker를 가린다. 버튼 쪽에 붙여 두고 줄바꿈시킨다.
                    .widthIn(max = NOTICE_MAX_WIDTH)
                    .clip(RoundedCornerShape(LocalGilpickRadius.current.md))
                    .background(colors.darkMap.copy(alpha = 0.85f))
                    .padding(horizontal = spacing.space3, vertical = spacing.space2)
                    .testTag(TAG_MY_LOCATION_NOTICE),
            )
        }
    }
}

/**
 * 범례(Figma): `완료`·`이동 중`·`예정` 12dp 점 + 흰 60% 글자, 오른쪽 `지도 이동 가능`(흰 40%).
 *
 * Figma의 14dp 돋보기는 두지 않는다. 누를 수 있는 검색이 없는데 검색처럼 보여 혼란을 줬다(#592, QA 2026-09-16).
 */
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
 *
 * 긴 장소명이 두 줄이 되면 그 카드만 높아져 하단이 어긋났다. 행 높이를 [IntrinsicSize.Min](= 가장 높은 카드의
 * 필요 높이)으로 고정하고 카드가 그 높이를 채우게 해, 말줄임 없이 같은 행의 카드 높이를 맞춘다(#618).
 */
@Composable
private fun PlaceCards(markers: List<RouteMarkerDto>, statuses: Map<String, ItemStatus>, onSelect: (RouteMarkerDto) -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val gap = spacing.space2

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val share = (maxWidth - gap * (markers.size - 1)) / markers.size
        val cardWidth = if (share < CARD_MIN_WIDTH) CARD_MIN_WIDTH else share
        Row(
            modifier = Modifier
                .height(IntrinsicSize.Min)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            markers.forEach { marker ->
                PlaceCard(
                    marker = marker,
                    status = statuses[marker.itemId],
                    onSelect = { onSelect(marker) },
                    modifier = Modifier.width(cardWidth).fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * 카드 한 장: 6dp 상태 점 + 번호, 장소명 11sp, 상태 문구 9sp. 이동 중이면 `primary` 20% 배경, 아니면 흰 5%.
 * 카드 전체가 `지도에서 보기` 버튼이다 — 누르면 지도를 그 장소로 옮긴다(#618, Figma에 없는 새 상호작용).
 */
@Composable
private fun PlaceCard(marker: RouteMarkerDto, status: ItemStatus?, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val active = status == ItemStatus.EN_ROUTE
    val done = status == ItemStatus.COMPLETED || status == ItemStatus.ARRIVED
    val description = stringResource(R.string.route_marker_description, marker.sequence, statusName(marker.name, status))
    val focusLabel = stringResource(R.string.route_marker_focus)
    val dot = when {
        done -> colors.success
        active -> MaterialTheme.colorScheme.primary
        else -> Color.White.copy(alpha = LEGEND_ALPHA / 3)
    }

    Column(
        modifier = modifier
            .heightIn(min = MIN_TOUCH)
            .clip(RoundedCornerShape(radius.md))
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = ACTIVE_CARD_ALPHA) else Color.White.copy(alpha = CARD_ALPHA))
            .clickable(onClickLabel = focusLabel, role = Role.Button, onClick = onSelect)
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
    val fallbackNotice = stringResource(R.string.route_segment_walking_fallback)
    val description = stringResource(R.string.route_segment_description, segment.sequence, fromName, toName, mode, duration, distance)
        .let { if (segment.isWalkingFallback) "$it, $fallbackNotice" else it }

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
            // 대중교통 경로가 없어 도보로 대신 안내하는 구간(#685). 오류가 아니라 정상 결과라 인라인으로만 알린다.
            if (segment.isWalkingFallback) {
                Text(
                    text = fallbackNotice,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.primaryLight,
                    modifier = Modifier.testTag("$TAG_SEGMENT_WALKING_FALLBACK_PREFIX${segment.sequence}"),
                )
            }
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
internal const val TAG_SUMMARY = "route_summary"
internal const val TAG_ATTRIBUTION = "route_attribution"
internal const val TAG_SEGMENT_PREFIX = "route_segment_"
internal const val TAG_SEGMENT_WALKING_FALLBACK_PREFIX = "route_segment_walking_fallback_"
internal const val TAG_MARKER_PREFIX = "route_marker_"
internal const val TAG_MAP = "route_map"
private val NOTICE_MAX_WIDTH: Dp = 240.dp

internal const val TAG_MY_LOCATION = "route_my_location"
internal const val TAG_MY_LOCATION_NOTICE = "route_my_location_notice"

private const val LOADING_INDICATOR_DELAY_MILLIS = 1_000L

/** 가이드라인 10절 최소 터치 영역. */
private val MIN_TOUCH: Dp = 48.dp

/** Figma 헤더 버튼의 보이는 크기. */
private val HEADER_BUTTON: Dp = 36.dp

/** sheet 기본 높이 비율. 나머지는 지도 조작 영역이다(UI-009). */
private const val SHEET_DEFAULT_FRACTION = 0.45f

/** sheet 펼침 높이 비율. 펼쳐도 지도 윗부분은 남긴다(UI-009). */
private const val SHEET_EXPANDED_FRACTION = 0.85f

/** Figma 실측(범례 12dp 점, 카드 6dp 점, 흰 60%·40%·5%, `primary` 20%). 화면 전용이라 토큰이 아니다. */
private val LEGEND_DOT: Dp = 12.dp
private val CARD_DOT: Dp = 6.dp
/** 카드 최소 폭. 이보다 좁아지면 n등분을 포기하고 가로 스크롤한다. */
private val CARD_MIN_WIDTH: Dp = 88.dp
private const val LEGEND_ALPHA = 0.6f
private const val HINT_ALPHA = 0.4f

/** 손잡이 막대(흰 20%). */
private const val HANDLE_ALPHA = 0.2f
private const val CARD_ALPHA = 0.05f
private const val ACTIVE_CARD_ALPHA = 0.2f
/** 범례 `예정` 점. Figma `#1E3A5F`(3절 darkMap 행의 도로색, 이름 붙은 토큰 없음)는 흰 20%와 같은 뜻으로 쓴다(PR 기록). */
private val UPCOMING_DOT: Color = Color.White.copy(alpha = 0.2f)
