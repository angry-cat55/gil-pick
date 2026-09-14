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
import androidx.compose.ui.graphics.Brush
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
import com.gilpick.R
import com.gilpick.place.labelRes
import com.gilpick.route.Position
import com.gilpick.route.distanceLabel
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.layout.layout
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

/**
 * 대체 장소 화면(`spec.md` US2, Figma `AlternativePlacesScreen`/`alternativesEmpty`, T024).
 *
 * 위 38%는 지도([AlternativeMap]), 아래는 둥근 sheet에 감지 요약(장소명·이유·변수 칩, UI-002)과 후보
 * 목록(UI-003) 또는 후보 없음 안내(UI-006)다. 후보의 `경로 비교`·`비교`는 F010에 넘길 값을 만들 뿐
 * 일정을 바꾸지 않고(FR-023), `기존 일정 그대로 진행`은 감지를 거절한다(FR-016). Figma의 이동 시간 비교
 * 문구는 F010 범위라 그리지 않고, `reasons` code만 문구로 옮긴다(plan.md Figma 대조 결과).
 *
 * @param origin 기존 장소 좌표. 계약 응답에 없어 호출자가 모르면 `null`이고 지도는 후보만 그린다.
 * @param onBack 지도 위 뒤로 버튼·`돌아가기`·`진행 화면으로`. 진행 화면으로 돌아간다.
 * @param onRetry `error`의 `다시 시도하기`. 같은 조회를 다시 보낸다.
 * @param onSelect 후보 선택. F010 변경 경로 미리보기로 넘길 값이다.
 * @param onSearch `직접 검색`·`직접 검색해서 고르기`. 직접 검색 화면으로 간다(#331).
 * @param onKeep `기존 일정 그대로 진행`. 감지 거절을 보낸다.
 * @param onRetryKeep 거절 실패 안내의 `다시 시도`.
 * @param onDismissKeepError 거절 실패 안내의 `닫기`.
 * @param onReauthenticate 로그인 상태가 만료됐다. F001 재인증 흐름으로 넘어간다.
 * @param map 지도 영역. 기본은 Naver [AlternativeMap]이며 UI test·screenshot은 자리 표시로 바꿔 끼운다.
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
    origin: Position? = null,
    onRetryKeep: () -> Unit = onKeep,
    onDismissKeepError: () -> Unit = {},
    map: @Composable (Position?, List<AlternativeCandidateDto>, Modifier) -> Unit = { mapOrigin, candidates, mapModifier ->
        AlternativeMap(origin = mapOrigin, candidates = candidates, modifier = mapModifier)
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
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(MAP_WEIGHT),
        ) {
            val candidates = (state as? AlternativeUiState.Content)?.candidates?.items.orEmpty()
            Box(modifier = Modifier.fillMaxSize().testTag(TAG_MAP_SLOT)) {
                map(origin, candidates, Modifier.fillMaxSize())
            }
            // Figma: 지도 하단 40dp 흰 fade. sheet가 그 위로 16dp 겹친다.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(MAP_FADE)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface))),
            )
            BackButton(onBack = onBack, modifier = Modifier.statusBarsPadding().padding(spacing.space4))
        }
        Box(
            modifier = shadows.sheetBelowMap
                .fold(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f - MAP_WEIGHT)
                        // 자기 자리보다 16dp 높게 재고 16dp 위에 놓아 지도 위로 겹친다(Figma `-mt-4`). 아래 끝은 그대로다.
                        .layout { measurable, constraints ->
                            val overlap = spacing.space4.roundToPx()
                            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = constraints.maxHeight + overlap))
                            layout(placeable.width, constraints.maxHeight) { placeable.place(0, -overlap) }
                        },
                ) { acc, shadow -> acc.dropShadow(sheetShape, shadow) }
                .clip(sheetShape)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            when (state) {
                AlternativeUiState.Loading -> DelayedLoading()
                is AlternativeUiState.Closed -> ClosedState(state, onBack = onBack)
                is AlternativeUiState.Content -> Sheet(
                    content = state,
                    onSelect = onSelect,
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
            .testTag(TAG_BACK),
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

/** 둥근 sheet 본문: 손잡이, 감지 요약, 후보 목록 또는 후보 없음. */
@Composable
private fun Sheet(
    content: AlternativeUiState.Content,
    onSelect: (AlternativeCandidateDto) -> Unit,
    onSearch: () -> Unit,
    onKeep: () -> Unit,
    onRetryKeep: () -> Unit,
    onDismissKeepError: () -> Unit,
) {
    val spacing = LocalGilpickSpacing.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = spacing.space5),
    ) {
        Handle()
        Summary(content.detection)
        HorizontalDivider(color = MaterialTheme.colorScheme.background, modifier = Modifier.padding(vertical = spacing.space4))
        if (content.isEmpty) {
            EmptySection(content = content, onKeep = onKeep, onSearch = onSearch)
        } else {
            CandidateSection(content = content, onSelect = onSelect, onSearch = onSearch, onKeep = onKeep)
        }
        content.dismissError?.let { error ->
            KeepErrorBar(error = error, onRetry = onRetryKeep, onDismiss = onDismissKeepError)
        }
        Spacer(modifier = Modifier.height(spacing.space6))
    }
}

@Composable
private fun Handle() {
    val spacing = LocalGilpickSpacing.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.space3, bottom = spacing.space2),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(HANDLE_WIDTH)
                .height(HANDLE_HEIGHT)
                .background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
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

/** 후보 목록(UI-003): `추천 후보 N곳`, `직접 검색`, 후보 행, `기존 일정 그대로 진행`. */
@Composable
private fun CandidateSection(
    content: AlternativeUiState.Content,
    onSelect: (AlternativeCandidateDto) -> Unit,
    onSearch: () -> Unit,
    onKeep: () -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val items = content.candidates.items

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.alternative_count, items.size),
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
    items.forEachIndexed { index, candidate ->
        CandidateRow(candidate = candidate, top = index == 0, onSelect = { onSelect(candidate) })
        if (index < items.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.background)
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
 * 후보 한 행(UI-003): 순위 상자, 이름(+`TOP` 배지), `카테고리 · 거리 · ★평점`, 근거 문구, 운영 상태, 선택 버튼.
 *
 * 1위는 배경·배지·`1위` 설명을 함께 써 색만으로 구분하지 않는다(UI-008). 평점이 없으면 `★` 항목을,
 * 아는 근거 code가 없으면 근거 줄을 생략한다.
 */
@Composable
private fun CandidateRow(candidate: AlternativeCandidateDto, top: Boolean, onSelect: () -> Unit) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
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
            .then(if (top) Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(radius.md)) else Modifier)
            .padding(horizontal = if (top) spacing.space3 else 0.dp, vertical = spacing.space4)
            .testTag(TAG_CANDIDATE_PREFIX + candidate.rank),
    ) {
        Box(
            modifier = Modifier
                .size(RANK_BOX)
                .background(if (top) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background, RoundedCornerShape(radius.md)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = candidate.rank.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
                color = if (top) Color.White else colors.muted,
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
                    color = if (top) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
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
                SelectButton(top = top, onClick = onSelect)
            }
        }
    }
}

/** 선택 버튼. 1위는 채운 `경로 비교`, 나머지는 글자만 `비교`(Figma). 둘 다 48dp 터치 영역이다. */
@Composable
private fun SelectButton(top: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val shape = RoundedCornerShape(radius.md)
    // TOP 후보의 `경로 비교`는 목록 안 작은 주 행동 버튼 그림자(가이드라인 6절 `listPrimaryAction`)를 받는다.
    val shadows = if (top) LocalGilpickShadows.current.listPrimaryAction else emptyList()
    Box(
        modifier = shadows
            .fold(modifier.sizeIn(minWidth = MIN_TOUCH, minHeight = MIN_TOUCH)) { acc, shadow -> acc.dropShadow(shape, shadow) }
            .clip(shape)
            .then(if (top) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier)
            .clickable(onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(if (top) R.string.alternative_compare_top else R.string.alternative_compare),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            color = if (top) Color.White else MaterialTheme.colorScheme.primary,
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
internal const val TAG_BACK = "alternative_back"
internal const val TAG_TITLE = "alternative_title"
internal const val TAG_COUNT = "alternative_count"
internal const val TAG_SEARCH = "alternative_search"
internal const val TAG_KEEP = "alternative_keep"
internal const val TAG_KEEP_ERROR = "alternative_keep_error"
internal const val TAG_TO_PROGRESS = "alternative_to_progress"
internal const val TAG_EMPTY = "alternative_empty"
internal const val TAG_CANDIDATE_PREFIX = "alternative_candidate_"

private const val LOADING_INDICATOR_DELAY_MILLIS = 1_000L

/** Figma 지도 높이 비율(38%). */
private const val MAP_WEIGHT = 0.38f

/** 가이드라인 10절 최소 터치 영역. */
private val MIN_TOUCH: Dp = 48.dp
private val BACK_BUTTON: Dp = 40.dp
private val BACK_ICON: Dp = 18.dp
private val ICON_BOX: Dp = 40.dp
private val SUMMARY_ICON: Dp = 18.dp
private val SEARCH_ICON: Dp = 14.dp
private val RANK_BOX: Dp = 32.dp
private val HANDLE_WIDTH: Dp = 40.dp
private val HANDLE_HEIGHT: Dp = 4.dp
private val KEEP_HEIGHT: Dp = 52.dp
/** Figma 실측: 테두리형 보조 버튼 2dp, 지도 하단 fade 40dp, 뒤로 버튼 흰 90%. 화면 전용이라 토큰이 아니다. */
private val KEEP_BORDER: Dp = 2.dp
private val MAP_FADE: Dp = 40.dp
private const val BACK_BUTTON_ALPHA = 0.9f
/** 보내는 중 테두리형 버튼 흐림(Figma `disabled:opacity-40` 계열, 가이드라인 7절). */
private const val DISABLED_ALPHA = 0.4f
