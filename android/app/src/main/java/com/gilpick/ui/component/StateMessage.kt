package com.gilpick.ui.component

import androidx.annotation.DrawableRes
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gilpick.R
import com.gilpick.ui.theme.GilpickSpacing
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickShadows
import com.gilpick.ui.theme.LocalGilpickSizing
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.gilpick.ui.theme.displayFont
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 빈 상태·안내 아이콘 상자 4단계(가이드라인 5절, D3). 상자 크기와 제목 크기가 짝지어져 있다.
 *
 * @property box 아이콘 상자 한 변.
 * @property icon 아이콘 한 변.
 * @property iconGap 상자와 제목 사이.
 * @property bodyGap 제목과 설명 사이.
 * @property actionGap 설명과 다음 행동 사이.
 */
enum class EmptyStateSize(val box: Dp, val icon: Dp, internal val iconGap: Dp, internal val bodyGap: Dp, internal val actionGap: Dp) {
    /** 목록·검색 영역 안 빈 상태. 64dp `radiusLg`, 15sp 제목(`AddPlaceScreen`). */
    Inline(64.dp, 28.dp, 16.dp, 4.dp, 20.dp),

    /** 화면 전체 빈 상태. 80dp `radiusXl`, 18~22sp 제목(`MyTripsScreen`·`VariableMonitorScreen`). */
    Screen(80.dp, 34.dp, 24.dp, 8.dp, 28.dp),

    /** 헤더 없는 전체 화면 안내. 96dp `radiusXl`, Status title 24sp(`LocationPermissionScreen`). */
    Status(96.dp, 44.dp, 32.dp, 8.dp, 32.dp),

    /** 카드 안 완료 표시. 56dp `radiusLg`, 18sp 제목(`ActiveTravelScreen` 당일 완료). */
    Card(56.dp, 28.dp, 12.dp, 4.dp, 16.dp),
}

/** 아이콘 상자의 의미 계열. 가이드라인 5절 표의 "배경 / 아이콘" 열이다. */
enum class EmptyStateTone {
    /** `background` 상자 / `faint` 아이콘. 정보가 없다는 중립 안내. */
    Neutral,

    /** `primaryContainer` / `primary`. 사용자가 할 수 있는 안내(권한 요청 등). */
    Primary,

    /** `successContainer` / `success`. 할 일이 없는 것이 좋은 상태(감지 없음, 당일 완료). */
    Success,
}

/**
 * 빈 상태(가이드라인 9절 `empty`): 아이콘 상자, 제목, `muted` 설명, 다음 행동.
 *
 * 크기 단계는 [size]로 고른다. 세로 위치는 호출부가 정한다. 화면 전체를 채우는 빈 상태는
 * `Modifier.fillMaxSize()`를 넘기면 세로 중앙보다 살짝 위(하단 여백 60dp)에 놓인다.
 *
 * @param icon 장식 아이콘. 제목이 뜻을 전달하므로 설명 문구를 붙이지 않는다(10절).
 * @param titleStyle 제목 크기. 기본은 단계별 짝(15·22·24·18sp). `Screen`은 Figma가 18과 22를 섞어 쓰므로 필요하면 `titleMedium`을 넘긴다.
 * @param onDark 어두운 배경(일자 경로 화면) 위 변형. 상자는 흰 10%, 제목은 흰색, 설명은 `onDarkMuted`.
 * @param action 다음 행동 버튼. 없으면 자리도 비운다.
 */
@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    size: EmptyStateSize = EmptyStateSize.Screen,
    tone: EmptyStateTone = EmptyStateTone.Neutral,
    titleStyle: TextStyle = size.defaultTitleStyle(),
    onDark: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val scheme = MaterialTheme.colorScheme

    val (boxColor, iconColor) = when {
        onDark -> Color.White.copy(alpha = 0.1f) to colors.faint
        tone == EmptyStateTone.Primary -> scheme.primaryContainer to scheme.primary
        tone == EmptyStateTone.Success -> colors.successContainer to colors.success
        else -> scheme.background to colors.faint
    }
    val boxRadius = when (size) {
        EmptyStateSize.Inline, EmptyStateSize.Card -> radius.lg
        EmptyStateSize.Screen, EmptyStateSize.Status -> radius.xl
    }

    Column(
        modifier = modifier
            .padding(horizontal = spacing.space6)
            .padding(bottom = if (size == EmptyStateSize.Card) 0.dp else LocalGilpickSizing.current.emptyBottomPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = size.iconGap)
                .size(size.box)
                .background(boxColor, RoundedCornerShape(boxRadius)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painter = painterResource(icon), contentDescription = null, tint = iconColor, modifier = Modifier.size(size.icon))
        }
        Text(
            text = title,
            style = titleStyle,
            color = if (onDark) Color.White else scheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = size.bodyGap),
        )
        Text(
            text = body,
            style = size.bodyStyle(),
            color = if (onDark) colors.onDarkMuted else colors.muted,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Box(modifier = Modifier.padding(top = size.actionGap)) { action() }
        }
    }
}

@Composable
private fun EmptyStateSize.defaultTitleStyle(): TextStyle = when (this) {
    EmptyStateSize.Inline -> MaterialTheme.typography.titleSmall
    EmptyStateSize.Screen -> MaterialTheme.typography.headlineSmall
    EmptyStateSize.Status -> MaterialTheme.typography.headlineLarge
    EmptyStateSize.Card -> MaterialTheme.typography.titleMedium
}

/** 설명은 작은 단계 13sp, 큰 단계 14sp이며 굵기는 Figma대로 400이다. */
@Composable
private fun EmptyStateSize.bodyStyle(): TextStyle = when (this) {
    EmptyStateSize.Inline, EmptyStateSize.Card -> MaterialTheme.typography.bodyMedium
    EmptyStateSize.Screen, EmptyStateSize.Status -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal)
}

/**
 * 오류 화면 원인 카드의 두 행(가이드라인 9절, D5). 둘 다 없으면 카드를 그리지 않는다.
 *
 * 오류 코드·서버 메시지·stack trace를 넣을 자리는 일부러 없다. 사용자에게 뜻이 없는 문자열이기 때문이다.
 *
 * @property occurredAt 실패가 일어난 시각. `오후 2:32` 형식으로 표시한다.
 * @property lastAction 실패한 사용자 행동이나 시스템 작업. 기술 용어가 아니라 사용자가 알아듣는 이름(`경로 재계산`, 8절).
 */
data class ErrorCause(val occurredAt: Instant? = null, val lastAction: String? = null) {
    internal val isEmpty: Boolean get() = occurredAt == null && lastAction == null
}

private val OCCURRED_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN).withZone(ZoneId.of("Asia/Seoul"))

/** 발생 시각 표기. 한국어 오전·오후 + `h:mm`, KST. */
fun occurredAtLabel(instant: Instant): String = OCCURRED_AT_FORMAT.format(instant)

/**
 * 오류 화면(가이드라인 9절 "오류 화면", Figma `ErrorScreen`). 화면 전체를 대신하는 오류에 쓴다.
 * 화면 일부만 실패하면 9절 "실패 안내 박스"를 쓴다.
 *
 * `background` 바탕에 가운데 본문(스크롤 가능)과 하단 고정 버튼 두 개다. 세로 공간을 차지하므로
 * 호출부는 보통 `Modifier.fillMaxSize()`를 넘긴다.
 *
 * @param description 무엇이 실패했는지와 **기존 데이터가 그대로라는 사실**을 담은 두 줄.
 * @param primaryLabel 주버튼 문구(`다시 시도하기`). 세션 만료면 `다시 로그인`처럼 상황에 맞는 행동을 넘긴다.
 * @param title 기본은 `문제가 발생했어요`.
 * @param cause 원인 카드 2행. 값을 모르면 넘기지 않는다(12절, 값을 지어내지 않는다).
 * @param hint 안내 배너의 다음 행동 문장(`인터넷 연결을 확인한 후 재시도해주세요`). 없으면 배너를 그리지 않는다.
 * @param secondaryLabel 돌아갈 곳을 적은 보조 버튼 문구(`여행 진행으로 돌아가기`). 없으면 보조 버튼을 그리지 않는다.
 */
@Composable
fun ErrorState(
    description: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.state_error_title),
    cause: ErrorCause? = null,
    hint: String? = null,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val scheme = MaterialTheme.colorScheme

    Column(modifier = modifier.background(scheme.background)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.space6, vertical = spacing.space6),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .padding(bottom = spacing.space8)
                    .size(EmptyStateSize.Status.box)
                    .background(scheme.errorContainer, RoundedCornerShape(radius.xl)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_triangle_alert),
                    contentDescription = null,
                    tint = scheme.error,
                    modifier = Modifier.size(EmptyStateSize.Status.icon),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = spacing.space2),
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal),
                color = colors.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = spacing.space8),
            )
            if (cause != null && !cause.isEmpty) {
                CauseCard(cause, spacing)
            }
            if (hint != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(scheme.primaryContainer, RoundedCornerShape(radius.lg))
                        .padding(horizontal = spacing.space4, vertical = spacing.space3),
                    horizontalArrangement = Arrangement.spacedBy(HINT_ICON_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_info),
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(HINT_ICON),
                    )
                    Text(text = hint, style = MaterialTheme.typography.bodySmall, color = colors.primaryDark)
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.space4, end = spacing.space4, top = spacing.space4, bottom = ERROR_BOTTOM_PADDING),
            verticalArrangement = Arrangement.spacedBy(spacing.space2),
        ) {
            GradientButton(label = primaryLabel, onClick = onPrimary, modifier = Modifier.fillMaxWidth())
            if (secondaryLabel != null) {
                SecondaryButton(label = secondaryLabel, onClick = onSecondary, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** 흰 카드, `radiusLg`, 카드 그림자, 안쪽 20. 머리 `오류 정보` 다음 라벨·값 행. 값은 Outfit(숫자·라틴)이다. */
@Composable
private fun CauseCard(cause: ErrorCause, spacing: GilpickSpacing) {
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(radius.lg)

    Column(
        modifier = LocalGilpickShadows.current.card
            .fold(Modifier.fillMaxWidth().padding(bottom = spacing.space4)) { acc, shadow -> acc.dropShadow(shape, shadow) }
            .background(scheme.surface, shape)
            .padding(spacing.space5),
        verticalArrangement = Arrangement.spacedBy(spacing.space3),
    ) {
        Text(
            text = stringResource(R.string.state_error_info).uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
            color = colors.muted,
        )
        cause.occurredAt?.let { CauseRow(stringResource(R.string.state_error_occurred_at), occurredAtLabel(it)) }
        cause.lastAction?.let { CauseRow(stringResource(R.string.state_error_last_action), it) }
    }
}

@Composable
private fun CauseRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontFamily = value.displayFont()),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = LocalGilpickSpacing.current.space4),
        )
    }
}

/** Figma `ErrorScreen` 실측. 컴포넌트 전용이라 테마 토큰이 아니라 여기에 둔다. */
private val ERROR_BOTTOM_PADDING = 40.dp
private val HINT_ICON = 14.dp
private val HINT_ICON_GAP = 10.dp
