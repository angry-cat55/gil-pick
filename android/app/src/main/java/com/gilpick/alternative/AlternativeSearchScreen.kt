package com.gilpick.alternative

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gilpick.R
import com.gilpick.place.EmptyState
import com.gilpick.place.LoadingState
import com.gilpick.place.OutlineButton
import com.gilpick.place.PlaceRow
import com.gilpick.place.SearchField
import com.gilpick.place.StateMessage
import com.gilpick.route.distanceLabel
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.gilpick.ui.theme.displayFont
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 직접 검색 화면(spec US3, UI-007·UI-008, T032). 모양은 F003 `PlaceSearchScreen`을 따르되 카테고리 칩이 없다.
 *
 * 결과 행은 F003 [PlaceRow]를 재사용하고 본문 아래에 `기존 장소에서 N m`와 `방문 불가`·`이미 일정에 있음`을
 * 붙인다. 방문할 수 없는 행은 문구와 흐림을 병기하고 누를 수 없다(UI-007). 행을 누르면 [onSelect]로
 * F010에 넘길 값을 만들 뿐 일정은 바꾸지 않는다(FR-015).
 *
 * @param onBack 대체 장소 화면으로 돌아간다.
 * @param onSearch 키보드의 검색 동작.
 * @param onRetry 첫 페이지 조회 실패 뒤 다시 시도.
 * @param onReauthenticate 로그인 상태가 만료됐다. F001 재인증 흐름으로 넘어간다.
 * @param onLoadMore 목록 끝에 닿았다.
 * @param onRetryLoadMore 추가 조회 실패 뒤 다시 시도.
 * @param onSelect 방문 가능한 행을 골랐다.
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
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Header(query = state.query, onBack = onBack, onQueryChange = onQueryChange, onClearQuery = onClearQuery, onSearch = onSearch)
        Box(modifier = Modifier.weight(1f)) {
            when (val phase = state.phase) {
                AlternativeSearchPhase.Idle -> EmptyState(
                    icon = R.drawable.ic_lucide_search,
                    title = stringResource(R.string.place_search_idle_title),
                    body = stringResource(R.string.alternative_search_idle_hint),
                )

                AlternativeSearchPhase.Loading -> LoadingState(label = stringResource(R.string.place_search_loading))

                AlternativeSearchPhase.Content -> Results(
                    state = state,
                    onLoadMore = onLoadMore,
                    onRetryLoadMore = onRetryLoadMore,
                    onReauthenticate = onReauthenticate,
                    onSelect = onSelect,
                )

                AlternativeSearchPhase.Empty -> EmptyState(
                    icon = R.drawable.ic_lucide_search_x,
                    title = stringResource(R.string.place_search_empty_title, state.committedQuery),
                    body = stringResource(R.string.alternative_search_empty_hint),
                    live = true,
                )

                AlternativeSearchPhase.TooShort -> StateMessage(
                    title = stringResource(R.string.place_search_too_short),
                    body = null,
                    live = true,
                    action = {},
                )

                is AlternativeSearchPhase.Failed -> StateMessage(
                    title = stringResource(R.string.alternative_error_title),
                    titleColor = MaterialTheme.colorScheme.error,
                    body = stringResource(phase.error.messageRes),
                    live = true,
                    action = {
                        when {
                            phase.error == AlternativeError.SessionExpired ->
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

/** F003 헤더에서 칩을 뺀 것: 뒤로 가기·`직접 검색`, 검색창. */
@Composable
private fun Header(
    query: String,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onSearch: () -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val colors = MaterialTheme.colorScheme
    val title = stringResource(R.string.alternative_search)

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
            IconButton(onClick = onBack, modifier = Modifier.size(MIN_TOUCH).testTag(TAG_SEARCH_BACK)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(LocalGilpickRadius.current.md))
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
        SearchField(query = query, onQueryChange = onQueryChange, onClearQuery = onClearQuery, onSearch = onSearch)
    }
}

/** 결과 영역: `검색 결과 N곳` 요약 뒤 흰 블록 안 행 목록. 끝에 닿으면 다음 페이지를 받는다(F003 `Results`와 같다). */
@Composable
private fun Results(
    state: AlternativeSearchUiState,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onReauthenticate: () -> Unit,
    onSelect: (AlternativeSearchItemDto) -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val colors = MaterialTheme.colorScheme
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

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item(key = "summary") {
            Text(
                text = stringResource(R.string.place_search_summary, results.size),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                modifier = Modifier
                    .padding(horizontal = spacing.space5, vertical = spacing.space3)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        itemsIndexed(results, key = { _, item -> item.place.placeId }) { index, item ->
            Column(modifier = Modifier.background(colors.surface).testTag(TAG_SEARCH_ROW_PREFIX + item.place.placeId)) {
                PlaceRow(
                    place = item.place,
                    onClick = { onSelect(item) },
                    enabled = item.visitable,
                    footer = { ItemFooter(item) },
                )
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
                        text = stringResource(loadMoreError.messageRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (loadMoreError == AlternativeError.SessionExpired) {
                        OutlineButton(label = stringResource(R.string.place_reauthenticate), onClick = onReauthenticate)
                    } else {
                        OutlineButton(label = stringResource(R.string.place_search_load_more_retry), onClick = onRetryLoadMore)
                    }
                }
            }
        }
    }
}

/**
 * 행 본문 아래 한 줄: `기존 장소에서 820m`(좌표 없으면 생략) · `이미 일정에 있음`/`방문 불가`/`운영시간 확인 불가`.
 *
 * 일정 포함이 운영 상태보다 앞선다(둘 다 방문 불가지만 이유는 하나만 보인다).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemFooter(item: AlternativeSearchItemDto) {
    val extra = LocalGilpickColors.current
    val distance = item.distanceMeters?.let { stringResource(R.string.alternative_search_from_origin, distanceLabel(it)) }
    val status = if (item.inSchedule) stringResource(R.string.alternative_in_schedule) else operatingLabel(item.operatingStatus, closesAt = null)

    if (distance == null && status == null) return
    // 360dp·글자 2배에서 한 줄에 안 들어가면 문구 단위로 줄을 내린다(UI-008).
    FlowRow(
        modifier = Modifier.padding(top = LocalGilpickSpacing.current.space1),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (distance != null) {
            Text(text = distance, fontSize = 12.sp, color = extra.muted)
        }
        if (status != null) {
            Text(
                text = status,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (item.visitable) extra.muted else extra.warning,
            )
        }
    }
}

/** UI test가 찾는 tag. */
internal const val TAG_SEARCH_BACK = "alternative_search_back"
internal const val TAG_SEARCH_ROW_PREFIX = "alternative_search_row_"

/** 가이드라인 10절 최소 터치 영역. */
private val MIN_TOUCH: Dp = 48.dp
