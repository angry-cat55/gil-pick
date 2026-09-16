package com.gilpick.alternative

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gilpick.place.tourApiAttributionText
import com.gilpick.ui.component.TAG_HEADER_BACK
import com.gilpick.R
import com.gilpick.place.labelRes
import com.gilpick.route.Position
import com.gilpick.route.distanceLabel
import androidx.compose.ui.draw.dropShadow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.gilpick.ui.component.SheetDragState
import com.gilpick.ui.component.SheetHandle
import com.gilpick.ui.component.dragModifier
import com.gilpick.ui.component.measureSheet
import com.gilpick.ui.component.rememberSheetDragState
import com.gilpick.ui.component.EmptyState
import com.gilpick.ui.component.EmptyStateTone
import com.gilpick.ui.component.ErrorState
import com.gilpick.ui.component.GradientButton
import com.gilpick.ui.component.SecondaryButton
import com.gilpick.ui.theme.LocalGilpickShadows
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.gilpick.ui.theme.displayFont
import java.util.Locale
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.sp

/**
 * 대체 장소 화면(`spec.md` US2, Figma `AlternativePlacesScreen`/`alternativesEmpty`, T024).
 *
 * 전체 화면 지도 위에 후보 sheet가 겹친다. sheet는 끌어서 높이를 바꿀 수 있고(#660) 가장 낮은 단계에서도
 * 감지 요약과 `추천 후보 N곳` 머리말이 남아 무엇을 보고 있는지 알 수 있다. 지도에는 기존 장소와 후보가
 * 함께 그려진다(UI-004). 후보 행을 누르면 장소 상세로 가고, `비교`는 F010에 넘길 값을 만들 뿐 일정을
 * 바꾸지 않는다(FR-023). `기존 일정 그대로 진행`은 감지를 거절한다(FR-016). Figma의 이동 시간 비교
 * 문구는 F010 범위라 그리지 않고, `reasons` code만 문구로 옮긴다(plan.md Figma 대조 결과).
 *
 * @param onBack 지도 위 뒤로 버튼·`돌아가기`·`진행 화면으로`. 진행 화면으로 돌아간다.
 * @param onRetry `error`의 `다시 시도하기`. 같은 조회를 다시 보낸다.
 * @param onSelect 후보의 `비교`. F010 변경 경로 미리보기로 넘길 값이다.
 * @param onOpenDetail 후보 행 탭. 대체 장소 문맥의 장소 상세로 간다(#660).
 * @param onSearch `직접 검색`·`직접 검색해서 고르기`. 직접 검색 화면으로 간다(#331).
 * @param onKeep `기존 일정 그대로 진행`. 감지 거절을 보낸다.
 * @param onRetryKeep 거절 실패 안내의 `다시 시도`.
 * @param onDismissKeepError 거절 실패 안내의 `닫기`.
 * @param onReauthenticate 로그인 상태가 만료됐다. F001 재인증 흐름으로 넘어간다.
 * @param map 지도 영역. 세 번째 인자는 sheet가 덮는 화면 높이 비율이다. 기본은 Naver [AlternativeMap]이며
 *   UI test·screenshot은 자리 표시로 바꿔 끼운다.
 */
@Composable
fun AlternativePlacesScreen(
    state: AlternativeUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (AlternativeCandidateDto) -> Unit,
    onSearch: () -> Unit,
    onKeep: () -> Unit,
    onReauthenticate: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDetail: (AlternativeCandidateDto) -> Unit = {},
    onRetryKeep: () -> Unit = onKeep,
    onDismissKeepError: () -> Unit = {},
    map: @Composable (Position?, List<AlternativeCandidateDto>, Float, Modifier) -> Unit = { mapOrigin, candidates, sheetFraction, mapModifier ->
        AlternativeMap(origin = mapOrigin, candidates = candidates, sheetFraction = sheetFraction, modifier = mapModifier)
    },
) {
    val radius = LocalGilpickRadius.current
    val spacing = LocalGilpickSpacing.current
    val shadows = LocalGilpickShadows.current

    // 오류는 화면 전체를 대신한다(가이드라인 9절 오류 화면). 돌아가기는 보조 버튼이 맡는다.
    if (state is AlternativeUiState.Error) {
        ErrorState(state, onRetry = onRetry, onBack = onBack, onReauthenticate = onReauthenticate, modifier = modifier.fillMaxSize())
        return
    }

    val sheetShape = RoundedCornerShape(topStart = radius.xl, topEnd = radius.xl)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val sheet = rememberSheetDragState(
            maxPx = constraints.maxHeight.toFloat(),
            defaultFraction = SHEET_DEFAULT_FRACTION,
            expandedFraction = SHEET_EXPANDED_FRACTION,
        )
        val content = state as? AlternativeUiState.Content
        Box(modifier = Modifier.fillMaxSize().testTag(TAG_MAP_SLOT)) {
            map(content?.origin, content?.candidates?.items.orEmpty(), sheet.fraction, Modifier.fillMaxSize())
        }
        BackButton(onBack = onBack, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(spacing.space4))
        Box(
            modifier = shadows.sheetBelowMap
                .fold(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .heightIn(max = sheet.height)
                        .measureSheet(sheet),
                ) { acc, shadow -> acc.dropShadow(sheetShape, shadow) }
                .clip(sheetShape)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            when (state) {
                AlternativeUiState.Loading -> DelayedLoading()
                is AlternativeUiState.Closed -> ClosedState(state, onBack = onBack)
                is AlternativeUiState.Content -> Sheet(
                    content = state,
                    sheet = sheet,
                    onSelect = onSelect,
                    onOpenDetail = onOpenDetail,
                    onSearch = onSearch,
                    onKeep = onKeep,
                    onRetryKeep = onRetryKeep,
                    onDismissKeepError = onDismissKeepError,
                )
                is AlternativeUiState.Error -> Unit
            }
        }
    }
}

/** 지도 위 둥근 뒤로 버튼(Figma). 보이는 크기는 40dp, 터치 영역은 48dp다(UI-008). */
/** 지도 위 떠 있는 뒤로 버튼(가이드라인 7절): 40dp 흰 90% 원 + `mapFloatingButton` 그림자, 18dp `onSurface`. 터치 영역은 48dp다(UI-008). */
@Composable
private fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(MIN_TOUCH)
            .clip(CircleShape)
            .clickable(onClick = onBack, role = Role.Button)
            .testTag(TAG_HEADER_BACK),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = LocalGilpickShadows.current.mapFloatingButton
                .fold(Modifier.size(BACK_BUTTON)) { acc, shadow -> acc.dropShadow(CircleShape, shadow) }
                .background(MaterialTheme.colorScheme.surface.copy(alpha = BACK_BUTTON_ALPHA), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_arrow_left),
                contentDescription = stringResource(R.string.alternative_back),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(BACK_ICON),
            )
        }
    }
}

/** 조회 대기 표시. 1초를 넘길 때만 표시한다(UI-006, 가이드라인 9절). */
@Composable
private fun DelayedLoading() {
    var visible by remember { mutableStateOf(false) }
    val label = stringResource(R.string.alternative_loading)

    LaunchedEffect(Unit) {
        delay(LOADING_INDICATOR_DELAY_MILLIS)
        visible = true
    }

    if (visible) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.clearAndSetSemantics { contentDescription = label })
        }
    }
}

/** 추천 실패(UI-006): 원인, `다시 시도하기`(가능할 때), `돌아가기`. 기존 일정은 그대로다. */
/**
 * 추천 실패: 공통 오류 화면(가이드라인 9절). 원인 문구(기존 일정 유지 포함), `다시 시도하기`(세션 만료면 `다시 로그인`,
 * 재시도 불가면 `돌아가기`가 주버튼), 보조 `돌아가기`. 발생 시각·마지막 동작은 이 화면이 모르는 값이라 원인 카드를 그리지 않는다.
 */
@Composable
private fun ErrorState(state: AlternativeUiState.Error, onRetry: () -> Unit, onBack: () -> Unit, onReauthenticate: () -> Unit, modifier: Modifier = Modifier) {
    val back = stringResource(R.string.alternative_go_back)
    val (primaryLabel, onPrimary) = when {
        state.error == AlternativeError.SessionExpired -> stringResource(R.string.place_reauthenticate) to onReauthenticate
        state.retryable -> stringResource(R.string.alternative_retry) to onRetry
        else -> back to onBack
    }
    ErrorState(
        title = stringResource(R.string.alternative_error_title),
        description = stringResource(state.error.messageRes),
        primaryLabel = primaryLabel,
        onPrimary = onPrimary,
        secondaryLabel = back.takeIf { onPrimary !== onBack },
        onSecondary = onBack,
        modifier = modifier,
    )
}

/** 이미 처리된 감지(UI-006): 현재 상태 안내와 `진행 화면으로`. */
/** 이미 처리된 감지: 공통 빈 상태(화면 전체 단계, 성공 계열 체크) + `진행 화면으로` 주버튼. */
@Composable
private fun ClosedState(state: AlternativeUiState.Closed, onBack: () -> Unit) {
    EmptyState(
        icon = R.drawable.ic_lucide_check,
        title = stringResource(R.string.alternative_closed_title),
        body = stringResource(state.status.closedRes),
        tone = EmptyStateTone.Success,
        titleStyle = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        action = { GradientButton(label = stringResource(R.string.alternative_to_progress), onClick = onBack, modifier = Modifier.testTag(TAG_TO_PROGRESS)) },
    )
}

/**
 * 둥근 sheet 본문: 끌 수 있는 윗부분(손잡이·감지 요약·머리말)과 그 아래 스크롤 영역이다.
 *
 * 윗부분 높이가 곧 sheet의 접힘 높이라, 가장 낮은 단계에서도 감지 요약과 `추천 후보 N곳`이 남는다(#660).
 */
@Composable
private fun Sheet(
    content: AlternativeUiState.Content,
    sheet: SheetDragState,
    onSelect: (AlternativeCandidateDto) -> Unit,
    onOpenDetail: (AlternativeCandidateDto) -> Unit,
    onSearch: () -> Unit,
    onKeep: () -> Unit,
    onRetryKeep: () -> Unit,
    onDismissKeepError: () -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val density = LocalDensity.current
    // 접힘 아래 여백은 머리말 아래 간격과 같게 둬 첫 후보가 조금도 비치지 않게 한다.
    val bottomExtraPx = with(density) { spacing.space2.roundToPx() } + WindowInsets.navigationBars.getBottom(density)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = spacing.space5),
    ) {
        Column(
            modifier = sheet.dragModifier()
                .fillMaxWidth()
                // 접힌 sheet 안에서도 윗부분의 원래 높이를 재도록 높이 제한 없이 잰다.
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .onSizeChanged { sheet.onTopMeasured(it.height + bottomExtraPx) },
        ) {
            SheetHandle(
                anchor = sheet.anchor,
                onAnchorChange = { sheet.anchor = it },
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Summary(content.detection)
            HorizontalDivider(color = MaterialTheme.colorScheme.background, modifier = Modifier.padding(vertical = spacing.space4))
            if (!content.isEmpty) {
                CandidateHeader(count = content.candidates.items.size, onSearch = onSearch)
            }
        }
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .then(if (sheet.collapsed) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            if (content.isEmpty) {
                EmptySection(content = content, onKeep = onKeep, onSearch = onSearch)
            } else {
                CandidateSection(content = content, onSelect = onSelect, onOpenDetail = onOpenDetail, onKeep = onKeep)
            }
            content.dismissError?.let { error ->
                KeepErrorBar(error = error, onRetry = onRetryKeep, onDismiss = onDismissKeepError)
            }
            Spacer(modifier = Modifier.height(spacing.space6))
        }
    }
}

/** 감지 요약(UI-002): 경고 아이콘 상자, `장소명 · 방문 어려움 감지`, 감지 이유, 위험 변수 칩. 값은 DETECT-002 그대로다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(detection: DetectionDetailDto) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val title = stringResource(R.string.alternative_title, detection.placeName)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space3),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = spacing.space3)
            .semantics(mergeDescendants = true) {},
    ) {
        Box(
            modifier = Modifier
                .size(ICON_BOX)
                .background(colors.warningContainer, RoundedCornerShape(radius.lg)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_triangle_alert),
                contentDescription = null,
                tint = colors.warning,
                modifier = Modifier.size(SUMMARY_ICON),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = title.displayFont(),
                fontWeight = FontWeight.Black,
                modifier = Modifier.testTag(TAG_TITLE),
            )
            Text(text = detection.reason, style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
    }
    val chips = detection.variables.riskChips()
    if (chips.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.space2),
            verticalArrangement = Arrangement.spacedBy(spacing.space2),
            modifier = Modifier.fillMaxWidth(),
        ) {
            chips.forEach { chip ->
                Text(
                    text = chip,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.onWarningContainer,
                    modifier = Modifier
                        .background(colors.warningContainer, RoundedCornerShape(radius.md))
                        .padding(horizontal = spacing.space3, vertical = spacing.space1),
                )
            }
        }
    }
}

/** 후보 목록 머리말: `추천 후보 N곳`과 `직접 검색`. sheet를 접어도 남는 줄이다(#660). */
@Composable
private fun CandidateHeader(count: Int, onSearch: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.alternative_count, count),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            modifier = Modifier.weight(1f).testTag(TAG_COUNT),
        )
        TextButton(onClick = onSearch, modifier = Modifier.heightIn(min = MIN_TOUCH).testTag(TAG_SEARCH)) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_search),
                contentDescription = null,
                modifier = Modifier.size(SEARCH_ICON),
            )
            Spacer(modifier = Modifier.width(spacing.space1))
            Text(stringResource(R.string.alternative_search), fontWeight = FontWeight.Bold)
        }
    }
}

/** 후보 목록(UI-003): 후보 행과 `기존 일정 그대로 진행`. 머리말은 [CandidateHeader]가 그린다. */
@Composable
private fun CandidateSection(
    content: AlternativeUiState.Content,
    onSelect: (AlternativeCandidateDto) -> Unit,
    onOpenDetail: (AlternativeCandidateDto) -> Unit,
    onKeep: () -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val items = content.candidates.items

    items.forEachIndexed { index, candidate ->
        CandidateRow(candidate = candidate, onSelect = { onSelect(candidate) }, onOpenDetail = { onOpenDetail(candidate) })
        if (index < items.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.background)
    }
    // 후보 목록 단위 공공데이터 출처 한 줄(F009 UI-011, #578). 후보별 배지로 붙이지 않는다.
    items.map { it.place }.tourApiAttributionText()?.let { attribution ->
        Text(
            text = attribution,
            fontSize = 11.sp,
            color = LocalGilpickColors.current.muted,
            modifier = Modifier.padding(top = spacing.space3),
        )
    }
    Spacer(modifier = Modifier.height(spacing.space5))
    // Figma 테두리형 보조 버튼: 2dp `outlineVariant`, 50dp, `radiusLg`, 14sp 600 `onSurfaceVariant`.
    val keepEnabled = !content.dismissPending
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = KEEP_HEIGHT)
            .alpha(if (keepEnabled) 1f else DISABLED_ALPHA)
            .border(KEEP_BORDER, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(LocalGilpickRadius.current.lg))
            .clip(RoundedCornerShape(LocalGilpickRadius.current.lg))
            .clickable(enabled = keepEnabled, onClick = onKeep, role = Role.Button)
            .testTag(TAG_KEEP),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(if (keepEnabled) R.string.alternative_keep else R.string.alternative_keep_pending),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = spacing.space2, vertical = spacing.space2),
        )
    }
}

/**
 * 후보 한 행(UI-003): 순위 상자, 이름(+`TOP` 배지), `카테고리 · 거리 · ★평점`, 근거 문구, 운영 상태, `비교`.
 *
 * 1위는 `TOP` 배지와 `1위` 설명으로만 구분한다. 강조 배경·채운 버튼은 이미 고른 후보처럼 읽혀 다른 후보를
 * 눌러도 되는지 헷갈리게 했다(#660). 평점이 없으면 `★` 항목을, 아는 근거 code가 없으면 근거 줄을 생략한다.
 *
 * 행 전체를 누르면 장소 상세로 가고, `비교`만 경로 비교로 간다.
 */
@Composable
private fun CandidateRow(candidate: AlternativeCandidateDto, onSelect: () -> Unit, onOpenDetail: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val top = candidate.rank == 1
    val category = stringResource(candidate.place.category.labelRes)
    val distance = distanceLabel(candidate.distanceMeters)
    val operating = operatingLabel(candidate.operatingStatus, candidate.closesAt)
    val closingSoon = candidate.operatingStatus == OperatingStatus.CLOSING_SOON
    val description = stringResource(
        R.string.alternative_candidate_description,
        candidate.rank,
        candidate.place.name,
        category,
        distance,
        operating ?: "",
    )
    val meta = listOfNotNull(
        category,
        distance,
        candidate.adjustedRating?.let { stringResource(R.string.alternative_rating, String.format(Locale.ROOT, "%.1f", it)) },
    ).joinToString(stringResource(R.string.alternative_separator))

    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(spacing.space3),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetail, role = Role.Button)
            .padding(vertical = spacing.space4)
            .testTag(TAG_CANDIDATE_PREFIX + candidate.rank),
    ) {
        Box(
            modifier = Modifier
                .size(RANK_BOX)
                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(radius.md)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = candidate.rank.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
                color = colors.muted,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) { contentDescription = description },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.space1)) {
                Text(
                    text = candidate.place.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = candidate.place.name.displayFont(),
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (top) {
                    Text(
                        text = stringResource(R.string.alternative_top),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(radius.xs))
                            .padding(horizontal = spacing.space2, vertical = spacing.space1 / 2),
                    )
                }
            }
            Text(text = meta, style = MaterialTheme.typography.bodySmall, color = colors.muted)
            reasonsLabel(candidate.reasons)?.let { reasons ->
                Text(text = reasons, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 운영 상태와 선택 버튼을 한 줄에 둔다. Figma는 오른쪽 세로 배치지만 360dp·2.0배율에서 이름이
            // 한 글자씩 꺾이므로(F006 교훈) 이름 줄을 통째로 쓰고 버튼은 아래로 내린다(UI-008).
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = operating ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (closingSoon) colors.warning else colors.muted,
                    modifier = Modifier.weight(1f).padding(end = spacing.space2),
                )
                SelectButton(rank = candidate.rank, onClick = onSelect)
            }
        }
    }
}

/** 선택 버튼. 모든 후보가 같은 글자만 `비교`다(#660). 터치 영역은 48dp다. */
@Composable
private fun SelectButton(rank: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val shape = RoundedCornerShape(radius.md)
    Box(
        modifier = modifier
            .sizeIn(minWidth = MIN_TOUCH, minHeight = MIN_TOUCH)
            .clip(shape)
            .clickable(onClick = onClick, role = Role.Button)
            .testTag(TAG_COMPARE_PREFIX + rank),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.alternative_compare),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = spacing.space3, vertical = spacing.space2),
        )
    }
}

/** 후보 없음(Figma `alternativesEmpty`, UI-006): 안내와 `기존 일정 그대로 진행`(gradient)·`직접 검색해서 고르기`. */
@Composable
private fun EmptySection(content: AlternativeUiState.Content, onKeep: () -> Unit, onSearch: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.space8)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.alternative_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(TAG_EMPTY),
        )
        Text(
            text = stringResource(R.string.alternative_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.space1),
        )
    }
    GradientButton(
        label = stringResource(if (content.dismissPending) R.string.alternative_keep_pending else R.string.alternative_keep),
        onClick = onKeep,
        processing = content.dismissPending,
        height = KEEP_HEIGHT,
        modifier = Modifier.fillMaxWidth().testTag(TAG_KEEP),
    )
    Spacer(modifier = Modifier.height(spacing.space2))
    SecondaryButton(
        label = stringResource(R.string.alternative_search_manually),
        onClick = onSearch,
        modifier = Modifier.fillMaxWidth().testTag(TAG_SEARCH),
    )
}

/** 거절 실패 안내(UI-005): 원인 문구, `다시 시도`, `닫기`. 후보 목록은 그대로다. */
@Composable
private fun KeepErrorBar(error: AlternativeError, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.space3)
            .border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(radius.md))
            .padding(horizontal = spacing.space3, vertical = spacing.space2)
            .testTag(TAG_KEEP_ERROR),
    ) {
        Text(
            text = stringResource(error.messageRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.space2), modifier = Modifier.align(Alignment.End)) {
            if (error.retryable) {
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = MIN_TOUCH)) {
                    Text(stringResource(R.string.alternative_dismiss_retry))
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MIN_TOUCH)) {
                Text(stringResource(R.string.alternative_dismiss_close))
            }
        }
    }
}

/** UI test가 찾는 tag. */
internal const val TAG_MAP_SLOT = "alternative_map_slot"
internal const val TAG_TITLE = "alternative_title"
internal const val TAG_COUNT = "alternative_count"
internal const val TAG_SEARCH = "alternative_search"
internal const val TAG_KEEP = "alternative_keep"
internal const val TAG_KEEP_ERROR = "alternative_keep_error"
internal const val TAG_TO_PROGRESS = "alternative_to_progress"
internal const val TAG_EMPTY = "alternative_empty"
internal const val TAG_CANDIDATE_PREFIX = "alternative_candidate_"
internal const val TAG_COMPARE_PREFIX = "alternative_compare_"

private const val LOADING_INDICATOR_DELAY_MILLIS = 1_000L

/** sheet 기본 높이 비율. Figma의 지도 38%와 같은 첫 화면이고, 끌어서 더 낮추거나 높일 수 있다(#660). */
private const val SHEET_DEFAULT_FRACTION = 0.62f

/** sheet 펼침 높이 비율. 펼쳐도 지도 윗부분은 남긴다. */
private const val SHEET_EXPANDED_FRACTION = 0.9f

/** 가이드라인 10절 최소 터치 영역. */
private val MIN_TOUCH: Dp = 48.dp
private val BACK_BUTTON: Dp = 40.dp
private val BACK_ICON: Dp = 18.dp
private val ICON_BOX: Dp = 40.dp
private val SUMMARY_ICON: Dp = 18.dp
private val SEARCH_ICON: Dp = 14.dp
private val RANK_BOX: Dp = 32.dp
private val KEEP_HEIGHT: Dp = 52.dp
/** Figma 실측: 테두리형 보조 버튼 2dp, 뒤로 버튼 흰 90%. 화면 전용이라 토큰이 아니다. */
private val KEEP_BORDER: Dp = 2.dp
private const val BACK_BUTTON_ALPHA = 0.9f
/** 보내는 중 테두리형 버튼 흐림(Figma `disabled:opacity-40` 계열, 가이드라인 7절). */
private const val DISABLED_ALPHA = 0.4f
