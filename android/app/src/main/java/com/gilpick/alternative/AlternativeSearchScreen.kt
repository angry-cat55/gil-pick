package com.gilpick.alternative

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.gilpick.progress.DeviceLocationProvider
import com.gilpick.route.MyLocationButton
import com.gilpick.route.Position
import com.gilpick.route.RouteFocus
import com.gilpick.place.tourApiAttributionText
import com.gilpick.ui.component.TAG_HEADER_BACK
import com.gilpick.R
import com.gilpick.place.PlaceCategory
import com.gilpick.place.labelRes
import com.gilpick.route.distanceLabel
import com.gilpick.ui.component.EmptyState
import com.gilpick.ui.component.EmptyStateSize
import com.gilpick.ui.component.GradientButton
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickShadows
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.gilpick.ui.theme.displayFont
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 직접 검색 화면 — 지도형(spec US3, UI-007·UI-008·UI-010, Figma `MapSearchScreen`, #450).
 *
 * 전체 화면 지도 위에 떠 있는 검색창, (조건부) 카테고리 칩, 하단 결과 시트. 시트 행과 지도 마커는
 * [AlternativeSearchUiState.selectedPlaceId] 하나를 공유하며 어느 쪽을 눌러도 [onToggleSelect]로 같은 값을 바꾼다.
 * 행의 `선택` 버튼만 [onSelect]로 F010에 넘길 값을 만들고 일정은 바꾸지 않는다(FR-015).
 *
 * **응답에 없는 것은 그리지 않는다(12절)**: 카테고리 칩은 [AlternativeSearchFilters.categories], 반경 부제는
 * [AlternativeSearchFilters.originName]·`radiusMeters`, `혼잡`·`마감` 배지는 항목의 `crowded`·`closesAt`가 있을 때만 보인다.
 *
 * @param onToggleSelect 행·마커 탭. 같은 결과를 다시 누르면 선택이 풀린다.
 * @param onSelectCategory 카테고리 칩. `null`은 `전체`.
 * @param hasLocationPermission 위치 권한이 있는지. 기본은 실제 기기 권한이며, UI test는 권한 상태를 바꿔 끼운다.
 * @param currentLocation `내 위치로 이동`이 누를 때마다 확인하는 현재 위치(`[경도, 위도]`). 못 얻으면 `null`이고
 *   화면은 지도를 옮기는 대신 이유를 알린다(#660, F005와 같은 처리).
 * @param map 지도 영역. 네 번째 인자는 `내 위치로 이동`이 확인한 좌표다. 기본은 Naver [AlternativeSearchMap]이며
 *   UI test·screenshot은 자리 표시로 바꿔 끼운다.
 */
@Composable
fun AlternativeSearchScreen(
    state: AlternativeSearchUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onSearch: () -> Unit,
    onRetry: () -> Unit,
    onReauthenticate: () -> Unit,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onSelect: (AlternativeSearchItemDto) -> Unit,
    modifier: Modifier = Modifier,
    onToggleSelect: (placeId: String) -> Unit = {},
    onSelectCategory: (PlaceCategory?) -> Unit = {},
    hasLocationPermission: (Context) -> Boolean = { DeviceLocationProvider.hasLocationPermission(it) },
    currentLocation: suspend (Context) -> Position? = { context ->
        DeviceLocationProvider.forMap(context)?.let { listOf(it.longitude, it.latitude) }
    },
    map: @Composable (List<AlternativeSearchItemDto>, String?, (String) -> Unit, RouteFocus.MyLocation?, Modifier) -> Unit = { results, selected, onMarkerClick, focus, mapModifier ->
        AlternativeSearchMap(results = results, selectedPlaceId = selected, onMarkerClick = onMarkerClick, focus = focus, modifier = mapModifier)
    },
) {
    val spacing = LocalGilpickSpacing.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 누를 때마다 위치를 새로 확인한다. 화면에 들어온 시점에 위치 서비스가 꺼져 있었더라도 이 누름에서
    // 다시 시도하고, 끝내 못 얻으면 아무 일도 없는 대신 이유를 알린다(#651과 같은 처리).
    var focus by remember { mutableStateOf<RouteFocus.MyLocation?>(null) }
    var notice by remember { mutableStateOf<Int?>(null) }
    var locating by remember { mutableStateOf(false) }
    fun locate() {
        if (locating) return
        locating = true
        notice = null
        scope.launch {
            val position = currentLocation(context)
            locating = false
            if (position == null) {
                notice = R.string.route_my_location_unavailable
            } else {
                focus = RouteFocus.MyLocation(position, (focus?.tick ?: 0) + 1)
            }
        }
    }
    // 권한을 받은 뒤에야 현재 위치를 알 수 있으므로, 허용된 뒤에 확인한다(F003 PlaceNavigation과 같은 방식).
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate() else notice = R.string.route_my_location_denied
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val sheetMaxHeight = maxHeight * SHEET_MAX_FRACTION
        Box(modifier = Modifier.fillMaxSize().testTag(TAG_SEARCH_MAP)) {
            map(state.results, state.selectedPlaceId, onToggleSelect, focus, Modifier.fillMaxSize())
        }
        Column(modifier = Modifier.statusBarsPadding().padding(top = spacing.space4)) {
            FloatingSearchBar(
                query = state.query,
                onBack = onBack,
                onQueryChange = onQueryChange,
                onClearQuery = onClearQuery,
                onSearch = onSearch,
                modifier = Modifier.padding(horizontal = spacing.space4),
            )
            val categories = state.filters?.categories
            if (!categories.isNullOrEmpty()) {
                CategoryChips(categories = categories, selected = state.category, onSelect = onSelectCategory, modifier = Modifier.padding(top = spacing.space3))
            }
            // 권한 거부와 위치 확인 실패를 같은 자리에서 구분해 알린다(F005 `내 위치로 이동`과 같은 control).
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
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = spacing.space3, end = spacing.space4),
            )
        }
        ResultSheet(
            state = state,
            onRetry = onRetry,
            onReauthenticate = onReauthenticate,
            onLoadMore = onLoadMore,
            onRetryLoadMore = onRetryLoadMore,
            onToggleSelect = onToggleSelect,
            onSelect = onSelect,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .heightIn(max = sheetMaxHeight),
        )
    }
}

/**
 * 떠 있는 검색창(가이드라인 7절 입력창 "지도 검색창 예외"): 흰 96%, 52dp, `radiusLg`, 플로팅 그림자, 뒤로 가기가 입력창 안.
 * 검색은 키보드의 검색 동작으로만 실행한다(FR-003a 규칙 유지).
 */
@Composable
private fun FloatingSearchBar(
    query: String,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalGilpickSpacing.current
    val colors = MaterialTheme.colorScheme
    val extra = LocalGilpickColors.current
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(LocalGilpickRadius.current.lg)
    val hint = stringResource(R.string.alternative_search_hint)

    Row(
        modifier = LocalGilpickShadows.current.floatingCard
            .fold(modifier.fillMaxWidth()) { acc, shadow -> acc.dropShadow(shape, shadow) }
            .heightIn(min = SEARCH_BAR_HEIGHT)
            .clip(shape)
            .background(colors.surface.copy(alpha = SEARCH_BAR_ALPHA))
            .padding(start = spacing.space1, end = spacing.space4),
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(MIN_TOUCH)
                .clip(CircleShape)
                .clickable(onClick = onBack, role = Role.Button)
                .testTag(TAG_HEADER_BACK),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_arrow_left),
                contentDescription = stringResource(R.string.place_search_back),
                tint = colors.onSurface,
                modifier = Modifier.size(BACK_ICON),
            )
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    keyboard?.hide()
                    onSearch()
                },
            ),
            modifier = Modifier
                .weight(1f)
                // 항상 보이는 라벨을 placeholder가 대신하므로 판독기에는 이름을 붙인다.
                .semantics { contentDescription = hint },
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text(text = hint, style = MaterialTheme.typography.bodyLarge, color = extra.muted)
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(MIN_TOUCH)
                    .clip(CircleShape)
                    .clickable(onClick = onClearQuery, role = Role.Button),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_circle_x),
                    contentDescription = stringResource(R.string.place_search_clear),
                    tint = extra.faint,
                    modifier = Modifier.size(SEARCH_ICON),
                )
            }
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_search),
                contentDescription = null,
                tint = extra.muted,
                modifier = Modifier.size(SEARCH_ICON),
            )
        }
    }
}

/**
 * 원형 카테고리 칩(가로 스크롤): 선택 `primary` 배경·흰 글자 + `calendarSelected` 그림자(`0 2px 8px rgba(59,123,248,0.35)`),
 * 비선택 흰 90%·`onSurfaceVariant`. 첫 칩 `전체`는 `null`이다. 서버가 필터를 지원할 때만 그려진다.
 */
@Composable
private fun CategoryChips(categories: List<PlaceCategory>, selected: PlaceCategory?, onSelect: (PlaceCategory?) -> Unit, modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = spacing.space4)
            .testTag(TAG_SEARCH_CHIPS),
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        CategoryChip(label = stringResource(R.string.alternative_chip_all), selected = selected == null, onClick = { onSelect(null) })
        categories.forEach { category ->
            CategoryChip(label = stringResource(category.labelRes), selected = selected == category, onClick = { onSelect(category) })
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val colors = MaterialTheme.colorScheme
    val shadows = LocalGilpickShadows.current
    val shadow = if (selected) shadows.calendarSelected else shadows.card
    // 보이는 칩은 Figma대로 작지만 터치 영역은 48dp다(10절). 바깥 상자가 누름을 받는다.
    Box(
        modifier = Modifier
            .heightIn(min = MIN_TOUCH)
            .clip(CircleShape)
            .clickable(onClick = onClick, role = Role.Button)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        val base: Modifier = Modifier
        Box(
            modifier = shadow
                .fold(base) { acc, s -> acc.dropShadow(CircleShape, s) }
                .clip(CircleShape)
                .background(if (selected) colors.primary else colors.surface.copy(alpha = CHIP_ALPHA))
                .padding(horizontal = spacing.space4, vertical = spacing.space2),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) colors.onPrimary else colors.onSurfaceVariant,
            )
        }
    }
}

/** 하단 결과 시트(비모달, 지도 위): `radiusXl` 상단, 손잡이, `sheetOverMap` 그림자, 최대 55%. 상태별 본문은 시트 안에 둔다. */
@Composable
private fun ResultSheet(
    state: AlternativeSearchUiState,
    onRetry: () -> Unit,
    onReauthenticate: () -> Unit,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onToggleSelect: (String) -> Unit,
    onSelect: (AlternativeSearchItemDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val shape = RoundedCornerShape(topStart = radius.xl, topEnd = radius.xl)

    Column(
        modifier = LocalGilpickShadows.current.sheetOverMap
            .fold(modifier.fillMaxWidth()) { acc, shadow -> acc.dropShadow(shape, shadow) }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .testTag(TAG_SEARCH_SHEET),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = spacing.space3, bottom = spacing.space2)
                .width(HANDLE_WIDTH)
                .height(HANDLE_HEIGHT)
                .background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
        when (val phase = state.phase) {
            AlternativeSearchPhase.Idle -> SheetMessage(
                icon = R.drawable.ic_lucide_search,
                title = stringResource(R.string.place_search_idle_title),
                body = stringResource(R.string.alternative_search_idle_hint),
            )

            AlternativeSearchPhase.Loading -> DelayedLoading()

            AlternativeSearchPhase.Content -> Results(
                state = state,
                onLoadMore = onLoadMore,
                onRetryLoadMore = onRetryLoadMore,
                onReauthenticate = onReauthenticate,
                onToggleSelect = onToggleSelect,
                onSelect = onSelect,
            )

            AlternativeSearchPhase.Empty -> SheetMessage(
                icon = R.drawable.ic_lucide_search_x,
                title = stringResource(R.string.place_search_empty_title, state.committedQuery),
                body = stringResource(R.string.alternative_search_empty_hint),
                live = true,
            )

            AlternativeSearchPhase.TooShort -> SheetMessage(
                icon = R.drawable.ic_lucide_search,
                title = stringResource(R.string.place_search_too_short),
                body = stringResource(R.string.alternative_search_idle_hint),
                live = true,
            )

            is AlternativeSearchPhase.Failed -> SheetMessage(
                icon = R.drawable.ic_lucide_circle_x,
                title = stringResource(R.string.alternative_error_title),
                body = stringResource(phase.error.messageRes),
                live = true,
                action = when {
                    phase.error == AlternativeError.SessionExpired -> {
                        { GradientButton(label = stringResource(R.string.place_reauthenticate), onClick = onReauthenticate, height = RETRY_HEIGHT) }
                    }
                    phase.error.retryable -> {
                        { GradientButton(label = stringResource(R.string.place_search_retry), onClick = onRetry, height = RETRY_HEIGHT) }
                    }
                    else -> null
                },
            )
        }
    }
}

/** 시트 안 안내(가이드라인 5절 "목록·검색 영역 안 빈 상태", 64dp 상자). 검색 전·결과 없음·2글자 미만·실패가 같은 틀이다. */
@Composable
private fun SheetMessage(icon: Int, title: String, body: String, live: Boolean = false, action: (@Composable () -> Unit)? = null) {
    val spacing = LocalGilpickSpacing.current
    EmptyState(
        icon = icon,
        title = title,
        body = body,
        size = EmptyStateSize.Inline,
        action = action,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.space5)
            .then(if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
    )
}

/** 조회 대기 표시. 1초를 넘길 때만 표시한다(가이드라인 9절). */
@Composable
private fun DelayedLoading() {
    var visible by remember { mutableStateOf(false) }
    val label = stringResource(R.string.place_search_loading)
    val spacing = LocalGilpickSpacing.current

    LaunchedEffect(Unit) {
        delay(LOADING_INDICATOR_DELAY_MILLIS)
        visible = true
    }

    Box(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.space8), contentAlignment = Alignment.Center) {
        if (visible) {
            CircularProgressIndicator(modifier = Modifier.clearAndSetSemantics { contentDescription = label })
        }
    }
}

/** `검색 결과 N곳`(N `primary`) + 조건부 반경 부제, 결과 행 목록. 끝에 닿으면 다음 페이지를 요청한다. */
@Composable
private fun Results(
    state: AlternativeSearchUiState,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onReauthenticate: () -> Unit,
    onToggleSelect: (String) -> Unit,
    onSelect: (AlternativeSearchItemDto) -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val colors = MaterialTheme.colorScheme
    val extra = LocalGilpickColors.current
    val listState = rememberLazyListState()
    val results = state.results
    val loadingMoreLabel = stringResource(R.string.place_search_loading_more)

    LaunchedEffect(listState, state.hasNext) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { lastVisible ->
                // 첫 항목은 요약 행이라 결과 index는 하나 밀린다.
                if (state.hasNext && lastVisible != null && lastVisible >= results.size - 1) onLoadMore()
            }
    }

    val attribution = results.map { it.place }.tourApiAttributionText()

    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().testTag(TAG_SEARCH_LIST)) {
        item(key = "summary") {
            val count = results.size.toString()
            val summary = stringResource(R.string.place_search_summary, results.size)
            val start = summary.indexOf(count)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = spacing.space5, end = spacing.space5, bottom = spacing.space2)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildAnnotatedString {
                        append(summary.substring(0, start))
                        withStyle(SpanStyle(color = colors.primary)) { append(count) }
                        append(summary.substring(start + count.length))
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Black,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                val filters = state.filters
                if (filters?.originName != null && filters.radiusMeters != null) {
                    Text(
                        text = stringResource(R.string.alternative_search_radius, filters.originName, distanceLabel(filters.radiusMeters)),
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                    )
                }
            }
        }
        itemsIndexed(results, key = { _, item -> item.place.placeId }) { index, item ->
            ResultRow(
                number = index + 1,
                item = item,
                selected = item.place.placeId == state.selectedPlaceId,
                onToggle = { onToggleSelect(item.place.placeId) },
                onSelect = { onSelect(item) },
            )
            if (index < results.lastIndex) {
                HorizontalDivider(color = colors.background, modifier = Modifier.padding(horizontal = spacing.space5))
            }
        }
        if (state.loadingMore) {
            item(key = "loading_more") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(spacing.space4)
                        .semantics { contentDescription = loadingMoreLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(spacing.space6))
                }
            }
        }
        val loadMoreError = state.loadMoreError
        if (loadMoreError != null) {
            item(key = "load_more_failed") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(spacing.space4)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing.space2),
                ) {
                    Text(text = stringResource(R.string.place_search_load_more_failed), style = MaterialTheme.typography.bodyMedium, color = colors.error)
                    Text(
                        text = stringResource(loadMoreError.messageRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    if (loadMoreError == AlternativeError.SessionExpired) {
                        GradientButton(label = stringResource(R.string.place_reauthenticate), onClick = onReauthenticate, height = RETRY_HEIGHT)
                    } else {
                        GradientButton(label = stringResource(R.string.place_search_load_more_retry), onClick = onRetryLoadMore, height = RETRY_HEIGHT)
                    }
                }
            }
        }
        // 결과 목록 단위 공공데이터 출처 한 줄(F009 UI-011, #578). 결과별 배지로 붙이지 않는다.
        attribution?.let { attribution ->
            item(key = "attribution") {
                Text(
                    text = attribution,
                    fontSize = 11.sp,
                    color = extra.muted,
                    modifier = Modifier.padding(horizontal = spacing.space5, vertical = spacing.space3),
                )
            }
        }
        item(key = "bottom") { Box(modifier = Modifier.height(spacing.space6)) }
    }
}

/**
 * 결과 행(Figma): 36dp 번호 상자(선택 시 `primary` 채움), 장소명, 조건부 `혼잡`·`마감` 배지, 거리·상태, `선택`(32dp, 터치 48).
 * 행 탭은 지도 마커와 같은 선택 상태를 바꾸고, 방문할 수 없는 결과는 문구와 흐림을 병기하며 `선택`이 비활성이다(UI-007).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultRow(number: Int, item: AlternativeSearchItemDto, selected: Boolean, onToggle: () -> Unit, onSelect: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = MaterialTheme.colorScheme
    val extra = LocalGilpickColors.current
    val distance = item.distanceMeters?.let { stringResource(R.string.alternative_search_from_origin, distanceLabel(it)) }
    val status = if (item.inSchedule) stringResource(R.string.alternative_in_schedule) else operatingLabel(item.operatingStatus, closesAt = null)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) extra.surfaceTint else colors.surface)
            .clickable(onClick = onToggle, role = Role.Button)
            .semantics { this.selected = selected }
            .padding(horizontal = spacing.space5, vertical = spacing.space4)
            .testTag(TAG_SEARCH_ROW_PREFIX + item.place.placeId),
        horizontalArrangement = Arrangement.spacedBy(spacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(NUMBER_BOX)
                .border(NUMBER_BORDER, if (selected) colors.primary else colors.outlineVariant, RoundedCornerShape(radius.md))
                .background(if (selected) colors.primary else colors.surface, RoundedCornerShape(radius.md)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Black,
                fontFamily = number.toString().displayFont(),
                color = if (selected) colors.onPrimary else colors.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(if (item.visitable) 1f else DISABLED_ALPHA),
            verticalArrangement = Arrangement.spacedBy(spacing.space1 / 2),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.space2), verticalArrangement = Arrangement.Center) {
                Text(
                    text = item.place.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = item.place.name.displayFont(),
                    color = colors.onSurface,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                if (item.crowded == true) {
                    Badge(text = stringResource(R.string.alternative_badge_crowded), background = extra.warningContainer, color = extra.warning, bold = true)
                }
                item.closesAt?.let { closesAt ->
                    Badge(text = stringResource(R.string.alternative_badge_closes, clockLabel(closesAt)), background = colors.background, color = colors.onSurfaceVariant, bold = false)
                }
            }
            // 360dp·글자 2배에서 한 줄에 안 들어가면 문구 단위로 줄을 내린다(UI-008).
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (distance != null) {
                    Text(text = distance, style = MaterialTheme.typography.bodyMedium, color = extra.muted)
                }
                if (status != null) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (item.visitable) extra.muted else extra.warning,
                    )
                }
            }
        }
        SelectButton(enabled = item.visitable, onClick = onSelect)
    }
}

@Composable
private fun Badge(text: String, background: Color, color: Color, bold: Boolean) {
    val spacing = LocalGilpickSpacing.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, letterSpacing = 0.sp),
        color = color,
        modifier = Modifier
            .background(background, CircleShape)
            .padding(horizontal = spacing.space2, vertical = spacing.space1 / 2),
    )
}

/** `선택`: 32dp `primaryContainer` `radiusMd`, 13sp 700 `primary`, 터치 48dp. 방문 불가면 흐림 + 클릭 차단. */
@Composable
private fun SelectButton(enabled: Boolean, onClick: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val colors = MaterialTheme.colorScheme
    // 보이는 버튼은 32dp지만 터치 영역은 48dp다(10절). 바깥 상자가 누름을 받는다.
    Box(
        modifier = Modifier
            .sizeIn(minWidth = MIN_TOUCH, minHeight = MIN_TOUCH)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(RoundedCornerShape(LocalGilpickRadius.current.md))
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = SELECT_HEIGHT)
                .clip(RoundedCornerShape(LocalGilpickRadius.current.md))
                .background(colors.primaryContainer)
                .padding(horizontal = spacing.space3),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.alternative_select),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = colors.primary,
            )
        }
    }
}

/** UI test가 찾는 tag. */
internal const val TAG_SEARCH_ROW_PREFIX = "alternative_search_row_"
internal const val TAG_SEARCH_MAP = "alternative_search_map"
internal const val TAG_SEARCH_SHEET = "alternative_search_sheet"
internal const val TAG_SEARCH_LIST = "alternative_search_list"
internal const val TAG_SEARCH_CHIPS = "alternative_search_chips"

private const val LOADING_INDICATOR_DELAY_MILLIS = 1_000L

/** 시트가 차지할 수 있는 최대 화면 높이 비율(Figma `maxHeight: 55%`). 나머지는 지도 조작 영역이다. */
internal const val SHEET_MAX_FRACTION = 0.55f

/** 가이드라인 10절 최소 터치 영역. */
private val MIN_TOUCH: Dp = 48.dp

/** Figma 실측: 검색창 52dp·흰 96%, 뒤로 20dp, 돋보기 18dp, 칩 흰 90%, 손잡이 40×6, 번호 상자 36dp·2dp, `선택` 32dp. 화면 전용이라 토큰이 아니다. */
private val SEARCH_BAR_HEIGHT: Dp = 52.dp
private const val SEARCH_BAR_ALPHA = 0.96f
private val BACK_ICON: Dp = 20.dp
private val SEARCH_ICON: Dp = 18.dp
private const val CHIP_ALPHA = 0.9f
private val HANDLE_WIDTH: Dp = 40.dp
private val HANDLE_HEIGHT: Dp = 6.dp
private val NUMBER_BOX: Dp = 36.dp
private val NUMBER_BORDER: Dp = 2.dp
private val SELECT_HEIGHT: Dp = 32.dp
private val RETRY_HEIGHT: Dp = 48.dp
private const val DISABLED_ALPHA = 0.4f
