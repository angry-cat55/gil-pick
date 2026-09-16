package com.gilpick.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.SolidColor
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
import com.gilpick.notification.IconBoxButton
import com.gilpick.ui.component.ActiveTripCard
import com.gilpick.ui.component.CompletedTripCard
import com.gilpick.ui.component.EmptyState
import com.gilpick.ui.component.EmptyStateSize
import com.gilpick.ui.component.ErrorState as CommonErrorState
import com.gilpick.ui.component.SecondaryButton
import com.gilpick.ui.component.UpcomingTripCard
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickShadows
import com.gilpick.ui.theme.LocalGilpickSizing
import com.gilpick.ui.theme.LocalGilpickSpacing
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 여행 목록 화면(Figma `MyTripsScreen`, #441).
 *
 * 흰 헤더(윗제목·Hero title·알림 벨, 검색창, 필터 칩) 아래에 그룹별 카드 목록이 있고, 화면 하단 가운데에 `새 여행 만들기`
 * FAB가 떠 있다. 검색어와 상태 필터는 어떤 표시 단계에서도 남아 있다. 조회에 실패했다고 사용자가 입력한 조건이 사라지면
 * 다시 입력해야 한다.
 *
 * 표시 단계는 `docs/design/ui-guidelines.md` 9절의 네 상태를 따른다. 색상·간격·곡률은 `com.gilpick.ui.theme` 토큰에서만 읽는다.
 *
 * @param state 현재 목록 상태.
 * @param onQueryChange 검색어 입력을 반영한다.
 * @param onStatusFilterChange 상태 필터를 반영한다.
 * @param onRetry 실패한 조회를 다시 시도한다.
 * @param onLoadMore 다음 페이지를 요청한다.
 * @param onCreateTrip 여행 생성 화면으로 이동한다.
 * @param onTripClick 고른 여행의 상세 화면으로 이동한다.
 * @param onNotifications F011 알림 목록으로 이동한다.
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
    onNotifications: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header(
                query = state.query,
                onQueryChange = onQueryChange,
                selected = state.statusFilter,
                onSelect = onStatusFilterChange,
                onNotifications = onNotifications,
            )

            Box(modifier = Modifier.weight(1f)) {
                when (val phase = state.phase) {
                    TripListPhase.Loading -> LoadingState()

                    TripListPhase.Empty -> TripsEmptyState(
                        filtered = state.filtered,
                        onResetFilters = {
                            onQueryChange("")
                            onStatusFilterChange(null)
                        },
                    )

                    is TripListPhase.Failed -> ErrorState(error = phase.error, onRetry = onRetry)

                    TripListPhase.Content -> TripList(
                        trips = state.trips,
                        covers = state.covers,
                        loadingMore = state.loadingMore,
                        hasNext = state.hasNext,
                        onLoadMore = onLoadMore,
                        onTripClick = onTripClick,
                    )
                }
            }
        }

        // 조회 실패는 공통 오류 화면이 목록 자리를 대신하고 하단에 `다시 시도`가 고정돼 있어, 떠 있는 FAB가 그 버튼을 가린다.
        // 실패 동안에는 FAB를 두지 않는다(Figma `ErrorScreen`에도 없음, #446).
        if (state.phase !is TripListPhase.Failed) {
            CreateTripFab(
                onClick = onCreateTrip,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = LocalGilpickSpacing.current.space6),
            )
        }
    }
}

/**
 * 흰 헤더(Figma `MyTripsScreen` 상단): `MY TRIPS` 윗제목 + 26sp Hero title, 40dp 알림 벨, 검색창, 필터 칩.
 *
 * 알림 벨의 새 알림 점은 두지 않는다. 이 화면은 읽지 않은 알림 여부를 받지 않으므로 점을 그리면 사실과 다를 수 있다(12절).
 * 앱이 edge-to-edge라 흰 배경을 상태 표시줄 뒤까지 잇는다.
 */
@Composable
private fun Header(
    query: String,
    onQueryChange: (String) -> Unit,
    selected: TripStatus?,
    onSelect: (TripStatus?) -> Unit,
    onNotifications: () -> Unit,
) {
    val spacing = LocalGilpickSpacing.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .padding(start = spacing.space5, end = spacing.space5, top = spacing.space5, bottom = spacing.space4),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = spacing.space5),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.trips_overline),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = spacing.space1),
                )
                Text(
                    text = stringResource(R.string.trips_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconBoxButton(
                icon = R.drawable.ic_lucide_bell,
                contentDescription = stringResource(R.string.notification_open_bell),
                onClick = onNotifications,
                box = BELL_BOX,
                iconSize = BELL_ICON,
                modifier = Modifier.testTag(TAG_NOTIFICATIONS),
            )
        }
        SearchField(query = query, onQueryChange = onQueryChange)
        StatusFilters(selected = selected, onSelect = onSelect)
    }
}

/**
 * 검색 입력창(가이드라인 7절 "입력창" 검색형): 44dp `background` 채움, 테두리 없음, 앞 16dp `muted` 돋보기, placeholder만.
 *
 * 떠오르는 라벨이 없어 입력란 자체에 설명(`여행 이름 검색`)을 붙인다(10절).
 */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val label = stringResource(R.string.trips_search_label)

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = spacing.space4)
            .semantics { contentDescription = label },
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SEARCH_HEIGHT)
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(LocalGilpickRadius.current.md))
                    .padding(horizontal = SEARCH_HORIZONTAL_PADDING),
                horizontalArrangement = Arrangement.spacedBy(spacing.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_search),
                    contentDescription = null,
                    tint = colors.muted,
                    modifier = Modifier.size(SEARCH_ICON),
                )
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.trips_search_placeholder),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal),
                            color = colors.muted,
                        )
                    }
                    inner()
                }
            }
        },
    )
}

/**
 * 상태 필터. 선택된 칩을 다시 누르면 해제해 전체로 돌아간다.
 *
 * 글자 배율이 크면 한 줄에 네 칩이 들어가지 않아 다음 줄로 넘긴다(가로 스크롤·잘림 없음, 10절).
 */
@Composable
private fun StatusFilters(selected: TripStatus?, onSelect: (TripStatus?) -> Unit) {
    val spacing = LocalGilpickSpacing.current

    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.space2)) {
        StatusFilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            labelRes = R.string.trips_filter_all,
        )
        TripStatus.entries.forEach { status ->
            StatusFilterChip(
                selected = selected == status,
                onClick = { onSelect(if (selected == status) null else status) },
                labelRes = status.filterLabelRes,
            )
        }
    }
}

/**
 * 상태 필터 칩 하나(Figma `px-4 py-2 rounded-xl text-[13px] font-semibold`, 가이드라인 3절 D1).
 *
 * 선택은 `onSurface` 배경·흰 글자(17.74:1), 비선택은 `background` 배경·`onSurfaceVariant`, 테두리 없음(3절 컨트롤 경계).
 * 선택 여부는 색만이 아니라 `selectable` semantics로 전달한다(10절). 누르는 영역은 48dp 높이다.
 */
@Composable
private fun StatusFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    labelRes: Int,
) {
    val spacing = LocalGilpickSpacing.current
    val scheme = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .heightIn(min = MIN_TOUCH)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) scheme.surface else scheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(LocalGilpickRadius.current.md))
                .background(if (selected) scheme.onSurface else scheme.background)
                .padding(horizontal = spacing.space4, vertical = spacing.space2),
        )
    }
}

/**
 * 하단 가운데 FAB `새 여행 만들기`(Figma): 52dp, `primary`, 16dp 곡률, FAB 그림자 토큰(6절), 18dp 흰 `+`.
 *
 * 헤더의 글자 버튼을 대신한다. 목록 마지막 카드가 가리지 않도록 목록 아래에 여백을 둔다.
 */
@Composable
private fun CreateTripFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current
    val shape = RoundedCornerShape(LocalGilpickRadius.current.lg)
    val shadowed = LocalGilpickShadows.current.fab.fold(modifier) { acc, shadow -> acc.dropShadow(shape, shadow) }

    Row(
        modifier = shadowed
            .heightIn(min = FAB_HEIGHT)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = FAB_HORIZONTAL_PADDING),
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lucide_plus),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(FAB_ICON),
        )
        Text(
            text = stringResource(R.string.trips_create),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/**
 * 목록 그룹 헤더.
 *
 * Figma `MyTripsScreen`의 section 라벨이다. 8dp 상태 점과 라벨을 8dp 간격으로 두고,
 * 아래에 12dp를 띄운다. 좌우 4dp는 카드보다 살짝 안쪽으로 들여 쓰기 위한 값이다.
 *
 * **개수 배지는 그리지 않는다.** Figma 헤더에 없다(가이드라인 12절: 모양은 Figma가
 * 이긴다). 개수 자체는 [TripGroupSection.count]가 이미 들고 있어, 나중에 표시하기로
 * 하면 이 자리에 붙이면 된다.
 *
 * 점은 장식이라 별도 semantics를 붙이지 않는다. 그룹의 뜻은 옆 라벨이 전달한다.
 */
@Composable
private fun TripGroupHeader(group: TripGroup) {
    val spacing = LocalGilpickSpacing.current
    val sizing = LocalGilpickSizing.current
    val colors = LocalGilpickColors.current

    val dotColor = when (group) {
        TripGroup.IN_PROGRESS -> colors.success
        TripGroup.UPCOMING -> MaterialTheme.colorScheme.primary
        TripGroup.COMPLETED -> colors.faint
    }

    Row(
        modifier = Modifier.padding(horizontal = spacing.space1, vertical = spacing.space1),
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(sizing.groupDot)
                .background(dotColor, CircleShape),
        )
        Text(
            text = stringResource(group.labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 그룹 헤더 문구. */
private val TripGroup.labelRes: Int
    get() = when (this) {
        TripGroup.IN_PROGRESS -> R.string.trips_group_in_progress
        TripGroup.UPCOMING -> R.string.trips_group_upcoming
        TripGroup.COMPLETED -> R.string.trips_group_completed
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
 * 결과가 없는 상태(공통 [EmptyState], 가이드라인 5절 "화면 전체 빈 상태" 80dp).
 *
 * 여행이 아예 없으면 버튼을 두지 않는다. 하단 `새 여행 만들기` FAB와 같은 곳으로 가는 중복 진입점이라 FAB 하나만 남긴다(#568).
 * 조건에 맞는 결과가 없으면 조건 초기화 보조 버튼이다(Figma에 없는 상태라 공통 보조 버튼).
 */
@Composable
private fun TripsEmptyState(
    filtered: Boolean,
    onResetFilters: () -> Unit,
) {
    EmptyState(
        icon = if (filtered) R.drawable.ic_lucide_search_x else R.drawable.ic_map,
        title = stringResource(if (filtered) R.string.trips_no_results else R.string.trips_empty),
        body = stringResource(if (filtered) R.string.trips_no_results_hint else R.string.trips_empty_hint),
        modifier = Modifier.fillMaxSize(),
        size = EmptyStateSize.Screen,
        titleStyle = MaterialTheme.typography.titleMedium,
        action = if (filtered) {
            { SecondaryButton(label = stringResource(R.string.trips_reset_filters), onClick = onResetFilters) }
        } else {
            null
        },
    )
}

/**
 * 목록 조회 실패(공통 오류 화면, 가이드라인 9절, #446).
 *
 * 두 원인 모두 같은 요청을 다시 보내면 되므로 `다시 시도`가 주버튼이다. 최상위 탭이라 돌아갈 곳이 없어 보조 버튼은 두지 않는다.
 * 발생 시각·마지막 동작은 이 화면이 모르는 값이라 원인 카드를 그리지 않는다(12절). 검색어·필터 헤더는 그대로 남는다.
 */
@Composable
private fun ErrorState(error: TripListError, onRetry: () -> Unit) {
    CommonErrorState(
        description = stringResource(
            when (error) {
                TripListError.NETWORK -> R.string.trips_error_network
                TripListError.UNEXPECTED -> R.string.trips_error_unexpected
            },
        ),
        primaryLabel = stringResource(R.string.trips_retry),
        onPrimary = onRetry,
        modifier = Modifier.fillMaxSize(),
    )
}

/** 여행 목록. 끝에 닿으면 다음 페이지를 요청한다. */
@Composable
private fun TripList(
    trips: List<TripDto>,
    covers: Map<String, ByteArray>,
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
    //
    // 기준을 여행 수가 아니라 `totalItemsCount`로 잡는다. 목록에 그룹 헤더와 추가 로드
    // 표시가 섞여 있어 LazyColumn의 index와 여행 index가 더 이상 같지 않다.
    LaunchedEffect(listState, hasNext) {
        snapshotFlow {
            val info = listState.layoutInfo
            info.visibleItemsInfo.lastOrNull()?.index to info.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (lastVisible, total) ->
                if (hasNext && lastVisible != null && lastVisible >= total - 2) {
                    onLoadMore()
                }
            }
    }

    // 그룹 나누기는 상태 계층이 소유한다. 화면은 나뉜 결과를 그리기만 한다.
    val sections = remember(trips) { groupTrips(trips) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(spacing.space3),
        // 아래 여백은 떠 있는 FAB가 마지막 카드를 가리지 않게 한다(Figma `h-28`).
        contentPadding = PaddingValues(start = spacing.space4, end = spacing.space4, top = spacing.space4, bottom = FAB_CLEARANCE),
    ) {
        sections.forEach { section ->
            // 빈 그룹은 groupTrips가 이미 걸렀다. 헤더만 남는 구획은 생기지 않는다.
            item(key = section.group, contentType = GROUP_HEADER_TYPE) {
                TripGroupHeader(group = section.group)
            }
            items(
                items = section.trips,
                key = { it.tripId },
                contentType = { it.status },
            ) { trip ->
                TripItem(
                    trip = trip,
                    today = today,
                    onClick = { onTripClick(trip.tripId) },
                    image = covers[coverKey(trip)],
                )
            }
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
 * 상태별 카드(Figma 3종).
 *
 * 대표 이미지는 ViewModel이 받아 둔 원본을 [image]로 받는다(#617). 아직 못 받았거나 없으면 대체 배경이 보인다.
 * `TripDto`에 지역명·장소 수·건너뜀 수가 없다. 지어내지 않고 지역은 `정보 없음`, 장소·건너뜀 수는 자리를 비운다
 * (12절, Backend 계약 추가 요청 필요). 기간·일수·D-day는 받은 날짜로 계산한다.
 */
@Composable
private fun TripItem(trip: TripDto, today: LocalDate, onClick: () -> Unit, image: ByteArray? = null) {
    val period = periodLabel(trip.startDate, trip.endDate, today)
    val length = lengthLabel(trip.dayCount)
    val badge = stringResource(trip.status.labelRes)

    when (trip.status) {
        TripStatus.IN_PROGRESS -> ActiveTripCard(
            title = trip.name,
            region = stringResource(R.string.trip_detail_value_unknown),
            period = period,
            length = length,
            badgeLabel = badge,
            onClick = onClick,
            image = image,
        )

        TripStatus.UPCOMING -> UpcomingTripCard(
            title = trip.name,
            badgeLabel = ddayLabel(trip.startDate, today),
            period = period,
            meta = length,
            onClick = onClick,
            image = image,
        )

        TripStatus.COMPLETED -> CompletedTripCard(
            title = trip.name,
            period = period,
            badgeLabel = badge,
            onClick = onClick,
            image = image,
        )
    }
}

/** `5월 21일 – 5월 25일`. 올해가 아니면 Figma 지난 여행처럼 연도를 붙인다(`2024. 11. 3 – 11. 10`). 형식이 어긋나면 원문을 쓴다. */
@Composable
private fun periodLabel(startDate: String, endDate: String, today: LocalDate): String {
    val start = runCatching { LocalDate.parse(startDate) }.getOrNull()
    val end = runCatching { LocalDate.parse(endDate) }.getOrNull()
    if (start == null || end == null) return stringResource(R.string.trips_period, startDate, endDate)
    return if (start.year == today.year) {
        stringResource(R.string.trips_period_range, start.format(THIS_YEAR_DATE), end.format(THIS_YEAR_DATE))
    } else {
        stringResource(R.string.trips_period_range, start.format(OTHER_YEAR_DATE), end.format(SHORT_DATE))
    }
}

/** `4박 5일`. 하루짜리 여행은 `당일`이다. */
@Composable
private fun lengthLabel(dayCount: Int): String =
    if (dayCount <= 1) stringResource(R.string.trips_length_single) else stringResource(R.string.trips_length, dayCount - 1, dayCount)

/** 시작일까지 남은 날(`D-7`). 오늘 시작이면 `D-day`. */
@Composable
private fun ddayLabel(startDate: String, today: LocalDate): String {
    val start = runCatching { LocalDate.parse(startDate) }.getOrNull() ?: return stringResource(R.string.trips_status_upcoming)
    val days = ChronoUnit.DAYS.between(today, start)
    return if (days <= 0) stringResource(R.string.trips_dday_today) else stringResource(R.string.trips_dday, days.toInt())
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

private val THIS_YEAR_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일")
private val OTHER_YEAR_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy. M. d")
private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("M. d")

/** 가이드라인 9절: 1초를 넘길 때만 대기 표시를 띄운다. */
private const val LOADING_INDICATOR_DELAY_MILLIS = 1_000L

/** 가이드라인 5절·10절: 주요 CTA 52~56dp, 터치 영역 48dp 이상. */
private val PRIMARY_BUTTON_HEIGHT = Dp(56f)
private val MIN_TOUCH = Dp(48f)

/** Figma `MyTripsScreen` 알림 버튼 상자(40dp)와 벨(20dp). */
private val BELL_BOX = Dp(40f)
private val BELL_ICON = 20.dp

/** 가이드라인 7절 검색 입력창: 44dp, 좌우 14, 돋보기 16. */
private val SEARCH_HEIGHT = 44.dp
private val SEARCH_HORIZONTAL_PADDING = 14.dp
private val SEARCH_ICON = 16.dp

/** Figma FAB(`h-[52px] px-7`, `+` 18)와 목록 아래 여백(`h-28`), 빈 상태 버튼 52dp. */
private val FAB_HEIGHT = 52.dp
private val FAB_HORIZONTAL_PADDING = 28.dp
private val FAB_ICON = 18.dp
private val FAB_CLEARANCE = 112.dp

/** 헤더 알림 벨 test tag. */
const val TAG_NOTIFICATIONS = "trips_notifications"

/** LazyColumn이 같은 종류의 항목끼리 layout을 재사용하도록 구분한다. */
private const val GROUP_HEADER_TYPE = "group-header"
