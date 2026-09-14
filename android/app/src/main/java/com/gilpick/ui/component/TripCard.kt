package com.gilpick.ui.component

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gilpick.R
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickShadows
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.gilpick.ui.theme.displayFont

/*
 * 여행 카드 3종(Figma `MyTripsScreen`, #441).
 *
 * 도메인 타입을 받지 않고 문자열만 받는다. `com.gilpick.ui.component`가 특정 feature에 의존하면 다른 feature가 이 컴포넌트를
 * 쓸 근거가 없어진다(가이드라인 11절). 커버 이미지는 여행 API에 값이 없어 지어내지 않고 이미지 대체 배경(`faint`)만 둔다(12절).
 * 카드 전체가 누를 수 있는 하나의 대상이다(48dp 이상, 10절).
 */

/**
 * 진행 중 여행 카드: 140dp 커버 자리 + 아래 어둡게, 이미지 위 제목·지역·`여행 중` 칩, 2dp `primary` 테두리 + 진행 중 카드 그림자.
 *
 * @param region 지역 줄. 값이 없으면 호출부가 `정보 없음`을 넘긴다.
 * @param period 기간(`5월 21일 – 5월 25일`).
 * @param length 일수(`4박 5일`).
 * @param trailing 오른쪽 파란 강조(`장소 12곳`). 값이 없으면 `null`이라 자리를 비운다.
 */
@Composable
fun ActiveTripCard(
    title: String,
    region: String,
    period: String,
    length: String,
    badgeLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    val spacing = LocalGilpickSpacing.current
    val scheme = MaterialTheme.colorScheme
    val onImage = scheme.onPrimary
    val shape = RoundedCornerShape(LocalGilpickRadius.current.lg)

    CardSurface(shape = shape, shadows = LocalGilpickShadows.current.activeTripCard, onClick = onClick, modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ACTIVE_COVER_HEIGHT)
                .background(LocalGilpickColors.current.faint),
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to scheme.scrim.copy(alpha = 0f),
                            COVER_SHADE_START to scheme.scrim.copy(alpha = 0f),
                            1f to scheme.scrim.copy(alpha = COVER_SHADE_END),
                        ),
                    ),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = spacing.space4, vertical = spacing.space3)
                    .padding(top = ACTIVE_TEXT_TOP),
                horizontalArrangement = Arrangement.spacedBy(spacing.space2),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = title.displayFont(),
                        color = onImage,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(text = region, style = MaterialTheme.typography.bodySmall, color = onImage.copy(alpha = REGION_ALPHA))
                }
                StatusBadge(label = badgeLabel, tone = BadgeTone.SUCCESS)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.space4, vertical = spacing.space3),
            horizontalArrangement = Arrangement.spacedBy(spacing.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(spacing.space3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = period, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
                Box(modifier = Modifier.size(DOT).background(LocalGilpickColors.current.faint, CircleShape))
                Text(text = length, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            trailing?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = scheme.primary)
            }
        }
    }
}

/**
 * 예정 여행 카드: 64dp 썸네일 자리, 제목 + D-day 칩, 기간, 일수·장소 수, chevron.
 *
 * @param badgeLabel D-day(`D-7`). 오늘과 시작일로 계산한 값이다.
 * @param meta 셋째 줄(`4박 5일 · 10곳`).
 */
@Composable
fun UpcomingTripCard(
    title: String,
    badgeLabel: String,
    period: String,
    meta: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val shape = RoundedCornerShape(LocalGilpickRadius.current.lg)

    CardSurface(shape = shape, shadows = LocalGilpickShadows.current.card, onClick = onClick, modifier = modifier) {
        Row(
            modifier = Modifier.padding(spacing.space4),
            horizontalArrangement = Arrangement.spacedBy(spacing.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(size = UPCOMING_THUMB)
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.space2), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        // Figma는 한 줄 말줄임이지만 글자 배율이 크면 제목이 거의 사라져 두 줄까지 둔다(10절).
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    StatusBadge(label = badgeLabel, tone = BadgeTone.ACCENT)
                }
                Text(text = period, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = meta, style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
            Icon(
                painter = painterResource(R.drawable.ic_lucide_chevron_right),
                // 카드 전체가 누를 수 있는 대상이라 장식이다(10절).
                contentDescription = null,
                tint = colors.faint,
                modifier = Modifier.size(CHEVRON),
            )
        }
    }
}

/**
 * 완료 여행 카드: 56dp 썸네일 자리, 흐림(75%), 제목·기간·방문 요약, `완료` 칩.
 *
 * @param meta 셋째 줄(`14곳 방문 · 1곳 건너뜀`). 값이 없으면 `null`이라 줄을 비운다.
 */
@Composable
fun CompletedTripCard(
    title: String,
    period: String,
    badgeLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val shape = RoundedCornerShape(LocalGilpickRadius.current.lg)

    CardSurface(
        shape = shape,
        shadows = LocalGilpickShadows.current.card,
        onClick = onClick,
        modifier = modifier.alpha(COMPLETED_ALPHA),
    ) {
        Row(
            modifier = Modifier.padding(spacing.space4),
            horizontalArrangement = Arrangement.spacedBy(spacing.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(size = COMPLETED_THUMB)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = period, style = MaterialTheme.typography.bodySmall, color = colors.muted)
                meta?.let { Text(text = it, style = MaterialTheme.typography.bodySmall, color = colors.muted) }
            }
            StatusBadge(label = badgeLabel, tone = BadgeTone.NEUTRAL)
        }
    }
}

/** 흰 카드 바탕: 그림자 토큰(CSS 겹 순서) → 곡률 → `surface` → 누를 수 있는 영역. */
@Composable
private fun CardSurface(
    shape: Shape,
    shadows: List<Shadow>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = shadows.fold(modifier.fillMaxWidth()) { acc, shadow -> acc.dropShadow(shape, shadow) }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .heightIn(min = MIN_TOUCH)
            .clickable(onClick = onClick),
    ) { content() }
}

/** 커버 이미지 자리. API에 이미지가 없어 대체 배경만 둔다(Figma `bg-[#E8EDF5]` 자리, `faint`). */
@Composable
private fun Thumbnail(size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(LocalGilpickRadius.current.md))
            .background(LocalGilpickColors.current.faint),
    )
}

/** Figma 카드 치수: 진행 중 커버 140, 예정 썸네일 64, 완료 썸네일 56, chevron 16, 기간 사이 점 4. */
private val ACTIVE_COVER_HEIGHT = 140.dp
private val UPCOMING_THUMB = 64.dp
private val COMPLETED_THUMB = 56.dp
private val CHEVRON = 16.dp
private val DOT = 4.dp

/** 진행 중 커버 글자가 위로 넘치지 않게 두는 최소 여백. */
private val ACTIVE_TEXT_TOP = 48.dp

/** Figma 커버 어둡게(`transparent 40% → rgba(0,0,0,0.6)`)와 지역 글자 `text-white/70`. */
private const val COVER_SHADE_START = 0.4f
private const val COVER_SHADE_END = 0.6f
private const val REGION_ALPHA = 0.7f

/** Figma 완료 카드 `opacity-75`. */
private const val COMPLETED_ALPHA = 0.75f

/** 가이드라인 10절: 누를 수 있는 영역은 48dp 이상. */
private val MIN_TOUCH = 48.dp
