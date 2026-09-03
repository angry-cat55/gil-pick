package com.gilpick.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.gilpick.R
import com.gilpick.ui.component.BadgeTone
import com.gilpick.ui.component.TripCard
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSizing
import com.gilpick.ui.theme.LocalGilpickSpacing
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 여행 목록 화면.
 *
 * 검색어와 상태 필터는 어떤 표시 단계에서도 남아 있다. 조회에 실패했다고 사용자가
 * 입력한 조건이 사라지면 다시 입력해야 한다.
 *
 * 표시 단계는 `docs/design/ui-guidelines.md` 9절의 네 상태를 따른다. 색상·간격·곡률은
 * `com.gilpick.ui.theme` 토큰에서만 읽는다.
 *
 * @param state 현재 목록 상태.
 * @param onQueryChange 검색어 입력을 반영한다.
 * @param onStatusFilterChange 상태 필터를 반영한다.
 * @param onRetry 실패한 조회를 다시 시도한다.
 * @param onLoadMore 다음 페이지를 요청한다.
 * @param onCreateTrip 여행 생성 화면으로 이동한다.
 * @param onTripClick 고른 여행의 상세 화면으로 이동한다.
 * @param onLogout 현재 기기에서 로그아웃한다.
 */
@Composable
fun TripListScreen(
    state: TripListUiState,
    onQueryChange: (String) -> Unit,
    onStatusFilterChange: (TripStatus?) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onCreateTrip: () -> Unit,
    onTripClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onLogout: () -> Unit = {},
) {
    val spacing = LocalGilpickSpacing.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = spacing.space5),
        verticalArrangement = Arrangement.spacedBy(spacing.space4),
    ) {
        Header(onCreateTrip = onCreateTrip, onLogout = onLogout)
        SearchField(query = state.query, onQueryChange = onQueryChange)
        StatusFilters(selected = state.statusFilter, onSelect = onStatusFilterChange)

        Box(modifier = Modifier.weight(1f)) {
            when (val phase = state.phase) {
                TripListPhase.Loading -> LoadingState()

                TripListPhase.Empty -> EmptyState(
                    filtered = state.filtered,
                    onCreateTrip = onCreateTrip,
                    onResetFilters = {
                        onQueryChange("")
                        onStatusFilterChange(null)
                    },
                )

                is TripListPhase.Failed -> ErrorState(error = phase.error, onRetry = onRetry)

                TripListPhase.Content -> TripList(
                    trips = state.trips,
                    loadingMore = state.loadingMore,
                    hasNext = state.hasNext,
                    onLoadMore = onLoadMore,
                    onTripClick = onTripClick,
                )
            }
        }
    }
}

/** 화면 제목과 주요 행동. */
@Composable
private fun Header(onCreateTrip: () -> Unit, onLogout: () -> Unit) {
    val spacing = LocalGilpickSpacing.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.space5),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.trips_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCreateTrip, modifier = Modifier.heightIn(min = MIN_TOUCH)) {
                Text(stringResource(R.string.trips_create))
            }
            // ponytail: 설정 화면(F012)이 생기기 전까지 로그아웃 진입점을 여기 둔다.
            // F001의 빈 shell에 있던 것을 잃지 않기 위한 임시 자리다.
            TextButton(onClick = onLogout, modifier = Modifier.heightIn(min = MIN_TOUCH)) {
                Text(stringResource(R.string.logout))
            }
        }
    }
}

/** 여행명 검색. 라벨을 placeholder로 대체하지 않는다(가이드라인 7절). */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val radius = LocalGilpickRadius.current

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_FIELD_HEIGHT),
        label = { Text(stringResource(R.string.trips_search_label)) },
        singleLine = true,
        shape = RoundedCornerShape(radius.sm),
    )
}

/** 상태 필터. 선택된 칩을 다시 누르면 해제해 전체로 돌아간다. */
@Composable
private fun StatusFilters(selected: TripStatus?, onSelect: (TripStatus?) -> Unit) {
    val spacing = LocalGilpickSpacing.current

    Row(horizontalArrangement = Arrangement.spacedBy(spacing.space2)) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.trips_filter_all)) },
            modifier = Modifier.heightIn(min = MIN_TOUCH),
        )
        TripStatus.entries.forEach { status ->
            FilterChip(
                selected = selected == status,
                onClick = { onSelect(if (selected == status) null else status) },
                label = { Text(stringResource(status.filterLabelRes)) },
                modifier = Modifier.heightIn(min = MIN_TOUCH),
            )
        }
    }
}

/**
 * 조회 대기 표시.
 *
 * 1초를 넘길 때만 표시한다. 금방 끝나는 조회에서 skeleton이 깜빡이면 오히려 느리게
 * 느껴진다(가이드라인 9절).
 */
@Composable
private fun LoadingState() {
    var visible by remember { mutableStateOf(false) }
    val label = stringResource(R.string.trips_loading)

    LaunchedEffect(Unit) {
        delay(LOADING_INDICATOR_DELAY_MILLIS)
        visible = true
    }

    if (visible) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.clearAndSetSemantics { contentDescription = label },
            )
        }
    }
}

/**
 * 결과가 없는 상태.
 *
 * 여행이 아예 없는 경우와 조건에 맞는 결과가 없는 경우는 다음 행동이 다르다.
 */
@Composable
private fun EmptyState(
    filtered: Boolean,
    onCreateTrip: () -> Unit,
    onResetFilters: () -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val sizing = LocalGilpickSizing.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = sizing.emptyBottomPadding),
        verticalArrangement = Arrangement.spacedBy(spacing.space3, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyStateIcon(filtered = filtered)
        Text(
            text = stringResource(
                if (filtered) R.string.trips_no_results else R.string.trips_empty,
            ),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(
                if (filtered) R.string.trips_no_results_hint else R.string.trips_empty_hint,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = if (filtered) onResetFilters else onCreateTrip,
            modifier = Modifier.heightIn(min = PRIMARY_BUTTON_HEIGHT),
        ) {
            Text(
                stringResource(
                    if (filtered) R.string.trips_reset_filters else R.string.trips_empty_create,
                ),
            )
        }
    }
}

/**
 * 빈 상태 아이콘.
 *
 * pen `23. 빈 상태 – 여행 0개`와 `24. 빈 상태 – 검색 0건`의 `IconCircle > I` 구조다.
 * `surfaceVariant` 원 위에 `outline` 색 아이콘을 올린다.
 *
 * 아이콘은 장식이므로 `contentDescription`을 비운다(가이드라인 10절). 왜 비었는지는
 * 아래 제목과 본문이 이미 전달하므로, 아이콘까지 읽으면 같은 뜻을 두 번 듣게 된다.
 */
@Composable
private fun EmptyStateIcon(filtered: Boolean) {
    val sizing = LocalGilpickSizing.current

    Box(
        modifier = Modifier
            .size(sizing.emptyIconCircle)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(
                if (filtered) R.drawable.ic_search_x else R.drawable.ic_map,
            ),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(sizing.emptyIcon),
        )
    }
}

/** 실패 상태. 원인과 다음 행동을 함께 쓴다(가이드라인 9절). */
@Composable
private fun ErrorState(error: TripListError, onRetry: () -> Unit) {
    val spacing = LocalGilpickSpacing.current

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(spacing.space3, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                when (error) {
                    TripListError.NETWORK -> R.string.trips_error_network
                    TripListError.UNEXPECTED -> R.string.trips_error_unexpected
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = PRIMARY_BUTTON_HEIGHT)) {
            Text(stringResource(R.string.trips_retry))
        }
    }
}

/** 여행 목록. 끝에 닿으면 다음 페이지를 요청한다. */
@Composable
private fun TripList(
    trips: List<TripDto>,
    loadingMore: Boolean,
    hasNext: Boolean,
    onLoadMore: () -> Unit,
    onTripClick: (String) -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val listState = rememberLazyListState()
    val loadingMoreLabel = stringResource(R.string.trips_loading_more)
    // 목록이 열려 있는 동안 날짜가 바뀌는 일은 드물어 한 번만 읽는다.
    val today = remember { LocalDate.now(KST) }

    // 마지막에서 두 번째 항목이 보이면 미리 받아 스크롤이 멈추지 않게 한다.
    LaunchedEffect(listState, hasNext) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (hasNext && lastVisible != null && lastVisible >= trips.lastIndex - 1) {
                    onLoadMore()
                }
            }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(spacing.space3),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = spacing.space6),
    ) {
        items(items = trips, key = { it.tripId }) { trip ->
            TripCard(
                title = trip.name,
                period = stringResource(R.string.trips_period, trip.startDate, trip.endDate),
                supporting = trip.supportingText(today),
                badgeLabel = stringResource(trip.status.labelRes),
                badgeTone = trip.status.tone,
                onClick = { onTripClick(trip.tripId) },
            )
        }
        if (loadingMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = spacing.space4),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .clearAndSetSemantics { contentDescription = loadingMoreLabel },
                    )
                }
            }
        }
    }
}

/**
 * 카드 아래 한 줄 부가 정보.
 *
 * 진행 중인 여행만 `며칠째`를 쓴다. 진행 중일 때 알고 싶은 것은 여행이 며칠짜리인지가
 * 아니라 지금 몇째 날인지다(pen `02. 여행 목록 화면`의 `현재 진행 중인 여행` 그룹).
 */
@Composable
private fun TripDto.supportingText(today: LocalDate): String =
    if (status == TripStatus.IN_PROGRESS) {
        stringResource(R.string.trips_day_index, tripDayIndex(startDate, today))
    } else {
        stringResource(R.string.trips_day_count, dayCount)
    }

/** 상태 뱃지 문구. 색 없이도 뜻이 통해야 한다. */
private val TripStatus.labelRes: Int
    get() = when (this) {
        TripStatus.UPCOMING -> R.string.trips_status_upcoming
        TripStatus.IN_PROGRESS -> R.string.trips_status_in_progress
        TripStatus.COMPLETED -> R.string.trips_status_completed
    }

/** 필터 칩 문구. */
private val TripStatus.filterLabelRes: Int
    get() = when (this) {
        TripStatus.UPCOMING -> R.string.trips_filter_upcoming
        TripStatus.IN_PROGRESS -> R.string.trips_filter_in_progress
        TripStatus.COMPLETED -> R.string.trips_filter_completed
    }

/**
 * 상태 뱃지 강조 수준.
 *
 * 지금 진행 중인 여행만 강조한다. 예정과 완료는 같은 색을 쓰고 문구로 구분한다.
 * 가이드라인 3절이 대비를 검증한 뱃지 조합이 두 가지뿐이라, 검증되지 않은 색을
 * 새로 만들기보다 색 역할을 줄였다.
 */
private val TripStatus.tone: BadgeTone
    get() = if (this == TripStatus.IN_PROGRESS) BadgeTone.ACCENT else BadgeTone.NEUTRAL

/** 가이드라인 9절: 1초를 넘길 때만 대기 표시를 띄운다. */
private const val LOADING_INDICATOR_DELAY_MILLIS = 1_000L

/** 가이드라인 5절·10절: 주요 CTA 52~56dp, 터치 영역 48dp 이상. */
private val PRIMARY_BUTTON_HEIGHT = Dp(56f)
private val MIN_FIELD_HEIGHT = Dp(56f)
private val MIN_TOUCH = Dp(48f)
