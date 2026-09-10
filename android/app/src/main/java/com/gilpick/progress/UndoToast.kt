package com.gilpick.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.gilpick.R
import com.gilpick.replacement.ReplacementError
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing
import java.time.Duration
import java.time.Instant

/**
 * 자동 확정 되돌리기 토스트(UI-003, Figma `ActiveTravelScreen` Toast).
 *
 * 무응답으로 자동 확정된 전환을 정해진 시간 안에 되돌릴 수 있게 한다. 사용자가 질문을 보지
 * 못한 채 상태가 바뀔 수 있으므로(FR-015a) 이 토스트가 유일한 즉시 복구 수단이다.
 *
 * 남은 시간은 서버가 준 [UndoableTransitionDto.undoDeadline]과 기기 시각으로 **표시만** 한다.
 * 만료 판정은 서버가 서버 시각으로 하고(FR-018), 앱은 만료된 뒤 버튼을 감춰 헛된 요청을
 * 줄이기만 한다.
 *
 * @param undoable 되돌릴 수 있는 자동 확정.
 * @param placeName 대상 장소명. 진행 화면이 일정에서 찾아 넘긴다.
 * @param now 남은 시간 계산에 쓰는 기기 시각.
 * @param submitting 되돌리는 중. 버튼을 잠근다.
 * @param error 마지막 되돌리기 실패. 원인을 문구로 보인다.
 * @param onUndo `되돌리기`.
 */
@Composable
fun UndoToast(
    undoable: UndoableTransitionDto,
    placeName: String,
    now: Instant,
    submitting: Boolean,
    error: DetectionError?,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    UndoToastShell(
        message = if (error != null) {
            stringResource(error.messageRes)
        } else {
            stringResource(undoable.type.undoneMessageRes, placeName)
        },
        remainingSeconds = remainingSeconds(undoable.undoDeadline, now),
        submitting = submitting,
        onUndo = onUndo,
        modifier = modifier,
    )
}

/**
 * 장소 변경 되돌리기 토스트(F010 UI-006, Figma `ActiveTravelScreen` Toast).
 *
 * F007 자동 확정 되돌리기와 **같은 자리·같은 형식**을 쓴다(F010 UI-006). 그래서 모양은
 * [UndoToastShell]을 공유하고 문구와 실패 안내만 다르다.
 *
 * 되돌릴 수 있는 시간이 지나면 행동만 사라지고 **무엇이 바뀌었는지는 계속 보인다**(F010 UI-006).
 * 실패하면 원인과 함께 **일정 편집으로 바꿀 수 있다는 안내**를 보인다(F010 FR-019·UI-007) —
 * 되돌릴 수 없게 된 뒤에도 사용자에게 남은 길이 있음을 알려야 하기 때문이다.
 *
 * @param undo 되돌릴 수 있는 장소 변경. 서버가 되돌릴 수 있는 동안만 실어 준다.
 * @param now 남은 시간 계산에 쓰는 기기 시각. 만료 판정은 서버가 한다(F010 FR-015).
 * @param submitting 되돌리는 중. 버튼을 잠근다.
 * @param error 마지막 되돌리기 실패. 원인과 일정 편집 안내를 보인다.
 * @param onUndo `되돌리기`.
 */
@Composable
fun ReplacementUndoToast(
    undo: UndoableReplacementDto,
    now: Instant,
    submitting: Boolean,
    error: ReplacementError?,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    UndoToastShell(
        message = if (error != null) {
            stringResource(error.undoMessageRes) + " " + stringResource(R.string.replacement_undo_edit_hint)
        } else {
            stringResource(R.string.replacement_undo_toast, undo.newPlaceName)
        },
        remainingSeconds = remainingSeconds(undo.undoExpiresAt, now),
        // 실패한 뒤에는 같은 요청을 다시 보내도 결과가 같다. 행동을 감추고 안내만 남긴다.
        submitting = submitting,
        onUndo = onUndo,
        showAction = error == null,
        modifier = modifier,
    )
}

/**
 * 되돌리기 토스트의 공용 모양(Figma `ActiveTravelScreen` Toast).
 *
 * F007 자동 확정과 F010 장소 변경이 **같은 자리에 같은 형식으로** 보여야 해서(F010 UI-006)
 * 한 곳에 둔다. 두 되돌리기가 동시에 가능할 때 무엇을 보일지는 `ProgressUiState.Content`가
 * 정한다(F010 UI-006a).
 *
 * @param remainingSeconds 남은 초. 0 이하이면 남은 시간과 행동을 감추고 문구만 남긴다.
 * @param showAction 행동을 보일지. 되돌릴 수 없게 된 실패에서는 `false`다.
 */
@Composable
private fun UndoToastShell(
    message: String,
    remainingSeconds: Int,
    submitting: Boolean,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
    showAction: Boolean = true,
) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current

    Surface(
        color = colors.toast,
        shape = RoundedCornerShape(radius.lg),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = spacing.space4, end = spacing.space2, top = spacing.space2, bottom = spacing.space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space2),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.weight(1f),
            )

            // 만료된 뒤에는 남은 시간과 버튼을 감춘다. 되돌릴 수 없는 행동을 보여 주지 않는다.
            if (remainingSeconds > 0 && showAction) {
                Text(
                    text = stringResource(R.string.detection_undo_remaining, remainingSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onDarkMuted,
                )
                TextButton(
                    onClick = onUndo,
                    enabled = !submitting,
                    modifier = Modifier.heightIn(min = MIN_TOUCH),
                ) {
                    Text(
                        text = stringResource(R.string.detection_undo),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.success,
                    )
                }
            }
        }
    }
}

/** 되돌릴 수 있는 남은 초. 이미 지났으면 0이다. 표시 전용이다. */
internal fun remainingSeconds(undoDeadline: String, now: Instant): Int {
    val target = runCatching { Instant.parse(undoDeadline) }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(undoDeadline).toInstant() }.getOrNull()
        ?: return 0
    val seconds = Duration.between(now, target).seconds
    return if (seconds < 0) 0 else seconds.toInt()
}

/**
 * 장소 변경 되돌리기 실패 원인 문구(F010 UI-007).
 *
 * 되돌릴 수 없게 된 두 원인을 구분한다. 그 밖의 실패는 다시 시도할 수 있으므로 하나로 묶는다.
 */
private val ReplacementError.undoMessageRes: Int
    get() = when (this) {
        ReplacementError.UndoExpired -> R.string.replacement_undo_expired
        ReplacementError.FollowUpChangeExists -> R.string.replacement_undo_follow_up
        else -> R.string.replacement_undo_failed
    }

/** 자동으로 무엇이 처리됐는지 알리는 문구. 종류마다 다르다. */
private val UndoableKind.undoneMessageRes: Int
    get() = when (this) {
        UndoableKind.ARRIVAL -> R.string.detection_auto_arrived_toast
        UndoableKind.DEPARTURE -> R.string.detection_auto_departed_toast
        UndoableKind.COMPOSITE -> R.string.detection_auto_moved_toast
    }

/** 가이드라인 10절: 터치 영역 48dp 이상. */
private val MIN_TOUCH = Dp(48f)
