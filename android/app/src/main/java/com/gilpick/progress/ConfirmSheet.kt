package com.gilpick.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gilpick.R
import com.gilpick.ui.component.GradientButton
import com.gilpick.ui.component.SecondaryButton
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.gilpick.ui.theme.displayFont
import java.time.Duration
import java.time.Instant

/**
 * 도착·출발 확인 시트(UI-001·UI-002, Figma `ActiveTravelScreen` Arrived/Depart confirm sheet).
 *
 * 두 종류가 같은 구조라 한 composable을 쓰고 문구와 행동 라벨만 [DetectionKind]로 고른다.
 * 어떤 응답을 받을지는 서버가 [TransitionCandidateDto.allowedDecisions]로 알려 주므로 앱이
 * 정하지 않는다.
 *
 * 남은 시간은 서버가 준 [TransitionCandidateDto.autoFinalizeAt]과 기기 시각으로 **표시만** 한다.
 * 자동 확정 여부는 서버가 서버 시각으로 판정한다(FR-018).
 *
 * @param candidate 답을 기다리는 후보.
 * @param placeName 대상 장소명. 진행 화면이 일정에서 찾아 넘긴다.
 * @param now 남은 시간 계산에 쓰는 기기 시각. ViewModel이 매분 갱신한다.
 * @param submitting 응답을 보내는 중. 두 행동을 잠그고 진행 표시를 띄운다(UI-006).
 * @param error 마지막 응답 실패. 원인과 다시 시도를 보이고 후보는 그대로 둔다(UI-006).
 * @param onDecide 고른 응답을 보낸다.
 * @param onRetry 실패한 응답을 같은 내용으로 다시 보낸다.
 * @param onDismiss 시트를 닫는다. 후보는 살아 있고 수동 진행을 계속할 수 있다(UI-007).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(
    candidate: TransitionCandidateDto,
    placeName: String,
    now: Instant,
    submitting: Boolean,
    error: DetectionError?,
    onDecide: (TransitionDecision) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { if (!submitting) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(
            topStart = LocalGilpickRadius.current.sheet,
            topEnd = LocalGilpickRadius.current.sheet,
        ),
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = 0.4f),
    ) {
        ConfirmSheetContent(
            candidate = candidate,
            placeName = placeName,
            now = now,
            submitting = submitting,
            error = error,
            onDecide = onDecide,
            onRetry = onRetry,
        )
    }
}

/**
 * 시트 내용. screenshot·UI test가 시트 창 없이 그대로 렌더할 수 있도록 분리한다(F004·F006 방식).
 */
@Composable
internal fun ConfirmSheetContent(
    candidate: TransitionCandidateDto,
    placeName: String,
    now: Instant,
    submitting: Boolean,
    error: DetectionError?,
    onDecide: (TransitionDecision) -> Unit,
    onRetry: () -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val arrival = candidate.type == DetectionKind.ARRIVAL

    val title = stringResource(
        if (arrival) R.string.detection_confirm_arrival_title else R.string.detection_confirm_departure_title,
        placeName,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.space6)
            .padding(top = spacing.space5, bottom = spacing.space8)
            .navigationBarsPadding(),
    ) {
        // Figma 손잡이(`w-10 h-1`), 48dp `primaryContainer` 상자 안 22dp 지도 핀(도착)·화살표(출발).
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = spacing.space5)
                .size(width = HANDLE_WIDTH, height = HANDLE_HEIGHT)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Box(
            modifier = Modifier
                .size(ICON_BOX)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(radius.lg)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(if (arrival) R.drawable.ic_lucide_map_pin else R.drawable.ic_lucide_arrow_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(ICON),
            )
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = title.displayFont(),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = spacing.space4, bottom = spacing.space1),
        )

        Text(
            text = evidenceLabel(candidate, arrival),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
        )

        // 자동 확정까지 남은 시간. 표시만 하고 만료 판정은 서버가 한다(FR-018). Figma의 초 단위 `3:58`은
        // 데모 연출이라 그리지 않는다(F006 UI-007과 같은 판단). `now`는 매분 갱신된다.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.space4, bottom = spacing.space5)
                .background(colors.successContainer, RoundedCornerShape(radius.md))
                .padding(horizontal = spacing.space4, vertical = spacing.space2 + 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space2),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_clock),
                contentDescription = null,
                tint = colors.success,
                modifier = Modifier.size(CLOCK_ICON),
            )
            Text(
                text = stringResource(
                    if (arrival) R.string.detection_auto_arrival_notice else R.string.detection_auto_departure_notice,
                    remainingMinutes(candidate.autoFinalizeAt, now),
                ),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.success,
                modifier = Modifier.weight(1f),
            )
        }

        if (error != null) {
            Text(
                text = stringResource(error.messageRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = spacing.space2),
            )
        }

        val confirmLabel = when {
            submitting -> R.string.detection_confirm_progress
            error != null -> R.string.detection_retry
            arrival -> R.string.detection_confirm_arrival_yes
            else -> R.string.detection_confirm_departure_yes
        }
        // 보내는 동안은 비활성이 아니라 처리 중(가이드라인 7절 "처리 중 주버튼").
        GradientButton(
            label = stringResource(confirmLabel),
            onClick = { if (error != null) onRetry() else onDecide(TransitionDecision.CONFIRM) },
            height = PRIMARY_BUTTON_HEIGHT,
            processing = submitting,
            modifier = Modifier.fillMaxWidth(),
        )

        val rejectDecision = if (arrival) TransitionDecision.NOT_ARRIVED else TransitionDecision.STILL_HERE
        // 서버가 허용한 응답만 보여 준다. 앱이 종류별 규칙을 따로 갖지 않는다.
        if (rejectDecision in candidate.allowedDecisions) {
            SecondaryButton(
                label = stringResource(if (arrival) R.string.detection_confirm_arrival_no else R.string.detection_confirm_departure_no),
                onClick = { onDecide(rejectDecision) },
                enabled = !submitting,
                height = SECONDARY_BUTTON_HEIGHT,
                modifier = Modifier
                    .padding(top = spacing.space2)
                    .fillMaxWidth(),
            )
        }
    }
}

/** 감지 근거 문구(UI-001). 도착은 머문 시간을, 출발은 벗어났음을 적는다. */
@Composable
private fun evidenceLabel(candidate: TransitionCandidateDto, arrival: Boolean): String {
    val detectedAt = formatDetectedAt(candidate.evidence.occurredAt)
    val dwell = candidate.evidence.dwellMinutes
    return when {
        arrival && dwell != null -> stringResource(R.string.detection_evidence_dwell, dwell, detectedAt)
        arrival -> stringResource(R.string.detection_evidence_arrival, detectedAt)
        else -> stringResource(R.string.detection_evidence_departure, detectedAt)
    }
}

/** 자동 확정까지 남은 분. 이미 지났으면 0이다. 표시 전용이다. */
internal fun remainingMinutes(autoFinalizeAt: String, now: Instant): Int {
    val target = runCatching { Instant.parse(autoFinalizeAt) }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(autoFinalizeAt).toInstant() }.getOrNull()
        ?: return 0
    val minutes = Duration.between(now, target).toMinutes()
    return if (minutes < 0) 0 else minutes.toInt()
}

/**
 * 감지 시각을 `오후 2:33`처럼 읽어 준다. 파싱할 수 없으면 원문을 그대로 둔다.
 *
 * 문구가 한글이므로 오전·오후 표기도 한국어로 고정한다. 기기 locale을 따르면 `PM 2:33`처럼
 * 한 문장 안에서 언어가 섞인다.
 */
internal fun formatDetectedAt(occurredAt: String): String =
    runCatching {
        java.time.OffsetDateTime.parse(occurredAt)
            .atZoneSameInstant(com.gilpick.trip.KST)
            .format(java.time.format.DateTimeFormatter.ofPattern("a h:mm", java.util.Locale.KOREAN))
    }.getOrElse { occurredAt }

/** 응답 실패 원인별 안내 문구. 원인을 뭉뚱그리지 않는다(가이드라인 9절). */
internal val DetectionError.messageRes: Int
    get() = when (this) {
        DetectionError.Network -> R.string.detection_error_network
        DetectionError.TransitionNotPending -> R.string.detection_error_not_pending
        DetectionError.UndoWindowExpired -> R.string.detection_error_undo_expired
        DetectionError.SessionExpired -> R.string.detection_error_session
        DetectionError.Forbidden, DetectionError.NotFound -> R.string.detection_error_not_found
        DetectionError.InvalidDecision,
        DetectionError.TransitionNotUndoable,
        DetectionError.IdempotencyKeyConflict,
        DetectionError.Unexpected,
        -> R.string.detection_error_unexpected
    }

/** Figma 시트의 손잡이, 아이콘 박스(안 아이콘 22), 남은 시간 줄 시계(14), 버튼 크기. */
private val HANDLE_WIDTH = Dp(40f)
private val HANDLE_HEIGHT = Dp(4f)
private val ICON_BOX = Dp(48f)
private val ICON = Dp(22f)
private val CLOCK_ICON = Dp(14f)
private val PRIMARY_BUTTON_HEIGHT = Dp(52f)
private val SECONDARY_BUTTON_HEIGHT = Dp(48f)
