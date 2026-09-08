package com.gilpick.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gilpick.R
import com.gilpick.itinerary.ItemStatus
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.gilpick.ui.theme.displayFont

/**
 * 상태 수정 시트(UI-003, Figma `ActiveTravelScreen` Status sheet, T035).
 *
 * 장소 행을 누르면 열리고 현재 상태에 맞는 행동만 보인다. 행동은 목표 상태 하나로 옮겨지고(research
 * 결정 3) 파생 전환은 서버가 계산한다. 오늘이 아닌 날짜와 시작 전 날짜에서는 화면이 열지 않는다.
 *
 * @param row 상태를 바꿀 장소.
 * @param onAction 고른 행동의 목표 상태. 화면은 시트를 닫고 전환을 요청한다.
 * @param onDismiss `취소`·바깥 탭.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusSheet(row: ProgressRow, onAction: (ItemStatus) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = LocalGilpickRadius.current.sheet, topEnd = LocalGilpickRadius.current.sheet),
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = 0.4f),
    ) {
        StatusSheetContent(row = row, onAction = onAction, onCancel = onDismiss)
    }
}

/**
 * 현재 상태별 행동(UI-003 표). 순서는 Figma `STATUS_ACTIONS`다.
 *
 * - `예정`·`이동 중`: `도착으로 변경`, `건너뛰기`
 * - `도착`: `건너뛰기`
 * - `완료`: `완료 취소`, `건너뛰기`
 * - `건너뜀`: `건너뛰기 취소`
 */
internal fun ItemStatus.sheetActions(): List<SheetAction> = when (this) {
    ItemStatus.PLANNED, ItemStatus.EN_ROUTE -> listOf(SheetAction.Arrive, SheetAction.Skip)
    ItemStatus.ARRIVED -> listOf(SheetAction.Skip)
    ItemStatus.COMPLETED -> listOf(SheetAction.UndoComplete, SheetAction.Skip)
    ItemStatus.SKIPPED -> listOf(SheetAction.UndoSkip)
}

/** 시트의 한 행동: 문구와 목표 상태. */
internal enum class SheetAction(val labelRes: Int, val target: ItemStatus) {
    Arrive(R.string.progress_sheet_arrive, ItemStatus.ARRIVED),
    Skip(R.string.progress_action_skip, ItemStatus.SKIPPED),
    UndoComplete(R.string.progress_sheet_undo_complete, ItemStatus.ARRIVED),
    UndoSkip(R.string.progress_sheet_undo_skip, ItemStatus.PLANNED),
}

/** 시트의 내용. 별도 window 없이 그릴 수 있어 screenshot test가 직접 찍는다(F004 `TransportSheetContent`와 같다). */
@Composable
internal fun StatusSheetContent(row: ProgressRow, onAction: (ItemStatus) -> Unit, onCancel: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val status = row.progress.status
    val name = row.item.place.name
    val statusLabel = stringResource(status.progressLabelRes)
    val description = stringResource(R.string.progress_sheet_description, name)

    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = spacing.space5, end = spacing.space5, top = spacing.space5, bottom = spacing.space8)
            .navigationBarsPadding()
            .semantics { contentDescription = description }
            .testTag(TAG_STATUS_SHEET),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = spacing.space5)
                .size(width = 40.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space2),
            modifier = Modifier.padding(start = spacing.space1, end = spacing.space1, bottom = spacing.space5),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = name.displayFont(),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
            StatusChip(status = status, label = statusLabel)
        }
        Column(verticalArrangement = Arrangement.spacedBy(spacing.space1), modifier = Modifier.padding(bottom = spacing.space4)) {
            status.sheetActions().forEach { action ->
                SheetButton(
                    label = stringResource(action.labelRes),
                    color = when (action) {
                        SheetAction.Arrive, SheetAction.UndoSkip -> MaterialTheme.colorScheme.primary
                        SheetAction.Skip -> colors.warning
                        SheetAction.UndoComplete -> colors.muted
                    },
                    onClick = { onAction(action.target) },
                    alignment = Alignment.CenterStart,
                )
            }
        }
        SheetButton(label = stringResource(R.string.progress_sheet_cancel), color = colors.muted, onClick = onCancel, alignment = Alignment.Center)
    }
    // Figma: 행동 버튼은 왼쪽 정렬 15sp, 취소는 가운데 14sp. 높이는 모두 48dp라 터치 기준을 만족한다.
}

@Composable
private fun SheetButton(label: String, color: Color, onClick: () -> Unit, alignment: Alignment) {
    val spacing = LocalGilpickSpacing.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SHEET_BUTTON_HEIGHT)
            .clip(RoundedCornerShape(LocalGilpickRadius.current.lg))
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = spacing.space5),
        contentAlignment = alignment,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = color)
    }
}

internal const val TAG_STATUS_SHEET = "progress_status_sheet"

/** Figma `h-[48px]`. */
private val SHEET_BUTTON_HEIGHT = 48.dp
