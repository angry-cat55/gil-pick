package com.gilpick.place

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gilpick.R
import com.gilpick.ui.component.RemoteImage
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSizing
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.gilpick.ui.theme.displayFont
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 장소 검색 화면. 모양은 Figma `AddPlaceScreen`을 그대로 따른다(UI-001·UI-010).
 *
 * 검색은 키보드의 검색 동작으로만 실행한다(FR-003a). 칩은 조건만 바꾼다. Figma의 `거리순 ▾`는
 * 정렬 API가 없어 표시만 하고, 행의 `HH:MM 마감`은 마감 시각을 계산하지 않으므로(FR-007) Google
 * 영업 상태 문구만 쓴다.
 *
 * @param state 현재 검색 상태.
 * @param onBack 이전 화면으로 돌아간다.
 * @param onQueryChange 입력창 값이 바뀌었다. 검색하지 않는다.
 * @param onClearQuery 입력창의 지우기 버튼.
 * @param onCategoryChange 칩을 골랐다. `null`은 `전체`.
 * @param onSearch 키보드의 검색 동작.
 * @param onRetry 첫 페이지 조회 실패 뒤 다시 시도.
 * @param onReauthenticate 로그인 상태가 만료됐다. F001 재인증 흐름으로 넘어간다.
 * @param onLoadMore 목록 끝에 닿았다.
 * @param onRetryLoadMore 추가 조회 실패 뒤 다시 시도.
 * @param onSearchByCategory 빈 결과의 `카테고리로 찾기`.
 * @param onPlaceClick 행의 이미지·본문을 눌러 상세로 간다.
 * @param onAddToSchedule 행의 `+` 시트에서 확정한 값. 저장은 F004가 맡는다(FR-014).
 */
@Composable
fun PlaceSearchScreen(
    state: PlaceSearchUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onCategoryChange: (PlaceCategory?) -> Unit,
    onSearch: () -> Unit,
    onRetry: () -> Unit,
    onReauthenticate: () -> Unit,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onSearchByCategory: () -> Unit,
    onPlaceClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onAddToSchedule: (PlaceDto, AddToScheduleRequest) -> Unit = { _, _ -> },
) {
    var sheetPlace by remember { mutableStateOf<PlaceDto?>(null) }

    sheetPlace?.let { place ->
        AddToScheduleSheet(
            placeName = place.name,
            defaultMinutes = place.recommendedStayMinutes,
            onDismiss = { sheetPlace = null },
            onConfirm = { request ->
                sheetPlace = null
                onAddToSchedule(place, request)
            },
        )
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Header(
            query = state.query,
            category = state.category,
            onBack = onBack,
            onQueryChange = onQueryChange,
            onClearQuery = onClearQuery,
            onCategoryChange = onCategoryChange,
            onSearch = onSearch,
        )
        Box(modifier = Modifier.weight(1f)) {
            when (val phase = state.phase) {
                PlaceSearchPhase.Idle -> EmptyState(
                    icon = R.drawable.ic_lucide_search,
                    title = stringResource(R.string.place_search_idle_title),
                    body = stringResource(R.string.place_search_idle_hint),
                )

                PlaceSearchPhase.Loading -> LoadingState(label = stringResource(R.string.place_search_loading))

                PlaceSearchPhase.Content -> Results(
                    state = state,
                    onLoadMore = onLoadMore,
                    onRetryLoadMore = onRetryLoadMore,
                    onReauthenticate = onReauthenticate,
                    onPlaceClick = onPlaceClick,
                    onAdd = { sheetPlace = it },
                )

                PlaceSearchPhase.Empty -> EmptyState(
                    icon = R.drawable.ic_lucide_search_x,
                    title = stringResource(R.string.place_search_empty_title, state.committedQuery),
                    body = stringResource(R.string.place_search_empty_hint),
                    live = true,
                ) {
                    OutlineButton(label = stringResource(R.string.place_search_by_category), onClick = onSearchByCategory)
                }

                is PlaceSearchPhase.Invalid -> StateMessage(
                    title = stringResource(
                        when (phase.reason) {
                            InvalidReason.NO_CONDITION -> R.string.place_search_needs_condition
                            InvalidReason.TOO_SHORT -> R.string.place_search_too_short
                        },
                    ),
                    body = null,
                    live = true,
                    action = {},
                )

                is PlaceSearchPhase.Failed -> StateMessage(
                    title = stringResource(phase.error.searchMessageRes),
                    titleColor = MaterialTheme.colorScheme.error,
                    body = null,
                    live = true,
                    action = {
                        // 재시도해도 같은 실패(호출 한도 등)에는 버튼을 두지 않는다. 문구가 잠시 후를 안내한다.
                        when {
                            phase.error.kind == PlaceErrorKind.SESSION_EXPIRED ->
                                OutlineButton(label = stringResource(R.string.place_reauthenticate), onClick = onReauthenticate)
                            phase.error.retryable ->
                                OutlineButton(label = stringResource(R.string.place_search_retry), onClick = onRetry)
                        }
                    },
                )
            }
        }
    }
}

/** Figma 헤더: 뒤로 가기·제목, 검색창, 카테고리 칩. 흰 배경 위에 놓이고 스크롤되지 않는다. */
@Composable
private fun Header(
    query: String,
    category: PlaceCategory?,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onCategoryChange: (PlaceCategory?) -> Unit,
    onSearch: () -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val title = stringResource(R.string.place_search_title)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(start = spacing.space5, end = spacing.space5, top = spacing.space3, bottom = spacing.space4),
    ) {
        Row(
            modifier = Modifier.padding(bottom = spacing.space4),
            horizontalArrangement = Arrangement.spacedBy(spacing.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Figma 36dp 사각 버튼. 터치 영역은 48dp로 두고 사각 밖은 투명이다.
            IconButton(onClick = onBack, modifier = Modifier.size(MIN_TOUCH)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(radius.md))
                        .background(colors.background),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_arrow_left),
                        contentDescription = stringResource(R.string.place_search_back),
                        tint = colors.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = title.displayFont(),
                color = colors.onSurface,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = spacing.space3)
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(radius.md))
                .background(colors.background)
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(spacing.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_search),
                contentDescription = null,
                tint = LocalGilpickColors.current.muted,
                modifier = Modifier.size(16.dp),
            )
            val hint = stringResource(R.string.place_search_hint)
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
                            Text(text = hint, style = MaterialTheme.typography.bodyLarge, color = LocalGilpickColors.current.muted)
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = onClearQuery, modifier = Modifier.size(MIN_TOUCH)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_circle_x),
                        contentDescription = stringResource(R.string.place_search_clear),
                        tint = LocalGilpickColors.current.faint,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.space2),
        ) {
            CategoryChip(
                label = stringResource(R.string.place_search_category_all),
                selected = category == null,
                onClick = { onCategoryChange(null) },
            )
            CHIP_CATEGORIES.forEach { option ->
                CategoryChip(
                    label = stringResource(option.labelRes),
                    selected = category == option,
                    onClick = { onCategoryChange(option) },
                )
            }
        }
    }
}

/** Figma 칩: 선택 시 `onSurface` 배경·흰 글자, 아니면 `background` 배경·`onSurfaceVariant` 글자. */
@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(LocalGilpickRadius.current.md)

    Box(
        // 보이는 칩은 36dp 남짓이라 터치 영역만 48dp로 늘린다.
        modifier = Modifier
            .heightIn(min = MIN_TOUCH)
            .clickable(onClick = onClick, role = Role.Tab)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) colors.surface else colors.onSurfaceVariant,
            modifier = Modifier
                .clip(shape)
                .background(if (selected) colors.onSurface else colors.background)
                .padding(horizontal = 14.dp, vertical = LocalGilpickSpacing.current.space2),
        )
    }
}

/** Figma 결과 영역: `검색 결과 N곳`·`거리순` 요약 뒤에 흰 블록 안 행 목록. 끝에 닿으면 다음 페이지를 받는다. */
@Composable
private fun Results(
    state: PlaceSearchUiState,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onReauthenticate: () -> Unit,
    onPlaceClick: (String) -> Unit,
    onAdd: (PlaceDto) -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val results = state.results
    val loadingMoreLabel = stringResource(R.string.place_search_loading_more)

    // 마지막에서 두 번째 행이 보이면 미리 받아 스크롤이 멈추지 않게 한다.
    LaunchedEffect(listState, state.hasNext) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { lastVisible ->
                // 첫 항목은 요약 행이라 결과 index는 하나 밀린다.
                if (state.hasNext && lastVisible != null && lastVisible >= results.size - 1) onLoadMore()
            }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item(key = "summary") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.space5, vertical = spacing.space3),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.place_search_summary, results.size),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    // 결과가 바뀌면 판독기가 요약을 읽는다(UI-006).
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(
                    text = stringResource(R.string.place_search_sort_distance),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary,
                )
            }
        }
        itemsIndexed(results, key = { _, place -> place.placeId }) { index, place ->
            Column(modifier = Modifier.background(colors.surface)) {
                PlaceRow(place = place, onClick = { onPlaceClick(place.placeId) }, onAdd = { onAdd(place) })
                if (index < results.lastIndex) {
                    HorizontalDivider(color = colors.background, modifier = Modifier.padding(horizontal = spacing.space5))
                }
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
                    Text(
                        text = stringResource(R.string.place_search_load_more_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.error,
                    )
                    Text(
                        text = stringResource(loadMoreError.searchMessageRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    // 추가 조회는 호출 한도라도 사용자가 다시 시도할 수 있게 둔다(FR-012). 로그인 만료만 재인증으로 보낸다.
                    if (loadMoreError.kind == PlaceErrorKind.SESSION_EXPIRED) {
                        OutlineButton(label = stringResource(R.string.place_reauthenticate), onClick = onReauthenticate)
                    } else {
                        OutlineButton(label = stringResource(R.string.place_search_load_more_retry), onClick = onRetryLoadMore)
                    }
                }
            }
        }
    }
}

/** Figma 결과 행: 60dp 썸네일, 이름·category·`★` 평점·영업 상태, 끝의 `+`. 이미지·본문은 상세로, `+`는 시트로. */
@Composable
private fun PlaceRow(place: PlaceDto, onClick: () -> Unit, onAdd: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = MaterialTheme.colorScheme
    val extra = LocalGilpickColors.current
    val statusRes = businessStatusLabelRes(place.businessStatus)
    val ratingText = place.rating?.toRatingText()
    val addLabel = stringResource(R.string.place_search_add, place.name)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.space5, end = spacing.space5 - CIRCLE_INSET, top = spacing.space4, bottom = spacing.space4),
        horizontalArrangement = Arrangement.spacedBy(spacing.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(radius.md))
                .clickable(onClick = onClick, role = Role.Button),
            horizontalArrangement = Arrangement.spacedBy(spacing.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 장소명이 바로 옆에 있어 썸네일은 장식이다(UI-005).
            RemoteImage(
                url = place.imageUrl,
                contentDescription = null,
                shape = RoundedCornerShape(radius.md),
                modifier = Modifier.size(60.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = place.name, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                Text(
                    text = stringResource(place.category.labelRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    modifier = Modifier.padding(bottom = spacing.space1),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (ratingText != null) {
                        Text(text = "★", fontSize = 11.sp, color = extra.star)
                        Text(
                            text = ratingText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = ratingText.displayFont(),
                            color = colors.onSurface,
                            modifier = Modifier.semantics {
                                contentDescription = "평점 ${ratingText}점"
                            },
                        )
                    }
                    if (statusRes != null) {
                        Text(
                            text = stringResource(statusRes),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (place.businessStatus == PlaceBusinessStatus.OPERATIONAL) extra.success else extra.warning,
                        )
                    }
                }
            }
        }
        // Figma 32dp 사각 버튼. 터치 영역은 48dp.
        IconButton(onClick = onAdd, modifier = Modifier.size(MIN_TOUCH)) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(radius.sm))
                    .background(colors.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_plus),
                    contentDescription = addLabel,
                    tint = colors.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** Figma 빈 상태: `background` 상자 안 `faint` 아이콘, 제목, 안내, 선택적 행동. 검색 전과 결과 없음이 같은 틀이다. */
@Composable
private fun EmptyState(
    icon: Int,
    title: String,
    body: String,
    live: Boolean = false,
    action: @Composable () -> Unit = {},
) {
    val spacing = LocalGilpickSpacing.current
    val sizing = LocalGilpickSizing.current
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = spacing.space8)
            .padding(bottom = sizing.emptyBottomPadding)
            .then(if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = spacing.space4)
                .size(sizing.emptyIconCircle)
                .clip(RoundedCornerShape(LocalGilpickRadius.current.lg))
                .background(colors.background),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = LocalGilpickColors.current.faint,
                modifier = Modifier.size(sizing.emptyIcon),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = spacing.space1),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalGilpickColors.current.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = spacing.space5),
        )
        action()
    }
}

/** Figma `카테고리로 찾기`: 44dp, 2dp `outlineVariant` 테두리, 16dp 곡률. 재시도 버튼도 같은 모양이다. */
@Composable
private fun OutlineButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(LocalGilpickRadius.current.lg)

    Box(
        modifier = Modifier
            .heightIn(min = MIN_TOUCH)
            .clip(shape)
            .clickable(onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(44.dp)
                .border(2.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .padding(horizontal = LocalGilpickSpacing.current.space6),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Figma 칩 순서. `기타`는 Figma에 없어 두지 않는다. */
private val CHIP_CATEGORIES = listOf(
    PlaceCategory.NATURE,
    PlaceCategory.HISTORY_CULTURE,
    PlaceCategory.FOOD,
    PlaceCategory.CAFE,
    PlaceCategory.SHOPPING,
)

private val MIN_TOUCH = 48.dp

/** 48dp 터치 영역 안에 32dp 사각을 가운데 두면 밖 여백은 8dp다. Figma 오른쪽 여백 20에서 이만큼 뺀다. */
private val CIRCLE_INSET = 8.dp
