package com.gilpick.progress

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gilpick.R
import com.gilpick.ui.component.EmptyStateSize
import com.gilpick.ui.component.GradientButton
import com.gilpick.ui.component.SecondaryButton
import com.gilpick.ui.theme.GilpickTheme
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickShadows
import com.gilpick.ui.theme.LocalGilpickSpacing

/**
 * 위치 권한 안내 화면(Figma `LocationPermissionScreen`, #440).
 *
 * `오늘 여행 시작` 전에 앱 사용 중 위치 권한이 없을 때 시스템 권한 창보다 먼저 보인다(F006 FR-004b).
 * 권한 요청 결과는 시스템 창이 처리하므로 이 화면에는 loading·empty·error 상태가 없다.
 * 백그라운드 위치는 여기서 묻지 않는다. 여행 중 화면의 자동 감지 안내가 두 번째 단계로 이어받는다(F007 UI-005).
 *
 * @param onAllow `위치 권한 허용하기`. 호출부가 시스템 권한 창을 띄운다.
 * @param onLater `나중에 하기`. 권한 없이 시작한다(FR-020, 수동 행동은 권한과 무관하게 동작).
 */
@Composable
fun LocationPermissionScreen(
    onAllow: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val scheme = MaterialTheme.colorScheme
    val cardShape = RoundedCornerShape(radius.lg)

    Column(modifier = modifier.background(scheme.background)) {
        // 360dp·글자 2.0배에서 본문이 넘치면 스크롤한다. 버튼은 아래에 고정한다.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.space6)
                .padding(top = spacing.space8, bottom = spacing.space4),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .padding(bottom = EmptyStateSize.Status.iconGap)
                    .size(EmptyStateSize.Status.box)
                    .background(scheme.primaryContainer, RoundedCornerShape(radius.xl)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_map_pin),
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(EmptyStateSize.Status.icon),
                )
            }
            Text(
                text = stringResource(R.string.location_permission_title),
                style = MaterialTheme.typography.headlineLarge,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = spacing.space2),
            )
            Text(
                text = stringResource(R.string.location_permission_body),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal),
                color = colors.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = spacing.space8),
            )
            PermissionCard(
                label = R.string.location_permission_benefits,
                labelColor = colors.muted,
                rows = listOf(
                    R.string.location_permission_benefit_route,
                    R.string.location_permission_benefit_detect,
                    R.string.location_permission_benefit_accuracy,
                ),
                icon = R.drawable.ic_lucide_check,
                iconSize = CHECK_ICON,
                iconColor = colors.success,
                circleColor = colors.successContainer,
                textColor = scheme.onSurface,
                modifier = LocalGilpickShadows.current.card
                    .fold(Modifier.padding(bottom = spacing.space4)) { acc, shadow -> acc.dropShadow(cardShape, shadow) }
                    .background(scheme.surface, cardShape),
            )
            PermissionCard(
                label = R.string.location_permission_limits,
                labelColor = colors.warning,
                rows = listOf(
                    R.string.location_permission_limit_reroute,
                    R.string.location_permission_limit_detect,
                    R.string.location_permission_limit_variables,
                ),
                icon = R.drawable.ic_lucide_lock,
                iconSize = LOCK_ICON,
                iconColor = colors.warning,
                circleColor = colors.cautionSoft,
                textColor = colors.onWarningContainer,
                modifier = Modifier.background(colors.warningContainer, cardShape),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.space4, end = spacing.space4, top = spacing.space4, bottom = BOTTOM_PADDING),
            verticalArrangement = Arrangement.spacedBy(spacing.space2),
        ) {
            GradientButton(
                label = stringResource(R.string.location_permission_allow),
                onClick = onAllow,
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryButton(
                label = stringResource(R.string.location_permission_later),
                onClick = onLater,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 머리 라벨 + 원 아이콘 3행 카드. 배경·그림자는 [modifier]로 받는다. */
@Composable
private fun PermissionCard(
    @StringRes label: Int,
    labelColor: Color,
    rows: List<Int>,
    @DrawableRes icon: Int,
    iconSize: Dp,
    iconColor: Color,
    circleColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalGilpickSpacing.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(spacing.space5),
        verticalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        Text(
            text = stringResource(label).uppercase(),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Black),
            color = labelColor,
            modifier = Modifier.padding(bottom = spacing.space1),
        )
        rows.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.space3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(ROW_CIRCLE).background(circleColor, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painter = painterResource(icon), contentDescription = null, tint = iconColor, modifier = Modifier.size(iconSize))
                }
                Text(
                    text = stringResource(row),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = textColor,
                )
            }
        }
    }
}

/** Figma `LocationPermissionScreen` 실측. 화면 전용이라 테마 토큰이 아니라 여기에 둔다. */
private val ROW_CIRCLE = 20.dp
private val CHECK_ICON = 10.dp
private val LOCK_ICON = 9.dp
private val BOTTOM_PADDING = 40.dp

@Preview(widthDp = 360, heightDp = 800)
@Composable
private fun LocationPermissionScreenPreview() = GilpickTheme {
    LocationPermissionScreen(onAllow = {}, onLater = {}, modifier = Modifier.fillMaxSize())
}
