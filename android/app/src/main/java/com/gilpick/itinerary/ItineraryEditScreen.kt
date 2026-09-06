package com.gilpick.itinerary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gilpick.R
import com.gilpick.place.LoadingState
import com.gilpick.place.StateMessage
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.gilpick.ui.theme.displayFont
import java.time.LocalDate

/**
 * 일정 편집 화면. 모양은 Figma `ScheduleEditScreen`을 따른다(UI-001·UI-011).
 *
 * 이 화면은 US1(장소 추가·저장·취소 확인)까지 담는다. 행의 체류 시간 대화상자, 이동 수단
 * `변경`, 삭제, 순서 이동 손잡이·버튼과 처리된 장소 표시는 US2(#191)에서 같은 파일에 붙는다.
 * 경로 상태가 `NOT_CALCULATED`이므로 Figma의 도착 시각·구간 소요 시간과 `변경하면 남은
 * 일정의 도착 시각이 다시 계산됩니다` 안내는 표시하지 않는다(FR-018). Figma 헤더 오른쪽의
 * 되돌리기 모양 버튼은 동작이 정의되지 않아 두지 않는다.
 *
 * @param state 현재 편집 상태.
 * @param onClose 닫기 버튼과 시스템 뒤로 가기. 변경 여부에 따른 확인은 ViewModel이 정한다.
 * @param onSelectDate 날짜 탭.
 * @param onAddPlace `장소 추가`. F003 장소 검색으로 간다.
 * @param onSave 하단 `저장`.
 * @param onRetry 조회 실패 뒤 `다시 시도`.
 * @param onReauthenticate 로그인 상태가 만료됐다. F001 재인증 흐름으로 넘어간다.
 * @param onDismissDialog 취소 확인의 `계속 편집`.
 * @param onConfirmDiscard 취소 확인의 `취소하고 나가기`.
 * @param onNoticeShown 추가 거부 안내를 사용자가 봤다.
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
    modifier: Modifier = Modifier,
) {
    val spacing = LocalGilpickSpacing.current
    val snackbarHost = remember { SnackbarHostState() }
    val noticeText = state.notice?.let {
        stringResource(
            when (it) {
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

                    ItineraryEditPhase.Content -> Content(state = state, onAddPlace = onAddPlace)
                }
            }
            SaveBar(
                saving = state.saving,
                enabled = state.phase is ItineraryEditPhase.Content && state.selectedDate != null,
                saveError = state.saveError,
                onSave = onSave,
            )
        }
        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = spacing.space8 + spacing.space8),
        ) { data ->
            Snackbar(snackbarData = data, containerColor = LocalGilpickColors.current.toast, contentColor = Color.White)
        }
    }

    when (val dialog = state.dialog) {
        is EditDialog.Discard -> DiscardConfirmDialog(onKeep = onDismissDialog, onDiscard = onConfirmDiscard)
        null -> Unit
    }
}

/** Figma 헤더: 36dp `background` 사각 닫기 버튼과 `일정 편집` 제목. 터치 영역은 48dp로 넓힌다. */
@Composable
private fun Header(onClose: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val close = stringResource(R.string.itinerary_edit_close)
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
                .semantics { contentDescription = close },
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
                    painter = painterResource(R.drawable.ic_lucide_x),
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

/** 조회 실패 안내. 원인과 `다시 시도`를 제공하고 세션 만료는 `다시 로그인`으로 잇는다. */
@Composable
private fun FailedState(error: ItineraryError, onRetry: () -> Unit, onReauthenticate: () -> Unit) {
    StateMessage(
        title = stringResource(error.messageRes),
        body = null,
        titleColor = MaterialTheme.colorScheme.error,
        live = true,
        action = {
            if (error == ItineraryError.SessionExpired) {
                Button(onClick = onReauthenticate, modifier = Modifier.heightIn(min = MIN_TOUCH)) {
                    Text(stringResource(R.string.place_reauthenticate))
                }
            } else {
                Button(onClick = onRetry, modifier = Modifier.heightIn(min = MIN_TOUCH)) {
                    Text(stringResource(R.string.itinerary_edit_retry))
                }
            }
        },
    )
}

/** 선택한 날짜의 제목·요약, 방문 장소 카드, 점선 `장소 추가` 버튼. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Content(state: ItineraryEditUiState, onAddPlace: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val date = state.selectedDate ?: return
    val day = state.selectedDay ?: return

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
                    state.draft.forEachIndexed { index, item ->
                        PlaceRow(index = index, item = item, isLast = index == state.draft.lastIndex)
                        if (index < state.draft.lastIndex) {
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
 * 방문 장소 행(UI-002): 순서 번호 원, 장소명, 체류 시간, 다음 구간 이동 수단.
 *
 * 도착 시각은 경로가 계산되기 전이라 표시하지 않고 체류 시간만 쓴다(FR-018). 체류 시간
 * 편집, `변경`, 삭제, 순서 이동은 US2(#191)에서 이 행에 붙는다.
 */
@Composable
private fun PlaceRow(index: Int, item: DraftItem, isLast: Boolean) {
    val spacing = LocalGilpickSpacing.current
    val sequence = stringResource(R.string.itinerary_edit_sequence, index + 1)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.space4, vertical = spacing.space4),
        horizontalArrangement = Arrangement.spacedBy(spacing.space3),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                // 글자 배율이 커지면 숫자가 잘리지 않도록 원이 함께 커진다.
                .defaultMinSize(SEQUENCE_CIRCLE, SEQUENCE_CIRCLE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = spacing.space1)
                .semantics { contentDescription = sequence },
            contentAlignment = Alignment.Center,
        ) {
            val number = (index + 1).toString()
            Text(
                text = number,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = number.displayFont(),
                color = Color.White,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.space1 / 2)) {
            Text(
                text = item.place.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.itinerary_edit_stay_minutes, item.stayMinutes),
                style = MaterialTheme.typography.bodySmall,
                color = LocalGilpickColors.current.muted,
            )
            val transport = item.transportToNext
            if (!isLast && transport != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.space1 + 2.dp),
                    modifier = Modifier.padding(top = spacing.space1),
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
                }
            }
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

/** 하단 고정 `저장` 주버튼. 저장 중에는 비활성화하고 진행 중임을 표시한다(UI-007). */
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

/** 가이드라인 10절 최소 터치 영역. */
private val MIN_TOUCH = 48.dp

/** Figma 헤더 닫기 버튼 크기(`w-9 h-9`). */
private val HEADER_BUTTON = 36.dp

/** Figma 순서 번호 원(`w-7 h-7`). */
private val SEQUENCE_CIRCLE = 28.dp

/** Figma `장소 추가` 버튼 높이(`h-[50px]`). */
private val ADD_BUTTON_HEIGHT = 50.dp

/** Figma `저장` 버튼 높이(`h-[54px]`). */
private val SAVE_BUTTON_HEIGHT = 54.dp
