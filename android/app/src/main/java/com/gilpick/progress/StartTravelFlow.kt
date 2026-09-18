package com.gilpick.progress

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gilpick.R
import com.gilpick.auth.AuthResult
import com.gilpick.itinerary.TransportMode
import com.gilpick.itinerary.toPlaceTransport
import com.gilpick.place.TransportOption
import com.gilpick.settings.PolicyDocument
import com.gilpick.settings.PolicyDocumentLauncher
import com.gilpick.ui.component.GradientButton
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.gilpick.ui.theme.displayFont
import kotlinx.coroutines.launch

/**
 * `오늘 여행 시작하기`를 누른 뒤의 흐름(#711): 시작 방식 시트(#654) → 필요하면 위치 권한 안내(#440) → [onStart].
 *
 * 일정 상세에 있던 것을 여행 중 화면으로 옮겼다. 앱 사용 중 위치 권한은 여기서 요청한다(research.md 결정 7).
 * 허용·거부 어느 쪽이든 시작은 진행하고, 위치를 실을지는 ViewModel의 [DeviceLocationProvider]가 권한을
 * 다시 확인해 정한다(FR-020).
 *
 * @param open 시작 방식 시트를 띄운다. 권한 안내는 시트가 닫힌 뒤에도 이 흐름이 이어서 띄운다.
 * @param firstPlaceName 오늘의 첫 장소명. 모르면 `null`이고 안내 문구만 바뀐다.
 * @param onDismiss 시트를 닫는다. 시작 방식을 골랐을 때도 호출된다.
 * @param onStart 고른 값으로 시작한다. `MOVE_TO_FIRST`는 고른 이동수단을, `AT_FIRST_PLACE`는 `null`을 함께 넘긴다.
 */
@Composable
fun StartTravelFlow(
    open: Boolean,
    firstPlaceName: String?,
    onDismiss: () -> Unit,
    onStart: (StartMode, TransportMode?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lbsConsentRepository = remember(context) { LbsConsentRepository.default(context) }
    val policyLauncher = remember(context) { PolicyDocumentLauncher.default(context) }
    // 시작 방식 시트에서 고른 이동수단. 위치 권한 흐름을 거쳐 돌아와도 그 선택으로 시작한다(#654).
    var pendingTransport by rememberSaveable { mutableStateOf(TransportMode.WALK) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onStart(StartMode.MOVE_TO_FIRST, pendingTransport) }
    // 권한이 없으면 시스템 창 전에 위치 권한 안내 화면을 먼저 보인다(#440). 허용·거부·나중에 하기 모두 시작한다(FR-020).
    var permissionGuideOpen by rememberSaveable { mutableStateOf(false) }
    var lbsAgreed by rememberSaveable { mutableStateOf(false) }
    var savingLbsConsent by rememberSaveable { mutableStateOf(false) }
    var lbsConsentFailed by rememberSaveable { mutableStateOf(false) }
    // 현재 위치에서 첫 장소로 이동하는 시작만 위치가 필요하다. 현장 시작은 권한을 묻지 않는다.
    val moveToFirst = { transport: TransportMode ->
        pendingTransport = transport
        if (DeviceLocationProvider.hasLocationPermission(context)) {
            onStart(StartMode.MOVE_TO_FIRST, transport)
        } else {
            permissionGuideOpen = true
        }
    }
    if (open) {
        StartModeSheet(
            firstPlaceName = firstPlaceName,
            onDismiss = onDismiss,
            onMoveToFirst = { transport ->
                onDismiss()
                moveToFirst(transport)
            },
            onAtFirstPlace = {
                onDismiss()
                onStart(StartMode.AT_FIRST_PLACE, null)
            },
        )
    }
    if (permissionGuideOpen) {
        // 헤더 없는 전체 화면 안내라 창 폭 제한을 끈 Dialog로 덮는다. 뒤로 가기는 시작하지 않고 닫는다.
        Dialog(
            onDismissRequest = { permissionGuideOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            LocationPermissionScreen(
                onAllow = {
                    savingLbsConsent = true
                    lbsConsentFailed = false
                    scope.launch {
                        when (lbsConsentRepository.agree()) {
                            is AuthResult.Success -> {
                                savingLbsConsent = false
                                permissionGuideOpen = false
                                permissionLauncher.launch(
                                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                                )
                            }
                            is AuthResult.Failure -> {
                                savingLbsConsent = false
                                lbsConsentFailed = true
                            }
                        }
                    }
                },
                onLater = {
                    permissionGuideOpen = false
                    onStart(StartMode.MOVE_TO_FIRST, pendingTransport)
                },
                lbsAgreed = lbsAgreed,
                onLbsAgreedChange = {
                    lbsAgreed = it
                    lbsConsentFailed = false
                },
                onOpenLbsTerms = { policyLauncher.open(PolicyDocument.LOCATION_TERMS) },
                submitting = savingLbsConsent,
                submitFailed = lbsConsentFailed,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 시작 방식 선택 시트(#654, #650 PROG-002). Figma에 없는 새 시트라 F004 이동 수단 시트의 모양을 따른다.
 *
 * `첫 장소로 이동하기`는 시작 구간 이동수단까지 골라 `MOVE_TO_FIRST`로, `첫 장소에서 시작하기`는
 * 위치도 이동수단도 없이 `AT_FIRST_PLACE`로 시작한다. 두 선택은 색이 아니라 라벨·설명·체크로 구분한다.
 *
 * @param firstPlaceName 오늘의 첫 장소명. 일정 개요를 아직 못 받았으면 `null`이고 안내 문구만 바뀐다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartModeSheet(
    firstPlaceName: String?,
    onDismiss: () -> Unit,
    onMoveToFirst: (TransportMode) -> Unit,
    onAtFirstPlace: () -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    var mode by rememberSaveable { mutableStateOf(StartMode.MOVE_TO_FIRST) }
    var transport by rememberSaveable { mutableStateOf(TransportMode.WALK) }
    val title = stringResource(R.string.trip_detail_start_sheet_title)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = LocalGilpickRadius.current.sheet, topEnd = LocalGilpickRadius.current.sheet),
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .padding(start = spacing.space6, end = spacing.space6, top = spacing.space5, bottom = spacing.space8)
                .navigationBarsPadding()
                .testTag(TAG_START_MODE_SHEET),
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
                text = firstPlaceName
                    ?.let { stringResource(R.string.trip_detail_start_sheet_subtitle, it) }
                    ?: stringResource(R.string.trip_detail_start_sheet_subtitle_unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalGilpickColors.current.muted,
                modifier = Modifier.padding(top = spacing.space1, bottom = spacing.space5),
            )
            StartModeOption(
                label = stringResource(R.string.trip_detail_start_move_label),
                description = stringResource(R.string.trip_detail_start_move_description),
                selected = mode == StartMode.MOVE_TO_FIRST,
                onClick = { mode = StartMode.MOVE_TO_FIRST },
                modifier = Modifier.padding(bottom = spacing.space2),
            )
            StartModeOption(
                label = stringResource(R.string.trip_detail_start_at_place_label),
                description = stringResource(R.string.trip_detail_start_at_place_description),
                selected = mode == StartMode.AT_FIRST_PLACE,
                onClick = { mode = StartMode.AT_FIRST_PLACE },
            )
            // 현장 시작은 시작 구간이 없어 이동수단을 묻지 않는다(#650 계약).
            if (mode == StartMode.MOVE_TO_FIRST) {
                Text(
                    text = stringResource(R.string.trip_detail_start_transport_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = spacing.space5, bottom = spacing.space3),
                )
                TransportMode.entries.forEach { option ->
                    TransportOption(
                        option = option.toPlaceTransport(),
                        selected = transport == option,
                        onClick = { transport = option },
                        modifier = Modifier.padding(bottom = spacing.space2),
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = spacing.space4),
                horizontalArrangement = Arrangement.spacedBy(spacing.space3),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(SHEET_BUTTON_HEIGHT)
                        .clip(RoundedCornerShape(LocalGilpickRadius.current.md))
                        .background(MaterialTheme.colorScheme.background)
                        .clickable(onClick = onDismiss, role = Role.Button),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.place_detail_cancel),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                GradientButton(
                    label = stringResource(R.string.trip_detail_start_sheet_confirm),
                    onClick = { if (mode == StartMode.MOVE_TO_FIRST) onMoveToFirst(transport) else onAtFirstPlace() },
                    modifier = Modifier.weight(2f),
                    height = SHEET_BUTTON_HEIGHT,
                )
            }
        }
    }
}

/** 시작 방식 카드. 라벨과 한 줄 설명을 함께 두어 선택지를 색·체크만으로 구분하지 않는다(가이드라인 10절). */
@Composable
private fun StartModeOption(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(LocalGilpickRadius.current.lg)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick, role = Role.RadioButton)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .padding(horizontal = LocalGilpickSpacing.current.space4, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(LocalGilpickSpacing.current.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = LocalGilpickColors.current.muted,
            )
        }
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 시작 방식 선택 시트. UI test가 시트가 열렸는지 확인한다(#654). */
internal const val TAG_START_MODE_SHEET = "start_mode_sheet"

/** 시트 하단 `취소`·`시작하기` 버튼 높이. F004 이동 수단 시트와 같다. */
private val SHEET_BUTTON_HEIGHT = 50.dp
