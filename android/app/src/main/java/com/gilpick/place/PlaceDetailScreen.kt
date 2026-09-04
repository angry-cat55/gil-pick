package com.gilpick.place

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.gilpick.R
import com.gilpick.ui.theme.LocalGilpickSpacing
import kotlinx.coroutines.delay

/**
 * 장소 상세 화면.
 *
 * 모양은 Figma Make `Design UI from Reference`의 `PlaceDetailScreen.tsx`를 그대로 따른다
 * (사용자 결정: pen 정본·테마 토큰보다 Figma 우선). 색·글자 크기·간격은 Figma 값이며
 * 이 파일 안의 [Figma] 팔레트에 모여 있다. Figma 팔레트를 앱 전체로 올리려면 `Theme.kt`로
 * 옮기면 된다.
 *
 * Figma에 있지만 API(`PlaceDto`)에 없는 값(입장료·혼잡도·날씨·운영시간 요약)은 지어내지
 * 않고 `정보 없음`으로 둔다(FR-007). Google 평점·영업정보 attribution은 Google 약관상
 * 필수라 Figma에 없어도 붙인다(FR-021).
 *
 * @param state 현재 상세 상태.
 * @param onBack 이전 화면(검색 결과)으로 돌아간다.
 * @param onRetry 실패한 조회를 다시 시도한다.
 * @param onAddToSchedule `일정에 추가`. F004에서 연결한다.
 * @param onOpenMap 하단 지도 버튼. 지도 기능에서 연결한다.
 * @param onFavorite hero의 찜 버튼. 찜 기능은 아직 없다.
 */
@Composable
fun PlaceDetailScreen(
    state: PlaceDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onAddToSchedule: () -> Unit = {},
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
                modifier = Modifier.semantics { contentDescription = label },
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
 * Figma `PlaceDetailScreen`: hero(고정) → 스크롤(stats·정보 행·지도) → 하단 CTA.
 * Figma처럼 hero와 CTA는 스크롤되지 않는다.
 */
@Composable
private fun Content(
    place: PlaceDto,
    onBack: () -> Unit,
    onAddToSchedule: () -> Unit,
    onOpenMap: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // 앱은 edge-to-edge라 상태 표시줄 뒤까지 그려진다. Figma처럼 그 띠는 흰색으로 두고 hero는 그 아래서 시작한다.
            .background(Color.White)
            .statusBarsPadding()
            .background(Figma.Background),
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
        ActionBar(onOpenMap = onOpenMap, onAddToSchedule = onAddToSchedule)
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
            .background(Figma.ImagePlaceholder)
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
                .padding(top = 16.dp - CIRCLE_INSET, start = 20.dp - CIRCLE_INSET),
        )
        CircleIconButton(
            icon = R.drawable.ic_lucide_heart,
            contentDescription = stringResource(R.string.place_detail_favorite),
            onClick = onFavorite,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp - CIRCLE_INSET, end = 20.dp - CIRCLE_INSET),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        ) {
            if (statusRes != null) {
                Text(
                    text = stringResource(statusRes),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Figma.Success,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .background(Figma.SuccessContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Text(
                text = place.name,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                fontFamily = place.name.displayFont(),
                color = Color.White,
            )
            Text(
                text = place.address ?: stringResource(R.string.place_detail_missing),
                fontSize = 13.sp,
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
            .background(Color.White)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
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
            color = Figma.Text,
            textAlign = TextAlign.Center,
            modifier = if (description != null) Modifier.semantics { contentDescription = description } else Modifier,
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = Figma.Muted,
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
            .padding(top = 8.dp)
            .background(Color.White),
    ) {
        InfoRow(R.drawable.ic_lucide_map_pin, stringResource(R.string.place_detail_address_label), place.address ?: missing)
        HorizontalDivider(color = Figma.Background)
        InfoRow(R.drawable.ic_lucide_clock, stringResource(R.string.place_detail_hours_label), hours)
        HorizontalDivider(color = Figma.Background)
        InfoRow(R.drawable.ic_lucide_users, stringResource(R.string.place_detail_crowd_label), missing)
        HorizontalDivider(color = Figma.Background)
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
                color = Figma.Muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun InfoRow(@DrawableRes icon: Int, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Figma.Muted,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp),
        )
        Column {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.55.sp,
                color = Figma.Muted,
                modifier = Modifier.padding(bottom = 2.dp),
            )
            Text(
                text = value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Figma.Text,
            )
        }
    }
}

/** Figma `Map`: 격자·도로·핀을 그린 130dp 자리 표시. 실제 지도는 지도 기능에서 넣는다. */
@Composable
private fun MapPreview(name: String) {
    Box(
        modifier = Modifier
            .padding(top = 8.dp, start = 16.dp, end = 16.dp)
            .fillMaxWidth()
            .height(130.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Figma.MapBackground),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            // Figma SVG viewBox 340×130 좌표를 실제 크기로 늘린다.
            val sx = size.width / 340f
            val sy = size.height / 130f
            val grid = Figma.Primary.copy(alpha = 0.5f)
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
            drawCircle(Figma.Primary.copy(alpha = 0.2f), radius = 22f * sx, center = pin)
            drawCircle(Figma.Primary, radius = 12f * sx, center = pin)
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
    val shape = RoundedCornerShape(12.dp)
    val openMap = stringResource(R.string.place_detail_open_map)

    Column(
        modifier = Modifier
            .background(Color.White)
            // 제스처 바 뒤까지 흰색을 채우고 버튼은 그 위에 둔다.
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(color = Figma.Divider)
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 52.dp)
                    .clip(shape)
                    .background(Figma.Background)
                    .clickable(onClick = onOpenMap, role = Role.Button)
                    .semantics { contentDescription = openMap },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_map_pin),
                    contentDescription = null,
                    tint = Figma.Icon,
                    modifier = Modifier.size(18.dp),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(shape)
                    .background(Brush.linearGradient(listOf(Figma.Primary, Figma.PrimaryDark)))
                    .clickable(onClick = onAddToSchedule, role = Role.Button),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.place_detail_add_to_schedule),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * Figma `index.css`의 `Outfit, 'Noto Sans KR'`: 숫자·라틴은 Outfit, 한글은 시스템 폰트.
 * Outfit 패밀리에 한글을 맡기면 fallback 글리프에 굵기가 적용되지 않아 얇게 나오므로 문자열 단위로 고른다.
 */
private fun String.displayFont(): FontFamily =
    if (any { it in '가'..'힣' || it in 'ㄱ'..'ㆎ' }) FontFamily.Default else Outfit

/** Outfit 가변 폰트(OFL — `app/OFL-Outfit.txt`). */
private val Outfit = FontFamily(
    Font(R.font.outfit, FontWeight.Bold),
    Font(R.font.outfit, FontWeight.ExtraBold),
    Font(R.font.outfit, FontWeight.Black),
)

/** Figma Make `Design UI from Reference` 팔레트. `Theme.kt`(pen 정본)와 다르며 이 화면만 쓴다. */
private object Figma {
    val Background = Color(0xFFF4F6FB)
    val Text = Color(0xFF111827)
    val Muted = Color(0xFF94A3B8)
    val Icon = Color(0xFF6B7280)
    val Divider = Color(0xFFE2E8F0)
    val Primary = Color(0xFF3B7BF8)
    val PrimaryDark = Color(0xFF2457C5)
    val Success = Color(0xFF10B981)
    val SuccessContainer = Color(0xFFECFDF5)
    val MapBackground = Color(0xFFEBF2FF)
    val ImagePlaceholder = Color(0xFFCBD5E1)
}

/** 가이드라인 9절: 1초를 넘길 때만 대기 표시를 띄운다. */
private const val LOADING_INDICATOR_DELAY_MILLIS = 1_000L

/** 가이드라인 5절·10절: 주요 CTA 52~56dp, 터치 영역 48dp 이상. */
private val PRIMARY_BUTTON_HEIGHT = 56.dp
private val MIN_TOUCH = 48.dp

/** 48dp 터치 영역 안에 36dp 원을 가운데 두면 원 밖 여백은 6dp다. Figma 위치(16/20)에서 이만큼 뺀다. */
private val CIRCLE_INSET = 6.dp
