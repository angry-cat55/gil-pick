package com.gilpick.itinerary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gilpick.ui.component.TAG_HEADER_BACK
import com.gilpick.R
import com.gilpick.ui.component.ErrorState as CommonErrorState
import com.gilpick.place.LoadingState
import com.gilpick.place.StateMessage
import com.gilpick.place.StepButton
import com.gilpick.place.TransportOption
import kotlin.math.abs
import com.gilpick.ui.component.GradientButtonDefaults
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.gilpick.ui.theme.displayFont
import java.time.LocalDate

/**
 * 일정 편집 화면. 모양은 Figma `ScheduleEditScreen`을 따른다(UI-001·UI-011).
 *
 * US1(장소 추가·저장·취소 확인)과 US2(체류 시간 대화상자, 이동 수단 시트, 삭제, 순서 이동
 * 버튼·손잡이 끌기, 처리된 장소 표시)를 담는다. 경로 상태가 `NOT_CALCULATED`이므로 Figma의 도착 시각·구간 소요 시간과 `변경하면 남은
 * 일정의 도착 시각이 다시 계산됩니다` 안내는 표시하지 않는다(FR-018). Figma 헤더 오른쪽의
 * 되돌리기 모양 버튼은 동작이 정의되지 않아 두지 않는다.
 *
 * @param state 현재 편집 상태.
 * @param onClose 헤더 뒤로 가기와 시스템 뒤로 가기. 변경 여부에 따른 확인은 ViewModel이 정한다.
 * @param onSelectDate 날짜 탭.
 * @param onAddPlace `장소 추가`. F003 장소 검색으로 간다.
 * @param onSave 하단 `저장`.
 * @param onRetry 조회 실패 뒤 `다시 시도`.
 * @param onReauthenticate 로그인 상태가 만료됐다. F001 재인증 흐름으로 넘어간다.
 * @param onDismissDialog 취소 확인의 `계속 편집`.
 * @param onConfirmDiscard 취소 확인의 `취소하고 나가기`.
 * @param onNoticeShown 저장 완료·추가 거부 안내를 사용자가 봤다.
 * @param onEditStay 행의 체류 시간을 눌렀다(UI-003).
 * @param onApplyStay 체류 시간 대화상자의 `적용`.
 * @param onChangeTransport 행의 `변경`(UI-004).
 * @param onApplyTransport 이동 수단 시트의 `적용`.
 * @param onRemove 행의 삭제.
 * @param onMove 위·아래 버튼 또는 손잡이 끌기로 항목을 `from`에서 `to`로 옮긴다(UI-008).
 */
@Composable
fun ItineraryEditScreen(
    state: ItineraryEditUiState,
    onClose: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onAddPlace: () -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onReauthenticate: () -> Unit,
    onDismissDialog: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onNoticeShown: () -> Unit,
    onEditStay: (Int) -> Unit,
    onApplyStay: (Int) -> Unit,
    onChangeTransport: (Int) -> Unit,
    onApplyTransport: (TransportMode) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalGilpickSpacing.current
    val snackbarHost = remember { SnackbarHostState() }
    val noticeText = state.notice?.let {
        stringResource(
            when (it) {
                EditNotice.SAVED -> R.string.itinerary_edit_notice_saved
                EditNotice.NO_COORDINATES -> R.string.itinerary_edit_notice_no_coordinates
                EditNotice.LIMIT_REACHED -> R.string.itinerary_edit_notice_limit
            },
        )
    }

    BackHandler(onBack = onClose)

    LaunchedEffect(noticeText) {
        if (noticeText != null) {
            snackbarHost.showSnackbar(noticeText)
            onNoticeShown()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header(onClose = onClose)
            DayTabs(days = state.days, selectedDate = state.selectedDate, onSelectDate = onSelectDate)
            Box(modifier = Modifier.weight(1f)) {
                when (val phase = state.phase) {
                    ItineraryEditPhase.Loading -> LoadingState(label = stringResource(R.string.itinerary_edit_loading))
                    is ItineraryEditPhase.Failed -> FailedState(
                        error = phase.error,
                        onRetry = onRetry,
                        onReauthenticate = onReauthenticate,
                    )

                    ItineraryEditPhase.Content -> Content(
                        state = state,
                        onAddPlace = onAddPlace,
                        onEditStay = onEditStay,
                        onChangeTransport = onChangeTransport,
                        onRemove = onRemove,
                        onMove = onMove,
                    )
                }
                // 목록 영역 바닥에 띄워 하단 `저장` 버튼을 가리지 않는다(#506 저장 완료 안내).
                SnackbarHost(
                    hostState = snackbarHost,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = spacing.space3),
                ) { data ->
                    Snackbar(snackbarData = data, containerColor = LocalGilpickColors.current.toast, contentColor = Color.White)
                }
            }
            SaveBar(
                saving = state.saving,
                enabled = state.phase is ItineraryEditPhase.Content && state.selectedDate != null,
                saveError = state.saveError,
                onSave = onSave,
            )
        }
    }

    when (val dialog = state.dialog) {
        is EditDialog.Discard -> DiscardConfirmDialog(onKeep = onDismissDialog, onDiscard = onConfirmDiscard)
        is EditDialog.StayTime -> state.draft.getOrNull(dialog.index)?.let { item ->
            StayTimeDialog(placeName = item.place.name, initialMinutes = item.stayMinutes, onCancel = onDismissDialog, onApply = onApplyStay)
        }

        is EditDialog.Transport -> state.draft.getOrNull(dialog.index)?.let { item ->
            TransportSheet(
                nextPlaceName = state.draft.getOrNull(dialog.index + 1)?.place?.name ?: item.place.name,
                current = item.transportToNext,
                onCancel = onDismissDialog,
                onApply = onApplyTransport,
            )
        }

        null -> Unit
    }
}

/** Figma 헤더: 36dp `background` 사각 뒤로 가기 버튼(#510에서 ✕→←)과 `일정 편집` 제목. 터치 영역은 48dp로 넓힌다. */
@Composable
private fun Header(onClose: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val back = stringResource(R.string.itinerary_edit_back)
    val title = stringResource(R.string.itinerary_edit_title)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = spacing.space5 - (MIN_TOUCH - HEADER_BUTTON) / 2, end = spacing.space5, top = spacing.space1, bottom = spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space3 - (MIN_TOUCH - HEADER_BUTTON) / 2),
    ) {
        Box(
            modifier = Modifier
                .size(MIN_TOUCH)
                .clip(RoundedCornerShape(LocalGilpickRadius.current.md))
                .clickable(onClick = onClose, role = Role.Button)
                .semantics { contentDescription = back }
                .testTag(TAG_HEADER_BACK),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(HEADER_BUTTON)
                    .clip(RoundedCornerShape(LocalGilpickRadius.current.md))
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_arrow_left),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = title.displayFont(),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Figma 날짜 탭: 날짜 숫자와 요일, 선택 탭은 `onBackground` 배경에 흰 글자. */
@Composable
private fun DayTabs(
    days: List<DayTab>,
    selectedDate: LocalDate?,
    onSelectDate: (LocalDate) -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val shape = RoundedCornerShape(LocalGilpickRadius.current.lg)

    // 7일 여행을 360dp·글자 배율 2.0에서 열면 탭이 화면을 넘으므로 가로로 밀어 볼 수 있게 한다(UI-010).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = spacing.space5)
            .padding(bottom = spacing.space4),
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        days.forEach { day ->
            val selected = day.date == selectedDate
            val label = stringResource(R.string.itinerary_edit_day_tab, day.date.dayOfMonth, day.date.shortDayOfWeek)
            val number = day.date.dayOfMonth.toString()
            Column(
                modifier = Modifier
                    .heightIn(min = MIN_TOUCH)
                    .clip(shape)
                    .background(if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.background)
                    .clickable(onClick = { onSelectDate(day.date) }, role = Role.Tab)
                    .semantics {
                        this.selected = selected
                        contentDescription = label
                    }
                    .padding(horizontal = spacing.space4, vertical = spacing.space2 + spacing.space1 / 2),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = number,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = number.displayFont(),
                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = day.date.shortDayOfWeek,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) Color.White.copy(alpha = 0.7f) else LocalGilpickColors.current.muted,
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.background)
}

/**
 * 조회 실패 안내(공통 오류 화면, 가이드라인 9절, #446). 원인과 `다시 시도`를 제공하고 세션 만료는 `다시 로그인`으로 잇는다.
 *
 * 나가는 길은 헤더 ←가 이미 있어 보조 버튼을 두지 않는다. 발생 시각·마지막 동작은 이 화면이 모르는 값이라 원인 카드를 그리지 않는다.
 */
@Composable
private fun FailedState(error: ItineraryError, onRetry: () -> Unit, onReauthenticate: () -> Unit) {
    val sessionExpired = error == ItineraryError.SessionExpired
    CommonErrorState(
        description = stringResource(error.messageRes),
        primaryLabel = stringResource(if (sessionExpired) R.string.place_reauthenticate else R.string.itinerary_edit_retry),
        onPrimary = if (sessionExpired) onReauthenticate else onRetry,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * 선택한 날짜의 제목·요약, 방문 장소 카드, 점선 `장소 추가` 버튼.
 *
 * 손잡이 끌기(UI-008)는 foundation gesture로만 만든다. 끌리는 행은 손가락을 따라 `translationY`로
 * 움직이고, 이웃 행 높이의 절반을 넘으면 [onMove]로 한 칸 옮긴 뒤 그만큼 오프셋을 되돌려 화면이
 * 튀지 않게 한다. 하루 최대 10곳이라 `LazyColumn` 없이 `Column`으로 충분하다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Content(
    state: ItineraryEditUiState,
    onAddPlace: () -> Unit,
    onEditStay: (Int) -> Unit,
    onChangeTransport: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val date = state.selectedDate ?: return
    val day = state.selectedDay ?: return
    // ponytail: 끌기 중 자동 스크롤은 없다. 화면 밖 위치로는 나눠 끌거나 TalkBack 커스텀 액션을 쓴다. 필요해지면 verticalScroll 상태로 보강한다.
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeights = remember { mutableStateMapOf<Int, Int>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.space4, vertical = spacing.space4),
    ) {
        // 글자 배율 2.0에서는 제목을 단어 중간에서 끊지 않고 요약이 다음 줄로 내려간다(UI-010).
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.space1, end = spacing.space1, bottom = spacing.space3),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(spacing.space1),
        ) {
            Text(
                text = stringResource(R.string.itinerary_edit_section_title, date.monthValue, date.dayOfMonth),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.itinerary_edit_section_summary, day.dayNumber, state.draft.size),
                style = MaterialTheme.typography.bodySmall,
                color = LocalGilpickColors.current.muted,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }

        if (state.draft.isEmpty()) {
            EmptyCard()
        } else {
            Surface(
                shape = RoundedCornerShape(LocalGilpickRadius.current.lg),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    val draft = state.draft
                    val canMove: (Int, Int) -> Boolean = { from, to ->
                        from in draft.indices && to in draft.indices && draft[from].editable && draft[to].editable
                    }
                    draft.forEachIndexed { index, item ->
                        PlaceRow(
                            index = index,
                            item = item,
                            isLast = index == draft.lastIndex,
                            onEditStay = { onEditStay(index) },
                            onChangeTransport = { onChangeTransport(index) },
                            onRemove = { onRemove(index) },
                            onMoveUp = if (canMove(index, index - 1)) ({ onMove(index, index - 1) }) else null,
                            onMoveDown = if (canMove(index, index + 1)) ({ onMove(index, index + 1) }) else null,
                            onDragStart = { dragging = index; dragOffset = 0f },
                            onDragEnd = { dragging = null; dragOffset = 0f },
                            onDrag = { dy ->
                                dragOffset += dy
                                val from = dragging ?: return@PlaceRow
                                val to = if (dragOffset > 0) from + 1 else from - 1
                                val neighbor = rowHeights[to] ?: return@PlaceRow
                                if (abs(dragOffset) > neighbor / 2f && canMove(from, to)) {
                                    onMove(from, to)
                                    dragging = to
                                    dragOffset += if (to > from) -neighbor.toFloat() else neighbor.toFloat()
                                }
                            },
                            modifier = Modifier
                                .onSizeChanged { rowHeights[index] = it.height }
                                .then(
                                    if (dragging == index) Modifier
                                        .zIndex(1f)
                                        .graphicsLayer { translationY = dragOffset }
                                    else Modifier,
                                ),
                        )
                        if (index < draft.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.background,
                                modifier = Modifier.padding(horizontal = spacing.space4),
                            )
                        }
                    }
                }
            }
        }

        AddPlaceButton(enabled = !state.addDisabled, onClick = onAddPlace)
        if (state.addDisabled) {
            Text(
                text = stringResource(R.string.itinerary_edit_limit_hint),
                style = MaterialTheme.typography.labelSmall,
                color = LocalGilpickColors.current.muted,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.space3),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(spacing.space6))
    }
}

/** 빈 날짜 안내(UI-007). Figma에 빈 상태가 없어 가이드라인 9절의 빈 상태 틀을 카드 안에 쓴다. */
@Composable
private fun EmptyCard() {
    val spacing = LocalGilpickSpacing.current
    Surface(
        shape = RoundedCornerShape(LocalGilpickRadius.current.lg),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = spacing.space5, vertical = spacing.space6),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.space1),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_map_pin),
                contentDescription = null,
                tint = LocalGilpickColors.current.faint,
                modifier = Modifier
                    .padding(bottom = spacing.space2)
                    .size(28.dp),
            )
            Text(
                text = stringResource(R.string.itinerary_edit_empty_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.itinerary_edit_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalGilpickColors.current.muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 방문 장소 행(UI-002): 손잡이, 순서 번호 또는 상태 원, 장소명, 체류 시간, 다음 구간 이동 수단과
 * `변경`, 삭제 버튼.
 *
 * 순서 변경은 손잡이 끌기만 보인다(#507). 끌기가 어려운 사용자를 위해 손잡이에 TalkBack 커스텀 액션
 * `위로 이동`·`아래로 이동`을 붙인다. [onMoveUp]·[onMoveDown]이 `null`이면 그 방향으로 옮길 수 없어
 * 액션을 두지 않는다.
 *
 * 도착 시각은 경로가 계산되기 전이라 표시하지 않는다(FR-018). 처리된 항목은 손잡이·삭제·`변경`을
 * 숨기고 체류 시간만 바꿀 수 있으며 상태를 색·아이콘·문구로 함께 보인다(FR-017, UI-009).
 * Figma의 32dp 삭제 버튼은 48dp 터치 영역 안에 둔다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceRow(
    index: Int,
    item: DraftItem,
    isLast: Boolean,
    onEditStay: () -> Unit,
    onChangeTransport: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDrag: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val name = item.place.name
    val sequence = stringResource(R.string.itinerary_edit_sequence, index + 1)
    val handleDescription = stringResource(R.string.itinerary_edit_drag_handle, name)
    val moveUpLabel = stringResource(R.string.itinerary_edit_move_up)
    val moveDownLabel = stringResource(R.string.itinerary_edit_move_down)
    val editable = item.editable

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = spacing.space1, end = spacing.space4, top = spacing.space2, bottom = spacing.space2),
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        // 손잡이: Figma 14px 6점 아이콘, 터치 영역 48dp. 예정 항목에만 둔다(UI-008).
        Box(
            modifier = Modifier
                .size(MIN_TOUCH)
                .then(
                    if (editable) Modifier
                        .semantics {
                            contentDescription = handleDescription
                            customActions = listOfNotNull(
                                onMoveUp?.let { move -> CustomAccessibilityAction(moveUpLabel) { move(); true } },
                                onMoveDown?.let { move -> CustomAccessibilityAction(moveDownLabel) { move(); true } },
                            )
                        }
                        .pointerInput(index) {
                            detectDragGestures(
                                onDragStart = { onDragStart() },
                                onDragEnd = onDragEnd,
                                onDragCancel = onDragEnd,
                                onDrag = { change, delta -> change.consume(); onDrag(delta.y) },
                            )
                        }
                    else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (editable) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_grip_vertical),
                    contentDescription = null,
                    tint = colors.faint,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        StatusCircle(index = index, status = item.status, description = sequence)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = spacing.space2 + spacing.space1 / 2),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = if (editable) MaterialTheme.colorScheme.onSurface else colors.muted,
            )
            item.status.labelRes?.let { labelRes ->
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.status == ItemStatus.COMPLETED) colors.success else colors.muted,
                )
            }
            // 글자 배율 2.0에서는 체류 시간과 이동 수단이 두 줄로 내려간다(UI-010).
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.space2)) {
                StayChip(minutes = item.stayMinutes, name = name, onClick = onEditStay)
                val transport = item.transportToNext
                if (!isLast && transport != null) {
                    TransportChip(transport = transport, name = name, editable = editable, onChange = onChangeTransport)
                }
            }
        }
        if (editable) {
            DeleteButton(name = name, onClick = onRemove, modifier = Modifier.align(Alignment.CenterVertically))
        } else {
            // 처리된 행도 장소명 폭이 같도록 삭제 버튼 자리를 비워 둔다.
            Spacer(modifier = Modifier.size(width = MIN_TOUCH, height = 0.dp))
        }
    }
}

/** 순서 번호 원. 완료는 초록 체크, 건너뜀은 회색 X로 바꾼다(UI-002). */
@Composable
private fun StatusCircle(index: Int, status: ItemStatus, description: String) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val (background, icon) = when (status) {
        ItemStatus.COMPLETED -> colors.success to R.drawable.ic_lucide_check
        ItemStatus.SKIPPED -> colors.faint to R.drawable.ic_lucide_x
        else -> MaterialTheme.colorScheme.primary to null
    }

    Box(
        modifier = Modifier
            .padding(top = spacing.space2 + spacing.space1 / 2)
            // 글자 배율이 커지면 숫자가 잘리지 않도록 원이 함께 커진다.
            .defaultMinSize(SEQUENCE_CIRCLE, SEQUENCE_CIRCLE)
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = spacing.space1)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
        } else {
            val number = (index + 1).toString()
            Text(
                text = number,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = number.displayFont(),
                color = Color.White,
            )
        }
    }
}

/** 체류 시간 `90분`과 연필 아이콘. 누르면 체류 시간 대화상자를 연다. 처리된 항목도 누를 수 있다(FR-017). */
@Composable
private fun StayChip(minutes: Int, name: String, onClick: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val description = stringResource(R.string.itinerary_edit_stay_edit, name)
    Row(
        modifier = Modifier
            .heightIn(min = MIN_TOUCH)
            .clip(RoundedCornerShape(LocalGilpickRadius.current.md))
            .clickable(onClick = onClick, role = Role.Button)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space1),
    ) {
        Text(
            text = stringResource(R.string.itinerary_edit_stay_minutes, minutes),
            style = MaterialTheme.typography.bodySmall,
            color = LocalGilpickColors.current.muted,
        )
        Icon(
            painter = painterResource(R.drawable.ic_lucide_pencil),
            contentDescription = null,
            tint = LocalGilpickColors.current.faint,
            modifier = Modifier.size(11.dp),
        )
    }
}

/** 다음 구간 이동 수단 아이콘·이름과 `변경`. 처리된 항목은 `변경`을 숨긴다(FR-017). */
@Composable
private fun TransportChip(transport: TransportMode, name: String, editable: Boolean, onChange: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val description = stringResource(R.string.itinerary_edit_transport_change_description, name)
    Row(
        modifier = Modifier.heightIn(min = MIN_TOUCH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space1 + 2.dp),
    ) {
        Icon(
            painter = painterResource(transport.iconRes),
            contentDescription = null,
            tint = LocalGilpickColors.current.faint,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = stringResource(transport.labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = LocalGilpickColors.current.faint,
        )
        if (editable) {
            Text(
                text = stringResource(R.string.itinerary_edit_transport_change),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .heightIn(min = MIN_TOUCH)
                    .clip(RoundedCornerShape(LocalGilpickRadius.current.md))
                    .clickable(onClick = onChange, role = Role.Button)
                    .semantics { contentDescription = description }
                    .padding(horizontal = spacing.space1)
                    .wrapContentHeight(),
            )
        }
    }
}

/** Figma 32dp 삭제 버튼을 48dp 터치 영역 안에 둔다. */
@Composable
private fun DeleteButton(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.itinerary_edit_remove, name)
    Box(
        modifier = modifier
            .size(MIN_TOUCH)
            .clip(RoundedCornerShape(LocalGilpickRadius.current.md))
            .clickable(onClick = onClick, role = Role.Button)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(DELETE_BUTTON)
                .clip(RoundedCornerShape(LocalGilpickRadius.current.md))
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_trash),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** Figma 점선 테두리 `장소 추가` 버튼. 10곳이면 비활성화한다(FR-021). */
@Composable
private fun AddPlaceButton(enabled: Boolean, onClick: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current.lg
    val shape = RoundedCornerShape(radius)
    val primary = MaterialTheme.colorScheme.primary
    val label = stringResource(R.string.itinerary_edit_add_place)
    val stroke = with(LocalDensity.current) { 2.dp.toPx() }
    val cornerPx = with(LocalDensity.current) { radius.toPx() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.space3)
            .height(ADD_BUTTON_HEIGHT)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
            .drawBehind {
                drawRoundRect(
                    color = primary.copy(alpha = 0.25f),
                    cornerRadius = CornerRadius(cornerPx),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(stroke * 3, stroke * 2))),
                )
            }
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(spacing.space2, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) primary else LocalGilpickColors.current.muted
        Icon(
            painter = painterResource(R.drawable.ic_lucide_plus),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = tint,
        )
    }
}

/**
 * 하단 고정 `저장` 주버튼. 저장 중에는 비활성화하고 진행 중임을 표시한다(UI-007).
 *
 * 저장할 수 없을 때(일정을 불러오는 중·실패, 날짜 미선택)는 비활성 표현(가이드라인 7절 D2: `faint` + 40%)이다.
 * 저장 중 표현은 처리 중이라 이 버튼에서는 바꾸지 않았다(#433 범위 밖).
 */
@Composable
private fun SaveBar(saving: Boolean, enabled: Boolean, saveError: ItineraryError?, onSave: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val shape = RoundedCornerShape(LocalGilpickRadius.current.lg)
    val save = stringResource(R.string.itinerary_edit_save)
    val savingLabel = stringResource(R.string.itinerary_edit_saving)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.space4)
            .padding(top = spacing.space3, bottom = spacing.space8)
            .navigationBarsPadding(),
    ) {
        if (saveError != null) {
            Text(
                text = stringResource(R.string.itinerary_edit_save_failed, stringResource(saveError.messageRes)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = spacing.space2)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SAVE_BUTTON_HEIGHT)
                .alpha(if (!enabled && !saving) GradientButtonDefaults.DisabledAlpha else 1f)
                .clip(shape)
                .background(
                    if (enabled && !saving) Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, LocalGilpickColors.current.primaryDark))
                    else Brush.linearGradient(listOf(LocalGilpickColors.current.faint, LocalGilpickColors.current.faint)),
                )
                .clickable(enabled = enabled && !saving, onClick = onSave, role = Role.Button)
                .semantics { contentDescription = if (saving) savingLabel else save },
            contentAlignment = Alignment.Center,
        ) {
            if (saving) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Text(text = save, style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
    }
}

/**
 * 취소 확인 대화상자(UI-005). Figma: 제목, 설명, `계속 편집` 주버튼, `취소하고 나가기` 빨간 글자.
 *
 * F002 삭제 확인과 같은 이유로 [BasicAlertDialog]를 쓴다. `AlertDialog`는 세로로 쌓인 두
 * 행동과 24dp 안쪽 여백을 그대로 만들지 못한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscardConfirmDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val title = stringResource(R.string.itinerary_edit_discard_title)

    BasicAlertDialog(onDismissRequest = onKeep) {
        Surface(
            shape = RoundedCornerShape(LocalGilpickRadius.current.xl),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(spacing.space6)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = title.displayFont(),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.itinerary_edit_discard_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.space2, bottom = spacing.space6),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(LocalGilpickRadius.current.lg))
                        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, LocalGilpickColors.current.primaryDark)))
                        .clickable(onClick = onKeep, role = Role.Button),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.itinerary_edit_discard_keep),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.space2)
                        .heightIn(min = MIN_TOUCH)
                        .clip(RoundedCornerShape(LocalGilpickRadius.current.md))
                        .clickable(onClick = onDiscard, role = Role.Button),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.itinerary_edit_discard_confirm),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * 체류 시간 대화상자(UI-003). Figma: `{장소명} 체류 시간` 제목, `−`·`+` 44dp 원과 분 표시, 60·90·120분
 * 빠른 선택, `취소`·`적용`. Figma의 `변경 시 이후 일정 도착 시각이 함께 조정됩니다`와 시간 범위는
 * 경로 계산 전이라 표시하지 않는다(FR-018). 30분 미만·360분 초과로는 조절되지 않는다(FR-004).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StayTimeDialog(placeName: String, initialMinutes: Int, onCancel: () -> Unit, onApply: (Int) -> Unit) {
    BasicAlertDialog(onDismissRequest = onCancel) {
        StayTimeDialogContent(placeName = placeName, initialMinutes = initialMinutes, onCancel = onCancel, onApply = onApply)
    }
}

/** 체류 시간 대화상자의 내용. 별도 window 없이 그릴 수 있어 screenshot test가 직접 찍는다. */
@Composable
internal fun StayTimeDialogContent(placeName: String, initialMinutes: Int, onCancel: () -> Unit, onApply: (Int) -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val shape = RoundedCornerShape(LocalGilpickRadius.current.lg)
    var minutes by rememberSaveable(initialMinutes) { mutableIntStateOf(initialMinutes) }
    val title = stringResource(R.string.itinerary_edit_stay_title, placeName)
    val minutesText = minutes.toString()

    Surface(shape = RoundedCornerShape(LocalGilpickRadius.current.xl), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.padding(spacing.space6)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = title.displayFont(),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = spacing.space5),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = spacing.space4, vertical = spacing.space4),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepButton(
                    label = "−",
                    contentDescription = stringResource(R.string.place_detail_stay_decrease),
                    primary = false,
                    onClick = { minutes = (minutes - ItineraryEditViewModel.STAY_STEP).coerceAtLeast(ItineraryEditViewModel.STAY_MIN) },
                    size = DIALOG_STEP_BUTTON,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = minutesText,
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = minutesText.displayFont(),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.itinerary_edit_stay_unit),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = spacing.space1, bottom = spacing.space1),
                    )
                }
                StepButton(
                    label = "+",
                    contentDescription = stringResource(R.string.place_detail_stay_increase),
                    primary = true,
                    onClick = { minutes = (minutes + ItineraryEditViewModel.STAY_STEP).coerceAtMost(ItineraryEditViewModel.STAY_MAX) },
                    size = DIALOG_STEP_BUTTON,
                )
            }
            Row(
                modifier = Modifier.padding(top = spacing.space4, bottom = spacing.space5),
                horizontalArrangement = Arrangement.spacedBy(spacing.space2),
            ) {
                QUICK_MINUTES.forEach { quick ->
                    val selected = minutes == quick
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = MIN_TOUCH)
                            .clip(RoundedCornerShape(LocalGilpickRadius.current.md))
                            .background(if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.background)
                            .clickable(onClick = { minutes = quick }, role = Role.RadioButton)
                            .semantics { this.selected = selected },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.itinerary_edit_stay_minutes, quick),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            CancelApplyRow(onCancel = onCancel, onApply = { onApply(minutes) })
        }
    }
}

/**
 * 이동 수단 변경 시트(UI-004). Figma: `이동 수단 변경` 제목, `{장소명}까지 어떻게 이동하시겠어요?`,
 * 도보·대중교통·자동차 카드, `취소`·`적용`. 체류 시간은 여기서 조절하지 않는다. 카드는 F003 시트의
 * [TransportOption]을 그대로 쓴다. 이동 시간·거리는 경로 계산 전이라 표시하지 않는다(FR-018).
 *
 * @param nextPlaceName 이 구간이 향하는 다음 장소. 안내 문구의 `{장소명}`이다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransportSheet(nextPlaceName: String, current: TransportMode?, onCancel: () -> Unit, onApply: (TransportMode) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = LocalGilpickRadius.current.sheet, topEnd = LocalGilpickRadius.current.sheet),
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        TransportSheetContent(nextPlaceName = nextPlaceName, current = current, onCancel = onCancel, onApply = onApply)
    }
}

/** 이동 수단 시트의 내용. 별도 window 없이 그릴 수 있어 screenshot test가 직접 찍는다. */
@Composable
internal fun TransportSheetContent(nextPlaceName: String, current: TransportMode?, onCancel: () -> Unit, onApply: (TransportMode) -> Unit) {
    val spacing = LocalGilpickSpacing.current
    var selected by rememberSaveable(current) { mutableStateOf(current ?: TransportMode.WALK) }
    val title = stringResource(R.string.itinerary_edit_transport_title)

    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = spacing.space6, end = spacing.space6, top = spacing.space5, bottom = spacing.space8)
            .navigationBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = spacing.space5)
                .size(width = 40.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = title.displayFont(),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.itinerary_edit_transport_subtitle, nextPlaceName),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalGilpickColors.current.muted,
            modifier = Modifier.padding(top = spacing.space1, bottom = spacing.space5),
        )
        TransportMode.entries.forEach { mode ->
            TransportOption(
                option = mode.toPlaceTransport(),
                selected = selected == mode,
                onClick = { selected = mode },
                modifier = Modifier.padding(bottom = spacing.space2),
            )
        }
        CancelApplyRow(onCancel = onCancel, onApply = { onApply(selected) }, modifier = Modifier.padding(top = spacing.space4))
    }
}

/**
 * Figma 대화상자·시트 하단: `취소`(1) : `적용`(2), 높이 50dp.
 *
 * 한 줄을 가로로 나눈 버튼이라 곡률은 12dp다(가이드라인 6절 R3, D4). 체류 시간 dialog와 이동 수단 시트가 함께 쓴다.
 */
@Composable
private fun CancelApplyRow(onCancel: () -> Unit, onApply: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current
    val shape = RoundedCornerShape(LocalGilpickRadius.current.md)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(spacing.space3)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(ADD_BUTTON_HEIGHT)
                .clip(shape)
                .background(MaterialTheme.colorScheme.background)
                .clickable(onClick = onCancel, role = Role.Button),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.place_detail_cancel),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .weight(2f)
                .height(ADD_BUTTON_HEIGHT)
                .clip(shape)
                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, LocalGilpickColors.current.primaryDark)))
                .clickable(onClick = onApply, role = Role.Button),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.itinerary_edit_apply),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
        }
    }
}

/** 가이드라인 10절 최소 터치 영역. */
private val MIN_TOUCH = 48.dp

/** Figma 헤더 버튼 크기(`w-9 h-9`). */
private val HEADER_BUTTON = 36.dp

/** Figma 순서 번호 원(`w-7 h-7`). */
private val SEQUENCE_CIRCLE = 28.dp

/** Figma 위·아래 이동 버튼(`w-7 h-7`)과 삭제 버튼(`w-8 h-8`). */
private val DELETE_BUTTON = 32.dp

/** Figma 체류 시간 대화상자 `−`·`+` 원(`w-11 h-11`). F003 시트의 40dp보다 크다. */
private val DIALOG_STEP_BUTTON = 44.dp

/** 체류 시간 빠른 선택(UI-003). */
private val QUICK_MINUTES = listOf(60, 90, 120)

/** Figma `장소 추가` 버튼 높이(`h-[50px]`). */
private val ADD_BUTTON_HEIGHT = 50.dp

/** Figma `저장` 버튼 높이(`h-[54px]`). */
private val SAVE_BUTTON_HEIGHT = 54.dp
