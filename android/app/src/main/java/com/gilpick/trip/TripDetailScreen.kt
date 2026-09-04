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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.Dp
import com.gilpick.R
import com.gilpick.ui.component.BadgeTone
import com.gilpick.ui.component.StatusBadge
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing
import kotlinx.coroutines.delay

/**
 * 여행 상세 화면.
 *
 * pen `08. 여행 상세 화면` 가운데 **Summary(이름·기간·상태)와 AppBar**만 구현한다.
 * 같은 화면의 `Day`·`Step` 일정 목록과 `ActionBar`(`오늘 여행 시작`,
 * `여행 진행 화면 보기`)는 F004 일정·F005 진행 데이터가 있어야 그릴 수 있고 F002
 * 계약(`TripDto`)에 해당 값이 없다. 그 데이터가 생길 때까지 부분 화면이며 나머지는
 * 후속 feature에서 채운다.
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
    modifier: Modifier = Modifier,
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

                is TripDetailPhase.Content -> Summary(
                    trip = phase.trip,
                    modifier = Modifier.padding(horizontal = spacing.space5),
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
