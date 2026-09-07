package com.gilpick.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.Dp
import com.gilpick.R
import com.gilpick.itinerary.DayItineraryDto
import com.gilpick.itinerary.ItineraryError
import com.gilpick.itinerary.ItineraryItemDto
import com.gilpick.itinerary.TransportMode
import com.gilpick.route.RouteDto
import com.gilpick.route.RouteSegmentDto
import com.gilpick.route.distanceLabel
import com.gilpick.route.durationLabel
import com.gilpick.route.messageRes
import com.gilpick.ui.component.BadgeTone
import com.gilpick.ui.component.StatusBadge
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.coroutines.delay

/**
 * 여행 상세 화면.
 *
 * F002에서 만든 Summary(이름·기간·상태)와 AppBar에 F004가 날짜별 일정 목록,
 * `일정 편집` 진입, 날짜 헤더 `장소 추가`를 더한다(`spec.md` UI-006). 화면 구조는 F002
 * 것을 유지한다.
 *
 * Figma `TripDetailScreen`의 요약 통계 가운데 `총 이동`과 `오늘 여행 시작`은 F005 경로
 * 계산·F006 여행 진행 데이터가 있어야 값이 생긴다. spec UI-006대로 F004에서는 자리만
 * 두고 각각 `정보 없음`과 비활성으로 표시한다. Figma hero 이미지·지역명은 F002
 * 계약(`TripDto`)에 대응하는 값이 없어 F002가 만든 AppBar 구조를 그대로 둔다.
 *
 * AppBar `ellipsis`의 더보기 메뉴는 pen `Menu`대로 `수정`(#106)과 `삭제`(#107)를
 * 갖는다. `triangle-alert`는 F005 변수 감지에 해당하는데 지금 눌러서 할 수 있는 일이
 * 없어 만들지 않는다. AGENTS.md 6절이 pen에도 명세에도 없는 기능을 임의로 추가하지
 * 않도록 정한다.
 *
 * 표시 단계는 `docs/design/ui-guidelines.md` 9절을 따른다. `empty`는 상세 조회에
 * 성립하지 않으므로 만들지 않는다(근거는 [TripDetailPhase]).
 *
 * @param state 현재 상세 상태.
 * @param onBack 이전 화면으로 돌아간다.
 * @param onRetry 실패한 조회를 다시 시도한다.
 * @param onEdit 수정 화면으로 이동한다. 여행을 받아 둔 상태에서만 쓸 수 있다.
 * @param onDelete 확인 다이얼로그에서 삭제를 확정했을 때 실제 삭제를 요청한다.
 * @param onDeleteErrorShown 삭제 실패 안내를 사용자가 닫았음을 알린다.
 * @param onRetryItinerary 실패한 일정 조회만 다시 시도한다. 여행 정보는 그대로 둔다.
 * @param onEditItinerary 일정 편집 화면으로 이동한다. 여행의 첫 날짜로 들어간다.
 * @param onAddPlace 그 날짜(`yyyy-MM-dd`)로 장소 검색에 들어간다.
 * @param onSelectPlace 장소 상세(F003)로 이동한다. 인자는 `placeId`다.
 * @param routes 날짜(`yyyy-MM-dd`)별 경로 영역 상태(F005). 없는 날짜는 경로 정보 없음으로 그린다.
 * @param onOpenRoute 그 날짜의 경로 화면(F005)으로 이동한다. 인자는 날짜와 일차다.
 * @param onRetryRoute 실패한 날짜의 경로 계산을 같은 입력으로 다시 시도한다(F005 FR-010). 인자는 날짜다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(
    state: TripDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDeleteErrorShown: () -> Unit,
    onRetryItinerary: () -> Unit,
    onEditItinerary: () -> Unit,
    onAddPlace: (date: String) -> Unit,
    onSelectPlace: (placeId: String) -> Unit,
    modifier: Modifier = Modifier,
    routes: Map<String, DayRoutePhase> = emptyMap(),
    onOpenRoute: (date: String, dayNumber: Int) -> Unit = { _, _ -> },
    onRetryRoute: (date: String) -> Unit = {},
) {
    val spacing = LocalGilpickSpacing.current
    var menuOpen by remember { mutableStateOf(false) }

    // 다이얼로그를 열었는지는 화면 안에서만 쓰이는 표시 상태다. ViewModel에 두면 화면
    // 밖에서 아무도 읽지 않는 값을 함께 들고 다니게 된다. 회전으로 사라지지 않도록
    // rememberSaveable만 쓴다.
    var confirmOpen by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.trip_detail_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.size(MIN_TOUCH)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        // 아이콘 전용 버튼이므로 설명이 필수다(가이드라인 10절).
                        contentDescription = stringResource(R.string.trip_detail_back),
                    )
                }
            },
            actions = {
                // 여행을 받아 둔 상태에서만 할 수 있는 일이다. 실패·대기 중에는 메뉴를
                // 열어도 누를 것이 없다.
                if (state.phase is TripDetailPhase.Content) {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(MIN_TOUCH),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.trip_detail_more),
                        )
                    }
                    TripDetailMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        onEdit = {
                            menuOpen = false
                            onEdit()
                        },
                        onDelete = {
                            menuOpen = false
                            confirmOpen = true
                        },
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )

        Box(modifier = Modifier.weight(1f)) {
            when (val phase = state.phase) {
                TripDetailPhase.Loading -> LoadingState()

                is TripDetailPhase.Content -> DetailContent(
                    trip = phase.trip,
                    itinerary = state.itinerary,
                    routes = routes,
                    onRetryItinerary = onRetryItinerary,
                    onEditItinerary = onEditItinerary,
                    onAddPlace = onAddPlace,
                    onSelectPlace = onSelectPlace,
                    onOpenRoute = onOpenRoute,
                    onRetryRoute = onRetryRoute,
                )

                is TripDetailPhase.Failed -> ErrorState(
                    error = phase.error,
                    onRetry = onRetry,
                    onBack = onBack,
                    modifier = Modifier.padding(horizontal = spacing.space5),
                )
            }
        }
    }

    val phase = state.phase
    if (confirmOpen && phase is TripDetailPhase.Content) {
        DeleteConfirmDialog(
            tripName = phase.trip.name,
            deletion = state.deletion,
            onConfirm = onDelete,
            onDismiss = {
                confirmOpen = false
                onDeleteErrorShown()
            },
        )
    }
}

/**
 * 삭제 확인 다이얼로그.
 *
 * pen `Dialog`를 따른다. `AlertDialog`가 아니라 [BasicAlertDialog]를 쓰는 이유는
 * `AlertDialog`가 제목·본문·버튼의 배치와 간격을 스스로 정해서, pen이 요구하는 균등
 * 분할 버튼과 24dp 안쪽 여백을 그대로 만들 수 없기 때문이다. [BasicAlertDialog]는
 * 창 동작(뒤로 가기, scrim, `paneTitle` semantics)만 주고 내용은 호출자가 채운다.
 *
 * 파괴적 행동이라 `취소`를 왼쪽에 먼저 두고 `삭제`를 오른쪽에 둔다. 두 버튼의 너비를
 * 같게 하는 pen의 배치를 그대로 지킨다.
 *
 * @param tripName 본문에 인용할 여행명.
 * @param deletion 삭제 요청의 진행 단계. 진행 중에는 버튼을 잠그고 실패하면 안내를 붙인다.
 * @param onConfirm 삭제를 확정한다.
 * @param onDismiss 다이얼로그를 닫는다. 진행 중에는 호출되지 않는다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteConfirmDialog(
    tripName: String,
    deletion: TripDeletePhase,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val deleting = deletion is TripDeletePhase.Deleting

    BasicAlertDialog(
        // 요청을 보낸 사이에 닫히면 결과를 전달할 화면이 사라진다.
        onDismissRequest = { if (!deleting) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !deleting,
            dismissOnClickOutside = !deleting,
            // pen이 정한 너비를 쓰려면 platform 기본 너비 제약을 꺼야 한다.
            usePlatformDefaultWidth = false,
        ),
        // pen은 326dp 고정이지만 그보다 좁은 화면에서는 잘린다. 최대값으로 두어 좁은
        // 화면에서만 줄어들게 한다(가이드라인 10절: 360dp에서 잘림 없음).
        modifier = Modifier
            .padding(horizontal = spacing.space5)
            .widthIn(max = DIALOG_WIDTH),
    ) {
        Surface(
            shape = RoundedCornerShape(radius.xl),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(spacing.space6),
                verticalArrangement = Arrangement.spacedBy(spacing.space2),
            ) {
                Text(
                    text = stringResource(R.string.trip_delete_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.trip_delete_body, tripName),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 실패해도 다이얼로그를 닫지 않는다. 여행은 그대로 남아 있으므로 같은
                // 자리에서 다시 시도하거나 취소할 수 있어야 한다.
                if (deletion is TripDeletePhase.Failed) {
                    Text(
                        text = stringResource(deletion.error.messageRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.space4),
                    horizontalArrangement = Arrangement.spacedBy(spacing.space2),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !deleting,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = DIALOG_BUTTON_HEIGHT),
                        shape = RoundedCornerShape(radius.md),
                    ) {
                        Text(
                            text = stringResource(R.string.trip_delete_cancel),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = !deleting,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = DIALOG_BUTTON_HEIGHT),
                        shape = RoundedCornerShape(radius.md),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            // pen은 이 라벨을 $on-primary로 적었지만 배경이 $error이므로
                            // 대응하는 역할은 onError다. 두 토큰의 값은 같다.
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text(
                            stringResource(
                                if (deleting) R.string.trip_delete_progress
                                else R.string.trip_delete_confirm,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** 삭제 실패 안내 문구. */
private val TripDeleteError.messageRes: Int
    get() = when (this) {
        TripDeleteError.NETWORK -> R.string.trip_delete_error_network
        TripDeleteError.NOT_FOUND -> R.string.trip_delete_error_not_found
        TripDeleteError.FORBIDDEN -> R.string.trip_delete_error_forbidden
        TripDeleteError.UNEXPECTED -> R.string.trip_delete_error_unexpected
    }

/**
 * AppBar 더보기 메뉴.
 *
 * pen의 `Menu` 요소다. `수정`과 `삭제` 사이의 구분선, 삭제 항목의 `error` 색까지
 * pen을 따른다. 삭제는 되돌릴 수 없으므로 색으로도 구분해 두지만, 색만으로 뜻을
 * 전달하지 않도록 라벨이 함께 있다(가이드라인 10절).
 */
@Composable
private fun TripDetailMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        // Material 3 기본 메뉴 배경은 이 앱이 정의하지 않은 surface 계열 색이라 연보라로
        // 나온다. pen의 Menu는 $surface다(가이드라인 3절).
        modifier = Modifier.background(MaterialTheme.colorScheme.surface),
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.trip_detail_edit)) },
            onClick = onEdit,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Edit,
                    // 바로 옆 라벨이 뜻을 전달한다(가이드라인 10절).
                    contentDescription = null,
                )
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DropdownMenuItem(
            text = {
                Text(
                    text = stringResource(R.string.trip_detail_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            onClick = onDelete,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
        )
    }
}

/**
 * 여행 요약.
 *
 * 이름·기간·상태는 US3 Acceptance Scenario 1이 요구하는 세 가지다. 상태는 색만으로
 * 구분하지 않도록 문구를 가진 [StatusBadge]로 표시한다(가이드라인 10절).
 */
@Composable
private fun Summary(trip: TripDto, modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current

    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(vertical = spacing.space4),
            verticalArrangement = Arrangement.spacedBy(spacing.space1),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = trip.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                StatusBadge(
                    label = stringResource(trip.status.detailLabelRes),
                    tone = trip.status.detailTone,
                )
            }
            Text(
                text = stringResource(R.string.trips_period, trip.startDate, trip.endDate),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.trips_day_count, trip.dayCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 여행을 받은 뒤의 본문. 요약·통계·행동과 날짜별 일정이 하나로 스크롤된다.
 *
 * 하루 10곳 × 최대 7일이라 항목 수가 70개를 넘지 않는다. `LazyColumn` 대신
 * `verticalScroll`을 쓰는 이유이며 plan.md도 페이징·가상화가 불필요하다고 적었다.
 */
@Composable
private fun DetailContent(
    trip: TripDto,
    itinerary: ItineraryOverviewPhase,
    routes: Map<String, DayRoutePhase>,
    onRetryItinerary: () -> Unit,
    onEditItinerary: () -> Unit,
    onAddPlace: (date: String) -> Unit,
    onSelectPlace: (placeId: String) -> Unit,
    onOpenRoute: (date: String, dayNumber: Int) -> Unit,
    onRetryRoute: (date: String) -> Unit,
) {
    val spacing = LocalGilpickSpacing.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(horizontal = spacing.space5)) {
                Summary(trip = trip)
                TripStats(trip = trip, itinerary = itinerary, routes = routes)
                ItineraryActions(onEditItinerary = onEditItinerary)
            }
        }

        ItinerarySection(
            itinerary = itinerary,
            routes = routes,
            onRetry = onRetryItinerary,
            onAddPlace = onAddPlace,
            onSelectPlace = onSelectPlace,
            onOpenRoute = onOpenRoute,
            onRetryRoute = onRetryRoute,
        )
    }
}

/**
 * Figma의 요약 통계 세 칸.
 *
 * `총 방문지`는 일정 개요에서 센다. 일정을 아직 못 받았으면 0곳이라고 단정할 수 없으므로
 * `정보 없음`으로 둔다. `총 이동`은 장소가 있는 모든 날짜의 경로가 `READY`일 때만 합계를 적고,
 * 하나라도 실패·미계산이면 일부만 더한 값을 보이지 않고 `정보 없음`으로 둔다(F005 UI-002).
 */
@Composable
private fun TripStats(trip: TripDto, itinerary: ItineraryOverviewPhase, routes: Map<String, DayRoutePhase>) {
    val spacing = LocalGilpickSpacing.current
    val unknown = stringResource(R.string.trip_detail_value_unknown)
    val days = (itinerary as? ItineraryOverviewPhase.Content)?.days
    val placeCount = days?.sumOf { it.items.size }
    val readyRoutes = days
        ?.filter { it.items.isNotEmpty() }
        ?.map { routes[it.date] as? DayRoutePhase.Ready }
    val totalTravel = readyRoutes
        ?.takeIf { it.isNotEmpty() && it.all { route -> route != null } }
        ?.sumOf { it!!.route.totalDurationSeconds }
        ?.let { durationLabel(it) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.space4),
        horizontalArrangement = Arrangement.spacedBy(spacing.space4),
    ) {
        Stat(
            value = placeCount
                ?.let { stringResource(R.string.trip_detail_place_count, it) }
                ?: unknown,
            label = stringResource(R.string.trip_detail_stat_places),
            modifier = Modifier.weight(1f),
        )
        Stat(
            value = stringResource(R.string.trip_detail_day_value, trip.dayCount),
            label = stringResource(R.string.trip_detail_stat_period),
            modifier = Modifier.weight(1f),
        )
        Stat(
            value = totalTravel ?: unknown,
            label = stringResource(R.string.trip_detail_stat_travel),
            modifier = Modifier.weight(1f),
        )
    }
}

/** 통계 한 칸. 값은 핵심 정보라 `onSurface`, 라벨은 보조 정보라 `muted`다(가이드라인 10절). */
@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = LocalGilpickColors.current.muted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * `오늘 여행 시작`과 `일정 편집`.
 *
 * `오늘 여행 시작`은 F006 여행 진행 범위라 눌러도 갈 곳이 없다. spec UI-006대로 자리만
 * 두고 비활성으로 표시하되, 비활성 상태를 흐린 색으로만 알리지 않도록 이유를 문장으로
 * 함께 적는다(가이드라인 10절 "색 단독 의미 전달 금지").
 */
@Composable
private fun ItineraryActions(onEditItinerary: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current

    Column(
        modifier = Modifier.padding(bottom = spacing.space2),
        verticalArrangement = Arrangement.spacedBy(spacing.space1),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(
            onClick = {},
            enabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PRIMARY_BUTTON_HEIGHT),
            shape = RoundedCornerShape(radius.lg),
        ) {
            Text(stringResource(R.string.trip_detail_start_travel))
        }
        Text(
            text = stringResource(R.string.trip_detail_start_travel_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = LocalGilpickColors.current.muted,
            textAlign = TextAlign.Center,
        )
        TextButton(
            onClick = onEditItinerary,
            modifier = Modifier.heightIn(min = MIN_TOUCH),
        ) {
            Text(
                text = stringResource(R.string.trip_detail_edit_itinerary),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                // 바로 옆 라벨이 뜻을 전달한다(가이드라인 10절).
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 날짜별 일정 영역.
 *
 * 여행 정보와 다른 요청이라 이 영역만 따로 대기·실패한다(US3 Acceptance Scenario 4).
 */
@Composable
private fun ItinerarySection(
    itinerary: ItineraryOverviewPhase,
    routes: Map<String, DayRoutePhase>,
    onRetry: () -> Unit,
    onAddPlace: (date: String) -> Unit,
    onSelectPlace: (placeId: String) -> Unit,
    onOpenRoute: (date: String, dayNumber: Int) -> Unit,
    onRetryRoute: (date: String) -> Unit,
) {
    when (itinerary) {
        ItineraryOverviewPhase.Loading -> ItineraryLoading()

        is ItineraryOverviewPhase.Failed ->
            ItineraryErrorState(error = itinerary.error, onRetry = onRetry)

        is ItineraryOverviewPhase.Content -> Column {
            itinerary.days.forEach { day ->
                DayGroup(
                    day = day,
                    route = routes[day.date] ?: DayRoutePhase.NotCalculated,
                    onAddPlace = onAddPlace,
                    onSelectPlace = onSelectPlace,
                    onOpenRoute = { onOpenRoute(day.date, day.dayNumber) },
                    onRetryRoute = { onRetryRoute(day.date) },
                )
            }
        }
    }
}

/** 일정 조회 대기. 여행 정보와 같은 1초 규칙을 쓴다(가이드라인 9절). */
@Composable
private fun ItineraryLoading() {
    val spacing = LocalGilpickSpacing.current
    var visible by remember { mutableStateOf(false) }
    val label = stringResource(R.string.trip_detail_itinerary_loading)

    LaunchedEffect(Unit) {
        delay(LOADING_INDICATOR_DELAY_MILLIS)
        visible = true
    }

    if (visible) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.space8),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.clearAndSetSemantics { contentDescription = label },
            )
        }
    }
}

/**
 * 일정만 실패한 상태.
 *
 * 여행 이름·기간·상태는 위에 그대로 남아 있고 이 영역만 원인과 `다시 시도`를 보여 준다.
 * 다시 시도해도 결과가 같은 원인(권한 없음·없는 여행·만료된 session)에는 버튼을 두지
 * 않는다. 그 경우 이미 여행 정보 쪽에서 같은 실패가 드러난다.
 */
@Composable
private fun ItineraryErrorState(error: ItineraryError, onRetry: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val retryable = error == ItineraryError.Network || error == ItineraryError.Unexpected

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.space5, vertical = spacing.space6),
        verticalArrangement = Arrangement.spacedBy(spacing.space3),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(error.messageRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        if (retryable) {
            Button(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = PRIMARY_BUTTON_HEIGHT),
            ) {
                Text(stringResource(R.string.trips_retry))
            }
        }
    }
}

/**
 * 한 날짜 그룹. 헤더(일차 배지·날짜·장소 수·`추가`)와 장소 행으로 이뤄진다.
 *
 * 장소가 없는 날짜도 헤더를 그대로 두고 빈 행만 다르게 보여 준다. 그래야 어느 날짜가
 * 비었는지 구분되고(US3 Acceptance Scenario 2) 그 자리에서 바로 채울 수 있다.
 */
@Composable
private fun DayGroup(
    day: DayItineraryDto,
    route: DayRoutePhase,
    onAddPlace: (date: String) -> Unit,
    onSelectPlace: (placeId: String) -> Unit,
    onOpenRoute: () -> Unit,
    onRetryRoute: () -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val dateLabel = day.date.toDateLabel()
    val readyRoute = (route as? DayRoutePhase.Ready)?.route
    // 구간 이동시간·거리는 출발 항목 ID로 찾는다. 경로 구간 순서는 일정 순서와 같다(FR-005).
    val segmentsByFrom = readyRoute?.segments?.associateBy { it.fromItemId }.orEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = spacing.space5)
            .heightIn(min = MIN_TOUCH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space2),
            modifier = Modifier.weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .size(DAY_BADGE)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(radius.sm)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = day.dayNumber.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Text(
                text = dateLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space2),
        ) {
            Text(
                text = stringResource(R.string.trip_detail_place_count, day.items.size),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
            AddPlaceButton(
                dateLabel = dateLabel,
                onClick = { onAddPlace(day.date) },
            )
        }
    }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (day.items.isEmpty()) {
                Text(
                    text = stringResource(R.string.trip_detail_day_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                    modifier = Modifier.padding(
                        horizontal = spacing.space5,
                        vertical = spacing.space4,
                    ),
                )
            } else {
                day.items.forEachIndexed { index, item ->
                    PlaceRow(item = item, onClick = { onSelectPlace(item.place.placeId) })
                    if (index < day.items.lastIndex) {
                        TransportRow(mode = item.transportModeToNext, segment = segmentsByFrom[item.itemId])
                    }
                }
                when (route) {
                    is DayRoutePhase.Ready -> RouteSummaryRow(route = route.route, dateLabel = dateLabel, onOpenRoute = onOpenRoute)
                    is DayRoutePhase.Failed -> RouteFailedRow(route = route, dateLabel = dateLabel, onRetry = onRetryRoute)
                    DayRoutePhase.Calculating -> RouteCalculatingRow()
                    DayRoutePhase.NotCalculated -> Unit
                }
            }
        }
    }
}

/**
 * 날짜 경로 요약(F005 UI-002): 전체 이동시간·거리와 `경로 보기`.
 *
 * 장소가 한 곳이면 구간이 없어 합계가 0이지만, 지도에서 위치를 볼 수 있으므로 `경로 보기`는 그대로 둔다.
 * 정상 경로에는 재계산 행동을 두지 않는다(FR-019).
 */
@Composable
private fun RouteSummaryRow(route: RouteDto, dateLabel: String, onOpenRoute: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val openDescription = stringResource(R.string.trip_detail_route_open_description, dateLabel)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.space5, end = spacing.space3)
            .heightIn(min = MIN_TOUCH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(
                R.string.trip_detail_route_total,
                durationLabel(route.totalDurationSeconds),
                distanceLabel(route.totalDistanceMeters),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onOpenRoute,
            modifier = Modifier
                .heightIn(min = MIN_TOUCH)
                .semantics { contentDescription = openDescription },
        ) {
            Text(stringResource(R.string.trip_detail_route_open))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(ADD_ICON + spacing.space1),
            )
        }
    }
}

/**
 * 자동 경로 계산 실패 안내와 `다시 시도`(F005 UI-002a, FR-010). 일정 내용과 독립된 행이라 장소 행은 그대로 남는다.
 *
 * 원인을 아직 모르면(개요 응답에는 원인이 없다) 일반 문구를 쓴다. 마지막 `다시 시도` 요청 자체가
 * 실패했으면(통신 단절 등) 그 이유를 한 줄 더 적는다.
 */
@Composable
private fun RouteFailedRow(route: DayRoutePhase.Failed, dateLabel: String, onRetry: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val retryDescription = stringResource(R.string.trip_detail_route_retry_description, dateLabel)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.space5)
            .padding(bottom = spacing.space3)
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(radius.md))
            .padding(start = spacing.space3, end = spacing.space1, top = spacing.space1, bottom = spacing.space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lucide_circle_x),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(ADD_ICON + spacing.space1),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = route.failure?.let { stringResource(it.messageRes) }
                    ?: stringResource(R.string.trip_detail_route_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            route.requestError?.let {
                Text(
                    text = stringResource(it.messageRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        TextButton(
            onClick = onRetry,
            modifier = Modifier
                .heightIn(min = MIN_TOUCH)
                .semantics { contentDescription = retryDescription },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
        ) {
            Text(stringResource(R.string.trip_detail_route_retry))
        }
    }
}

/** 재계산 응답을 기다리는 동안의 표시(UI-004). 장소 행은 그대로 두고 경로 영역만 바뀐다. */
@Composable
private fun RouteCalculatingRow() {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val label = stringResource(R.string.trip_detail_route_retrying)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.space5)
            .heightIn(min = MIN_TOUCH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(ADD_ICON + spacing.space1)
                .semantics { contentDescription = label },
            strokeWidth = 2.dp,
        )
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = colors.muted)
    }
}

/**
 * 날짜 헤더의 `추가` pill.
 *
 * Figma는 24dp 남짓한 작은 pill이지만 터치 영역은 48dp 이상이어야 하므로(가이드라인 10절)
 * pill을 48dp 높이의 누를 수 있는 영역 안에 담는다. 보이는 크기는 Figma 그대로다.
 */
@Composable
private fun AddPlaceButton(dateLabel: String, onClick: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current

    Box(
        modifier = Modifier
            .heightIn(min = MIN_TOUCH)
            .widthIn(min = MIN_TOUCH)
            .clickable(
                onClick = onClick,
                onClickLabel = stringResource(R.string.trip_detail_day_add_description, dateLabel),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.primaryContainer,
                    RoundedCornerShape(radius.sm),
                )
                .padding(horizontal = spacing.space2, vertical = spacing.space1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space1),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                // 바로 옆 라벨이 뜻을 전달한다(가이드라인 10절).
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(ADD_ICON),
            )
            Text(
                text = stringResource(R.string.trip_detail_day_add),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * 장소 한 줄. 순서 번호·장소명·체류 시간을 보여 주고 누르면 F003 장소 상세로 간다.
 *
 * Figma는 장소명 아래에 `10:00 · 1시간 30분`처럼 도착 시각을 함께 적지만, 도착 시각은
 * F005 경로 계산 결과다. F004 응답에는 없으므로 체류 시간만 적는다(spec 범위 밖 항목).
 */
@Composable
private fun PlaceRow(item: ItineraryItemDto, onClick: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = MIN_TOUCH)
            .padding(horizontal = spacing.space5, vertical = spacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space3),
    ) {
        Box(
            modifier = Modifier
                .size(SEQUENCE_CIRCLE)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = item.sequence.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.place.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stayLabel(item.plannedStayMinutes),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            // 행 전체가 누를 수 있는 하나의 대상이라 아이콘은 장식이다(가이드라인 10절).
            contentDescription = null,
            tint = colors.faint,
        )
    }
}

/**
 * 구간 이동 수단. 경로가 `READY`면 그 구간의 이동시간·거리를 함께 적는다(F005 UI-002).
 *
 * 수단이 없는 항목은 줄만 그어 두 장소가 이어져 있음을 유지한다.
 */
@Composable
private fun TransportRow(mode: TransportMode?, segment: RouteSegmentDto? = null) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current

    Row(
        modifier = Modifier.padding(start = TRANSPORT_INDENT, bottom = spacing.space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        Box(
            modifier = Modifier
                .width(TRANSPORT_LINE)
                .height(TRANSPORT_LINE_HEIGHT)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        if (mode != null) {
            Text(
                text = if (segment != null) {
                    stringResource(
                        R.string.trip_detail_route_segment,
                        stringResource(mode.labelRes),
                        durationLabel(segment.durationSeconds),
                        distanceLabel(segment.distanceMeters),
                    )
                } else {
                    stringResource(mode.labelRes)
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
    }
}

/** 이동 수단 문구. */
private val TransportMode.labelRes: Int
    get() = when (this) {
        TransportMode.WALK -> R.string.trip_detail_transport_walk
        TransportMode.TRANSIT -> R.string.trip_detail_transport_transit
        TransportMode.CAR -> R.string.trip_detail_transport_car
    }

/** 일정 조회 실패 원인별 안내 문구. 원인을 뭉뚱그리지 않는다(가이드라인 9절). */
private val ItineraryError.messageRes: Int
    get() = when (this) {
        ItineraryError.Network -> R.string.trip_detail_itinerary_error_network
        ItineraryError.Forbidden -> R.string.trip_detail_itinerary_error_forbidden
        ItineraryError.NotFound -> R.string.trip_detail_itinerary_error_not_found
        ItineraryError.SessionExpired -> R.string.trip_detail_itinerary_error_session

        // 아래 셋은 저장(ITIN-002)에서만 나오는 실패다. 개요 조회에서 받으면 계약과
        // 다른 응답이므로 알 수 없는 실패와 같이 다룬다.
        ItineraryError.VersionConflict,
        is ItineraryError.InvalidItinerary,
        is ItineraryError.ItemLocked,
        ItineraryError.Unexpected,
        -> R.string.trip_detail_itinerary_error_unexpected
    }

/**
 * 체류 시간 문구. 30~360분이라 `90분`보다 `1시간 30분`이 읽기 쉽다(Figma 표기).
 *
 * 편집 화면(T017)이 만들 `ItineraryLabels.kt`와 같은 규칙이지만, 그 파일은 다른 Issue의
 * 소유라 여기서 만들지 않는다. 두 화면이 모두 자리 잡은 뒤 한곳으로 모은다.
 */
@Composable
private fun stayLabel(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> stringResource(R.string.trip_detail_stay_minutes, rest)
        rest == 0 -> stringResource(R.string.trip_detail_stay_hours, hours)
        else -> stringResource(R.string.trip_detail_stay_hours_minutes, hours, rest)
    }
}

/**
 * `yyyy-MM-dd`를 `8월 12일`로 바꾼다.
 *
 * 서버가 계약과 다른 형식을 주더라도 화면이 죽지 않도록 원문을 그대로 보여 준다.
 */
@Composable
private fun String.toDateLabel(): String {
    val date = try {
        LocalDate.parse(this)
    } catch (e: DateTimeParseException) {
        return this
    }
    return stringResource(R.string.trip_detail_date, date.monthValue, date.dayOfMonth)
}

/** 조회 대기 표시. 1초를 넘길 때만 표시한다(가이드라인 9절). */
@Composable
private fun LoadingState() {
    var visible by remember { mutableStateOf(false) }
    val label = stringResource(R.string.trip_detail_loading)

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
 * 실패 상태.
 *
 * 원인과 다음 행동을 함께 준다(가이드라인 9절). 다만 다음 행동이 원인마다 다르다.
 * 통신 실패와 알 수 없는 실패는 같은 요청을 다시 보내면 되지만, 없는 여행(`404`)과
 * 권한 없는 여행(`403`)은 몇 번을 다시 보내도 결과가 같다. 그 경우 재시도 대신
 * 목록으로 돌아가는 길을 준다.
 */
@Composable
private fun ErrorState(
    error: TripDetailError,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalGilpickSpacing.current
    val retryable = error == TripDetailError.NETWORK || error == TripDetailError.UNEXPECTED

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(spacing.space3, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(error.messageRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = if (retryable) onRetry else onBack,
            modifier = Modifier.heightIn(min = PRIMARY_BUTTON_HEIGHT),
        ) {
            Text(
                stringResource(
                    if (retryable) R.string.trips_retry else R.string.trip_detail_back_to_list,
                ),
            )
        }
    }
}

/** 실패 원인별 안내 문구. 원인을 뭉뚱그리지 않는다(가이드라인 9절). */
private val TripDetailError.messageRes: Int
    get() = when (this) {
        TripDetailError.NETWORK -> R.string.trip_detail_error_network
        TripDetailError.NOT_FOUND -> R.string.trip_detail_error_not_found
        TripDetailError.FORBIDDEN -> R.string.trip_detail_error_forbidden
        TripDetailError.UNEXPECTED -> R.string.trip_detail_error_unexpected
    }

/** 상태 뱃지 문구. 색 없이도 뜻이 통해야 한다. */
private val TripStatus.detailLabelRes: Int
    get() = when (this) {
        TripStatus.UPCOMING -> R.string.trips_status_upcoming
        TripStatus.IN_PROGRESS -> R.string.trips_status_in_progress
        TripStatus.COMPLETED -> R.string.trips_status_completed
    }

/** 목록 카드와 같은 규칙으로 진행 중인 여행만 강조한다. */
private val TripStatus.detailTone: BadgeTone
    get() = if (this == TripStatus.IN_PROGRESS) BadgeTone.ACCENT else BadgeTone.NEUTRAL

/** 가이드라인 9절: 1초를 넘길 때만 대기 표시를 띄운다. */
private const val LOADING_INDICATOR_DELAY_MILLIS = 1_000L

/** 가이드라인 5절·10절: 주요 CTA 52~56dp, 터치 영역 48dp 이상. */
private val PRIMARY_BUTTON_HEIGHT = Dp(56f)
private val MIN_TOUCH = Dp(48f)

/** pen `Dialog`의 너비. 좁은 화면에서는 이보다 줄어든다. */
private val DIALOG_WIDTH = 326.dp

/** pen `Dialog`의 버튼 높이. 가이드라인 5절의 주요 CTA 높이(52~56dp) 안이다. */
private val DIALOG_BUTTON_HEIGHT = 52.dp

/** Figma 날짜 헤더의 일차 배지와 `추가` 아이콘, 장소 순서 번호 원의 크기. */
private val DAY_BADGE = 24.dp
private val ADD_ICON = 12.dp
private val SEQUENCE_CIRCLE = 24.dp

/** 이동 수단 줄의 들여쓰기와 세로선. Figma의 `ml-11`·1px 선에 해당한다. */
private val TRANSPORT_INDENT = 44.dp
private val TRANSPORT_LINE = 1.dp
private val TRANSPORT_LINE_HEIGHT = 16.dp
