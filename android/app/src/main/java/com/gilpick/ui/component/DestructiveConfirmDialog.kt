package com.gilpick.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.gilpick.R
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickShadows
import com.gilpick.ui.theme.LocalGilpickSpacing

/**
 * 되돌릴 수 없는 행동의 확인 다이얼로그(Figma `TripDetailScreen` 삭제 확인).
 *
 * `AlertDialog`가 아니라 [BasicAlertDialog]를 쓰는 이유는 `AlertDialog`가 제목·본문·버튼의 배치와
 * 간격을 스스로 정해서 Figma 배치를 그대로 만들 수 없기 때문이다. [BasicAlertDialog]는 창 동작(뒤로 가기,
 * scrim, `paneTitle` semantics)만 주고 내용은 호출자가 채운다.
 *
 * Figma대로 48dp `errorContainer` 아이콘 상자, 제목, 본문 아래에 파란 취소(폭을 채움)와 빨간 글자
 * 확정을 세로로 둔다. **되돌릴 수 없는 행동이라 강조는 취소에 준다.** 여행 삭제(#442)와 계정
 * 탈퇴(#667)가 같은 규칙을 쓰도록 배치를 여기 한 곳에 둔다.
 *
 * @param title·body 무엇이 사라지는지와 복구 불가를 알리는 문구. 화면이 자기 대상에 맞춰 채운다.
 * @param confirmLabel 확정 버튼 문구. [busy]일 때는 [progressLabel]로 바뀐다.
 * @param errorMessage 실패 안내. `null`이 아니면 본문 아래에 붙는다. 실패해도 다이얼로그를 닫지
 *   않는다 — 대상이 그대로 남아 있으므로 같은 자리에서 다시 시도하거나 취소할 수 있어야 한다.
 * @param busy 요청이 진행 중. 버튼을 잠가 중복 요청을 막고, 결과를 전달할 화면이 사라지지 않도록
 *   바깥 탭·뒤로 가기로도 닫히지 않게 한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestructiveConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    progressLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
    errorMessage: String? = null,
) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current

    BasicAlertDialog(
        // 요청을 보낸 사이에 닫히면 결과를 전달할 화면이 사라진다.
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !busy,
            dismissOnClickOutside = !busy,
            // pen이 정한 너비를 쓰려면 platform 기본 너비 제약을 꺼야 한다.
            usePlatformDefaultWidth = false,
        ),
        // pen은 326dp 고정이지만 그보다 좁은 화면에서는 잘린다. 최대값으로 두어 좁은
        // 화면에서만 줄어들게 한다(가이드라인 10절: 360dp에서 잘림 없음).
        modifier = Modifier
            .padding(horizontal = spacing.space5)
            .widthIn(max = DIALOG_WIDTH),
    ) {
        val shape = RoundedCornerShape(radius.xl)
        val shadowed = LocalGilpickShadows.current.dialog.fold(Modifier as Modifier) { acc, shadow -> acc.dropShadow(shape, shadow) }
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            modifier = shadowed,
        ) {
            Column(modifier = Modifier.padding(spacing.space6)) {
                Box(
                    modifier = Modifier
                        .size(DIALOG_ICON_BOX)
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(radius.lg)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_trash),
                        // 제목이 뜻을 전달한다(가이드라인 10절).
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(DIALOG_ICON),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = spacing.space4, bottom = spacing.space2),
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = spacing.space2),
                    )
                }

                GradientButton(
                    label = cancelLabel,
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.space6),
                    width = GradientButtonWidth.Standalone,
                    height = DIALOG_BUTTON_HEIGHT,
                    enabled = !busy,
                )
                TextButton(
                    onClick = onConfirm,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.space2)
                        .heightIn(min = MIN_TOUCH),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                        disabledContentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(
                        text = if (busy) progressLabel else confirmLabel,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

/** pen 다이얼로그 너비(`w-[326px]`). */
private val DIALOG_WIDTH = 326.dp

/** Figma 삭제 확인 `취소` 버튼 높이(`h-[52px]`). */
private val DIALOG_BUTTON_HEIGHT = 52.dp

/** Figma 삭제 확인 아이콘 상자(`w-12 h-12`)와 휴지통 아이콘(22). */
private val DIALOG_ICON_BOX = 48.dp
private val DIALOG_ICON = 22.dp

/** 가이드라인 10절 최소 터치 영역. */
private val MIN_TOUCH: Dp = 48.dp
