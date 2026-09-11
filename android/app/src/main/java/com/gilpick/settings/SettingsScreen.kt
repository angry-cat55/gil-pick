package com.gilpick.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gilpick.R
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing
import kotlinx.coroutines.delay

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
        Text(
            text = stringResource(R.string.settings_notification_section),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Black,
            color = LocalGilpickColors.current.muted,
            modifier = Modifier.padding(
                start = spacing.space5,
                end = spacing.space5,
                top = spacing.space4,
                bottom = spacing.space2,
            ),
        )

        when (phase) {
            PreferencePhase.Loading -> PreferenceRow(
                checked = false,
                enabled = false,
                onToggle = onToggle,
                trailing = { DelayedLoading() },
            )

            is PreferencePhase.Content -> PreferenceRow(
                checked = phase.value,
                enabled = !phase.isSaving,
                onToggle = onToggle,
                // 저장 중임을 색이 아니라 문구로 알린다(UI-005).
                badge = if (phase.isSaving) stringResource(R.string.settings_saving) else null,
            )

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
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.muted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(LocalGilpickRadius.current.sm))
                            .background(MaterialTheme.colorScheme.background)
                            .padding(horizontal = spacing.space2, vertical = spacing.space1)
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
                modifier = Modifier.size(MIN_TOUCH),
                contentAlignment = Alignment.Center,
                content = { trailing() },
            )
        } else {
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
                enabled = enabled,
                modifier = Modifier
                    .heightIn(min = MIN_TOUCH)
                    .testTag(TAG_TOGGLE),
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
            strokeWidth = SPINNER_STROKE,
            modifier = Modifier
                .size(SPINNER_SIZE)
                .clearAndSetSemantics { contentDescription = label },
        )
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
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val isLoadFailure = phase.lastConfirmedValue == null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.space5)
            .padding(bottom = spacing.space4)
            .clip(RoundedCornerShape(radius.md))
            .background(colors.warningContainer)
            .padding(spacing.space3)
            .testTag(TAG_ERROR_BAR),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lucide_triangle_alert),
            contentDescription = null,
            tint = colors.warning,
            modifier = Modifier.size(ERROR_ICON),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    if (isLoadFailure) R.string.settings_load_failed else R.string.settings_save_failed,
                ),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(phase.error.messageRes),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
        // Figma의 `다시 시도`는 32dp지만 터치 영역은 48dp를 지킨다(UI-005, AGENTS.md 6절).
        TextButton(
            onClick = when {
                phase.error == SettingsError.SessionExpired -> onReauthenticate
                isLoadFailure -> onRetryLoad
                else -> onRetrySave
            },
            modifier = Modifier
                .heightIn(min = MIN_TOUCH)
                .widthIn(min = MIN_TOUCH)
                .testTag(TAG_RETRY),
        ) {
            Text(
                text = stringResource(
                    if (phase.error == SettingsError.SessionExpired) R.string.place_reauthenticate else R.string.settings_retry,
                ),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = colors.warning,
            )
        }
    }
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
) {
    val spacing = LocalGilpickSpacing.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .testTag(TAG_POLICY_SECTION),
    ) {
        Text(
            text = stringResource(R.string.settings_app_section),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Black,
            color = LocalGilpickColors.current.muted,
            modifier = Modifier.padding(
                start = spacing.space5,
                end = spacing.space5,
                top = spacing.space4,
                bottom = spacing.space2,
            ),
        )
        PolicyRow(
            label = stringResource(R.string.settings_privacy_policy),
            tag = TAG_PRIVACY_POLICY,
            onClick = { onOpen(PolicyDocument.PRIVACY_POLICY) },
        )
        PolicyRow(
            label = stringResource(R.string.settings_terms_of_service),
            tag = TAG_TERMS_OF_SERVICE,
            onClick = { onOpen(PolicyDocument.TERMS_OF_SERVICE) },
        )
        openError?.let { failure ->
            PolicyErrorBar(failure = failure, onRetry = onRetry, onDismiss = onDismissError)
        }
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
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.space5)
            .padding(bottom = spacing.space4)
            .clip(RoundedCornerShape(radius.md))
            .background(colors.warningContainer)
            .padding(spacing.space3)
            .testTag(TAG_POLICY_ERROR),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lucide_triangle_alert),
            contentDescription = null,
            tint = colors.warning,
            modifier = Modifier.size(ERROR_ICON),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_policy_open_failed),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(failure.messageRes),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
        TextButton(
            onClick = onRetry,
            modifier = Modifier.heightIn(min = MIN_TOUCH).widthIn(min = MIN_TOUCH).testTag(TAG_POLICY_RETRY),
        ) {
            Text(
                text = stringResource(R.string.settings_retry),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = colors.warning,
            )
        }
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.heightIn(min = MIN_TOUCH).widthIn(min = MIN_TOUCH).testTag(TAG_POLICY_DISMISS),
        ) {
            Text(
                text = stringResource(R.string.settings_policy_dismiss),
                style = MaterialTheme.typography.labelMedium,
                color = colors.muted,
            )
        }
    }
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

internal const val TAG_PREFERENCE_SECTION = "settings_preference_section"
internal const val TAG_TOGGLE = "settings_toggle"
internal const val TAG_SAVING_BADGE = "settings_saving_badge"
internal const val TAG_ERROR_BAR = "settings_error_bar"
internal const val TAG_RETRY = "settings_retry"
internal const val TAG_POLICY_SECTION = "settings_policy_section"
internal const val TAG_PRIVACY_POLICY = "settings_privacy_policy"
internal const val TAG_TERMS_OF_SERVICE = "settings_terms_of_service"
internal const val TAG_POLICY_ERROR = "settings_policy_error"
internal const val TAG_POLICY_RETRY = "settings_policy_retry"
internal const val TAG_POLICY_DISMISS = "settings_policy_dismiss"

private const val LOADING_INDICATOR_DELAY_MILLIS = 1_000L
private val MIN_TOUCH: Dp = 48.dp
private val ROW_MIN_HEIGHT: Dp = 72.dp
private val SPINNER_SIZE: Dp = 20.dp
private val SPINNER_STROKE: Dp = 2.dp
private val ERROR_ICON: Dp = 16.dp
private val POLICY_ROW_HEIGHT: Dp = 56.dp
private val POLICY_CHEVRON: Dp = 14.dp
