package com.gilpick.place

import androidx.annotation.DrawableRes
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.gilpick.ui.component.TAG_HEADER_BACK
import com.gilpick.R
import com.gilpick.ui.component.ErrorState as CommonErrorState
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
 * Figma의 평점·운영시간·입장료 3열과 혼잡도·날씨 행은 그리지 않는다(#481, 선택지 A): 입장료·혼잡도·날씨는
 * API(`PlaceDto`)에 원천이 없어 늘 `정보 없음`이었고, 운영시간은 정보 행에 이미 있다. 평점은 정보 행 맨 위로
 * 옮긴다. 주소·운영시간처럼 계약에 있는 값이 비면 지어내지 않고 `정보 없음`으로 둔다(FR-007).
 * Google 평점·영업정보 attribution은 Google 약관상 필수라 Figma에 없어도 붙인다(FR-021).
 *
 * @param state 현재 상세 상태.
 * @param onBack 이전 화면(검색 결과)으로 돌아간다.
 * @param onRetry 실패한 조회를 다시 시도한다.
 * @param onReauthenticate 로그인 상태가 만료됐다. F001 재인증 흐름으로 넘어간다.
 * @param onAddToSchedule 시트에서 이동 수단·체류 시간을 확정했을 때. 저장은 F004가 맡는다(FR-014).
 * @param onOpenMap 하단 지도 버튼. 지도 기능에서 연결한다.
 * @param onReplace `null`이 아니면 대체 장소 문맥이다. 하단 CTA가 `일정에 추가` 대신 `장소 변경`이 되고
 *   시트 없이 바로 변경 흐름으로 간다(#660).
 */
@Composable
fun PlaceDetailScreen(
    state: PlaceDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onReauthenticate: () -> Unit,
    modifier: Modifier = Modifier,
    onAddToSchedule: (AddToScheduleRequest) -> Unit = {},
    onOpenMap: () -> Unit = {},
    onReplace: (() -> Unit)? = null,
    askTransport: Boolean = true,
) {
    when (val phase = state.phase) {
        is PlaceDetailPhase.Content -> Content(
            place = phase.place,
            onBack = onBack,
            onAddToSchedule = onAddToSchedule,
            onOpenMap = onOpenMap,
            onReplace = onReplace,
            askTransport = askTransport,
            modifier = modifier,
        )

        PlaceDetailPhase.Loading -> WithAppBar(onBack = onBack, modifier = modifier) { LoadingState() }

        // 공통 오류 화면(가이드라인 9절, #446). 없는 장소는 다시 시도해도 같아 `검색으로 돌아가기`만 주버튼이다.
        PlaceDetailPhase.NotFound -> WithAppBar(onBack = onBack, modifier = modifier) {
            CommonErrorState(
                title = stringResource(R.string.place_detail_not_found_title),
                description = stringResource(R.string.place_detail_not_found_hint),
                primaryLabel = stringResource(R.string.place_detail_back_to_search),
                onPrimary = onBack,
                modifier = Modifier.fillMaxSize(),
            )
        }

        is PlaceDetailPhase.Failed -> WithAppBar(onBack = onBack, modifier = modifier) {
            // 재시도해도 결과가 같은 실패(호출 한도 등)에는 검색으로 돌아가는 길을, 로그인 만료에는 재인증을 준다.
            val retryable = phase.error.retryable
            val sessionExpired = phase.error.kind == PlaceErrorKind.SESSION_EXPIRED
            val back = stringResource(R.string.place_detail_back_to_search)
            // 공통 오류 화면(가이드라인 9절, #446). 주버튼이 돌아가기가 아니면 `검색으로 돌아가기`를 보조 버튼으로 둔다.
            // 발생 시각·마지막 동작은 이 화면이 모르는 값이라 원인 카드를 그리지 않는다.
            CommonErrorState(
                description = stringResource(phase.error.detailMessageRes),
                primaryLabel = when {
                    sessionExpired -> stringResource(R.string.place_reauthenticate)
                    retryable -> stringResource(R.string.place_detail_retry)
                    else -> back
                },
                onPrimary = when {
                    sessionExpired -> onReauthenticate
                    retryable -> onRetry
                    else -> onBack
                },
                secondaryLabel = back.takeIf { sessionExpired || retryable },
                onSecondary = onBack,
                modifier = Modifier.fillMaxSize(),
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
                IconButton(onClick = onBack, modifier = Modifier.size(MIN_TOUCH).testTag(TAG_HEADER_BACK)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_arrow_left),
                        // 아이콘 전용 버튼이므로 설명이 필수다(가이드라인 10절).
                        contentDescription = stringResource(R.string.place_detail_back),
                        modifier = Modifier.size(BACK_ICON),
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
                modifier = Modifier.padding(bottom = spacing.space2),
            )
        }
        action()
    }
}

/**
 * Figma `PlaceDetailScreen`: hero(고정) → 스크롤(정보 행·지도) → 하단 CTA.
 * Figma처럼 hero와 CTA는 스크롤되지 않는다. `일정에 추가`는 이동 수단·체류 시간 시트를 연다.
 */
@Composable
private fun Content(
    place: PlaceDto,
    onBack: () -> Unit,
    onAddToSchedule: (AddToScheduleRequest) -> Unit,
    onOpenMap: () -> Unit,
    onReplace: (() -> Unit)?,
    askTransport: Boolean,
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
            askTransport = askTransport,
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
        Hero(place = place, onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            InfoRows(place = place)
            MapPreview(place = place)
            Spacer(modifier = Modifier.height(96.dp))
        }
        ActionBar(
            onOpenMap = onOpenMap,
            label = stringResource(if (onReplace != null) R.string.place_detail_replace else R.string.place_detail_add_to_schedule),
            onPrimary = onReplace ?: { showSheet = true },
        )
    }
}

/**
 * Figma `Hero`: 240dp 사진, 위 30%·아래 50% 검정 gradient, 원형 뒤로 가기, 상태 칩, 이름, 주소.
 * Figma의 찜 버튼은 찜 기능이 MVP에 없어 누를 수 없는 버튼이 되므로 그리지 않는다(#518).
 */
@Composable
private fun Hero(place: PlaceDto, onBack: () -> Unit) {
    val status = place.statusLabel()
    // #515 사진은 URL이 있어도 받는 중이거나 받지 못할 수 있다. 실제로 그려졌을 때만 사진 설명을 준다.
    var imageState by remember(place.imageUrl) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    val imageShown = imageState is AsyncImagePainter.State.Success
    // 사진이 보이지 않는 동안에는 빈 자리 자체가 내용이므로 그 상태를 컨테이너에 붙인다(가이드라인 10절).
    val fallbackDescription = when {
        place.imageUrl == null -> stringResource(R.string.place_detail_no_image)
        imageState is AsyncImagePainter.State.Error -> stringResource(R.string.place_detail_image_failed)
        imageShown -> null
        else -> stringResource(R.string.place_detail_image_loading)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(LocalGilpickColors.current.faint)
            .then(if (fallbackDescription != null) Modifier.semantics { contentDescription = fallbackDescription } else Modifier),
    ) {
        // 대체 표현. 검색 결과 썸네일(`RemoteImage`)과 같은 지도 핀·`outline` 기준이며, 사진이 그려지면 치운다.
        if (!imageShown) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_map_pin),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(HERO_FALLBACK_ICON)
                    .testTag(TAG_HERO_IMAGE_FALLBACK),
            )
        }
        if (place.imageUrl != null) {
            AsyncImage(
                model = place.imageUrl,
                contentDescription = if (imageShown) stringResource(R.string.place_detail_image_description, place.name) else null,
                contentScale = ContentScale.Crop,
                onState = { imageState = it },
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
                .testTag(TAG_HEADER_BACK)
                .align(Alignment.TopStart)
                .padding(top = LocalGilpickSpacing.current.space4 - CIRCLE_INSET, start = LocalGilpickSpacing.current.space5 - CIRCLE_INSET),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = LocalGilpickSpacing.current.space5, end = LocalGilpickSpacing.current.space5, bottom = LocalGilpickSpacing.current.space4),
        ) {
            if (status != null) {
                val colors = LocalGilpickColors.current
                Text(
                    text = stringResource(status.textRes),
                    style = MaterialTheme.typography.labelSmall,
                    // 닫힌 상태를 성공색으로 두면 색과 문구가 어긋난다(#576).
                    color = if (status.closed) colors.onWarningContainer else colors.success,
                    modifier = Modifier
                        .padding(bottom = LocalGilpickSpacing.current.space2)
                        .background(
                            if (status.closed) colors.warningContainer else colors.successContainer,
                            RoundedCornerShape(LocalGilpickRadius.current.sm),
                        )
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

/**
 * Figma `Info rows` 틀에 평점·주소·운영시간을 쓴다(#481). 평점은 검색 행처럼 없으면 행 자체를 두지 않고,
 * 있으면 평점 수를 함께 쓴다(FR-017). 운영시간은 [openingHoursDetail] 규칙이다(#480).
 */
@Composable
private fun InfoRows(place: PlaceDto) {
    val missing = stringResource(R.string.place_detail_missing)
    val hours = place.openingHoursDetail ?: missing
    val ratingText = place.rating?.toRatingText()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = LocalGilpickSpacing.current.space2)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (ratingText != null) {
            val count = place.userRatingCount?.let { String.format(java.util.Locale.KOREA, "%,d", it) }
            InfoRow(
                icon = R.drawable.ic_lucide_star,
                label = stringResource(R.string.place_detail_rating_label),
                value = if (count != null) stringResource(R.string.place_detail_rating_with_count, ratingText, count) else ratingText,
                description = stringResource(R.string.place_rating_description, ratingText),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.background)
        }
        InfoRow(R.drawable.ic_lucide_map_pin, stringResource(R.string.place_detail_address_label), place.address ?: missing)
        HorizontalDivider(color = MaterialTheme.colorScheme.background)
        InfoRow(R.drawable.ic_lucide_clock, stringResource(R.string.place_detail_hours_label), hours)
        // 정보 영역 하단의 출처. `tourapi:` 장소면 공공데이터 출처(#578), Google 정보가 있으면 Google attribution을 둔다.
        val attributions = listOfNotNull(listOf(place).tourApiAttributionText(), listOf(place).googleAttributionText())
        if (attributions.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(horizontal = LocalGilpickSpacing.current.space5, vertical = LocalGilpickSpacing.current.space3),
            ) {
                attributions.forEach { attribution ->
                    Text(text = attribution, fontSize = 11.sp, color = LocalGilpickColors.current.muted)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(@DrawableRes icon: Int, label: String, value: String, description: String? = null) {
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
                modifier = if (description != null) Modifier.semantics { contentDescription = description } else Modifier,
            )
        }
    }
}

/**
 * Figma `Map` 자리(130dp)에 실제 Naver 지도를 넣는다(#479).
 *
 * 확대·이동할 수 있다(#503). 지도 위에서 시작한 제스처는 지도가 받고, 화면 스크롤은 지도 밖에서 한다.
 */
@Composable
private fun MapPreview(place: PlaceDto) {
    PlaceMap(
        name = place.name,
        latitude = place.latitude,
        longitude = place.longitude,
        zoomControls = false,
        modifier = Modifier
            .padding(top = LocalGilpickSpacing.current.space2, start = LocalGilpickSpacing.current.space4, end = LocalGilpickSpacing.current.space4)
            .fillMaxWidth()
            .height(130.dp)
            .clip(RoundedCornerShape(LocalGilpickRadius.current.lg)),
    )
}

/**
 * Figma `CTA`: 지도 버튼(48×52, #F4F6FB)과 gradient 주 버튼(52dp). 위에 1dp 선.
 *
 * 주 버튼 문구는 문맥이 정한다: F004는 `일정에 추가`, 대체 장소 문맥은 `장소 변경`이다(#660).
 */
@Composable
private fun ActionBar(onOpenMap: () -> Unit, label: String, onPrimary: () -> Unit) {
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
                    .clickable(onClick = onPrimary, role = Role.Button)
                    .testTag(PLACE_DETAIL_PRIMARY_TAG),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
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
    askTransport: Boolean = true,
) {
    var transport by remember { mutableStateOf(PlaceTransport.TRANSIT) }
    var minutes by remember { mutableIntStateOf(defaultMinutes.coerceIn(STAY_MIN, STAY_MAX)) }
    val shape = RoundedCornerShape(LocalGilpickRadius.current.lg)
    // `취소`·`일정에 추가`는 한 줄을 가로로 나눈 버튼이라 12dp다(가이드라인 6절 R3, D4).
    val buttonShape = RoundedCornerShape(LocalGilpickRadius.current.md)
    val title = stringResource(if (askTransport) R.string.place_detail_sheet_title else R.string.place_detail_sheet_title_first)
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
                text = stringResource(
                    if (askTransport) R.string.place_detail_sheet_subtitle else R.string.place_detail_sheet_subtitle_first,
                    placeName,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalGilpickColors.current.muted,
                modifier = Modifier.padding(top = LocalGilpickSpacing.current.space1, bottom = LocalGilpickSpacing.current.space5),
            )
            // 첫 장소는 앞 구간이 없어 이동 수단을 묻지 않는다(#654). 체류 시간만 고른다.
            if (askTransport) {
                PlaceTransport.entries.forEach { option ->
                    TransportOption(
                        option = option,
                        selected = transport == option,
                        onClick = { transport = option },
                        modifier = Modifier.padding(bottom = LocalGilpickSpacing.current.space2),
                    )
                }
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
                        .clip(buttonShape)
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
                        .clip(buttonShape)
                        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, LocalGilpickColors.current.primaryDark)))
                        .clickable(
                            onClick = { onConfirm(AddToScheduleRequest(transport.takeIf { askTransport }, minutes)) },
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

/** Figma 이동 수단 카드: 2dp 테두리, 선택 시 `#3B7BF8` 테두리·`#EBF2FF` 배경·체크. F004 이동 수단 시트도 쓴다. */
@Composable
internal fun TransportOption(
    option: PlaceTransport,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
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
        // F004 이동 수단 시트의 소요 시간·거리(#508). F003 시트는 넘기지 않는다.
        if (detail != null) {
            Text(text = detail, style = MaterialTheme.typography.bodySmall, color = LocalGilpickColors.current.muted)
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

/** Figma 체류 시간 ±버튼: 보이는 원 40dp(흰색+그림자 / gradient), 터치 영역 48dp. F004 대화상자는 44dp 원을 쓴다. */
@Composable
internal fun StepButton(
    label: String,
    contentDescription: String,
    primary: Boolean,
    onClick: () -> Unit,
    size: Dp = 40.dp,
) {
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
                .size(size)
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

/** 로딩·오류 AppBar 뒤로 가기 아이콘(Figma PlaceDetailScreen 뒤로 가기 18). */
private val BACK_ICON = 18.dp

/** Figma 체류 시간 stepper 범위: 30~360분, 30분 단위. */
private const val STAY_MIN = 30
private const val STAY_MAX = 360
private const val STAY_STEP = 30

/** UI test가 시트의 확정 버튼을 하단 CTA와 구분하는 tag. */
internal const val ADD_TO_SCHEDULE_CONFIRM_TAG = "place_detail_add_to_schedule_confirm"

/** 하단 주 버튼(`일정에 추가` 또는 `장소 변경`). */
internal const val PLACE_DETAIL_PRIMARY_TAG = "place_detail_primary"

/** 48dp 터치 영역 안에 36dp 원을 가운데 두면 원 밖 여백은 6dp다. Figma 위치(16/20)에서 이만큼 뺀다. */
private val CIRCLE_INSET = 6.dp

/** hero 사진 대체 표현 아이콘(#515). 240dp hero에 맞춰 검색 썸네일(24dp)보다 크게 둔다. */
private val HERO_FALLBACK_ICON = 32.dp

/** hero 사진 대체 표현. 사진이 보이지 않는 상태를 test가 확인한다. */
internal const val TAG_HERO_IMAGE_FALLBACK = "place_detail_hero_image_fallback"
