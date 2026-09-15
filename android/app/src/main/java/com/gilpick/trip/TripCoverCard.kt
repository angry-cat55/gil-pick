package com.gilpick.trip

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.gilpick.R
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickShadows
import com.gilpick.ui.theme.LocalGilpickSpacing
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 여행 커버 이미지 카드(Figma `CreateTripScreen`·`EditTripScreen` 커버, FR-019, #499).
 *
 * - `사진 업로드`·`사진 변경`을 누르면 시스템 Photo Picker(권한 불필요)를 연다.
 * - 이미지가 없으면 Figma의 외부 풍경 사진 대신 `faint` 대체 배경과 아이콘을 두고 `기본 이미지`라고 적는다(가이드라인 12절).
 * - 기본 이미지가 아니면 `기본으로`로 되돌린다. 저장은 폼 저장 버튼이 한다.
 * - 보이는 버튼은 Figma 크기(약 32dp)지만 누르는 영역은 48dp다(10절).
 *
 * @param image 커버에 그릴 원본. `null`이면 대체 표현만 보인다.
 * @param custom 기본 이미지가 아닌지.
 * @param editing 수정 화면이면 Figma `EditTripScreen` 배치(라벨·버튼 한 줄, `커스텀 이미지` 라벨)를 쓴다.
 * @param error 방금 고른 이미지를 쓸 수 없는 이유. 카드 아래에 적는다.
 */
@Composable
internal fun TripCoverCard(
    image: ByteArray?,
    custom: Boolean,
    editing: Boolean,
    enabled: Boolean,
    error: TripImageError?,
    onPick: (TripImagePick) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val scheme = MaterialTheme.colorScheme
    val onImage = scheme.onPrimary
    val shape = RoundedCornerShape(radius.lg)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { onPick(readTripImage(context, uri)) }
    }
    val openPicker = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.space2)) {
        Box(
            modifier = LocalGilpickShadows.current.card
                .fold(Modifier.fillMaxWidth().heightIn(min = COVER_HEIGHT) as Modifier) { acc, shadow -> acc.dropShadow(shape, shadow) }
                .clip(shape)
                .background(LocalGilpickColors.current.faint),
        ) {
            // 대체 표현. 이미지가 없거나 받는 중이면 이것이 보인다.
            Icon(
                painter = painterResource(R.drawable.ic_lucide_image),
                contentDescription = null,
                tint = onImage,
                modifier = Modifier.align(Alignment.Center).size(FALLBACK_ICON),
            )
            if (image != null) {
                AsyncImage(
                    model = image,
                    // 이미지 내용을 설명할 수 없다. 라벨(`커스텀 이미지`)이 뜻을 전달한다.
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            (if (editing) EDIT_SCRIM_START else CREATE_SCRIM_START) to Color.Transparent,
                            1f to scheme.scrim.copy(alpha = if (editing) EDIT_SCRIM_ALPHA else CREATE_SCRIM_ALPHA),
                        ),
                    ),
            )

            // 큰 글자 배율에서 한 줄에 다 들어가지 않으면 버튼이 다음 줄 오른쪽으로 내려가고, 카드는 160dp보다 길어진다(10절 잘림 금지).
            FlowRow(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = if (editing) spacing.space4 else spacing.space3, vertical = spacing.space3 - TOUCH_INSET),
                horizontalArrangement = Arrangement.spacedBy(spacing.space2, Alignment.End),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                // 라벨이 남는 폭을 차지해 버튼을 오른쪽 끝으로 민다. 라벨이 없으면 빈 칸이 그 역할을 한다.
                Box(modifier = Modifier.weight(1f)) {
                    if (editing || !custom) {
                        Text(
                            text = stringResource(if (custom) R.string.trip_form_cover_custom else R.string.trip_form_cover_default),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (editing) FontWeight.Normal else FontWeight.Medium, letterSpacing = 0.sp),
                            color = onImage.copy(alpha = LABEL_ALPHA),
                        )
                    }
                }
                CoverButton(
                    label = stringResource(
                        when {
                            editing || custom -> R.string.trip_form_cover_change
                            else -> R.string.trip_form_cover_upload
                        },
                    ),
                    icon = true,
                    editing = editing,
                    enabled = enabled,
                    onClick = openPicker,
                )
                if (custom) {
                    CoverButton(
                        label = stringResource(R.string.trip_form_cover_reset),
                        icon = false,
                        editing = editing,
                        enabled = enabled,
                        onClick = onRemove,
                    )
                }
            }
        }
        if (error != null) {
            Text(
                text = stringResource(error.messageRes),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
            )
        }
    }
}

/** 사진 위 반투명 버튼(Figma `rgba(0,0,0,0.5)` + blur, 13·12sp 700). 보이는 크기는 Figma, 누르는 영역은 48dp. */
@Composable
private fun CoverButton(label: String, icon: Boolean, editing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val onImage = MaterialTheme.colorScheme.onPrimary

    Box(
        modifier = Modifier
            .sizeIn(minWidth = MIN_TOUCH, minHeight = MIN_TOUCH)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(LocalGilpickRadius.current.md))
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = BUTTON_SCRIM_ALPHA))
                .padding(horizontal = BUTTON_PADDING_H, vertical = spacing.space2),
            horizontalArrangement = Arrangement.spacedBy(BUTTON_ICON_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_upload),
                    contentDescription = null,
                    tint = onImage,
                    modifier = Modifier.size(if (editing) EDIT_BUTTON_ICON else CREATE_BUTTON_ICON),
                )
            }
            Text(
                text = label,
                style = (if (editing) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium).copy(fontWeight = FontWeight.Bold),
                color = onImage,
            )
        }
    }
}

private val TripImageError.messageRes: Int
    get() = when (this) {
        TripImageError.TOO_LARGE -> R.string.trip_form_image_too_large
        TripImageError.UNSUPPORTED_TYPE -> R.string.trip_form_image_unsupported
        TripImageError.UNREADABLE -> R.string.trip_form_image_unreadable
    }

/**
 * Photo Picker가 돌려준 URI를 읽어 올릴 수 있는지 확인한다(FR-019: jpeg·png·webp, 5MB 이하).
 *
 * 5MB를 넘는 순간 읽기를 멈춰 큰 파일을 통째로 메모리에 올리지 않는다. 서버도 같은 제한으로 최종 판정한다.
 */
internal suspend fun readTripImage(context: Context, uri: Uri): TripImagePick = withContext(Dispatchers.IO) {
    val mimeType = context.contentResolver.getType(uri)
    if (mimeType !in TRIP_IMAGE_TYPES) return@withContext TripImagePick.Rejected(TripImageError.UNSUPPORTED_TYPE)

    try {
        val input = context.contentResolver.openInputStream(uri)
            ?: return@withContext TripImagePick.Rejected(TripImageError.UNREADABLE)
        input.use {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(READ_BUFFER)
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > MAX_TRIP_IMAGE_BYTES) return@withContext TripImagePick.Rejected(TripImageError.TOO_LARGE)
            }
            TripImagePick.Picked(PickedTripImage(out.toByteArray(), mimeType!!))
        }
    } catch (e: IOException) {
        TripImagePick.Rejected(TripImageError.UNREADABLE)
    } catch (e: SecurityException) {
        TripImagePick.Rejected(TripImageError.UNREADABLE)
    }
}

/** 서버가 받는 형식(FR-019, `api/app/services/trip_image.py`). */
private val TRIP_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp")

/** 서버 최대 크기 5MB(FR-019). */
private const val MAX_TRIP_IMAGE_BYTES = 5 * 1024 * 1024
private const val READ_BUFFER = 64 * 1024

/** Figma 커버 실측. 화면 전용이라 테마 토큰이 아니라 여기에 둔다. */
private val COVER_HEIGHT = 160.dp
private val FALLBACK_ICON = 32.dp
private val MIN_TOUCH = 48.dp

/** 버튼 `px-3.5`·`gap-1.5`. */
private val BUTTON_PADDING_H = 14.dp
private val BUTTON_ICON_GAP = 6.dp
private val CREATE_BUTTON_ICON = 14.dp
private val EDIT_BUTTON_ICON = 13.dp

/** 48dp 터치 영역이 보이는 버튼보다 커서 생기는 여백만큼 바깥 여백을 줄여 Figma `bottom-3` 위치를 맞춘다. */
private val TOUCH_INSET = 8.dp

/** 만들기: `transparent 50% → rgba(0,0,0,0.5)`, 수정: `transparent 40% → rgba(0,0,0,0.55)`. */
private const val CREATE_SCRIM_START = 0.5f
private const val CREATE_SCRIM_ALPHA = 0.5f
private const val EDIT_SCRIM_START = 0.4f
private const val EDIT_SCRIM_ALPHA = 0.55f
private const val BUTTON_SCRIM_ALPHA = 0.5f

/** `text-white/60`. */
private const val LABEL_ALPHA = 0.6f
