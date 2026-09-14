package com.gilpick.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.gilpick.R
import com.gilpick.ui.component.GradientButton
import com.gilpick.ui.component.GradientButtonWidth
import com.gilpick.ui.theme.GilpickTheme
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickShadows
import com.gilpick.ui.theme.LocalGilpickSpacing
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/**
 * 여행 생성·수정 화면(Figma `CreateTripScreen`·`EditTripScreen`, 가이드라인 7절 입력창·날짜 선택).
 *
 * - 헤더: 새 여행은 ←, 여행 수정은 ✕(기존 데이터를 고치는 편집 화면, 7절 헤더 아이콘 버튼).
 * - 이름: 흰 카드 안 폼 입력창. 입력을 시작하면 바로 오류 테두리·문구를 보여 준다(Figma).
 * - 기간: 새 여행은 카드 안 인라인 달력, 수정은 두 칸 날짜 표시이고 누르면 같은 달력이 카드 안에 펼쳐진다(#443 결정).
 * - 제출: 하단 고정 [GradientButton]. 입력이 부족하면 비활성(D2)이고 버튼 위에 이유 문장을 둔다(Figma `disabled={!canCreate}`, #443 결정).
 * - 수정 화면 아래 `여행 삭제`(Figma, #443 결정). 확인 대화상자는 상세와 같은 [TripDeleteConfirmDialog]다.
 * - 커버 이미지·사진 업로드는 API에 값이 없어 두지 않는다(범위 밖, Backend 계약 필요).
 *
 * 색상·간격·곡률은 `com.gilpick.ui.theme` 토큰에서 읽는다.
 *
 * @param state 현재 폼 상태.
 * @param onNameChange 여행명 입력을 반영한다.
 * @param onPeriodChange 고른 기간을 반영한다. 달력에서 시작일만 고른 상태는 종료일 `null`로 전달한다.
 * @param onSubmit 생성 또는 저장을 요청한다.
 * @param onConfirmDeleteOutOfRangeItems 기간 축소로 삭제될 일정에 동의하고 저장을 계속한다.
 * @param onCancelDeleteConfirmation 기간 축소 확인 대화상자를 저장하지 않고 닫는다.
 * @param onBack 헤더 ←·✕. 폼을 나간다.
 * @param onDelete 수정 화면 `여행 삭제` 확인 대화상자에서 삭제를 확정했다.
 * @param onDeleteErrorShown 삭제 실패 안내를 사용자가 닫았음을 알린다.
 */
@Composable
fun TripFormScreen(
    state: TripFormUiState,
    onNameChange: (String) -> Unit,
    onPeriodChange: (LocalDate?, LocalDate?) -> Unit,
    onSubmit: () -> Unit,
    onConfirmDeleteOutOfRangeItems: () -> Unit = {},
    onCancelDeleteConfirmation: () -> Unit = {},
    onBack: () -> Unit = {},
    onDelete: () -> Unit = {},
    onDeleteErrorShown: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val spacing = LocalGilpickSpacing.current
    val editing = state.mode is FormMode.Edit
    var deleteOpen by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Header(editing = editing, onBack = onBack)

        if (state.loading) {
            // 수정 모드는 폼을 채우기 전에 여행을 한 번 조회한다. 그동안 빈 입력창을
            // 보여주면 값이 없는 것으로 오해한다.
            Box(modifier = Modifier.weight(1f)) { LoadingState() }
            return@Column
        }

        // 제출 가능 여부는 입력마다 화면이 직접 계산한다. 버튼 비활성과 이유 문장이 입력에 바로 따라와야 한다.
        val validation = TripFormValidator.validate(state.name, state.startDate, state.endDate)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.space5, vertical = spacing.space5),
            verticalArrangement = Arrangement.spacedBy(if (editing) spacing.space4 else spacing.space5),
        ) {
            NameCard(
                value = state.name,
                // 아직 아무것도 쓰지 않은 칸을 오류로 칠하지 않는다. 쓰기 시작하면 바로 알린다(Figma).
                error = validation.nameError.takeIf { state.name.isNotEmpty() || state.showErrors },
                enabled = !state.submitting,
                onValueChange = onNameChange,
            )

            if (editing) {
                EditPeriodCard(
                    state = state,
                    enabled = !state.submitting && !state.periodLocked,
                    onPeriodChange = onPeriodChange,
                )
            } else {
                CalendarCard(
                    startDate = state.startDate,
                    endDate = state.endDate,
                    enabled = !state.submitting,
                    onPeriodChange = onPeriodChange,
                )
                Text(
                    text = stringResource(R.string.trip_form_period_max_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalGilpickColors.current.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 7일 초과처럼 달력으로 고른 뒤에야 알 수 있는 기간 오류는 카드 아래에 원인을 적는다.
            validation.periodError
                ?.takeIf { it != TripPeriodError.NOT_SELECTED && state.startDate != null && state.endDate != null }
                ?.let { PeriodErrorText(it) }

            state.submitError?.let { SubmitError(it) }
        }

        BottomActions(
            state = state,
            validation = validation,
            onSubmit = onSubmit,
            onRequestDelete = { deleteOpen = true },
        )
    }

    // 서버가 삭제될 장소 수를 알려 준 동안에만 띄운다. 동의하기 전에는 저장되지 않는다.
    state.deleteConfirmation?.let { deletedItemCount ->
        ShrinkConfirmDialog(
            deletedItemCount = deletedItemCount,
            submitting = state.submitting,
            onConfirm = onConfirmDeleteOutOfRangeItems,
            onDismiss = onCancelDeleteConfirmation,
        )
    }

    if (deleteOpen && editing) {
        TripDeleteConfirmDialog(
            tripName = state.name,
            deletion = state.deletion,
            onConfirm = onDelete,
            onDismiss = {
                deleteOpen = false
                onDeleteErrorShown()
            },
        )
    }
}

/** 흰 헤더: 36dp `background` 헤더 버튼과 18sp Page title(가이드라인 7절 헤더 아이콘 버튼). */
@Composable
private fun Header(editing: Boolean, onBack: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val description = stringResource(if (editing) R.string.trip_form_close else R.string.trip_form_back)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = spacing.space5 - HEADER_BUTTON_INSET, end = spacing.space5, top = spacing.space3 - HEADER_BUTTON_INSET, bottom = spacing.space4 - HEADER_BUTTON_INSET),
        horizontalArrangement = Arrangement.spacedBy(spacing.space3 - HEADER_BUTTON_INSET),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(MIN_TOUCH)
                .clickable(onClick = onBack, role = Role.Button)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(HEADER_BUTTON)
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(LocalGilpickRadius.current.md)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(if (editing) R.drawable.ic_lucide_x else R.drawable.ic_lucide_arrow_left),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(HEADER_ICON),
                )
            }
        }
        Text(
            text = stringResource(if (editing) R.string.trip_form_edit_title else R.string.trip_form_create_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 흰 카드(`radiusLg`, 카드 그림자, 안쪽 20dp). 폼 입력과 달력이 공유한다(가이드라인 7절). */
@Composable
private fun FormCard(modifier: Modifier = Modifier, padded: Boolean = true, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(LocalGilpickRadius.current.lg)
    val shadowed = LocalGilpickShadows.current.card.fold(modifier.fillMaxWidth()) { acc, shadow -> acc.dropShadow(shape, shadow) }
    Column(
        modifier = shadowed
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .then(if (padded) Modifier.padding(LocalGilpickSpacing.current.space5) else Modifier),
    ) { content() }
}

/** 카드 안 라벨(`여행 이름`·`여행 일정`, 12sp 700 `muted` 자간). */
@Composable
private fun CardLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = LocalGilpickColors.current.muted,
        modifier = modifier,
    )
}

/**
 * 여행명 폼 입력창(가이드라인 7절 "입력창").
 *
 * 50dp `background` 채움, 기본 2dp 투명 테두리(포커스 때 크기가 변하지 않게), 포커스 `primary`, 오류 `error`(포커스보다 우선).
 * M3 `OutlinedTextField`의 떠오르는 라벨·외곽선은 Figma에 없어 쓰지 않는다.
 */
@Composable
private fun NameCard(
    value: String,
    error: TripNameError?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val shape = RoundedCornerShape(LocalGilpickRadius.current.md)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val label = stringResource(R.string.trip_form_name_label)
    val borderColor = when {
        error != null -> MaterialTheme.colorScheme.error
        focused -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }

    FormCard {
        CardLabel(text = label, modifier = Modifier.padding(bottom = spacing.space3))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            // 가이드라인 7절: 16sp 600 `onSurface`. 타입 스케일에 16sp가 없어 Body를 키워 쓴다.
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = FIELD_TEXT_SIZE,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label },
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = FIELD_HEIGHT)
                        .background(MaterialTheme.colorScheme.background, shape)
                        .border(FIELD_BORDER, borderColor, shape)
                        .padding(horizontal = spacing.space4),
                    contentAlignment = Alignment.CenterStart,
                ) { inner() }
            },
        )
        if (error != null) {
            Text(
                text = stringResource(
                    when (error) {
                        TripNameError.TOO_SHORT -> R.string.trip_form_error_name_short
                        TripNameError.TOO_LONG -> R.string.trip_form_error_name_long
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = spacing.space2),
            )
        }
    }
}

/** 새 여행 기간: 카드 안 인라인 달력과 요약 줄(가이드라인 7절 "날짜 선택"). */
@Composable
private fun CalendarCard(
    startDate: LocalDate?,
    endDate: LocalDate?,
    enabled: Boolean,
    onPeriodChange: (LocalDate?, LocalDate?) -> Unit,
) {
    val spacing = LocalGilpickSpacing.current

    FormCard(padded = false) {
        Column(modifier = Modifier.padding(horizontal = spacing.space5, vertical = spacing.space4)) {
            CardLabel(text = stringResource(R.string.trip_form_calendar_label), modifier = Modifier.padding(bottom = spacing.space4))
            InlineCalendar(startDate = startDate, endDate = endDate, enabled = enabled, onPeriodChange = onPeriodChange)
        }
        if (startDate != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DIVIDER)
                    .background(MaterialTheme.colorScheme.background),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.space5, vertical = spacing.space3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SummaryValue(label = stringResource(R.string.trip_form_summary_start), date = startDate)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = spacing.space4)
                        .height(DIVIDER)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                SummaryValue(label = stringResource(R.string.trip_form_summary_end), date = endDate)
            }
        }
    }
}

@Composable
private fun SummaryValue(label: String, date: LocalDate?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = LocalGilpickColors.current.muted)
        Text(
            text = date?.format(SUMMARY_DATE) ?: stringResource(R.string.trip_form_summary_none),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 인라인 달력(가이드라인 7절 "날짜 선택").
 *
 * - 선택 흐름: 시작일만 고른 상태에서 다음 탭이 종료일이다. 시작일보다 앞 날짜를 누르면 그 날짜가 새 시작일이 된다.
 *   종료일까지 고른 뒤 다시 누르면 새 시작일부터 다시 고른다.
 * - **터치 영역**: Figma 칸은 40dp 높이지만 10절 48dp를 지키려고 칸 높이를 48dp로 둔다. 보이는 원·버튼은 36dp 그대로이고,
 *   누르는 영역은 칸 자체라 옆 칸과 겹치지 않는다. 360dp 화면에서 7칸의 너비는 40dp 남짓이라 가로 48dp는 확보할 수 없다.
 * - 과거 날짜도 고를 수 있게 둔다. 이미 다녀온 여행을 기록하는 것을 명세가 막지 않는다.
 */
@Composable
private fun InlineCalendar(
    startDate: LocalDate?,
    endDate: LocalDate?,
    enabled: Boolean,
    onPeriodChange: (LocalDate?, LocalDate?) -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    var month by remember { mutableStateOf(YearMonth.from(startDate ?: LocalDate.now())) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = spacing.space4 - MONTH_BUTTON_INSET),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonthButton(description = stringResource(R.string.trip_form_calendar_previous), flipped = true) { month = month.minusMonths(1) }
            Text(
                text = stringResource(R.string.trip_form_calendar_month, month.year, month.monthValue),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            MonthButton(description = stringResource(R.string.trip_form_calendar_next), flipped = false) { month = month.plusMonths(1) }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(bottom = spacing.space1)) {
            WEEK.forEach { day ->
                Text(
                    text = day.getDisplayName(JavaTextStyle.SHORT, Locale.KOREAN),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = spacing.space1),
                )
            }
        }

        // 일요일부터 시작하는 7열. 첫 주 앞 빈칸은 1일의 요일만큼이다.
        val leading = month.atDay(1).dayOfWeek.value % 7
        val cells = List(leading) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        cells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                (week + List(7 - week.size) { null }).forEach { date ->
                    DayCell(
                        date = date,
                        startDate = startDate,
                        endDate = endDate,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        onClick = { picked ->
                            if (startDate == null || endDate != null || picked < startDate) {
                                onPeriodChange(picked, null)
                            } else {
                                onPeriodChange(startDate, picked)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate?,
    startDate: LocalDate?,
    endDate: LocalDate?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: (LocalDate) -> Unit,
) {
    val radius = LocalGilpickRadius.current
    val range = MaterialTheme.colorScheme.primaryContainer

    Box(modifier = modifier.height(DAY_CELL_HEIGHT), contentAlignment = Alignment.Center) {
        // 빈 칸은 보이지 않고 누를 수 없다.
        if (date == null) return@Box

        val isStart = date == startDate
        val isEnd = date == endDate
        val inRange = startDate != null && endDate != null && date > startDate && date < endDate
        val label = stringResource(R.string.trip_form_calendar_day, date.monthValue, date.dayOfMonth)

        when {
            inRange -> Box(modifier = Modifier.matchParentSize().background(range))
            // 시작·종료일 칸은 범위 쪽 절반만 채워 띠가 원에 이어진다.
            isStart && endDate != null && endDate != startDate -> Row(Modifier.matchParentSize()) {
                Box(Modifier.weight(1f))
                Box(Modifier.weight(1f).fillMaxHeight().background(range))
            }
            isEnd && startDate != null && endDate != startDate -> Row(Modifier.matchParentSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().background(range))
                Box(Modifier.weight(1f))
            }
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(enabled = enabled, role = Role.Button) { onClick(date) }
                .semantics {
                    contentDescription = label
                    selected = isStart || isEnd
                },
        )

        val edge = isStart || isEnd
        val shape = if (edge) CircleShape else RoundedCornerShape(radius.sm)
        val shadowed = if (edge) {
            LocalGilpickShadows.current.calendarSelected.fold(Modifier as Modifier) { acc, shadow -> acc.dropShadow(shape, shadow) }
        } else {
            Modifier
        }
        Box(
            modifier = shadowed
                .size(DAY_BUTTON)
                .clip(shape)
                .then(
                    // 36dp 정사각형이라 CSS 135° gradient와 대각선 방향 `linearGradient`가 같다.
                    if (edge) Modifier.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, LocalGilpickColors.current.primaryDark)))
                    else Modifier,
                )
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = when {
                    edge -> MaterialTheme.colorScheme.onPrimary
                    inRange -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

/** 달력 월 이동 버튼: 보이는 32dp `background`(`radiusSm`), 터치 48dp. */
@Composable
private fun MonthButton(description: String, flipped: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick, role = Role.Button)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(MONTH_BUTTON)
                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(LocalGilpickRadius.current.sm)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(MONTH_ICON)
                    .then(if (flipped) Modifier.rotate(180f) else Modifier),
            )
        }
    }
}

/**
 * 여행 수정 기간: 두 칸 날짜 표시(가이드라인 7절). 칸을 누르면 같은 카드 안에 인라인 달력이 펼쳐진다(#443 결정).
 *
 * 원래 기간보다 줄어든 쪽 칸은 `errorContainer`·`error`로 바꾸고 아래에 경고 문구를 둔다. 실제 삭제 수는 저장할 때
 * 서버가 알려 주고 확인 대화상자가 동의를 받는다(FR-012). 완료된 여행은 기간을 바꿀 수 없어 누를 수 없고 이유를 적는다.
 */
@Composable
private fun EditPeriodCard(
    state: TripFormUiState,
    enabled: Boolean,
    onPeriodChange: (LocalDate?, LocalDate?) -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val lockedState = stringResource(R.string.trip_form_period_locked_state)

    FormCard {
        CardLabel(text = stringResource(R.string.trip_form_period_label), modifier = Modifier.padding(bottom = spacing.space3))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (state.periodLocked) Modifier.semantics { stateDescription = lockedState } else Modifier),
            horizontalArrangement = Arrangement.spacedBy(spacing.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DateBox(
                date = state.startDate,
                shrunk = state.startShrunk,
                enabled = enabled,
                description = stringResource(R.string.trip_form_start_date),
                onClick = { expanded = !expanded },
                modifier = Modifier.weight(1f),
            )
            Text(text = stringResource(R.string.trip_form_period_dash), color = colors.muted, style = MaterialTheme.typography.labelMedium)
            DateBox(
                date = state.endDate,
                shrunk = state.endShrunk,
                enabled = enabled,
                description = stringResource(R.string.trip_form_end_date),
                onClick = { expanded = !expanded },
                modifier = Modifier.weight(1f),
            )
        }
        when {
            state.endShrunk -> ShrinkWarning(R.string.trip_form_shrink_warning_end)
            state.startShrunk -> ShrinkWarning(R.string.trip_form_shrink_warning_start)
        }
        if (state.periodLocked) {
            // 색과 흐린 스타일만으로는 이유를 알 수 없다. 문구를 함께 둔다(10절).
            Text(
                text = stringResource(R.string.trip_form_period_locked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.space2),
            )
        }
        if (expanded && enabled) {
            Box(modifier = Modifier.padding(top = spacing.space4)) {
                InlineCalendar(
                    startDate = state.startDate,
                    endDate = state.endDate,
                    enabled = enabled,
                    onPeriodChange = onPeriodChange,
                )
            }
        }
    }
}

@Composable
private fun DateBox(
    date: LocalDate?,
    shrunk: Boolean,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (shrunk) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .heightIn(min = DATE_BOX_HEIGHT)
            .clip(RoundedCornerShape(LocalGilpickRadius.current.md))
            .background(if (shrunk) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.background)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
            .padding(horizontal = LocalGilpickSpacing.current.space4),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = date?.format(EDIT_DATE) ?: stringResource(R.string.trip_form_summary_none),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = tint,
        )
        Icon(
            painter = painterResource(R.drawable.ic_lucide_calendar),
            contentDescription = null,
            tint = if (shrunk) MaterialTheme.colorScheme.error else LocalGilpickColors.current.muted,
            modifier = Modifier.size(DATE_ICON),
        )
    }
}

@Composable
private fun ShrinkWarning(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodySmall,
        color = LocalGilpickColors.current.warning,
        modifier = Modifier.padding(top = LocalGilpickSpacing.current.space2),
    )
}

@Composable
private fun PeriodErrorText(error: TripPeriodError) {
    Text(
        text = stringResource(
            when (error) {
                TripPeriodError.NOT_SELECTED -> R.string.trip_form_error_period_required
                TripPeriodError.END_BEFORE_START -> R.string.trip_form_error_period_order
                TripPeriodError.TOO_LONG -> R.string.trip_form_error_period_long
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * 하단 고정 행동: 제출 [GradientButton](54dp, 폭을 채움 16dp)과 수정 화면의 `여행 삭제`.
 *
 * 입력이 부족하면 비활성(D2)이고, 흰 글자 대비가 낮아 버튼 위에 **아직 화면에 보이지 않는** 이유를 한 문장 둔다(7절).
 * 이미 입력창·달력 아래에 오류가 보이면 같은 문장을 반복하지 않는다. 전송 중은 비활성이 아니라 처리 중이다.
 */
@Composable
private fun BottomActions(
    state: TripFormUiState,
    validation: TripFormValidation,
    onSubmit: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val editing = state.mode is FormMode.Edit
    val reason = when {
        state.submitting || validation.isValid -> null
        validation.nameError != null && state.name.isEmpty() -> R.string.trip_form_error_name_short
        validation.periodError == TripPeriodError.NOT_SELECTED -> R.string.trip_form_error_period_required
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(start = spacing.space5, end = spacing.space5, top = spacing.space3, bottom = spacing.space8),
        verticalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        reason?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = LocalGilpickColors.current.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        GradientButton(
            label = stringResource(
                when {
                    state.submitting && editing -> R.string.trip_form_edit_submitting
                    state.submitting -> R.string.trip_form_submitting
                    editing -> R.string.trip_form_edit_submit
                    else -> R.string.trip_form_submit
                },
            ),
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
            width = GradientButtonWidth.Standalone,
            height = SUBMIT_HEIGHT,
            processing = state.submitting,
            enabled = validation.isValid,
        )
        if (editing) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MIN_TOUCH)
                    .clip(RoundedCornerShape(LocalGilpickRadius.current.lg))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable(enabled = !state.submitting, role = Role.Button, onClick = onRequestDelete),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.trip_detail_delete),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 수정할 여행을 조회하는 동안의 표시. 1초를 넘길 때만 띄운다(가이드라인 9절). */
@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    val label = stringResource(R.string.trip_form_loading)

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(LOADING_INDICATOR_DELAY_MILLIS)
        visible = true
    }

    if (visible) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.clearAndSetSemantics { contentDescription = label },
            )
        }
    }
}

/**
 * 기간 축소로 삭제될 일정에 동의를 받는 대화상자(Figma `EditTripScreen` 저장 확인).
 *
 * `AlertDialog`가 아니라 [BasicAlertDialog]를 쓰는 이유는 여행 삭제 대화상자와 같다. 구조는 유지하고 주버튼을
 * [GradientButton]으로, 창에 dialog 그림자를 준다(#443). 되돌릴 수 없는 삭제라 경고 색을 쓰지만 제목이 삭제될
 * 장소 수를 말하고 본문이 복구 불가를 적어 색만으로 알리지 않는다(가이드라인 10절).
 *
 * @param deletedItemCount 새 기간 밖으로 밀려나 삭제될 장소 수. 서버가 알려 준 값이다.
 * @param submitting 동의 후 재요청이 진행 중인지.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShrinkConfirmDialog(
    deletedItemCount: Int,
    submitting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current

    BasicAlertDialog(
        // 요청을 보낸 사이에 닫히면 결과를 전달할 화면이 사라진다.
        onDismissRequest = { if (!submitting) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !submitting,
            dismissOnClickOutside = !submitting,
            usePlatformDefaultWidth = false,
        ),
        modifier = Modifier.padding(horizontal = spacing.space6),
    ) {
        val shape = RoundedCornerShape(radius.xl)
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            modifier = LocalGilpickShadows.current.dialog.fold(Modifier as Modifier) { acc, shadow -> acc.dropShadow(shape, shadow) },
        ) {
            Column(modifier = Modifier.padding(spacing.space6)) {
                Box(
                    modifier = Modifier
                        .size(DIALOG_ICON_BOX)
                        .background(colors.warningContainer, RoundedCornerShape(radius.lg)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_triangle_alert),
                        // 바로 아래 제목이 같은 뜻을 말한다(가이드라인 10절).
                        contentDescription = null,
                        tint = colors.warning,
                        modifier = Modifier.size(DIALOG_ICON),
                    )
                }
                Text(
                    text = stringResource(R.string.trip_form_shrink_title, deletedItemCount),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = spacing.space4, bottom = spacing.space2),
                )
                Text(
                    text = stringResource(R.string.trip_form_shrink_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.trip_form_shrink_question),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = spacing.space6),
                )
                GradientButton(
                    label = stringResource(if (submitting) R.string.trip_form_shrink_progress else R.string.trip_form_shrink_confirm),
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth(),
                    width = GradientButtonWidth.Standalone,
                    height = DIALOG_BUTTON_HEIGHT,
                    processing = submitting,
                )
                TextButton(
                    onClick = onDismiss,
                    enabled = !submitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.space2)
                        .heightIn(min = MIN_TOUCH),
                ) {
                    Text(
                        text = stringResource(R.string.trip_form_shrink_cancel),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 전송 실패 원인과 다음 행동. "오류가 발생했습니다"로 끝내지 않는다. */
@Composable
private fun SubmitError(error: TripFormSubmitError) {
    Text(
        text = when (error) {
            TripFormSubmitError.NETWORK -> stringResource(R.string.trip_form_error_network)
            TripFormSubmitError.INVALID_INPUT -> stringResource(R.string.trip_form_error_invalid)
            TripFormSubmitError.VERSION_CONFLICT ->
                stringResource(R.string.trip_form_error_version_conflict)

            TripFormSubmitError.TRIP_LOCKED -> stringResource(R.string.trip_form_error_trip_locked)
            TripFormSubmitError.CONFIRMATION_REQUIRED ->
                stringResource(R.string.trip_form_error_confirmation_required)

            TripFormSubmitError.UNEXPECTED -> stringResource(R.string.trip_form_error_unexpected)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

/** 일요일부터 시작하는 요일 순서(Figma 달력 머리). */
private val WEEK = listOf(DayOfWeek.SUNDAY) + DayOfWeek.entries.filter { it != DayOfWeek.SUNDAY }

private val SUMMARY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d")
private val EDIT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy. M. d")

/** 가이드라인 10절: 터치 영역 48dp 이상. */
private val MIN_TOUCH = 48.dp

/** 가이드라인 7절 헤더 버튼(36dp, 아이콘 18). 48dp 터치 영역 안 가운데라 차이만큼 바깥 여백을 줄인다. */
private val HEADER_BUTTON = 36.dp
private val HEADER_ICON = 18.dp
private val HEADER_BUTTON_INSET = 6.dp

/** 가이드라인 7절 폼 입력창: 50dp, 2dp 테두리, 16sp. */
private val FIELD_HEIGHT = 50.dp
private val FIELD_BORDER = 2.dp
private val FIELD_TEXT_SIZE = 16.sp

/** 가이드라인 7절 날짜 선택: 보이는 날짜 버튼 36, 칸 높이(터치 48, 인라인 달력 KDoc), 월 이동 버튼 32·아이콘 14. */
private val DAY_BUTTON = 36.dp
private val DAY_CELL_HEIGHT = 48.dp
private val MONTH_BUTTON = 32.dp
private val MONTH_ICON = 14.dp
private val MONTH_BUTTON_INSET = 8.dp
private val DIVIDER = 1.dp

/** 가이드라인 7절 수정 화면 날짜 칸(48dp)·달력 아이콘(14). */
private val DATE_BOX_HEIGHT = 48.dp
private val DATE_ICON = 14.dp

/** Figma 제출 버튼(`h-[54px]`)과 확인 대화상자 아이콘 상자·아이콘·주버튼. */
private val SUBMIT_HEIGHT = 54.dp
private val DIALOG_ICON_BOX = 48.dp
private val DIALOG_ICON = 22.dp
private val DIALOG_BUTTON_HEIGHT = 52.dp

@Preview(showBackground = true)
@Composable
private fun TripFormScreenPreview() {
    GilpickTheme {
        TripFormScreen(
            state = TripFormUiState(name = "제주도 여행", startDate = LocalDate.of(2026, 9, 1), endDate = LocalDate.of(2026, 9, 5)),
            onNameChange = {},
            onPeriodChange = { _, _ -> },
            onSubmit = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TripFormScreenEditShrunkPreview() {
    GilpickTheme {
        TripFormScreen(
            state = TripFormUiState(
                name = "제주도 여행",
                startDate = LocalDate.of(2026, 9, 1),
                endDate = LocalDate.of(2026, 9, 3),
                originalStartDate = LocalDate.of(2026, 9, 1),
                originalEndDate = LocalDate.of(2026, 9, 5),
                mode = FormMode.Edit(tripId = "t1", version = 1, status = TripStatus.UPCOMING),
            ),
            onNameChange = {},
            onPeriodChange = { _, _ -> },
            onSubmit = {},
        )
    }
}

/** 가이드라인 9절: 1초를 넘길 때만 대기 표시를 띄운다. */
private const val LOADING_INDICATOR_DELAY_MILLIS = 1_000L
