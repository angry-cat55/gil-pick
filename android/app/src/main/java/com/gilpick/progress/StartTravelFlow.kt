package com.gilpick.progress

import android.Manifest
import android.content.Context
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import com.gilpick.ui.component.GradientButtonWidth
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickShadows
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.gilpick.ui.theme.displayFont
import kotlinx.coroutines.launch

/**
 * `오늘 여행 시작하기`를 누른 뒤의 흐름(#711): 시작 방식 시트(#654) → 필요하면 위치 권한 안내(#440) → [onStart].
 *
 * 일정 상세에 있던 것을 여행 중 화면으로 옮겼다. 앱 사용 중 위치 권한은 여기서 요청한다(research.md 결정 7).
 * `첫 장소로 이동하기`는 현재 위치가 필요해 위치 권한이 있을 때만 고를 수 있다(#731). 권한이 없으면 선택지를
 * 잠그고, 누르면 안내 창이 뜨며 그 안에서 권한 안내 화면으로 이어진다. `첫 장소에서 시작하기`는 권한과 무관하다(FR-020).
 * 권한이 있어도 위치를 못 얻을 수 있어, 위치를 실을지는 ViewModel의 [DeviceLocationProvider]가 다시 정한다.
 *
 * @param open 시작 방식 시트를 띄운다. 권한 안내는 시트 위에 겹쳐 뜨고, 닫으면 시트로 돌아온다.
 * @param firstPlaceName 오늘의 첫 장소명. 모르면 `null`이고 안내 문구만 바뀐다.
 * @param onDismiss 시트를 닫는다. 시작 방식을 골랐을 때도 호출된다.
 * @param onStart 고른 값으로 시작한다. `MOVE_TO_FIRST`는 고른 이동수단을, `AT_FIRST_PLACE`는 `null`을 함께 넘긴다.
 * @param hasLocationPermission 현재 위치 권한 확인. 기본값은 실제 기기 권한이며 UI test에서만 고정값으로 바꾼다.
 */
@Composable
fun StartTravelFlow(
    open: Boolean,
    firstPlaceName: String?,
    onDismiss: () -> Unit,
    onStart: (StartMode, TransportMode?) -> Unit,
    hasLocationPermission: (Context) -> Boolean = DeviceLocationProvider::hasLocationPermission,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lbsConsentRepository = remember(context) { LbsConsentRepository.default(context) }
    val policyLauncher = remember(context) { PolicyDocumentLauncher.default(context) }
    // 시트를 열 때마다 권한을 다시 읽는다. 시스템 설정에서 바꾼 결과도 다음 시작에 반영된다.
    var locationGranted by remember(open) { mutableStateOf(hasLocationPermission(context)) }
    // 시트에서 고른 시작 방식·이동수단. 권한 흐름을 거쳐 돌아와도 유지한다(#654).
    var mode by rememberSaveable(open) {
        mutableStateOf(if (locationGranted) StartMode.MOVE_TO_FIRST else StartMode.AT_FIRST_PLACE)
    }
    var transport by rememberSaveable(open) { mutableStateOf(TransportMode.WALK) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // 결과 맵이 아니라 실제 권한을 다시 읽는다. 허용하면 잠금이 풀리고 이동 시작을 고른 상태로 시트에 돌아온다.
        locationGranted = hasLocationPermission(context)
        if (locationGranted) mode = StartMode.MOVE_TO_FIRST
    }
    // 위치 없이는 `첫 장소로 이동하기`를 쓸 수 없다는 안내 창(#731). 권한 안내 화면은 이 창에서만 연다.
    var locationNoticeOpen by rememberSaveable { mutableStateOf(false) }
    var permissionGuideOpen by rememberSaveable { mutableStateOf(false) }
    var lbsAgreed by rememberSaveable { mutableStateOf(false) }
    var savingLbsConsent by rememberSaveable { mutableStateOf(false) }
    var lbsConsentFailed by rememberSaveable { mutableStateOf(false) }
    if (open) {
        StartModeSheet(
            firstPlaceName = firstPlaceName,
            mode = mode,
            transport = transport,
            moveEnabled = locationGranted,
            onModeChange = { selected ->
                if (selected == StartMode.MOVE_TO_FIRST && !locationGranted) locationNoticeOpen = true else mode = selected
            },
            onTransportChange = { transport = it },
            onDismiss = onDismiss,
            onConfirm = {
                when {
                    mode == StartMode.AT_FIRST_PLACE -> {
                        onDismiss()
                        onStart(StartMode.AT_FIRST_PLACE, null)
                    }
                    // 시트를 연 뒤 시스템 설정에서 권한을 회수한 경우.
                    !hasLocationPermission(context) -> {
                        locationGranted = false
                        mode = StartMode.AT_FIRST_PLACE
                        locationNoticeOpen = true
                    }
                    else -> {
                        onDismiss()
                        onStart(StartMode.MOVE_TO_FIRST, transport)
                    }
                }
            },
        )
    }
    if (locationNoticeOpen) {
        LocationRequiredDialog(
            onAllow = {
                locationNoticeOpen = false
                permissionGuideOpen = true
            },
            onDismiss = { locationNoticeOpen = false },
        )
    }
    if (permissionGuideOpen) {
        // 헤더 없는 전체 화면 안내라 창 폭 제한을 끈 Dialog로 덮는다. 뒤로 가기·`나중에 하기`는 시작하지 않고 시트로 돌아간다.
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
                onLater = { permissionGuideOpen = false },
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
 * 위치 권한이 없을 때 `첫 장소로 이동하기`를 누르면 뜨는 안내 창(#731). Figma에 없는 새 창이라 삭제 확인
 * ([com.gilpick.ui.component.DestructiveConfirmDialog])의 배치를 따르되, 되돌릴 수 없는 행동이 아니므로
 * 강조는 권한 허용에 주고 `닫기`는 글자 버튼으로 둔다.
 *
 * @param onAllow `위치 권한 허용하기`. 호출부가 위치 권한 안내 화면을 연다.
 * @param onDismiss `닫기`·바깥 탭·뒤로 가기. 시트로 돌아간다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationRequiredDialog(onAllow: () -> Unit, onDismiss: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val shape = RoundedCornerShape(radius.xl)

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .padding(horizontal = spacing.space5)
            .widthIn(max = NOTICE_DIALOG_WIDTH),
    ) {
        val shadowed = LocalGilpickShadows.current.dialog.fold(Modifier as Modifier) { acc, shadow -> acc.dropShadow(shape, shadow) }
        Surface(shape = shape, color = MaterialTheme.colorScheme.surface, modifier = shadowed) {
            Column(modifier = Modifier.padding(spacing.space6)) {
                Box(
                    modifier = Modifier
                        .size(NOTICE_ICON_BOX)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(radius.lg)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_map_pin),
                        // 본문이 뜻을 전달한다(가이드라인 10절).
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(NOTICE_ICON),
                    )
                }
                Text(
                    text = stringResource(R.string.trip_detail_start_location_required),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = spacing.space4),
                )
                GradientButton(
                    label = stringResource(R.string.location_permission_allow),
                    onClick = onAllow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.space6),
                    width = GradientButtonWidth.Standalone,
                    height = NOTICE_BUTTON_HEIGHT,
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.space2)
                        .heightIn(min = 48.dp),
                ) {
                    Text(
                        text = stringResource(R.string.trip_detail_start_location_notice_close),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
 * @param moveEnabled `false`면 `첫 장소로 이동하기`를 잠금 모양으로 보이되, 눌렀을 때 [onModeChange]가 안내를 띄우도록 그대로 받는다.
 * @param onModeChange 고른 시작 방식. 잠긴 이동 시작을 눌러도 호출되므로 호출부가 안내로 바꿀지 정한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartModeSheet(
    firstPlaceName: String?,
    mode: StartMode,
    transport: TransportMode,
    moveEnabled: Boolean,
    onModeChange: (StartMode) -> Unit,
    onTransportChange: (TransportMode) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
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
                onClick = { onModeChange(StartMode.MOVE_TO_FIRST) },
                modifier = Modifier.padding(bottom = spacing.space2),
                locked = !moveEnabled,
            )
            StartModeOption(
                label = stringResource(R.string.trip_detail_start_at_place_label),
                description = stringResource(R.string.trip_detail_start_at_place_description),
                selected = mode == StartMode.AT_FIRST_PLACE,
                onClick = { onModeChange(StartMode.AT_FIRST_PLACE) },
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
                        onClick = { onTransportChange(option) },
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
                    onClick = onConfirm,
                    modifier = Modifier.weight(2f),
                    height = SHEET_BUTTON_HEIGHT,
                )
            }
        }
    }
}

/**
 * 시작 방식 카드. 라벨과 한 줄 설명을 함께 두어 선택지를 색·체크만으로 구분하지 않는다(가이드라인 10절).
 *
 * @param locked 위치 권한이 없어 지금은 고를 수 없다. 회색 배경·글자에 자물쇠 아이콘과 `위치 권한 필요` 상태 설명을
 *   함께 보여 색만으로 알리지 않는다. 비활성 처리하지 않고 눌림은 그대로 받아, 호출부가 이유를 안내하게 한다.
 */
@Composable
private fun StartModeOption(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
) {
    val shape = RoundedCornerShape(LocalGilpickRadius.current.lg)
    val muted = LocalGilpickColors.current.muted
    val lockedState = stringResource(R.string.trip_detail_start_location_required_state)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                when {
                    locked -> MaterialTheme.colorScheme.background
                    selected -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surface
                },
            )
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick, role = Role.RadioButton)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                if (locked) stateDescription = lockedState
            }
            .padding(horizontal = LocalGilpickSpacing.current.space4, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(LocalGilpickSpacing.current.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    locked -> muted
                    selected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
        if (locked) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_lock),
                contentDescription = null,
                tint = muted,
                modifier = Modifier.size(18.dp),
            )
        } else if (selected) {
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

/** 위치 필요 안내 창 크기. 삭제 확인 dialog(326dp 폭, 52dp 버튼, 48dp 아이콘 상자)와 같다. */
private val NOTICE_DIALOG_WIDTH = 326.dp
private val NOTICE_BUTTON_HEIGHT = 52.dp
private val NOTICE_ICON_BOX = 48.dp
private val NOTICE_ICON = 22.dp
