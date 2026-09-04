package com.gilpick.place

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.gilpick.R
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.gilpick.ui.theme.displayFont
import kotlinx.coroutines.delay

/**
 * 장소 상세 화면.
 *
 * 모양은 Figma Make `Design UI from Reference`의 `PlaceDetailScreen.tsx`를 그대로 따른다
 * (사용자 결정: pen 정본보다 Figma 우선, UI-010·UI-013·UI-014). 색·곡률·타입 역할은
 * `Theme.kt`(Figma 값을 옮긴 토큰)에서 읽고, 토큰에 자리가 없는 크기만 Figma 값을 그대로 쓴다.
 *
 * Figma에 있지만 API(`PlaceDto`)에 없는 값(입장료·혼잡도·날씨·운영시간 요약)은 지어내지
 * 않고 `정보 없음`으로 둔다(FR-007). Google 평점·영업정보 attribution은 Google 약관상
 * 필수라 Figma에 없어도 붙인다(FR-021).
 *
 * @param state 현재 상세 상태.
 * @param onBack 이전 화면(검색 결과)으로 돌아간다.
 * @param onRetry 실패한 조회를 다시 시도한다.
 * @param onAddToSchedule 시트에서 이동 수단·체류 시간을 확정했을 때. 저장은 F004가 맡는다(FR-014).
 * @param onOpenMap 하단 지도 버튼. 지도 기능에서 연결한다.
 * @param onFavorite hero의 찜 버튼. 찜 기능은 아직 없다.
 */
@Composable
fun PlaceDetailScreen(
    state: PlaceDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onAddToSchedule: (AddToScheduleRequest) -> Unit = {},
    onOpenMap: () -> Unit = {},
    onFavorite: () -> Unit = {},
) {
    when (val phase = state.phase) {
        is PlaceDetailPhase.Content -> Content(
            place = phase.place,
            onBack = onBack,
            onAddToSchedule = onAddToSchedule,
            onOpenMap = onOpenMap,
            onFavorite = onFavorite,
            modifier = modifier,
        )

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

/** 이미지가 없는 상태(대기·오류·없음)는 일반 AppBar 아래에 안내를 둔다. Figma에 없는 상태다. */
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

/** 조회 대기 표시. 1초를 넘길 때만 표시한다(가이드라인 9절). 검색 첫 페이지도 같은 표시다. */
@Composable
internal fun LoadingState(label: String = stringResource(R.string.place_detail_loading)) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(LOADING_INDICATOR_DELAY_MILLIS)
        visible = true
    }

    if (visible) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.semantics { contentDescription = label },
            )
        }
    }
}

/** 가운데 정렬된 안내. 없음·오류가 같은 틀을 쓴다. 검색 화면의 오류·조건 안내도 같은 틀이다. */
@Composable
internal fun StateMessage(
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
 * Figma `PlaceDetailScreen`: hero(고정) → 스크롤(stats·정보 행·지도) → 하단 CTA.
 * Figma처럼 hero와 CTA는 스크롤되지 않는다. `일정에 추가`는 이동 수단·체류 시간 시트를 연다.
 */
@Composable
private fun Content(
    place: PlaceDto,
    onBack: () -> Unit,
    onAddToSchedule: (AddToScheduleRequest) -> Unit,
    onOpenMap: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSheet by remember { mutableStateOf(false) }

    if (showSheet) {
        AddToScheduleSheet(
            placeName = place.name,
            defaultMinutes = place.recommendedStayMinutes,
            onDismiss = { showSheet = false },
            onConfirm = { request ->
                showSheet = false
                onAddToSchedule(request)
            },
        )
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            // 앱은 edge-to-edge라 상태 표시줄 뒤까지 그려진다. Figma처럼 그 띠는 흰색으로 두고 hero는 그 아래서 시작한다.
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Hero(place = place, onBack = onBack, onFavorite = onFavorite)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Stats(place = place)
            InfoRows(place = place)
            MapPreview(name = place.name)
            Spacer(modifier = Modifier.height(96.dp))
        }
        ActionBar(onOpenMap = onOpenMap, onAddToSchedule = { showSheet = true })
    }
}

/** Figma `Hero`: 240dp 사진, 위 30%·아래 50% 검정 gradient, 원형 뒤로 가기·찜, 상태 칩, 이름, 주소. */
@Composable
private fun Hero(place: PlaceDto, onBack: () -> Unit, onFavorite: () -> Unit) {
    val noImage = stringResource(R.string.place_detail_no_image)
    val statusRes = businessStatusLabelRes(place.businessStatus)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(LocalGilpickColors.current.faint)
            // 이미지가 없을 때는 빈 자리 자체가 내용이므로 설명을 컨테이너에 붙인다.
            .then(if (place.imageUrl == null) Modifier.semantics { contentDescription = noImage } else Modifier),
    ) {
        if (place.imageUrl != null) {
            AsyncImage(
                model = place.imageUrl,
                contentDescription = stringResource(R.string.place_detail_image_description, place.name),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.3f),
                        0.5f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.5f),
                    ),
                ),
        )
        CircleIconButton(
            icon = R.drawable.ic_lucide_arrow_left,
            contentDescription = stringResource(R.string.place_detail_back),
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = LocalGilpickSpacing.current.space4 - CIRCLE_INSET, start = LocalGilpickSpacing.current.space5 - CIRCLE_INSET),
        )
        CircleIconButton(
            icon = R.drawable.ic_lucide_heart,
            contentDescription = stringResource(R.string.place_detail_favorite),
            onClick = onFavorite,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = LocalGilpickSpacing.current.space4 - CIRCLE_INSET, end = LocalGilpickSpacing.current.space5 - CIRCLE_INSET),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = LocalGilpickSpacing.current.space5, end = LocalGilpickSpacing.current.space5, bottom = LocalGilpickSpacing.current.space4),
        ) {
            if (statusRes != null) {
                Text(
                    text = stringResource(statusRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalGilpickColors.current.success,
                    modifier = Modifier
                        .padding(bottom = LocalGilpickSpacing.current.space2)
                        .background(LocalGilpickColors.current.successContainer, RoundedCornerShape(LocalGilpickRadius.current.sm))
                        .padding(horizontal = 10.dp, vertical = LocalGilpickSpacing.current.space1),
                )
            }
            Text(
                text = place.name,
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = place.name.displayFont(),
                color = Color.White,
            )
            Text(
                text = place.address ?: stringResource(R.string.place_detail_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

/** Figma의 36dp 원형 버튼(black 30%, 흰 18dp 아이콘). 터치 영역은 48dp로 두고 원 밖은 투명이다. */
@Composable
private fun CircleIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier.size(MIN_TOUCH)) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Figma `Stats`: 평점·운영시간·입장료 3열. 입장료는 API에 없어 `정보 없음`이다. */
@Composable
private fun Stats(place: PlaceDto) {
    val missing = stringResource(R.string.place_detail_missing)
    val ratingText = place.rating?.toRatingText()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = LocalGilpickSpacing.current.space5, vertical = LocalGilpickSpacing.current.space4),
        horizontalArrangement = Arrangement.spacedBy(LocalGilpickSpacing.current.space4),
    ) {
        Stat(
            value = ratingText ?: missing,
            label = stringResource(R.string.place_detail_stat_rating),
            description = ratingText?.let { stringResource(R.string.place_rating_description, it) },
            modifier = Modifier.weight(1f),
        )
        Stat(
            value = todayHoursLabel(place.currentOpeningHours ?: place.regularOpeningHours) ?: missing,
            label = stringResource(R.string.place_detail_stat_hours),
            modifier = Modifier.weight(1f),
        )
        Stat(
            value = missing,
            label = stringResource(R.string.place_detail_stat_fee),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier, description: String? = null) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
            fontFamily = value.displayFont(),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = if (description != null) Modifier.semantics { contentDescription = description } else Modifier,
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = LocalGilpickColors.current.muted,
        )
    }
}

/**
 * Figma `Info rows`: 주소·운영시간·혼잡도·날씨. 혼잡도·날씨는 API에 없어 `정보 없음`이다.
 * 운영시간에는 Google 영업시간과 TourAPI 운영 안내를 줄바꿈으로 함께 쓴다(FR-006).
 */
@Composable
private fun InfoRows(place: PlaceDto) {
    val missing = stringResource(R.string.place_detail_missing)
    val hours = listOfNotNull(
        place.regularOpeningHours?.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        place.operatingGuide,
    ).joinToString("\n").ifEmpty { missing }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = LocalGilpickSpacing.current.space2)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        InfoRow(R.drawable.ic_lucide_map_pin, stringResource(R.string.place_detail_address_label), place.address ?: missing)
        HorizontalDivider(color = MaterialTheme.colorScheme.background)
        InfoRow(R.drawable.ic_lucide_clock, stringResource(R.string.place_detail_hours_label), hours)
        HorizontalDivider(color = MaterialTheme.colorScheme.background)
        InfoRow(R.drawable.ic_lucide_users, stringResource(R.string.place_detail_crowd_label), missing)
        HorizontalDivider(color = MaterialTheme.colorScheme.background)
        InfoRow(R.drawable.ic_lucide_cloud_drizzle, stringResource(R.string.place_detail_weather_label), missing)
        if (place.hasGoogleData) {
            val attribution = place.googleAttributions
                ?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
                ?: stringResource(R.string.place_google_attribution_default)
            Text(
                text = stringResource(R.string.place_google_attribution, attribution),
                fontSize = 11.sp,
                color = LocalGilpickColors.current.muted,
                modifier = Modifier.padding(horizontal = LocalGilpickSpacing.current.space5, vertical = LocalGilpickSpacing.current.space3),
            )
        }
    }
}

@Composable
private fun InfoRow(@DrawableRes icon: Int, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalGilpickSpacing.current.space5, vertical = LocalGilpickSpacing.current.space4),
        horizontalArrangement = Arrangement.spacedBy(LocalGilpickSpacing.current.space4),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = LocalGilpickColors.current.muted,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp),
        )
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = LocalGilpickColors.current.muted,
                modifier = Modifier.padding(bottom = 2.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Figma `Map`: 격자·도로·핀을 그린 130dp 자리 표시. 실제 지도는 지도 기능에서 넣는다. */
@Composable
private fun MapPreview(name: String) {
    Box(
        modifier = Modifier
            .padding(top = LocalGilpickSpacing.current.space2, start = LocalGilpickSpacing.current.space4, end = LocalGilpickSpacing.current.space4)
            .fillMaxWidth()
            .height(130.dp)
            .clip(RoundedCornerShape(LocalGilpickRadius.current.lg))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val primary = MaterialTheme.colorScheme.primary
        Canvas(modifier = Modifier.matchParentSize()) {
            // Figma SVG viewBox 340×130 좌표를 실제 크기로 늘린다.
            val sx = size.width / 340f
            val sy = size.height / 130f
            val grid = primary.copy(alpha = 0.5f)
            var x = 0f
            while (x <= size.width) {
                drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.3f * sx)
                x += 24f * sx
            }
            var y = 0f
            while (y <= size.height) {
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.3f * sx)
                y += 24f * sx
            }
            val road = Path().apply {
                moveTo(0f, 65f * sy)
                quadraticTo(90f * sx, 50f * sy, 170f * sx, 65f * sy)
                quadraticTo(250f * sx, 80f * sy, 340f * sx, 55f * sy)
            }
            drawPath(road, Color.White.copy(alpha = 0.7f), style = Stroke(width = 8f * sx))
            drawLine(Color.White.copy(alpha = 0.5f), Offset(170f * sx, 0f), Offset(170f * sx, size.height), strokeWidth = 5f * sx)
            val pin = Offset(170f * sx, 65f * sy)
            drawCircle(primary.copy(alpha = 0.2f), radius = 22f * sx, center = pin)
            drawCircle(primary, radius = 12f * sx, center = pin)
        }
        Text(
            text = name,
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = name.displayFont(),
            color = Color.White,
        )
    }
}

/** Figma `CTA`: 지도 버튼(48×52, #F4F6FB)과 gradient `일정에 추가`(52dp). 위에 1dp 선. */
@Composable
private fun ActionBar(onOpenMap: () -> Unit, onAddToSchedule: () -> Unit) {
    val shape = RoundedCornerShape(LocalGilpickRadius.current.md)
    val openMap = stringResource(R.string.place_detail_open_map)

    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface)
            // 제스처 바 뒤까지 흰색을 채우고 버튼은 그 위에 둔다.
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.padding(horizontal = LocalGilpickSpacing.current.space5, vertical = LocalGilpickSpacing.current.space4),
            horizontalArrangement = Arrangement.spacedBy(LocalGilpickSpacing.current.space3),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 52.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.background)
                    .clickable(onClick = onOpenMap, role = Role.Button)
                    .semantics { contentDescription = openMap },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_map_pin),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(shape)
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, LocalGilpickColors.current.primaryDark)))
                    .clickable(onClick = onAddToSchedule, role = Role.Button),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.place_detail_add_to_schedule),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * Figma `Transport + duration modal`: 이동 수단 3종과 체류 시간(30~360분, 30분 단위) 선택 시트.
 * 검색 결과 행의 `+`도 같은 시트를 연다(UI-004).
 * 기본 체류 시간은 카테고리별 추천 체류시간이다(FR-009). Figma의 이동 시간·거리 설명은
 * 경로 계산 결과라 F003에 없어 표시하지 않는다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToScheduleSheet(
    placeName: String,
    defaultMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (AddToScheduleRequest) -> Unit,
) {
    var transport by remember { mutableStateOf(PlaceTransport.TRANSIT) }
    var minutes by remember { mutableIntStateOf(defaultMinutes.coerceIn(STAY_MIN, STAY_MAX)) }
    val shape = RoundedCornerShape(LocalGilpickRadius.current.lg)
    val title = stringResource(R.string.place_detail_sheet_title)
    val minutesText = stringResource(R.string.place_detail_stay_minutes, minutes)

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
                .padding(start = LocalGilpickSpacing.current.space6, end = LocalGilpickSpacing.current.space6, top = LocalGilpickSpacing.current.space5, bottom = LocalGilpickSpacing.current.space8)
                .navigationBarsPadding(),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = LocalGilpickSpacing.current.space5)
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
                text = stringResource(R.string.place_detail_sheet_subtitle, placeName),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalGilpickColors.current.muted,
                modifier = Modifier.padding(top = LocalGilpickSpacing.current.space1, bottom = LocalGilpickSpacing.current.space5),
            )
            PlaceTransport.entries.forEach { option ->
                TransportOption(
                    option = option,
                    selected = transport == option,
                    onClick = { transport = option },
                    modifier = Modifier.padding(bottom = LocalGilpickSpacing.current.space2),
                )
            }
            Text(
                text = stringResource(R.string.place_detail_stay_title),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = LocalGilpickSpacing.current.space3, bottom = LocalGilpickSpacing.current.space3),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = LocalGilpickSpacing.current.space4, vertical = LocalGilpickSpacing.current.space3),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepButton(
                    label = "−",
                    contentDescription = stringResource(R.string.place_detail_stay_decrease),
                    primary = false,
                    onClick = { minutes = (minutes - STAY_STEP).coerceAtLeast(STAY_MIN) },
                )
                Text(
                    text = minutesText,
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = minutesText.displayFont(),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                StepButton(
                    label = "+",
                    contentDescription = stringResource(R.string.place_detail_stay_increase),
                    primary = true,
                    onClick = { minutes = (minutes + STAY_STEP).coerceAtMost(STAY_MAX) },
                )
            }
            Row(
                modifier = Modifier.padding(top = LocalGilpickSpacing.current.space5),
                horizontalArrangement = Arrangement.spacedBy(LocalGilpickSpacing.current.space3),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .clip(shape)
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
                Box(
                    modifier = Modifier
                        .weight(2f)
                        .height(50.dp)
                        .clip(shape)
                        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, LocalGilpickColors.current.primaryDark)))
                        .clickable(
                            onClick = { onConfirm(AddToScheduleRequest(transport, minutes)) },
                            role = Role.Button,
                        )
                        .testTag(ADD_TO_SCHEDULE_CONFIRM_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.place_detail_add_to_schedule),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

/** Figma 이동 수단 카드: 2dp 테두리, 선택 시 `#3B7BF8` 테두리·`#EBF2FF` 배경·체크. */
@Composable
private fun TransportOption(
    option: PlaceTransport,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(LocalGilpickRadius.current.lg)
    val (icon, labelRes) = when (option) {
        PlaceTransport.WALK -> R.drawable.ic_lucide_walk to R.string.place_transport_walk
        PlaceTransport.TRANSIT -> R.drawable.ic_lucide_transit to R.string.place_transport_transit
        PlaceTransport.CAR -> R.drawable.ic_lucide_car to R.string.place_transport_car
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.White)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick, role = Role.RadioButton)
            .semantics { this.selected = selected }
            .padding(horizontal = LocalGilpickSpacing.current.space4, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(LocalGilpickSpacing.current.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else LocalGilpickColors.current.muted,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(labelRes),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
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

/** Figma 체류 시간 ±버튼: 보이는 원 40dp(흰색+그림자 / gradient), 터치 영역 48dp. */
@Composable
private fun StepButton(label: String, contentDescription: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(MIN_TOUCH)
            .clip(CircleShape)
            .clickable(onClick = onClick, role = Role.Button)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .then(if (primary) Modifier else Modifier.shadow(2.dp, CircleShape))
                .clip(CircleShape)
                .background(
                    if (primary) Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, LocalGilpickColors.current.primaryDark))
                    else Brush.linearGradient(listOf(Color.White, Color.White)),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = if (primary) Color.White else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}


/** 가이드라인 9절: 1초를 넘길 때만 대기 표시를 띄운다. */
private const val LOADING_INDICATOR_DELAY_MILLIS = 1_000L

/** 가이드라인 5절·10절: 주요 CTA 52~56dp, 터치 영역 48dp 이상. */
private val PRIMARY_BUTTON_HEIGHT = 56.dp
private val MIN_TOUCH = 48.dp

/** Figma 체류 시간 stepper 범위: 30~360분, 30분 단위. */
private const val STAY_MIN = 30
private const val STAY_MAX = 360
private const val STAY_STEP = 30

/** UI test가 시트의 확정 버튼을 하단 CTA와 구분하는 tag. */
internal const val ADD_TO_SCHEDULE_CONFIRM_TAG = "place_detail_add_to_schedule_confirm"

/** 48dp 터치 영역 안에 36dp 원을 가운데 두면 원 밖 여백은 6dp다. Figma 위치(16/20)에서 이만큼 뺀다. */
private val CIRCLE_INSET = 6.dp
