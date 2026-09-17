package com.gilpick.settings

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gilpick.BuildConfig
import com.gilpick.R
import com.gilpick.ui.component.RemoteImage
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickShadows
import com.gilpick.ui.theme.LocalGilpickSpacing
import kotlinx.coroutines.delay

/**
 * 계정 헤더(spec US4, FR-009, Figma `SettingsScreen` 상단).
 *
 * F001 session이 준 값을 **읽기 전용**으로 보인다. 프로필 조회 API를 부르지도, 수정 수단을
 * 두지도 않는다(FR-013).
 *
 * 닉네임과 프로필 이미지는 카카오 동의 항목이라 실제로 비어 올 수 있다. 그때 사람 이름처럼 보이는 값을 지어내지 않고
 * 계정 종류를 알리는 중립 이름 `카카오 사용자`를 보인다(#517, ui-guidelines 12절). 계정 헤더에서 `정보 없음`은 오류처럼
 * 읽혀서 바꿨다. 비어 온 이유(미동의 등)는 session이 알려 주지 않으므로 단정하는 문구를 쓰지 않는다.
 * 이미지가 없어도 [RemoteImage]가 같은 자리를 차지해 헤더 높이가 흔들리지 않는다.
 *
 * @param nickname session의 표시 이름. `null`이면 대체 표시로 바꾼다.
 * @param profileImageUrl session의 profile image 주소. `null`이면 기본 avatar만 보인다.
 * @param isKakaoConnected 연동 뱃지 표시 여부. 인증된 session의 존재가 곧 연동이다.
 */
@Composable
fun AccountSection(
    nickname: String?,
    profileImageUrl: String?,
    isKakaoConnected: Boolean,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalGilpickSpacing.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            // 계정 헤더가 화면 맨 위라 status bar 아래로 내용을 내린다(UI-006). 흰 배경은 bar 뒤까지 이어진다(Figma).
            .statusBarsPadding()
            .padding(horizontal = spacing.space5, vertical = spacing.space5)
            .testTag(TAG_ACCOUNT_SECTION),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(
            url = profileImageUrl,
            // 바로 옆에 닉네임이 있어 읽어 줄 내용이 겹친다. 장식으로 둔다(ui-guidelines 10절).
            contentDescription = null,
            shape = RoundedCornerShape(LocalGilpickRadius.current.lg),
            fallbackIcon = R.drawable.ic_lucide_user,
            fallbackIconSize = AVATAR_FALLBACK_ICON,
            modifier = Modifier.size(AVATAR_SIZE),
        )
        Spacer(Modifier.width(spacing.space4))
        Column {
            Text(
                text = nickname ?: stringResource(R.string.settings_account_default_name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (isKakaoConnected) {
                Spacer(Modifier.height(spacing.space1))
                KakaoBadge()
            }
        }
    }
}

/**
 * 카카오 연동 뱃지.
 *
 * 노란 원은 브랜드 표시일 뿐이고 뜻은 옆 문구가 전한다. 색만으로 의미를 전달하지 않는다
 * (ui-guidelines 10절).
 */
@Composable
private fun KakaoBadge() {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(KAKAO_BADGE)
                .clip(CircleShape)
                .background(colors.kakao),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_kakao_bubble),
                contentDescription = null,
                tint = colors.onKakao,
                modifier = Modifier.size(KAKAO_GLYPH),
            )
        }
        Spacer(Modifier.width(spacing.space1))
        Text(
            text = stringResource(R.string.settings_account_kakao_connected),
            // Figma `text-[12px] font-medium`.
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
    }
}

/**
 * 설정 화면의 알림 설정 영역(`spec.md` US1, Figma `SettingsScreen`, T015).
 *
 * F012가 사용자에게 주는 변경 가능 설정은 장소 변경 제안 알림 하나뿐이다(FR-001). 계정 헤더·앱
 * 정보·정책 문서·로그아웃은 각각 #402·#400·#401이 붙이며 이 함수는 그 자리를 만들지 않는다.
 *
 * **이 영역만 상태를 가진다.** 설정 조회가 실패해도 정책 문서와 로그아웃은 계속 쓸 수 있어야
 * 하므로(UI-004) 화면 전체를 `loading`·`error`로 덮지 않는다.
 *
 * `empty`는 없다. 인증된 사용자에게는 항상 저장된 값이 있다(UI-003).
 *
 * @param onToggle 토글 선택. 사용자가 원하는 절대값이 들어온다.
 * @param onRetryLoad 조회 실패의 `다시 시도`. 설정을 다시 조회한다.
 * @param onRetrySave 저장 실패의 `다시 시도`. 마지막 희망값을 다시 보낸다.
 * @param onReauthenticate 로그인 상태가 만료됐다. F001 재인증 흐름으로 넘어간다.
 */
@Composable
fun NotificationPreferenceSection(
    phase: PreferencePhase,
    onToggle: (Boolean) -> Unit,
    onRetryLoad: () -> Unit,
    onRetrySave: () -> Unit,
    onReauthenticate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalGilpickSpacing.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .testTag(TAG_PREFERENCE_SECTION),
    ) {
        SectionLabel(stringResource(R.string.settings_notification_section))

        when (phase) {
            PreferencePhase.Loading -> PreferenceRow(
                checked = false,
                enabled = false,
                onToggle = onToggle,
                // Figma: thumb 대신 꺼짐 트랙 가운데 대기 표시(7절 토글 "불러오는 중"). 트랙은 바로 보이고 표시는 1초 뒤다.
                trailing = { LoadingTrack() },
            )

            is PreferencePhase.Content -> {
                // 보통 저장은 아주 짧아 배지가 깜빡이기만 한다(#514). 1초를 넘길 때만 잠그고 문구로 알린다(UI-004·UI-005).
                // 그 전에 다시 눌러도 view model이 진행 중 요청 뒤에 마지막 값만 이어 보내므로 중복 요청은 생기지 않는다.
                val slowSaving = rememberSlowSaving(phase.isSaving)
                PreferenceRow(
                    checked = phase.value,
                    enabled = !slowSaving,
                    onToggle = onToggle,
                    badge = if (slowSaving) stringResource(R.string.settings_saving) else null,
                )
            }

            is PreferencePhase.Error -> {
                PreferenceRow(
                    checked = phase.lastConfirmedValue ?: false,
                    enabled = false,
                    onToggle = onToggle,
                )
                ErrorBar(
                    phase = phase,
                    onRetryLoad = onRetryLoad,
                    onRetrySave = onRetrySave,
                    onReauthenticate = onReauthenticate,
                )
            }
        }
    }
}

/**
 * 저장이 [SAVING_FEEDBACK_DELAY_MILLIS]보다 오래 걸리는 동안에만 `true`다(#514). 짧은 저장에는 아무것도 바꾸지 않는다.
 */
@Composable
private fun rememberSlowSaving(isSaving: Boolean): Boolean {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(isSaving) {
        slow = false
        if (isSaving) {
            delay(SAVING_FEEDBACK_DELAY_MILLIS)
            slow = true
        }
    }
    return isSaving && slow
}

/**
 * 설정 한 줄. 제목·설명과 오른쪽 토글이다.
 *
 * 줄 높이를 48dp 이상으로 두어 토글의 터치 영역을 확보한다(UI-005). 설명이 길어 시스템 글자
 * 확대에서도 잘리지 않도록 줄 수를 제한하지 않는다(UI-006).
 *
 * @param badge 제목 옆 보조 표시. 저장 중처럼 상태를 **문구로** 알릴 때 쓴다.
 * @param trailing 토글 대신 놓을 것. 조회 중에는 대기 표시가 들어온다.
 */
@Composable
private fun PreferenceRow(
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    badge: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .padding(horizontal = spacing.space5, vertical = spacing.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space4),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.space1),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.space2),
            ) {
                Text(
                    text = stringResource(R.string.settings_place_change_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                badge?.let {
                    // 7절 토글 "저장 중" 배지: 10sp 700 `muted`, `background`, 작은 배지 곡률(Figma `rounded-md`).
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = BADGE_TEXT_SIZE, letterSpacing = 0.sp),
                        fontWeight = FontWeight.Bold,
                        color = colors.muted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(LocalGilpickRadius.current.xs))
                            .background(MaterialTheme.colorScheme.background)
                            .padding(horizontal = spacing.space2, vertical = BADGE_VERTICAL_PADDING)
                            .testTag(TAG_SAVING_BADGE),
                    )
                }
            }
            // 이 토글이 도착·출발 확인 알림까지 끄는 것으로 오해하지 않게 함께 알린다(FR-005).
            Text(
                text = stringResource(R.string.settings_place_change_description),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }

        if (trailing != null) {
            Box(
                modifier = Modifier.heightIn(min = MIN_TOUCH),
                contentAlignment = Alignment.Center,
                content = { trailing() },
            )
        } else {
            GilpickToggle(
                checked = checked,
                enabled = enabled,
                onToggle = onToggle,
                modifier = Modifier.testTag(TAG_TOGGLE),
            )
        }
    }
}

/** 설정 조회 대기 표시. 1초를 넘길 때만 보인다(UI-003, 가이드라인 9절). */
@Composable
private fun DelayedLoading() {
    var visible by remember { mutableStateOf(false) }
    val label = stringResource(R.string.settings_loading)

    LaunchedEffect(Unit) {
        delay(LOADING_INDICATOR_DELAY_MILLIS)
        visible = true
    }

    if (visible) {
        CircularProgressIndicator(
            color = LocalGilpickColors.current.muted,
            strokeWidth = SPINNER_STROKE,
            modifier = Modifier
                .size(SPINNER_SIZE)
                .clearAndSetSemantics { contentDescription = label },
        )
    }
}

/** 설정 조회 중 토글 자리: 꺼짐 트랙과 그 가운데 대기 표시(7절 토글 "불러오는 중"). 누를 것이 없어 토글 tag를 두지 않는다. */
@Composable
private fun LoadingTrack() {
    Box(
        modifier = Modifier
            .size(TOGGLE_TRACK_WIDTH, TOGGLE_TRACK_HEIGHT)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.outlineVariant),
        contentAlignment = Alignment.Center,
    ) { DelayedLoading() }
}

/**
 * Figma 토글(가이드라인 7절 "토글"). M3 `Switch`(52×32, 테두리)와 모양이 달라 직접 그린다.
 *
 * 48×24 트랙(켜짐 `primary`, 꺼짐 `outlineVariant`), 20dp 흰 thumb + `shadow-sm` 토큰, 비활성 50%. 보이는 트랙은 24dp지만
 * 터치 영역은 48dp다(10절). `Role.Switch`로 켜짐·꺼짐 상태를 semantics에 싣는다(색만으로 전달하지 않음).
 */
@Composable
private fun GilpickToggle(
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbShape = CircleShape
    val thumbX by animateDpAsState(if (checked) TOGGLE_THUMB_ON else TOGGLE_THUMB_INSET, label = "toggleThumb")
    val shadowed = LocalGilpickShadows.current.toggleThumb.fold(Modifier as Modifier) { acc, shadow -> acc.dropShadow(thumbShape, shadow) }

    Box(
        modifier = modifier
            // 보이는 크기보다 큰 48dp 누르는 영역을 노드 자체에 준다(10절, 기존 48dp test가 노드 크기를 잰다).
            .sizeIn(minWidth = MIN_TOUCH, minHeight = MIN_TOUCH)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(TOGGLE_TRACK_WIDTH, TOGGLE_TRACK_HEIGHT)
                .alpha(if (enabled) 1f else TOGGLE_DISABLED_ALPHA)
                .clip(CircleShape)
                .background(if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                modifier = Modifier
                    .offset(x = thumbX, y = TOGGLE_THUMB_INSET)
                    .then(shadowed)
                    .size(TOGGLE_THUMB)
                    .background(MaterialTheme.colorScheme.surface, thumbShape),
            )
        }
    }
}

/** 섹션 라벨: Caption 11sp 900 `muted` 자간(가이드라인 4절, Figma `text-[11px] font-black uppercase tracking-wider`). */
@Composable
private fun SectionLabel(text: String) {
    val spacing = LocalGilpickSpacing.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Black,
        color = LocalGilpickColors.current.muted,
        modifier = Modifier.padding(start = spacing.space5, end = spacing.space5, top = spacing.space4, bottom = spacing.space2),
    )
}

/** 앱 정보 행 사이 1dp `background` 구분선(Figma `border-b border-[#F4F6FB]`). */
@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(DIVIDER)
            .background(MaterialTheme.colorScheme.background),
    )
}

/**
 * 실패 안내 박스 인라인형(가이드라인 9절, Figma `SettingsScreen` 설정 불러오기 실패).
 *
 * `warningContainer` + 1dp `warningBorder`, `radiusMd`, 좌우 16·위아래 12, 16dp `warning` 경고 삼각형, 제목 12sp 600
 * `onWarningContainer`, 오른쪽 32dp `warning` 채움 버튼(터치 48dp). 원인 문장은 F012 UI-003이 요구해 제목 아래 같은 크기로
 * 둔다(Figma는 제목 한 줄, #444 결정). 색만으로 알리지 않도록 아이콘과 문구를 함께 둔다(UI-005).
 */
@Composable
private fun InlineFailureBox(
    title: String,
    cause: String,
    actionLabel: String,
    actionTag: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    secondary: (@Composable () -> Unit)? = null,
) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val shape = RoundedCornerShape(radius.md)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.space5)
            .padding(bottom = spacing.space4)
            .clip(shape)
            .background(colors.warningContainer)
            .border(DIVIDER, colors.warningBorder, shape)
            .padding(horizontal = spacing.space4, vertical = spacing.space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space3),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lucide_triangle_alert),
            contentDescription = null,
            tint = colors.warning,
            modifier = Modifier.size(ERROR_ICON),
        )
        Column(modifier = Modifier.weight(1f).padding(vertical = spacing.space2)) {
            Text(text = title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = colors.onWarningContainer)
            Text(text = cause, style = MaterialTheme.typography.bodySmall, color = colors.onWarningContainer)
        }
        Box(
            modifier = Modifier
                // 보이는 크기보다 큰 48dp 누르는 영역을 노드 자체에 준다(10절, 기존 48dp test가 노드 크기를 잰다).
            .sizeIn(minWidth = MIN_TOUCH, minHeight = MIN_TOUCH)
                .clickable(onClick = onAction, role = Role.Button)
                .testTag(actionTag),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .heightIn(min = FAILURE_ACTION_HEIGHT)
                    .clip(RoundedCornerShape(radius.sm))
                    .background(colors.warning)
                    .padding(horizontal = spacing.space3),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        secondary?.invoke()
    }
}

/**
 * 조회·변경 실패 안내(UI-003).
 *
 * 조회 실패와 저장 실패는 다음 행동이 다르다. 조회 실패는 다시 조회하고, 저장 실패는 마지막
 * 희망값을 다시 보낸다. 아직 아는 값이 없으면(`lastConfirmedValue == null`) 조회 실패다.
 *
 * 색만으로 알리지 않도록 경고 아이콘과 문구를 함께 둔다(UI-005).
 */
@Composable
private fun ErrorBar(
    phase: PreferencePhase.Error,
    onRetryLoad: () -> Unit,
    onRetrySave: () -> Unit,
    onReauthenticate: () -> Unit,
) {
    val isLoadFailure = phase.lastConfirmedValue == null
    val sessionExpired = phase.error == SettingsError.SessionExpired

    InlineFailureBox(
        title = stringResource(if (isLoadFailure) R.string.settings_load_failed else R.string.settings_save_failed),
        cause = stringResource(phase.error.messageRes),
        actionLabel = stringResource(if (sessionExpired) R.string.place_reauthenticate else R.string.settings_retry),
        actionTag = TAG_RETRY,
        onAction = when {
            sessionExpired -> onReauthenticate
            isLoadFailure -> onRetryLoad
            else -> onRetrySave
        },
        modifier = Modifier.testTag(TAG_ERROR_BAR),
    )
}


/**
 * 앱 정보 섹션의 정책 문서 항목(FR-007, Figma `SettingsScreen` 앱 정보, T019).
 *
 * 개인정보처리방침과 이용약관을 **구분해** 고를 수 있고, 고르면 앱 내 브라우저로 승인된 문서를
 * 연다. 앱 안에 문서 본문을 두거나 정책 전용 화면을 만들지 않는다.
 *
 * **알림 설정과 서로 막지 않는다**(UI-004). 설정이 저장 중이거나 실패한 상태에서도 여기는
 * 그대로 쓸 수 있고, 문서 열기에 실패해도 설정 값은 건드리지 않는다.
 *
 * 버전 행은 #402가 이 섹션에 더한다.
 *
 * @param openError 문서를 열지 못한 이유. `null`이면 안내를 보이지 않는다.
 * @param onOpen 문서 선택.
 * @param onRetry 실패 안내의 `다시 시도`. 마지막으로 고른 문서를 다시 연다.
 * @param onDismissError 실패 안내의 `닫기`.
 */
@Composable
fun PolicyDocumentSection(
    openError: PolicyOpenFailure?,
    onOpen: (PolicyDocument) -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    versionName: String = BuildConfig.VERSION_NAME,
) {
    val spacing = LocalGilpickSpacing.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .testTag(TAG_POLICY_SECTION),
    ) {
        SectionLabel(stringResource(R.string.settings_app_section))
        VersionRow(versionName = versionName)
        RowDivider()
        DataSourceRow()
        RowDivider()
        PolicyRow(
            label = stringResource(R.string.settings_privacy_policy),
            tag = TAG_PRIVACY_POLICY,
            onClick = { onOpen(PolicyDocument.PRIVACY_POLICY) },
        )
        RowDivider()
        PolicyRow(
            label = stringResource(R.string.settings_terms_of_service),
            tag = TAG_TERMS_OF_SERVICE,
            onClick = { onOpen(PolicyDocument.TERMS_OF_SERVICE) },
        )
        RowDivider()
        PolicyRow(
            label = stringResource(R.string.settings_location_terms),
            tag = TAG_LOCATION_TERMS,
            onClick = { onOpen(PolicyDocument.LOCATION_TERMS) },
        )
        openError?.let { failure ->
            PolicyErrorBar(failure = failure, onRetry = onRetry, onDismiss = onDismissError)
        }
    }
}

/**
 * 앱 버전 한 줄(FR-009, Figma `SettingsScreen` 앱 정보 첫 행).
 *
 * 정책 문서 행과 달리 **누를 것이 없다.** 값을 읽는 행이라 clickable을 두지 않고, 그래서
 * 48dp 터치 최소 크기도 적용 대상이 아니다. 높이만 정책 행과 맞춰 목록이 고르게 보인다.
 */
@Composable
private fun VersionRow(versionName: String) {
    val spacing = LocalGilpickSpacing.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = POLICY_ROW_HEIGHT)
            .padding(horizontal = spacing.space5, vertical = spacing.space4)
            .testTag(TAG_APP_VERSION),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.settings_app_version),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = versionName,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalGilpickColors.current.muted,
        )
    }
}

/**
 * 서비스 전체 데이터 출처 한 줄(F012 FR-009a, #578). `장소 정보` · `출처: ⓒ한국관광공사`.
 *
 * 공모전 규정은 출처 표기를 서비스 단위로 요구하므로 이 행으로 요건을 충족한다. 버전 행과 같은 모양의 읽기 전용 행이고,
 * 로고 이미지는 쓰지 않는다. 큰 글자 배율에서 값이 길어지면 줄바꿈해 잘리지 않는다(UI-006).
 */
@Composable
private fun DataSourceRow() {
    val spacing = LocalGilpickSpacing.current

    // 한 줄에 다 들어가면 버전 행처럼 라벨·값이 양 끝에 놓이고, 큰 글자 배율로 넘치면 값이 통째로 다음 줄로 내려간다.
    // 값을 좁은 칸에 욱여넣으면 `한국관/광공사`처럼 이름 중간에서 끊긴다.
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = POLICY_ROW_HEIGHT)
            .padding(horizontal = spacing.space5, vertical = spacing.space4)
            .testTag(TAG_DATA_SOURCE),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.settings_data_source),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(end = spacing.space3),
        )
        Text(
            text = stringResource(R.string.place_tourapi_attribution),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalGilpickColors.current.muted,
        )
    }
}

/** 정책 문서 한 줄. 줄 전체가 터치 대상이고 48dp 이상이다(UI-005). */
@Composable
private fun PolicyRow(label: String, tag: String, onClick: () -> Unit) {
    val spacing = LocalGilpickSpacing.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = POLICY_ROW_HEIGHT)
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.space5, vertical = spacing.space4)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.ic_lucide_chevron_right),
            contentDescription = null,
            tint = LocalGilpickColors.current.faint,
            modifier = Modifier.size(POLICY_CHEVRON),
        )
    }
}

/**
 * 문서 열기 실패 안내(FR-008).
 *
 * 세 원인은 사용자가 할 수 있는 일이 다르지 않아 문구만 나누고 행동은 `다시 시도`·`닫기`로
 * 같다. 화면과 로그인 상태는 그대로 유지된다.
 */
@Composable
private fun PolicyErrorBar(failure: PolicyOpenFailure, onRetry: () -> Unit, onDismiss: () -> Unit) {
    // 설정 실패와 같은 인라인형 박스다(#444 결정). `닫기`는 이 상태에만 있어 오른쪽에 글자 버튼으로 둔다.
    InlineFailureBox(
        title = stringResource(R.string.settings_policy_open_failed),
        cause = stringResource(failure.messageRes),
        actionLabel = stringResource(R.string.settings_retry),
        actionTag = TAG_POLICY_RETRY,
        onAction = onRetry,
        modifier = Modifier.testTag(TAG_POLICY_ERROR),
        secondary = {
            Box(
                modifier = Modifier
                    // 보이는 크기보다 큰 48dp 누르는 영역을 노드 자체에 준다(10절, 기존 48dp test가 노드 크기를 잰다).
            .sizeIn(minWidth = MIN_TOUCH, minHeight = MIN_TOUCH)
                    .clickable(onClick = onDismiss, role = Role.Button)
                    .testTag(TAG_POLICY_DISMISS),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.settings_policy_dismiss),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = LocalGilpickColors.current.onWarningContainer,
                )
            }
        },
    )
}

/** 문서를 열지 못한 이유 문구(FR-008). 원인마다 사용자가 이해할 말이 다르다. */
@get:StringRes
internal val PolicyOpenFailure.messageRes: Int
    get() = when (this) {
        PolicyOpenFailure.UrlMissing -> R.string.settings_policy_error_missing
        PolicyOpenFailure.UrlNotHttps -> R.string.settings_policy_error_not_https
        PolicyOpenFailure.LauncherUnavailable -> R.string.settings_policy_error_unavailable
    }

/** 실패 원인 문구. 원인마다 다음에 할 일이 달라 문구도 나눈다(UI-003). */
@get:StringRes
internal val SettingsError.messageRes: Int
    get() = when (this) {
        SettingsError.Network -> R.string.settings_error_network
        SettingsError.SessionExpired -> R.string.settings_error_session
        SettingsError.Unexpected -> R.string.settings_error_unexpected
    }

internal const val TAG_ACCOUNT_SECTION = "settings_account_section"
internal const val TAG_APP_VERSION = "settings_app_version"
internal const val TAG_DATA_SOURCE = "settings_data_source"
internal const val TAG_PREFERENCE_SECTION = "settings_preference_section"
internal const val TAG_TOGGLE = "settings_toggle"
internal const val TAG_SAVING_BADGE = "settings_saving_badge"
internal const val TAG_ERROR_BAR = "settings_error_bar"
internal const val TAG_RETRY = "settings_retry"
internal const val TAG_POLICY_SECTION = "settings_policy_section"
internal const val TAG_PRIVACY_POLICY = "settings_privacy_policy"
internal const val TAG_TERMS_OF_SERVICE = "settings_terms_of_service"
internal const val TAG_LOCATION_TERMS = "settings_location_terms"
internal const val TAG_POLICY_ERROR = "settings_policy_error"
internal const val TAG_POLICY_RETRY = "settings_policy_retry"
internal const val TAG_POLICY_DISMISS = "settings_policy_dismiss"

private const val LOADING_INDICATOR_DELAY_MILLIS = 1_000L

/** `저장 중` 배지·토글 잠금을 보이기 전 기다리는 시간(#514). 조회 대기 표시와 같은 1초다. */
private const val SAVING_FEEDBACK_DELAY_MILLIS = 1_000L
private val MIN_TOUCH: Dp = 48.dp
private val ROW_MIN_HEIGHT: Dp = 72.dp
private val SPINNER_SIZE: Dp = 14.dp
private val SPINNER_STROKE: Dp = 2.dp
private val ERROR_ICON: Dp = 16.dp
private val POLICY_ROW_HEIGHT: Dp = 56.dp
private val POLICY_CHEVRON: Dp = 14.dp
private val AVATAR_SIZE: Dp = 56.dp
private val AVATAR_FALLBACK_ICON: Dp = 28.dp
private val KAKAO_BADGE: Dp = 16.dp
private val KAKAO_GLYPH: Dp = 10.dp

/** 가이드라인 7절 토글: 트랙 48×24, thumb 20(위·꺼짐 왼쪽 2, 켜짐 왼쪽 26), 비활성 50%. */
private val TOGGLE_TRACK_WIDTH: Dp = 48.dp
private val TOGGLE_TRACK_HEIGHT: Dp = 24.dp
private val TOGGLE_THUMB: Dp = 20.dp
private val TOGGLE_THUMB_INSET: Dp = 2.dp
private val TOGGLE_THUMB_ON: Dp = 26.dp
private const val TOGGLE_DISABLED_ALPHA = 0.5f

/** 7절 저장 중 배지 10sp·위아래 2dp(Figma `text-[10px] py-0.5`). */
private val BADGE_TEXT_SIZE = 10.sp
private val BADGE_VERTICAL_PADDING: Dp = 2.dp

/** 9절 인라인 실패 박스 행동 버튼 32dp, 박스 테두리·앱 정보 구분선 1dp. */
private val FAILURE_ACTION_HEIGHT: Dp = 32.dp
private val DIVIDER: Dp = 1.dp
