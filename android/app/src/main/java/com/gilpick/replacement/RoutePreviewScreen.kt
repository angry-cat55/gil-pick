package com.gilpick.replacement

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gilpick.R
import com.gilpick.alternative.clockLabel
import com.gilpick.progress.StateMessage
import com.gilpick.route.RouteMap
import com.gilpick.route.distanceLabel
import com.gilpick.route.durationLabel
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.gilpick.ui.theme.displayFont
import kotlinx.coroutines.delay

/**
 * 변경 경로 미리보기 화면(`spec.md` US1, Figma `RoutePreviewScreen`, T016).
 *
 * 위쪽은 지도로 기존 경로와 변경 경로를 함께 그리고, 아래쪽은 무엇이 무엇으로 바뀌는지·왜
 * 바꾸는지와 비교 네 항목이다. `변경 승인`은 T023(#349)이 연결하며 이 화면은 자리만 만든다.
 *
 * 색만으로 알리지 않는다(UI-001·UI-002). 지도 범례는 `기존`·`변경` **문구**를 함께 두고, 비교
 * 항목의 나아짐·나빠짐은 색과 함께 기호(`↓`·`↑`)와 접근성 문구로 알린다. 확인하지 못한 값은
 * 지어내지 않고 `정보 없음`으로 둔다(FR-002).
 *
 * `empty` 상태는 없다. 후보가 없는 상황은 F009가 처리한다(UI-004).
 *
 * @param onBack 뒤로 가기. 후보 목록으로 돌아간다.
 * @param onRetry `error`의 `다시 시도하기`. 같은 요청을 같은 `Idempotency-Key`로 다시 보낸다.
 * @param onApprove `변경 승인`. T023이 실제 승인에 연결한다.
 * @param onOtherCandidates `다른 후보 보기`. 미리보기를 폐기하고 F009 후보 목록으로 돌아간다(UI-003).
 * @param onReauthenticate 로그인 상태가 만료됐다. F001 재인증 흐름으로 넘어간다.
 * @param map 지도 영역. 기본은 F005 [RouteMap]이며 UI test·screenshot은 자리 표시로 바꿔 끼운다.
 */
@Composable
fun RoutePreviewScreen(
    state: PreviewUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onApprove: () -> Unit,
    onOtherCandidates: () -> Unit,
    onReauthenticate: () -> Unit,
    modifier: Modifier = Modifier,
    map: @Composable (PreviewUiState.Content, Modifier) -> Unit = { content, mapModifier ->
        PreviewMap(content = content, modifier = mapModifier)
    },
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Header(onBack = onBack, enabled = !state.isApproving)
        when (state) {
            PreviewUiState.Loading -> DelayedLoading()
            is PreviewUiState.Error -> ErrorState(
                error = state.error,
                onRetry = onRetry,
                onOtherCandidates = onOtherCandidates,
                onReauthenticate = onReauthenticate,
            )

            is PreviewUiState.Content -> ContentState(
                content = state,
                onApprove = onApprove,
                onRecreate = onRetry,
                onOtherCandidates = onOtherCandidates,
                map = map,
            )
        }
    }
}

/** 상단 바. Figma의 뒤로 버튼과 `경로 비교` 제목이다. */
@Composable
private fun Header(onBack: () -> Unit, enabled: Boolean = true) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .padding(horizontal = spacing.space5, vertical = spacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space3),
    ) {
        Box(
            modifier = Modifier
                .size(MIN_TOUCH)
                .clip(RoundedCornerShape(radius.md))
                .clickable(enabled = enabled, onClick = onBack)
                .testTag(TAG_BACK),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_arrow_left),
                contentDescription = stringResource(R.string.replacement_back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        val title = stringResource(R.string.replacement_preview_title)
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = title.displayFont(),
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 비교를 만드는 동안의 대기 표시. 1초를 넘길 때만 보인다(UI-004, 가이드라인 9절). */
@Composable
private fun DelayedLoading() {
    var visible by remember { mutableStateOf(false) }
    val label = stringResource(R.string.replacement_loading)

    LaunchedEffect(Unit) {
        delay(LOADING_INDICATOR_DELAY_MILLIS)
        visible = true
    }

    if (visible) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.clearAndSetSemantics { contentDescription = label })
        }
    }
}

/**
 * 미리보기 생성 실패(UI-004).
 *
 * 원인을 문구로 알리고 `다시 시도`와 `다른 후보 보기`를 함께 준다. **기존 일정이 그대로임을**
 * 반드시 함께 알린다 — 사용자가 일정이 망가졌다고 오해하지 않게 하는 것이 이 화면의 핵심이다(FR-007).
 */
@Composable
private fun ErrorState(
    error: ReplacementError,
    onRetry: () -> Unit,
    onOtherCandidates: () -> Unit,
    onReauthenticate: () -> Unit,
) {
    StateMessage(
        title = stringResource(R.string.replacement_error_title),
        body = stringResource(error.messageRes) + "\n" + stringResource(R.string.replacement_schedule_kept),
        icon = R.drawable.ic_lucide_circle_x,
    ) {
        if (error == ReplacementError.SessionExpired) {
            Button(
                onClick = onReauthenticate,
                modifier = Modifier.heightIn(min = MIN_TOUCH),
            ) {
                Text(stringResource(R.string.place_reauthenticate))
            }
        } else {
            Button(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = MIN_TOUCH).testTag(TAG_RETRY),
            ) {
                Text(stringResource(R.string.replacement_retry))
            }
        }
        TextButton(
            onClick = onOtherCandidates,
            modifier = Modifier.heightIn(min = MIN_TOUCH).testTag(TAG_OTHER_CANDIDATES),
        ) {
            Text(stringResource(R.string.replacement_other_candidates))
        }
    }
}

/** 비교 본문: 지도, 무엇이 무엇으로 바뀌는지, 비교 네 항목, 두 행동. */
@Composable
private fun ContentState(
    content: PreviewUiState.Content,
    onApprove: () -> Unit,
    onRecreate: () -> Unit,
    onOtherCandidates: () -> Unit,
    map: @Composable (PreviewUiState.Content, Modifier) -> Unit,
) {
    val spacing = LocalGilpickSpacing.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(MAP_HEIGHT)
                .padding(horizontal = spacing.space4)
                .testTag(TAG_MAP_SLOT),
        ) {
            map(content, Modifier.fillMaxSize())
            Legend(modifier = Modifier.align(Alignment.TopEnd).padding(spacing.space3))
        }
        Summary(content = content, modifier = Modifier.padding(spacing.space4))
        content.approveFailure?.let { failure ->
            ApproveFailure(
                failure = failure,
                placeName = content.preview.alternativePlace.name,
                modifier = Modifier.padding(horizontal = spacing.space4),
            )
        }
        Spacer(modifier = Modifier.height(spacing.space4))
        Actions(
            content = content,
            onApprove = onApprove,
            onRecreate = onRecreate,
            onOtherCandidates = onOtherCandidates,
        )
    }
}

/**
 * 지도 범례(UI-001).
 *
 * 색만으로 기존·변경을 구분하지 않도록 **문구를 함께** 둔다. 지도를 그리지 못하는 기기에서도
 * 이 문구가 남아 어느 쪽이 무엇인지 알 수 있다.
 */
@Composable
private fun Legend(modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        LegendItem(label = stringResource(R.string.replacement_legend_original), swatch = colors.faint, radius = radius.sm)
        LegendItem(label = stringResource(R.string.replacement_legend_changed), swatch = MaterialTheme.colorScheme.primary, radius = radius.sm)
    }
}

@Composable
private fun LegendItem(label: String, swatch: Color, radius: Dp) {
    val spacing = LocalGilpickSpacing.current

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(radius))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = spacing.space2, vertical = spacing.space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space1),
    ) {
        Box(
            modifier = Modifier
                .width(LEGEND_SWATCH_WIDTH)
                .height(LEGEND_SWATCH_HEIGHT)
                .background(swatch, CircleShape),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 무엇이 무엇으로 바뀌는지, 왜 바꾸는지, 그리고 비교 네 항목(UI-002). */
@Composable
private fun Summary(content: PreviewUiState.Content, modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val preview = content.preview

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius.lg))
            .background(MaterialTheme.colorScheme.surface)
            .padding(spacing.space5),
        verticalArrangement = Arrangement.spacedBy(spacing.space1),
    ) {
        Text(
            text = stringResource(R.string.replacement_change_badge),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Black,
            color = colors.warning,
            modifier = Modifier
                .clip(RoundedCornerShape(radius.sm))
                .background(colors.warningContainer)
                .padding(horizontal = spacing.space2, vertical = spacing.space1),
        )
        val summary = stringResource(
            R.string.replacement_change_summary,
            preview.originalPlace.name,
            preview.alternativePlace.name,
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = summary.displayFont(),
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag(TAG_CHANGE_SUMMARY),
        )
        // 왜 바꾸는지. F008 감지 결과의 reason을 그대로 보인다(UI-002).
        Text(
            text = preview.detectionReason,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            modifier = Modifier.testTag(TAG_REASON),
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.padding(vertical = spacing.space3),
        )

        val duration = preview.comparison.totalDurationSeconds
        ComparisonRow(
            label = stringResource(R.string.replacement_row_duration),
            before = duration.before?.let { durationLabel(it) },
            after = duration.after?.let { durationLabel(it) },
            better = isBetter(duration.before, duration.after),
        )
        val distance = preview.comparison.totalDistanceMeters
        ComparisonRow(
            label = stringResource(R.string.replacement_row_distance),
            before = distance.before?.let { distanceLabel(it) },
            after = distance.after?.let { distanceLabel(it) },
            better = isBetter(distance.before, distance.after),
        )
        ComparisonRow(
            label = stringResource(R.string.replacement_row_arrival),
            before = preview.comparison.estimatedArrivalAt.before?.let(::clockLabel),
            after = preview.comparison.estimatedArrivalAt.after?.let(::clockLabel),
            better = null,
        )
        ComparisonRow(
            label = stringResource(R.string.replacement_row_closes),
            before = preview.comparison.closesAt.before?.let(::clockLabel),
            after = preview.comparison.closesAt.after?.let(::clockLabel),
            better = null,
        )
    }
}

/**
 * 비교 한 줄(UI-002·FR-002).
 *
 * 확인하지 못한 값은 `정보 없음`이다. 값이 아니므로 나아짐·나빠짐 색과 기호를 쓰지 않는다.
 * 나아짐·나빠짐은 색과 함께 기호로 알리고, 접근성 문구로 항목명·이전 값·이후 값을 한 번에 읽힌다.
 *
 * @param better 나아졌는지. 판단 근거가 없거나 값이 없으면 `null`이며 강조하지 않는다.
 */
@Composable
private fun ComparisonRow(label: String, before: String?, after: String?, better: Boolean?) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val unknown = stringResource(R.string.replacement_value_unknown)
    val beforeText = before ?: unknown
    val afterText = after ?: unknown
    val missing = after == null
    val accent = when {
        missing || better == null -> colors.muted
        better -> colors.success
        else -> colors.warning
    }
    val mark = when {
        missing || better == null -> ""
        better -> BETTER_MARK
        else -> WORSE_MARK
    }
    val verdict = when {
        missing || better == null -> ""
        better -> " " + stringResource(R.string.replacement_better)
        else -> " " + stringResource(R.string.replacement_worse)
    }
    val description = stringResource(R.string.replacement_row_description, label, beforeText, afterText) + verdict

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            modifier = Modifier.width(ROW_LABEL_WIDTH),
        )
        Text(
            text = beforeText,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.faint,
            // 이전 값에만 취소선을 둔다. `정보 없음`은 값이 아니므로 긋지 않는다.
            textDecoration = if (before == null) TextDecoration.None else TextDecoration.LineThrough,
        )
        Text(
            text = afterText + mark,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
    }
}

/**
 * 아래쪽 두 행동(UI-003·UI-005).
 *
 * 평소에는 `변경 승인` + `다른 후보 보기`다. 승인 중에는 `변경 승인`이 진행 표시로 바뀌고 두 행동이
 * 모두 잠긴다. 승인이 실패하면 `변경 승인` 자리를 **원인별 다음 행동**이 대신한다(Figma 승인 실패
 * 상태). `다른 후보 보기`는 실패 상태에서도 계속 보인다.
 *
 * 둘 다 48dp 이상이고 8dp 이상 떨어진다(UI-008).
 */
@Composable
private fun Actions(
    content: PreviewUiState.Content,
    onApprove: () -> Unit,
    onRecreate: () -> Unit,
    onOtherCandidates: () -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val action = content.approveFailure?.approveAction

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.space4)
            .padding(bottom = spacing.space8),
        verticalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        Button(
            onClick = when (action) {
                ApproveAction.Recreate -> onRecreate
                ApproveAction.Candidates -> onOtherCandidates
                ApproveAction.Retry, null -> onApprove
            },
            enabled = !content.approving,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PRIMARY_BUTTON_HEIGHT)
                .testTag(TAG_APPROVE),
        ) {
            if (content.approving) {
                CircularProgressIndicator(
                    strokeWidth = SPINNER_STROKE,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(SPINNER_SIZE),
                )
                Spacer(modifier = Modifier.width(spacing.space2))
            }
            Text(
                stringResource(
                    when {
                        content.approving -> R.string.replacement_approving
                        action == ApproveAction.Recreate -> R.string.replacement_action_recreate
                        action == ApproveAction.Candidates -> R.string.replacement_action_candidates
                        action == ApproveAction.Retry -> R.string.replacement_action_retry
                        else -> R.string.replacement_approve
                    },
                ),
            )
        }
        OutlinedButton(
            onClick = onOtherCandidates,
            enabled = !content.approving,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MIN_TOUCH)
                .testTag(TAG_OTHER_CANDIDATES),
        ) {
            Text(stringResource(R.string.replacement_other_candidates))
        }
    }
}

/**
 * 승인 실패 안내(UI-005).
 *
 * 원인 문구와 **기존 일정이 그대로임**을 함께 알린다. 색만으로 알리지 않도록 경고 아이콘과 문구를
 * 함께 둔다(가이드라인 10절). 다음 행동은 [Actions]가 원인에 맞춰 보인다.
 *
 * @param placeName 대체 장소 이름. `방문할 수 없음` 안내에 어떤 장소인지 넣는다.
 */
@Composable
private fun ApproveFailure(failure: ReplacementError, placeName: String, modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val message = if (failure == ReplacementError.AlternativeUnavailable) {
        stringResource(R.string.replacement_error_alternative_unavailable, placeName)
    } else {
        stringResource(failure.messageRes)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius.lg))
            .background(colors.warningContainer)
            .padding(spacing.space4)
            .testTag(TAG_APPROVE_FAILURE),
        horizontalArrangement = Arrangement.spacedBy(spacing.space3),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lucide_triangle_alert),
            contentDescription = null,
            tint = colors.warning,
            modifier = Modifier.size(FAILURE_ICON),
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.space1)) {
            Text(
                text = stringResource(R.string.replacement_approve_failed),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.replacement_schedule_kept),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
    }
}

/**
 * 기존 경로와 변경 경로를 함께 그리는 지도(UI-001).
 *
 * F005 [RouteMap]을 그대로 쓰고 기존 경로만 `baseRoute`로 넘긴다. 기존 경로를 받지 못했으면
 * 변경 경로 하나만 그린다. 어느 쪽이 기존인지는 위의 [Legend]가 문구로 알린다.
 */
@Composable
internal fun PreviewMap(content: PreviewUiState.Content, modifier: Modifier = Modifier) {
    RouteMap(
        route = content.preview.route,
        baseRoute = content.originalRoute,
        modifier = modifier,
        // 이 화면은 지도 아래를 sheet가 덮지 않는다. 지도 높이 안에서 두 경로가 모두 보이면 된다.
        sheetFraction = 0f,
    )
}

/**
 * 승인 실패 뒤 사용자가 할 수 있는 다음 행동(UI-005, quickstart FE 4).
 *
 * 원인마다 달라야 한다(SC-007). 이 화면에서 바로 고칠 수 있으면 [Recreate], 이 후보로는 더 이상
 * 안 되면 [Candidates], 같은 요청을 그대로 다시 보내면 되면 [Retry]다.
 */
private enum class ApproveAction { Recreate, Candidates, Retry }

/**
 * 승인 실패 원인을 다음 행동으로 옮긴다.
 *
 * 명세가 정한 다섯 가지는 quickstart FE 4의 표 그대로다. 나머지 원인은 명세에 없어 성격이 같은
 * 쪽에 붙였다. 미리보기가 낡은 경우는 [Recreate], 이 감지·후보로 더 진행할 수 없는 경우는
 * [Candidates], 그 밖에는 [Retry]다.
 */
private val ReplacementError.approveAction: ApproveAction
    get() = when (this) {
        ReplacementError.ScheduleChanged, ReplacementError.PreviewExpired -> ApproveAction.Recreate
        ReplacementError.AlreadyVisited, ReplacementError.AlternativeUnavailable -> ApproveAction.Candidates
        ReplacementError.Network -> ApproveAction.Retry

        // 명세 밖 원인. 미리보기가 밀려났으면 다시 만들고, 감지·승인이 이미 끝났으면 후보 목록으로
        // 돌아가야 이 화면에서 벗어날 수 있다.
        ReplacementError.PreviewSuperseded -> ApproveAction.Recreate
        ReplacementError.AlreadyApproved,
        ReplacementError.DetectionNotActive,
        ReplacementError.SessionExpired,
        -> ApproveAction.Candidates

        ReplacementError.UndoExpired,
        ReplacementError.FollowUpChangeExists,
        is ReplacementError.RouteUnavailable,
        ReplacementError.Unexpected,
        -> ApproveAction.Retry
    }

/** 승인 처리 중인지. 승인 중에는 뒤로 가기까지 잠근다(Figma 승인 진행 중 상태). */
private val PreviewUiState.isApproving: Boolean
    get() = (this as? PreviewUiState.Content)?.approving == true

/** 값이 작아질수록 좋은 항목(이동 시간·거리)의 나아짐 판정. 값이 없거나 같으면 강조하지 않는다. */
private fun isBetter(before: Int?, after: Int?): Boolean? =
    if (before == null || after == null || before == after) null else after < before

/** 실패 원인별 안내 문구. 원인마다 다음 행동이 다르므로 문구도 나눈다(UI-004·UI-005). */
@get:StringRes
internal val ReplacementError.messageRes: Int
    get() = when (this) {
        ReplacementError.Network -> R.string.replacement_error_network
        ReplacementError.ScheduleChanged -> R.string.replacement_error_schedule_changed
        ReplacementError.PreviewExpired -> R.string.replacement_error_expired
        ReplacementError.PreviewSuperseded -> R.string.replacement_error_superseded
        ReplacementError.AlreadyApproved -> R.string.replacement_error_already_approved
        ReplacementError.AlreadyVisited -> R.string.replacement_error_already_visited
        ReplacementError.AlternativeUnavailable -> R.string.replacement_error_alternative_unavailable
        ReplacementError.DetectionNotActive -> R.string.replacement_error_detection_not_active
        ReplacementError.UndoExpired -> R.string.replacement_error_undo_expired
        ReplacementError.FollowUpChangeExists -> R.string.replacement_error_follow_up
        is ReplacementError.RouteUnavailable -> R.string.replacement_error_route
        ReplacementError.SessionExpired, ReplacementError.Unexpected -> R.string.replacement_error_unexpected
    }

internal const val TAG_BACK = "preview_back"
internal const val TAG_RETRY = "preview_retry"
internal const val TAG_APPROVE = "preview_approve"
internal const val TAG_OTHER_CANDIDATES = "preview_other_candidates"
internal const val TAG_MAP_SLOT = "preview_map"
internal const val TAG_CHANGE_SUMMARY = "preview_change_summary"
internal const val TAG_REASON = "preview_reason"
internal const val TAG_APPROVE_FAILURE = "preview_approve_failure"

/** 나아짐·나빠짐을 색 없이도 알리는 기호(UI-002). */
private const val BETTER_MARK = " ↓"
private const val WORSE_MARK = " ↑"

private const val LOADING_INDICATOR_DELAY_MILLIS = 1_000L
private val MIN_TOUCH: Dp = 48.dp
private val PRIMARY_BUTTON_HEIGHT: Dp = 54.dp
private val MAP_HEIGHT: Dp = 190.dp
private val ROW_MIN_HEIGHT: Dp = 44.dp
private val ROW_LABEL_WIDTH: Dp = 72.dp
private val LEGEND_SWATCH_WIDTH: Dp = 16.dp
private val LEGEND_SWATCH_HEIGHT: Dp = 2.dp
private val SPINNER_SIZE: Dp = 16.dp
private val SPINNER_STROKE: Dp = 2.dp
private val FAILURE_ICON: Dp = 20.dp
