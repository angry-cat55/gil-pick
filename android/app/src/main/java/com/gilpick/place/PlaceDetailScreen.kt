package com.gilpick.place

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.gilpick.R
import com.gilpick.ui.component.RemoteImage
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickSpacing
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 장소 상세 화면.
 *
 * 모양은 pen `22. 장소 상세`를 따른다. `Hero` 이미지, `Head`(이름·category·주소·평점),
 * `InfoBlock`(소개·추천 체류 시간·전화·주소)과 `Source` 안내까지 그린다. pen의
 * `ActionBar`(`일정에 추가`, 지도)는 F004·지도 범위라 만들지 않는다(`spec.md` FR-022).
 *
 * pen에 없는 `운영 안내`와 Google `영업시간` 목록은 FR-006이 요구하는 상세 정보라
 * `InfoBlock` 아래에 같은 형식으로 둔다(가이드라인 12절, 기능 요구가 pen보다 위).
 *
 * provider가 주지 않은 값은 지어내지 않고 `정보 없음`으로 구분한다(FR-007). 장소별
 * provider 배지는 없고, Google 평점·영업정보 영역에만 attribution을 붙인다(UI-012).
 *
 * @param state 현재 상세 상태.
 * @param onBack 이전 화면(검색 결과)으로 돌아간다.
 * @param onRetry 실패한 조회를 다시 시도한다.
 */
@Composable
fun PlaceDetailScreen(
    state: PlaceDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val phase = state.phase) {
        is PlaceDetailPhase.Content -> Content(place = phase.place, onBack = onBack, modifier = modifier)

        PlaceDetailPhase.Loading -> WithAppBar(onBack = onBack, modifier = modifier) { LoadingState() }

        PlaceDetailPhase.NotFound -> WithAppBar(onBack = onBack, modifier = modifier) {
            StateMessage(
                title = stringResource(R.string.place_detail_not_found_title),
                body = stringResource(R.string.place_detail_not_found_hint),
                action = {
                    Button(onClick = onBack, modifier = Modifier.heightIn(min = PRIMARY_BUTTON_HEIGHT)) {
                        Text(stringResource(R.string.place_detail_back_to_search))
                    }
                },
            )
        }

        is PlaceDetailPhase.Failed -> WithAppBar(onBack = onBack, modifier = modifier) {
            // 재시도해도 결과가 같은 실패(호출 한도, 로그인 만료 등)에는 검색으로 돌아가는 길을 준다.
            val retryable = phase.error.retryable
            StateMessage(
                title = stringResource(phase.error.detailMessageRes),
                titleColor = MaterialTheme.colorScheme.error,
                body = null,
                live = true,
                action = {
                    Button(
                        onClick = if (retryable) onRetry else onBack,
                        modifier = Modifier.heightIn(min = PRIMARY_BUTTON_HEIGHT),
                    ) {
                        Text(
                            stringResource(
                                if (retryable) R.string.place_detail_retry
                                else R.string.place_detail_back_to_search,
                            ),
                        )
                    }
                },
            )
        }
    }
}

/** 이미지가 없는 상태(대기·오류·없음)는 일반 AppBar 아래에 안내를 둔다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WithAppBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.place_detail_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.size(MIN_TOUCH)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        // 아이콘 전용 버튼이므로 설명이 필수다(가이드라인 10절).
                        contentDescription = stringResource(R.string.place_detail_back),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
        Box(modifier = Modifier.weight(1f)) { content() }
    }
}

/** 조회 대기 표시. 1초를 넘길 때만 표시한다(가이드라인 9절). */
@Composable
private fun LoadingState() {
    var visible by remember { mutableStateOf(false) }
    val label = stringResource(R.string.place_detail_loading)

    LaunchedEffect(Unit) {
        delay(LOADING_INDICATOR_DELAY_MILLIS)
        visible = true
    }

    if (visible) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.clearAndSetSemantics { contentDescription = label },
            )
        }
    }
}

/** 가운데 정렬된 안내. 없음·오류가 같은 틀을 쓴다. */
@Composable
private fun StateMessage(
    title: String,
    body: String?,
    titleColor: Color = MaterialTheme.colorScheme.onBackground,
    live: Boolean = false,
    action: @Composable () -> Unit,
) {
    val spacing = LocalGilpickSpacing.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = spacing.space5 + spacing.space3)
            .then(if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
        verticalArrangement = Arrangement.spacedBy(spacing.space3, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = titleColor,
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        action()
    }
}

/**
 * 장소 내용(pen `Hero`·`Head`·`InfoBlock`·`Source`).
 *
 * 세로 스크롤 하나로 둔다. 시스템 글자 확대 최대 배율에서도 잘리지 않도록 고정 높이는
 * hero 이미지에만 쓴다(가이드라인 4절).
 */
@Composable
private fun Content(place: PlaceDto, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current
    // 실시간 정보에는 갱신 시각을 붙인다(가이드라인 9절). 이 내용이 처음 그려진 시각이다.
    val fetchedAt = remember(place.placeId) { LocalTime.now().format(TIME_FORMAT) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Hero(place = place, onBack = onBack)
        Head(place = place)
        InfoBlock(place = place)
        if (place.hasGoogleData) {
            GoogleBlock(place = place, fetchedAt = fetchedAt)
        }
        Box(modifier = Modifier.height(spacing.space6))
    }
}

/**
 * 대표 이미지와 그 위의 뒤로 가기 버튼.
 *
 * 이미지는 화면의 주된 시각 정보라 설명을 붙인다(UI-005). 이미지가 없으면 같은 높이의
 * 대체 표현을 두고 `대표 사진 없음`을 표시해 누락을 구분한다.
 */
@Composable
private fun Hero(place: PlaceDto, onBack: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val noImage = stringResource(R.string.place_detail_no_image)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HERO_HEIGHT),
    ) {
        RemoteImage(
            url = place.imageUrl,
            contentDescription = if (place.imageUrl == null) noImage
            else stringResource(R.string.place_detail_image_description, place.name),
            fallbackIconSize = HERO_FALLBACK_ICON,
            modifier = Modifier.fillMaxSize(),
        )
        if (place.imageUrl == null) {
            Text(
                text = noImage,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(spacing.space4)
                    .clearAndSetSemantics {},
            )
        }
        // pen `BackButton`: 보이는 원은 40dp지만 누르는 영역은 48dp다(가이드라인 10절).
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(spacing.space2)
                .size(MIN_TOUCH),
        ) {
            Box(
                modifier = Modifier
                    .size(BACK_CIRCLE)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.place_detail_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** 이름·category·주소와 평점·리뷰 수·영업 상태(pen `Head`). */
@Composable
private fun Head(place: PlaceDto) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val categoryLabel = stringResource(place.category.labelRes)
    val statusRes = businessStatusLabelRes(place.businessStatus)
    val ratingText = place.rating?.toRatingText()

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(
                start = spacing.space5,
                end = spacing.space5,
                top = spacing.space4,
                bottom = spacing.space4 + spacing.space1 / 2,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.space1 + spacing.space1 / 2),
        ) {
            Text(
                text = place.name,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = place.address?.let { stringResource(R.string.place_detail_meta, categoryLabel, it) }
                    ?: categoryLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ratingText != null || place.userRatingCount != null || statusRes != null) {
                Row(
                    modifier = Modifier.padding(top = spacing.space1 / 2),
                    horizontalArrangement = Arrangement.spacedBy(spacing.space2 + spacing.space1 / 2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (ratingText != null) {
                        val description = stringResource(R.string.place_rating_description, ratingText)
                        Text(
                            text = stringResource(R.string.place_rating, ratingText),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.semantics { contentDescription = description },
                        )
                    }
                    place.userRatingCount?.let { count ->
                        Text(
                            text = stringResource(
                                R.string.place_rating_count,
                                String.format(Locale.KOREA, "%,d", count),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (statusRes != null) {
                        Text(
                            text = stringResource(statusRes),
                            style = MaterialTheme.typography.labelMedium,
                            color = when (place.businessStatus) {
                                PlaceBusinessStatus.OPERATIONAL -> colors.success
                                PlaceBusinessStatus.CLOSED_TEMPORARILY -> colors.warning
                                else -> MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 소개와 라벨·값 행들(pen `InfoBlock`). 없는 값은 `정보 없음`으로 쓴다. */
@Composable
private fun InfoBlock(place: PlaceDto) {
    val spacing = LocalGilpickSpacing.current
    val missing = stringResource(R.string.place_detail_missing)

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.space2),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = spacing.space5, vertical = spacing.space4),
            verticalArrangement = Arrangement.spacedBy(spacing.space3 + spacing.space1 / 2),
        ) {
            Text(
                text = place.description ?: stringResource(R.string.place_detail_description_missing),
                style = MaterialTheme.typography.bodyLarge,
                color = if (place.description == null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            InfoRow(
                icon = Icons.Filled.DateRange,
                label = stringResource(R.string.place_detail_stay_label),
                value = stringResource(R.string.place_detail_stay_value, place.recommendedStayMinutes),
                missing = false,
            )
            InfoRow(
                icon = Icons.Filled.Call,
                label = stringResource(R.string.place_detail_phone_label),
                value = place.phone ?: missing,
                missing = place.phone == null,
            )
            InfoRow(
                icon = Icons.Filled.Place,
                label = stringResource(R.string.place_detail_address_label),
                value = place.address ?: missing,
                missing = place.address == null,
            )
            InfoRow(
                icon = Icons.Filled.Info,
                label = stringResource(R.string.place_detail_operating_label),
                value = place.operatingGuide ?: missing,
                missing = place.operatingGuide == null,
            )
        }
    }
}

/** 아이콘·라벨(96dp)·값 한 줄(pen `Row`). 값이 길면 줄바꿈하고 라벨 폭은 유지한다. */
@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String, missing: Boolean) {
    val spacing = LocalGilpickSpacing.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.space2 + spacing.space1 / 2),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = spacing.space1 / 2)
                .size(INFO_ICON_SIZE),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(INFO_LABEL_WIDTH),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = if (missing) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Google 평점·영업정보 영역.
 *
 * Google 데이터가 표시되는 유일한 컨테이너라 필수 attribution을 여기 붙인다(FR-021,
 * UI-012). 갱신 시각도 함께 쓴다(가이드라인 9절). 영업시간 문자열은 Google이 준 표시
 * 문자열 그대로이며 현재 영업 여부를 계산하지 않는다(FR-007).
 */
@Composable
private fun GoogleBlock(place: PlaceDto, fetchedAt: String) {
    val spacing = LocalGilpickSpacing.current
    val attribution = place.googleAttributions
        ?.filter { it.isNotBlank() }
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString(", ")
        ?: stringResource(R.string.place_google_attribution_default)

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.space2),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = spacing.space5, vertical = spacing.space4),
            verticalArrangement = Arrangement.spacedBy(spacing.space3),
        ) {
            Text(
                text = stringResource(R.string.place_detail_google_section),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            HoursList(label = stringResource(R.string.place_detail_hours_regular), hours = place.regularOpeningHours)
            HoursList(label = stringResource(R.string.place_detail_hours_current), hours = place.currentOpeningHours)
            Text(
                text = stringResource(R.string.place_detail_google_note, fetchedAt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.place_google_attribution, attribution),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 영업시간 줄 목록. 없으면 그리지 않는다. */
@Composable
private fun HoursList(label: String, hours: List<String>?) {
    if (hours.isNullOrEmpty()) return
    val spacing = LocalGilpickSpacing.current

    Column(verticalArrangement = Arrangement.spacedBy(spacing.space1)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        hours.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 갱신 시각 표기. `오후 2:31` 형식. */
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREA)

/** 가이드라인 9절: 1초를 넘길 때만 대기 표시를 띄운다. */
private const val LOADING_INDICATOR_DELAY_MILLIS = 1_000L

/** 가이드라인 5절·10절: 주요 CTA 52~56dp, 터치 영역 48dp 이상. */
private val PRIMARY_BUTTON_HEIGHT = Dp(56f)
private val MIN_TOUCH = Dp(48f)

/** pen `22. 장소 상세`의 hero 220dp, 뒤로 가기 원 40dp, 행 아이콘 17dp, 라벨 폭 96dp. */
private val HERO_HEIGHT = Dp(220f)
private val HERO_FALLBACK_ICON = Dp(48f)
private val BACK_CIRCLE = Dp(40f)
private val INFO_ICON_SIZE = Dp(17f)
private val INFO_LABEL_WIDTH = Dp(96f)
