package com.gilpick.trip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.gilpick.R
import com.gilpick.ui.theme.GilpickTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 여행 생성 화면.
 *
 * 이름과 기간만 받는 단일 폼이다. 검증 오류는 입력 도중이 아니라 제출을 한 번 시도한
 * 뒤부터 해당 입력 바로 아래에 원인과 함께 표시한다.
 *
 * 색상·간격·곡률은 `com.gilpick.ui.theme` 토큰에서 읽는다. 값의 기준은
 * `docs/design/ui-guidelines.md`다.
 *
 * @param state 현재 폼 상태.
 * @param onNameChange 여행명 입력을 반영한다.
 * @param onPeriodChange 고른 기간을 반영한다.
 * @param onSubmit 여행 생성을 요청한다.
 */
@Composable
fun TripFormScreen(
    state: TripFormUiState,
    onNameChange: (String) -> Unit,
    onPeriodChange: (LocalDate?, LocalDate?) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = com.gilpick.ui.theme.LocalGilpickSpacing.current
    var showPeriodPicker by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.space5, vertical = spacing.space6),
        verticalArrangement = Arrangement.spacedBy(spacing.space5),
    ) {
        Text(
            text = stringResource(R.string.trip_form_create_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        NameField(
            value = state.name,
            error = state.visibleNameError,
            enabled = !state.submitting,
            onValueChange = onNameChange,
        )

        PeriodField(
            startDate = state.startDate,
            endDate = state.endDate,
            error = state.visiblePeriodError,
            enabled = !state.submitting,
            onClick = { showPeriodPicker = true },
        )

        state.submitError?.let { SubmitError(it) }

        SubmitButton(submitting = state.submitting, onClick = onSubmit)
    }

    if (showPeriodPicker) {
        PeriodPickerDialog(
            startDate = state.startDate,
            endDate = state.endDate,
            onDismiss = { showPeriodPicker = false },
            onConfirm = { start, end ->
                showPeriodPicker = false
                onPeriodChange(start, end)
            },
        )
    }
}

/** 여행명 입력. 라벨은 placeholder로 대체하지 않고 항상 보이게 둔다. */
@Composable
private fun NameField(
    value: String,
    error: TripNameError?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(stringResource(R.string.trip_form_name_label)) },
        isError = error != null,
        singleLine = true,
        supportingText = {
            Text(
                text = when (error) {
                    TripNameError.TOO_SHORT -> stringResource(R.string.trip_form_error_name_short)
                    TripNameError.TOO_LONG -> stringResource(R.string.trip_form_error_name_long)
                    // 오류가 없을 때는 남은 글자 수를 안내해 30자 제한을 미리 알린다.
                    null -> stringResource(R.string.trip_form_name_counter, value.trim().length)
                },
                style = MaterialTheme.typography.labelMedium,
            )
        },
    )
}

/**
 * 여행 기간 입력.
 *
 * 값을 직접 타이핑하지 않고 달력에서 고르므로 버튼으로 둔다. 고른 값이 없어도 무엇을
 * 고르는 자리인지 알 수 있도록 라벨을 항상 위에 표시한다.
 */
@Composable
private fun PeriodField(
    startDate: LocalDate?,
    endDate: LocalDate?,
    error: TripPeriodError?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val spacing = com.gilpick.ui.theme.LocalGilpickSpacing.current
    val radius = com.gilpick.ui.theme.LocalGilpickRadius.current

    Column(verticalArrangement = Arrangement.spacedBy(spacing.space2)) {
        Text(
            text = stringResource(R.string.trip_form_period_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FIELD_HEIGHT),
            shape = RoundedCornerShape(radius.sm),
        ) {
            Text(
                text = periodLabel(startDate, endDate),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        error?.let {
            Text(
                text = when (it) {
                    TripPeriodError.NOT_SELECTED ->
                        stringResource(R.string.trip_form_error_period_required)

                    TripPeriodError.END_BEFORE_START ->
                        stringResource(R.string.trip_form_error_period_order)

                    TripPeriodError.TOO_LONG ->
                        stringResource(R.string.trip_form_error_period_long)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 고른 기간을 `시작 ~ 종료 (N일)` 형태로 읽어준다. 아직 고르지 않았으면 안내 문구를 쓴다. */
@Composable
private fun periodLabel(startDate: LocalDate?, endDate: LocalDate?): String {
    if (startDate == null || endDate == null) {
        return stringResource(R.string.trip_form_period_empty)
    }
    val range = stringResource(
        R.string.trip_form_period_value,
        startDate.format(DISPLAY_DATE),
        endDate.format(DISPLAY_DATE),
    )
    val days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1
    // 기간이 뒤집힌 입력에서는 일수가 의미 없으므로 범위만 보여준다.
    return if (days > 0) "$range · ${stringResource(R.string.trip_form_period_days, days.toInt())}"
    else range
}

/** 전송 실패 원인과 다음 행동. "오류가 발생했습니다"로 끝내지 않는다. */
@Composable
private fun SubmitError(error: TripFormSubmitError) {
    Text(
        text = when (error) {
            TripFormSubmitError.NETWORK -> stringResource(R.string.trip_form_error_network)
            TripFormSubmitError.INVALID_INPUT -> stringResource(R.string.trip_form_error_invalid)
            TripFormSubmitError.UNEXPECTED -> stringResource(R.string.trip_form_error_unexpected)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * 주요 CTA.
 *
 * 전송 중에는 비활성화해 중복 제출을 막고, 진행 중임을 TalkBack에도 알린다.
 */
@Composable
private fun SubmitButton(submitting: Boolean, onClick: () -> Unit) {
    val radius = com.gilpick.ui.theme.LocalGilpickRadius.current
    val submittingLabel = stringResource(R.string.trip_form_submitting)

    Button(
        onClick = onClick,
        enabled = !submitting,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PRIMARY_BUTTON_HEIGHT),
        shape = RoundedCornerShape(radius.md),
    ) {
        if (submitting) {
            CircularProgressIndicator(
                modifier = Modifier
                    .heightIn(min = PROGRESS_SIZE)
                    .clearAndSetSemantics { contentDescription = submittingLabel },
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(
                text = stringResource(R.string.trip_form_submit),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 시작일과 종료일을 한 번에 고르는 달력.
 *
 * 과거 날짜도 고를 수 있게 둔다. 이미 다녀온 여행을 기록하는 것을 명세가 막지 않는다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodPickerDialog(
    startDate: LocalDate?,
    endDate: LocalDate?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate?, LocalDate?) -> Unit,
) {
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = startDate?.toUtcMillis(),
        initialSelectedEndDateMillis = endDate?.toUtcMillis(),
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        pickerState.selectedStartDateMillis?.toLocalDate(),
                        pickerState.selectedEndDateMillis?.toLocalDate(),
                    )
                },
            ) {
                Text(stringResource(R.string.trip_form_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.trip_form_cancel))
            }
        },
    ) {
        DateRangePicker(
            state = pickerState,
            modifier = Modifier.heightIn(max = PICKER_MAX_HEIGHT),
            showModeToggle = false,
        )
    }
}

/**
 * 제출을 시도하기 전에는 검증 오류를 숨긴다.
 *
 * 입력을 시작하자마자 빨간 글씨가 뜨면 아직 다 쓰지도 않은 사용자를 재촉하게 된다.
 */
private val TripFormUiState.visibleNameError: TripNameError?
    get() = validation.nameError.takeIf { showErrors }

private val TripFormUiState.visiblePeriodError: TripPeriodError?
    get() = validation.periodError.takeIf { showErrors }

/** 달력이 UTC 자정 기준 millis를 쓰므로 표시용 날짜와 그 기준으로 변환한다. */
private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

private val DISPLAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")

/** 가이드라인 5절: 주요 CTA 52~56dp, 입력 컨트롤은 터치 영역 48dp 이상. */
private val PRIMARY_BUTTON_HEIGHT = Dp(56f)
private val FIELD_HEIGHT = Dp(56f)
private val PROGRESS_SIZE = Dp(24f)

/**
 * 달력 다이얼로그가 작은 화면을 넘지 않도록 하는 상한.
 *
 * spacing·radius 토큰(최대 24dp)으로 표현할 수 없는 일회성 값이라 리터럴을 쓴다
 * (가이드라인 11절 예외). DateRangePicker는 내부에서 스크롤하므로 이 상한이 달력을
 * 자르지 않는다. API 26 기기에서 시스템 글자 크기 1.0과 1.3 모두 확인·취소 버튼에
 * 도달할 수 있음을 확인했다.
 */
private val PICKER_MAX_HEIGHT = Dp(480f)

@Preview(showBackground = true)
@Composable
private fun TripFormScreenPreview() {
    GilpickTheme {
        TripFormScreen(
            state = TripFormUiState(
                name = "제주도 여행",
                startDate = LocalDate.of(2026, 9, 1),
                endDate = LocalDate.of(2026, 9, 5)
            ),
            onNameChange = {},
            onPeriodChange = { _, _ -> },
            onSubmit = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TripFormScreenErrorPreview() {
    GilpickTheme {
        TripFormScreen(
            state = TripFormUiState(
                name = "A",
                startDate = LocalDate.of(2026, 9, 1),
                endDate = LocalDate.of(2026, 8, 30),
                showErrors = true,
                validation = TripFormValidation(
                    nameError = TripNameError.TOO_SHORT,
                    periodError = TripPeriodError.END_BEFORE_START
                )
            ),
            onNameChange = {},
            onPeriodChange = { _, _ -> },
            onSubmit = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TripFormScreenSubmittingPreview() {
    GilpickTheme {
        TripFormScreen(
            state = TripFormUiState(
                name = "제주도 여행",
                startDate = LocalDate.of(2026, 9, 1),
                endDate = LocalDate.of(2026, 9, 5),
                submitting = true
            ),
            onNameChange = {},
            onPeriodChange = { _, _ -> },
            onSubmit = {}
        )
    }
}
