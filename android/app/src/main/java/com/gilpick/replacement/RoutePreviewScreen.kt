package com.gilpick.replacement

import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gilpick.ui.component.TAG_HEADER_BACK
import com.gilpick.R
import com.gilpick.alternative.clockLabel
import com.gilpick.route.RouteMap
import com.gilpick.route.distanceLabel
import com.gilpick.route.durationLabel
import androidx.compose.foundation.border
import androidx.compose.ui.draw.dropShadow
import com.gilpick.notification.IconBoxButton
import com.gilpick.ui.component.ErrorState
import com.gilpick.ui.component.GradientButton
import com.gilpick.ui.component.GradientTone
import com.gilpick.ui.component.SecondaryButton
import com.gilpick.ui.theme.LocalGilpickShadows
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.gilpick.ui.theme.displayFont
import kotlinx.coroutines.delay

/**
 * 변경 경로 미리보기 화면(`spec.md` US1, Figma `RoutePreviewScreen`, T016).
 *
 * 위쪽은 지도로 기존 경로와 변경 경로를 함께 그리고, 아래쪽은 무엇이 무엇으로 바뀌는지·왜
 * 바꾸는지와 비교 네 항목이다. `변경 승인`은 T023(#349)이 연결하며 이 화면은 자리만 만든다.
 *
 * 색만으로 알리지 않는다(UI-001·UI-002). 지도 범례는 `기존`·`변경` **문구**를 함께 두고, 비교
 * 항목의 나아짐·나빠짐은 색과 함께 기호(`↓`·`↑`)와 접근성 문구로 알린다. 확인하지 못한 값은
 * 지어내지 않고 `정보 없음`으로 둔다(FR-002).
 *
 * `empty` 상태는 없다. 후보가 없는 상황은 F009가 처리한다(UI-004).
 *
 * @param onBack 뒤로 가기. 후보 목록으로 돌아간다.
 * @param onRetry `error`의 `다시 시도하기`. 같은 요청을 같은 `Idempotency-Key`로 다시 보낸다.
 * @param onApprove `변경 승인`. T023이 실제 승인에 연결한다.
 * @param onOtherCandidates `다른 후보 보기`. 미리보기를 폐기하고 F009 후보 목록으로 돌아간다(UI-003).
 * @param onReauthenticate 로그인 상태가 만료됐다. F001 재인증 흐름으로 넘어간다.
 * @param placeName 대체 장소명. `loading` 안내 문장에 넣고, 모르면 이름 없는 문장을 쓴다(값을 지어내지 않음).
 * @param approved 승인이 끝났다. 경로 재생성 완료 모양을 보인다(#736).
 * @param onContinue 완료 모양의 `여행 진행 화면으로 돌아가기`와 뒤로 가기.
 * @param map 지도 영역. 기본은 F005 [RouteMap]이며 UI test·screenshot은 자리 표시로 바꿔 끼운다.
 */
@Composable
fun RoutePreviewScreen(
    state: PreviewUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRecreate: () -> Unit,
    onApprove: () -> Unit,
    onOtherCandidates: () -> Unit,
    onReauthenticate: () -> Unit,
    modifier: Modifier = Modifier,
    placeName: String? = null,
    approved: Boolean = false,
    onContinue: () -> Unit = {},
    map: @Composable (PreviewUiState.Content, Modifier) -> Unit = { content, mapModifier ->
        PreviewMap(content = content, modifier = mapModifier)
    },
) {
    // 전체 경로 보기는 지도만 남기므로 상단 바도 감춘다. 닫기는 지도 위 버튼과 뒤로 가기가 맡는다(#660).
    var fullMap by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // `변경 승인` 뒤에는 Figma `RouteRecalculatingScreen` 흐름을 그린다(#736). 승인 중에는 떠나는 행동을
        // 잠그고(UI-005), 승인이 끝나면 버튼과 뒤로 가기 모두 진행 화면으로 간다. 실패하면 비교로 돌아온다.
        if (approved || state.isApproving) {
            BackHandler { if (approved) onContinue() }
            Header(title = R.string.replacement_recalculating_header, onBack = null)
            RouteRecalculatingContent(
                placeName = placeName,
                progress = if (approved) RecalcProgress.Done else RecalcProgress.Approving,
                onContinue = onContinue,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            if (!fullMap) Header(title = R.string.replacement_preview_title, onBack = onBack)
            when (state) {
                PreviewUiState.Loading -> DelayedLoading(placeName)
                is PreviewUiState.Error -> ErrorState(
                    error = state.error,
                    onRetry = onRetry,
                    onOtherCandidates = onOtherCandidates,
                    onReauthenticate = onReauthenticate,
                )

                is PreviewUiState.Content -> ContentState(
                    content = state,
                    fullMap = fullMap,
                    onFullMapChange = { fullMap = it },
                    onApprove = onApprove,
                    onRecreate = onRecreate,
                    onOtherCandidates = onOtherCandidates,
                    map = map,
                )
            }
        }
    }
}

/** 상단 바. Figma의 뒤로 버튼과 `경로 비교` 제목이다. */
/**
 * 상단 바(Figma 실측): 36dp `background` 상자 뒤로 가기, Page title 18sp. 경로 재생성 상태는 Figma대로
 * 뒤로 가기 없이 제목만 둔다(`px-5 pt-3 pb-4`).
 */
@Composable
private fun Header(@StringRes title: Int, onBack: (() -> Unit)?) {
    val spacing = LocalGilpickSpacing.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .then(
                if (onBack != null) Modifier.padding(start = spacing.space3, end = spacing.space5, top = spacing.space1, bottom = spacing.space2)
                else Modifier.padding(start = spacing.space5, end = spacing.space5, top = spacing.space3, bottom = spacing.space4),
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space1),
    ) {
        if (onBack != null) {
            IconBoxButton(
                icon = R.drawable.ic_lucide_arrow_left,
                contentDescription = stringResource(R.string.replacement_back),
                onClick = onBack,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(TAG_HEADER_BACK),
            )
        }
        val title = stringResource(title)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = title.displayFont(),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 미리보기 생성 대기. 1초를 넘길 때만 경로 재생성 모양(UI-004, Figma `RouteRecalculatingScreen`)을 보인다.
 * 그 전에는 아무것도 그리지 않는다(가이드라인 9절).
 */
@Composable
private fun DelayedLoading(placeName: String?) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(LOADING_INDICATOR_DELAY_MILLIS)
        visible = true
    }

    if (visible) {
        RouteRecalculatingContent(placeName = placeName, modifier = Modifier.fillMaxSize())
    }
}

/**
 * Figma `RouteRecalculatingScreen`에서 가져오는 요소만: 원형 진행 표시(112dp, `outlineVariant` 트랙 7dp,
 * `primary` 원호 회전), 가운데 32dp `primaryContainer` 상자 + 16dp `primary` navigation 아이콘, 제목,
 * 설명, 하단 `기존 일정 유지` 안내. 3단계 체크리스트·완료 화면·별도 route는 넣지 않는다(#439 결정).
 * REPL-001은 요청·응답 1회라 진행률을 알 수 없으므로 원호는 indeterminate로 계속 돈다.
 *
 * `변경 승인` 뒤([RecalcProgress.Approving]·[RecalcProgress.Done])에는 Figma의 나머지 요소도 그린다(#736):
 * `진행 단계` 카드, 완료 모양(초록 원·체크), `여행 진행 화면으로 돌아가기`. 승인도 요청·응답 1회이므로
 * 단계는 timer로 꾸미지 않고 요청 중·성공 두 상태에만 맞춘다.
 */
@Composable
internal fun RouteRecalculatingContent(
    placeName: String?,
    modifier: Modifier = Modifier,
    progress: RecalcProgress = RecalcProgress.Preview,
    onContinue: () -> Unit = {},
) {
    val done = progress == RecalcProgress.Done
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(
        when (progress) {
            RecalcProgress.Preview -> R.string.replacement_loading
            RecalcProgress.Approving -> R.string.replacement_recalculating_title
            RecalcProgress.Done -> R.string.replacement_recalculating_done_title
        },
    )
    val rotation by rememberInfiniteTransition(label = "recalculating").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(RECALC_SPIN_MILLIS, easing = LinearEasing), RepeatMode.Restart),
        label = "recalculatingRotation",
    )
    val track = scheme.outlineVariant
    val arc = scheme.primary

    Column(modifier = modifier) {
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
                    .size(RECALC_RING)
                    .semantics { contentDescription = label }
                    .testTag(TAG_RECALCULATING)
                    .drawBehind {
                        val strokePx = RECALC_STROKE.toPx()
                        val inset = strokePx / 2
                        val arcSize = Size(size.width - strokePx, size.height - strokePx)
                        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(strokePx))
                        // Figma dasharray 216/289 ≈ 원주의 75%. 회전 각도만 바뀐다. 완료면 `success` 원을 닫는다.
                        if (done) drawArc(colors.success, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(strokePx))
                        else drawArc(arc, rotation - 90f, RECALC_ARC_DEGREES, false, Offset(inset, inset), arcSize, style = Stroke(strokePx, cap = StrokeCap.Round))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(if (done) RECALC_DONE_BOX else RECALC_ICON_BOX)
                        .background(
                            if (done) colors.success else scheme.primaryContainer,
                            RoundedCornerShape(if (done) radius.lg else radius.md),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(if (done) R.drawable.ic_lucide_check else R.drawable.ic_lucide_navigation),
                        contentDescription = null,
                        tint = if (done) scheme.onPrimary else scheme.primary,
                        modifier = Modifier.size(if (done) RECALC_DONE_ICON else RECALC_ICON),
                    )
                }
            }
            val title = stringResource(if (done) R.string.replacement_recalculating_done_title else R.string.replacement_recalculating_title)
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = title.displayFont(),
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = spacing.space2),
            )
            val (named, unnamed) = when (progress) {
                RecalcProgress.Preview -> R.string.replacement_recalculating_body_named to R.string.replacement_recalculating_body
                RecalcProgress.Approving -> R.string.replacement_recalculating_approving_named to R.string.replacement_recalculating_approving
                RecalcProgress.Done -> R.string.replacement_recalculating_done_named to R.string.replacement_recalculating_done
            }
            Text(
                text = if (placeName != null) stringResource(named, placeName) else stringResource(unnamed),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal),
                color = colors.muted,
                textAlign = TextAlign.Center,
            )
            if (progress != RecalcProgress.Preview) {
                RecalcSteps(done = done, modifier = Modifier.padding(top = spacing.space8))
            }
        }
        val preview = progress == RecalcProgress.Preview
        Text(
            text = stringResource(R.string.replacement_recalculating_kept),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
            color = colors.faint,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.space4, end = spacing.space4, top = spacing.space4, bottom = if (preview) spacing.space8 else spacing.space3),
        )
        if (!preview) {
            // Figma `px-4 pb-8`, 버튼 52dp `background` 채움. 승인 중에는 잠근다(UI-005).
            SecondaryButton(
                label = stringResource(R.string.replacement_recalculating_continue),
                onClick = onContinue,
                enabled = done,
                height = RECALC_BUTTON_HEIGHT,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = spacing.space4, end = spacing.space4, bottom = spacing.space8)
                    .testTag(TAG_RECALC_CONTINUE),
            )
        }
    }
}

/** 미리보기 생성 중 / `변경 승인` 요청 중 / 승인 완료. */
internal enum class RecalcProgress { Preview, Approving, Done }

/**
 * Figma `진행 단계` 카드. 승인 요청 중에는 1단계 완료·2단계 진행 중·3단계 대기, 성공하면 모두 완료다.
 * 상태는 색과 함께 모양(체크·회전 원호·점)과 `완료` 문구, 접근성 상태 문구로 알린다(가이드라인 10절).
 */
@Composable
private fun RecalcSteps(done: Boolean, modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(radius.lg)
    val spin by rememberInfiniteTransition(label = "recalcStep").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(RECALC_STEP_SPIN_MILLIS, easing = LinearEasing), RepeatMode.Restart),
        label = "recalcStepRotation",
    )
    // 0 = 완료, 1 = 진행 중, 2 = 대기
    val steps = listOf(
        R.string.replacement_recalculating_step_check to 0,
        R.string.replacement_recalculating_step_route to if (done) 0 else 1,
        R.string.replacement_recalculating_step_eta to if (done) 0 else 2,
    )

    Column(
        modifier = LocalGilpickShadows.current.card
            .fold(modifier.fillMaxWidth()) { acc, shadow -> acc.dropShadow(shape, shadow) }
            .clip(shape)
            .background(scheme.surface)
            .padding(spacing.space5)
            .testTag(TAG_RECALC_STEPS),
        verticalArrangement = Arrangement.spacedBy(spacing.space4),
    ) {
        Text(
            text = stringResource(R.string.replacement_recalculating_steps),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
            color = colors.muted,
        )
        steps.forEach { (label, status) ->
            val stateLabel = stringResource(
                when (status) {
                    0 -> R.string.replacement_recalculating_step_done
                    1 -> R.string.replacement_recalculating_step_active
                    else -> R.string.replacement_recalculating_step_pending
                },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { stateDescription = stateLabel },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.space3),
            ) {
                Box(
                    modifier = Modifier
                        .size(RECALC_STEP_BOX)
                        .background(
                            when (status) {
                                0 -> colors.success
                                1 -> scheme.primaryContainer
                                else -> scheme.background
                            },
                            RoundedCornerShape(radius.md),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    when (status) {
                        0 -> Icon(
                            painter = painterResource(R.drawable.ic_lucide_check),
                            contentDescription = null,
                            tint = scheme.onPrimary,
                            modifier = Modifier.size(RECALC_STEP_CHECK),
                        )
                        1 -> {
                            val primary = scheme.primary
                            Box(
                                modifier = Modifier.size(RECALC_STEP_SPINNER).drawBehind {
                                    val strokePx = RECALC_STEP_STROKE.toPx()
                                    val arcSize = Size(size.width - strokePx, size.height - strokePx)
                                    drawArc(primary, spin, RECALC_ARC_DEGREES, false, Offset(strokePx / 2, strokePx / 2), arcSize, style = Stroke(strokePx))
                                },
                            )
                        }
                        else -> Box(modifier = Modifier.size(RECALC_STEP_DOT).background(colors.faint, CircleShape))
                    }
                }
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (status == 2) colors.faint else scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (status == 0) {
                    // 보이는 `완료`는 접근성 상태 문구와 같은 말이라 한 번만 읽히게 한다.
                    Text(
                        text = stateLabel,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = colors.success,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
        }
    }
}

/**
 * 미리보기 생성 실패: 공통 오류 화면(가이드라인 9절). 원인 + 기존 일정 유지 문장, `다시 시도하기`(세션 만료면
 * `다시 로그인`), 보조 `다른 후보 보기`(UI-004). 발생 시각·마지막 동작은 이 화면이 모르는 값이라 원인 카드를 그리지 않는다.
 */
@Composable
private fun ErrorState(
    error: ReplacementError,
    onRetry: () -> Unit,
    onOtherCandidates: () -> Unit,
    onReauthenticate: () -> Unit,
) {
    val sessionExpired = error == ReplacementError.SessionExpired
    ErrorState(
        title = stringResource(R.string.replacement_error_title),
        description = stringResource(error.messageRes) + "\n" + stringResource(R.string.replacement_schedule_kept),
        primaryLabel = stringResource(if (sessionExpired) R.string.place_reauthenticate else R.string.replacement_retry),
        onPrimary = if (sessionExpired) onReauthenticate else onRetry,
        secondaryLabel = stringResource(R.string.replacement_other_candidates),
        onSecondary = onOtherCandidates,
        modifier = Modifier.fillMaxSize(),
    )
}

/** 비교 본문: 지도, 무엇이 무엇으로 바뀌는지, 비교 네 항목, 두 행동. */
/**
 * `content`: 위쪽 지도(스크롤하지 않는다)와 아래 변경 요약·비교 표·승인 실패 박스, 하단 고정 버튼 두 개.
 *
 * 지도를 스크롤 영역 밖에 두는 이유는 제스처 때문이다. 스크롤 안에 있으면 세로로 끌 때 바깥 스크롤이
 * 가져가 지도 pan이 끊긴다(#660). 경로 전체를 크게 보려면 `전체 경로 보기`로 같은 지도를 화면 가득 연다.
 */
@Composable
private fun ContentState(
    content: PreviewUiState.Content,
    fullMap: Boolean,
    onFullMapChange: (Boolean) -> Unit,
    onApprove: () -> Unit,
    onRecreate: () -> Unit,
    onOtherCandidates: () -> Unit,
    map: @Composable (PreviewUiState.Content, Modifier) -> Unit,
) {
    val spacing = LocalGilpickSpacing.current

    if (fullMap) {
        FullMap(content = content, onClose = { onFullMapChange(false) }, map = map)
        return
    }
    Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.space3)
                .height(MAP_HEIGHT)
                .padding(horizontal = spacing.space4)
                .testTag(TAG_MAP_SLOT),
        ) {
            map(content, Modifier.fillMaxSize())
            Legend(modifier = Modifier.align(Alignment.TopEnd).padding(spacing.space3))
            ExpandMapButton(onClick = { onFullMapChange(true) }, modifier = Modifier.align(Alignment.BottomEnd).padding(spacing.space2))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Summary(content = content, modifier = Modifier.padding(horizontal = spacing.space4).padding(top = spacing.space3))
            content.approveFailure?.let { failure ->
                ApproveFailure(
                    failure = failure,
                    placeName = content.preview.alternativePlace.name,
                    modifier = Modifier.padding(horizontal = spacing.space4).padding(top = spacing.space3),
                )
            }
            Spacer(modifier = Modifier.height(spacing.space4))
        }
        Actions(
            content = content,
            onApprove = onApprove,
            onRecreate = onRecreate,
            onOtherCandidates = onOtherCandidates,
        )
    }
}

/**
 * 전체 경로 보기(#660): 같은 두 경로를 화면 가득 그린다. 상세 경로 화면(F005)처럼 지도만 있는 화면이라
 * pan·zoom이 다른 제스처와 겹치지 않는다. 뒤로 가기는 비교 화면으로 돌아온다.
 */
@Composable
private fun FullMap(
    content: PreviewUiState.Content,
    onClose: () -> Unit,
    map: @Composable (PreviewUiState.Content, Modifier) -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    BackHandler(onBack = onClose)
    Box(modifier = Modifier.fillMaxSize().testTag(TAG_FULL_MAP)) {
        map(content, Modifier.fillMaxSize())
        IconBoxButton(
            icon = R.drawable.ic_lucide_arrow_left,
            contentDescription = stringResource(R.string.replacement_full_map_close),
            onClick = onClose,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(spacing.space4)
                .testTag(TAG_FULL_MAP_CLOSE),
        )
        Legend(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(spacing.space4),
        )
    }
}

/** 지도 위 `전체 경로 보기` 버튼(#660). 흰 카드에 문구를 둬 아이콘만으로 뜻을 전하지 않는다. */
@Composable
private fun ExpandMapButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val shape = RoundedCornerShape(radius.md)

    Box(
        modifier = modifier
            .heightIn(min = MIN_TOUCH)
            .clip(shape)
            .clickable(onClick = onClick, role = Role.Button)
            .testTag(TAG_EXPAND_MAP),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = spacing.space3, vertical = spacing.space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space1),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_maximize),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(EXPAND_ICON),
            )
            Text(
                text = stringResource(R.string.replacement_full_map),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 지도 범례(UI-001).
 *
 * 색만으로 기존·변경을 구분하지 않도록 **문구를 함께** 둔다. 지도를 그리지 못하는 기기에서도
 * 이 문구가 남아 어느 쪽이 무엇인지 알 수 있다.
 */
@Composable
private fun Legend(modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        LegendItem(label = stringResource(R.string.replacement_legend_original), swatch = colors.faint, radius = radius.sm)
        LegendItem(label = stringResource(R.string.replacement_legend_changed), swatch = MaterialTheme.colorScheme.primary, radius = radius.sm)
    }
}

@Composable
private fun LegendItem(label: String, swatch: Color, radius: Dp) {
    val spacing = LocalGilpickSpacing.current

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(radius))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = spacing.space2, vertical = spacing.space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space1),
    ) {
        Box(
            modifier = Modifier
                .width(LEGEND_SWATCH_WIDTH)
                .height(LEGEND_SWATCH_HEIGHT)
                .background(swatch, CircleShape),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 무엇이 무엇으로 바뀌는지, 왜 바꾸는지, 그리고 비교 네 항목(UI-002). */
@Composable
private fun Summary(content: PreviewUiState.Content, modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val preview = content.preview

    val shape = RoundedCornerShape(radius.lg)

    Column(
        modifier = LocalGilpickShadows.current.card
            .fold(modifier.fillMaxWidth()) { acc, shadow -> acc.dropShadow(shape, shadow) }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(spacing.space5),
        verticalArrangement = Arrangement.spacedBy(spacing.space1),
    ) {
        Text(
            text = stringResource(R.string.replacement_change_badge),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Black,
            color = colors.warning,
            modifier = Modifier
                .clip(RoundedCornerShape(radius.sm))
                .background(colors.warningContainer)
                .padding(horizontal = spacing.space2, vertical = spacing.space1),
        )
        val summary = stringResource(
            R.string.replacement_change_summary,
            preview.originalPlace.name,
            preview.alternativePlace.name,
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = summary.displayFont(),
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag(TAG_CHANGE_SUMMARY),
        )
        // 왜 바꾸는지. F008 감지 결과의 reason을 그대로 보인다(UI-002).
        Text(
            text = preview.detectionReason,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            modifier = Modifier.testTag(TAG_REASON),
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.padding(vertical = spacing.space3),
        )

        val duration = preview.comparison.totalDurationSeconds
        val durationBefore = duration.before?.let { durationLabel(it) }
        val durationAfter = duration.after?.let { durationLabel(it) }
        ComparisonRow(
            label = stringResource(R.string.replacement_row_duration),
            before = durationBefore,
            after = durationAfter,
            better = comparisonTrend(duration.before, duration.after, durationBefore, durationAfter),
        )
        RowDivider()
        val distance = preview.comparison.totalDistanceMeters
        val distanceBefore = distance.before?.let { distanceLabel(it) }
        val distanceAfter = distance.after?.let { distanceLabel(it) }
        ComparisonRow(
            label = stringResource(R.string.replacement_row_distance),
            before = distanceBefore,
            after = distanceAfter,
            better = comparisonTrend(distance.before, distance.after, distanceBefore, distanceAfter),
        )
        RowDivider()
        // 도착 예정·마감 시간은 나아짐·나빠짐의 판정 근거가 명세에 없어 강조하지 않는다(Figma는 색을 준다, PR 기록).
        ComparisonRow(
            label = stringResource(R.string.replacement_row_arrival),
            before = preview.comparison.estimatedArrivalAt.before?.let(::clockLabel),
            after = preview.comparison.estimatedArrivalAt.after?.let(::clockLabel),
            better = null,
        )
        RowDivider()
        ComparisonRow(
            label = stringResource(R.string.replacement_row_closes),
            before = preview.comparison.closesAt.before?.let(::clockLabel),
            after = preview.comparison.closesAt.after?.let(::clockLabel),
            better = null,
        )
    }
}

/** 비교 표 행 사이 1dp `background` 구분선(Figma `border-b border-[#F4F6FB]`, 마지막 행 없음). */
@Composable
private fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.background)
}

/**
 * 비교 한 줄(UI-002·FR-002).
 *
 * 확인하지 못한 값은 `정보 없음`이다. 값이 아니므로 나아짐·나빠짐 색과 아이콘을 쓰지 않는다.
 * 나아짐·나빠짐은 색과 함께 아이콘(체크·경고 삼각형)으로 알리고, 접근성 문구로 항목명·이전 값·이후 값·판정을 한 번에 읽힌다.
 *
 * @param better 나아졌는지. 판단 근거가 없거나 값이 없으면 `null`이며 강조하지 않는다.
 */
@Composable
private fun ComparisonRow(label: String, before: String?, after: String?, better: Boolean?) {
    val spacing = LocalGilpickSpacing.current
    val colors = LocalGilpickColors.current
    val unknown = stringResource(R.string.replacement_value_unknown)
    val beforeText = before ?: unknown
    val afterText = after ?: unknown
    val missing = after == null
    val accent = when {
        missing || better == null -> colors.muted
        better -> colors.success
        else -> colors.warning
    }
    val verdict = when {
        missing || better == null -> ""
        better -> " " + stringResource(R.string.replacement_better)
        else -> " " + stringResource(R.string.replacement_worse)
    }
    val description = stringResource(R.string.replacement_row_description, label, beforeText, afterText) + verdict

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            modifier = Modifier.width(ROW_LABEL_WIDTH),
        )
        Text(
            text = beforeText,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.faint,
            // 이전 값에만 취소선을 둔다. `정보 없음`은 값이 아니므로 긋지 않는다.
            textDecoration = if (before == null) TextDecoration.None else TextDecoration.LineThrough,
        )
        Text(
            text = afterText,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
        // Figma: 나아짐은 12dp `success` 체크, 나빠짐은 12dp `warning` 경고 삼각형. 뜻은 contentDescription의 문구가 전달한다.
        if (!missing && better != null) {
            Icon(
                painter = painterResource(if (better) R.drawable.ic_lucide_check else R.drawable.ic_lucide_triangle_alert),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(ROW_MARK),
            )
        }
    }
}

/**
 * 하단 고정 버튼(Figma `px-4 pb-8 pt-4 space-y-2`). `변경 승인`은 주 gradient 54dp, 승인 실패 뒤 다음 행동은
 * **경고 gradient**(가이드라인 3절), `다른 후보 보기`는 `background` 채움 50dp. 승인 중에는 이 화면 대신 경로 재생성 모양이 보인다(#736).
 */
@Composable
private fun Actions(
    content: PreviewUiState.Content,
    onApprove: () -> Unit,
    onRecreate: () -> Unit,
    onOtherCandidates: () -> Unit,
) {
    val spacing = LocalGilpickSpacing.current
    val action = content.approveFailure?.approveAction

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.space4)
            .padding(top = spacing.space4, bottom = spacing.space8),
        verticalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        GradientButton(
            label = stringResource(
                when {
                    action == ApproveAction.Recreate -> R.string.replacement_action_recreate
                    action == ApproveAction.Candidates -> R.string.replacement_action_candidates
                    action == ApproveAction.Retry -> R.string.replacement_action_retry
                    else -> R.string.replacement_approve
                },
            ),
            onClick = when (action) {
                ApproveAction.Recreate -> onRecreate
                ApproveAction.Candidates -> onOtherCandidates
                ApproveAction.Retry, null -> onApprove
            },
            tone = if (action == null) GradientTone.Primary else GradientTone.Warning,
            modifier = Modifier.fillMaxWidth().testTag(TAG_APPROVE),
        )
        SecondaryButton(
            label = stringResource(R.string.replacement_other_candidates),
            onClick = onOtherCandidates,
            height = SECONDARY_BUTTON_HEIGHT,
            modifier = Modifier.fillMaxWidth().testTag(TAG_OTHER_CANDIDATES),
        )
    }
}

/**
 * 승인 실패 안내(가이드라인 9절 "실패 안내 박스" 블록형): `warningContainer` + 1dp `warningBorder`, 32dp `warning`
 * 채움 아이콘 상자, `onWarningContainer` 원인 문장, 보조 문장 `기존 일정은 그대로예요`(FR-007). 다음 행동은
 * 박스 밖 하단 CTA가 경고 gradient로 바뀐다([Actions]).
 */
@Composable
private fun ApproveFailure(failure: ReplacementError, placeName: String, modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current
    val radius = LocalGilpickRadius.current
    val colors = LocalGilpickColors.current
    val message = if (failure == ReplacementError.AlternativeUnavailable) {
        stringResource(R.string.replacement_error_alternative_unavailable, placeName)
    } else {
        stringResource(failure.messageRes)
    }
    val shape = RoundedCornerShape(radius.lg)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(FAILURE_BORDER, colors.warningBorder, shape)
            .clip(shape)
            .background(colors.warningContainer)
            .padding(spacing.space4)
            .semantics(mergeDescendants = true) {}
            .testTag(TAG_APPROVE_FAILURE),
        horizontalArrangement = Arrangement.spacedBy(spacing.space3),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(FAILURE_ICON_BOX)
                .background(colors.warning, RoundedCornerShape(radius.md)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_triangle_alert),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(FAILURE_ICON),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(spacing.space1), modifier = Modifier.weight(1f)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = colors.onWarningContainer,
            )
            // 보조 문장 색 `#B45309`(3절 괄호 값)은 이름 붙은 토큰이 없어 제목과 같은 `onWarningContainer`를 쓴다(PR 기록).
            Text(
                text = stringResource(R.string.replacement_schedule_kept),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onWarningContainer,
            )
        }
    }
}

/**
 * 기존 경로와 변경 경로를 함께 그리는 지도(UI-001).
 *
 * F005 [RouteMap]을 그대로 쓰고 기존 경로만 `baseRoute`로 넘긴다. 기존 경로를 받지 못했으면
 * 변경 경로 하나만 그린다. 어느 쪽이 기존인지는 위의 [Legend]가 문구로 알린다.
 */
@Composable
internal fun PreviewMap(content: PreviewUiState.Content, modifier: Modifier = Modifier) {
    RouteMap(
        route = content.preview.route,
        baseRoute = content.originalRoute,
        modifier = modifier,
        // 이 화면은 지도 아래를 sheet가 덮지 않는다. 지도 높이 안에서 두 경로가 모두 보이면 된다.
        sheetFraction = 0f,
    )
}

/**
 * 승인 실패 뒤 사용자가 할 수 있는 다음 행동(UI-005, quickstart FE 4).
 *
 * 원인마다 달라야 한다(SC-007). 이 화면에서 바로 고칠 수 있으면 [Recreate], 이 후보로는 더 이상
 * 안 되면 [Candidates], 같은 요청을 그대로 다시 보내면 되면 [Retry]다.
 */
private enum class ApproveAction { Recreate, Candidates, Retry }

/**
 * 승인 실패 원인을 다음 행동으로 옮긴다.
 *
 * 명세가 정한 다섯 가지는 quickstart FE 4의 표 그대로다. 나머지 원인은 명세에 없어 성격이 같은
 * 쪽에 붙였다. 미리보기가 낡은 경우는 [Recreate], 이 감지·후보로 더 진행할 수 없는 경우는
 * [Candidates], 그 밖에는 [Retry]다.
 */
private val ReplacementError.approveAction: ApproveAction
    get() = when (this) {
        ReplacementError.ScheduleChanged, ReplacementError.PreviewExpired -> ApproveAction.Recreate
        ReplacementError.AlreadyVisited, ReplacementError.AlternativeUnavailable -> ApproveAction.Candidates
        ReplacementError.Network -> ApproveAction.Retry

        // 명세 밖 원인. 미리보기가 밀려났으면 다시 만들고, 감지·승인이 이미 끝났으면 후보 목록으로
        // 돌아가야 이 화면에서 벗어날 수 있다.
        ReplacementError.PreviewSuperseded -> ApproveAction.Recreate
        ReplacementError.AlreadyApproved,
        ReplacementError.DetectionNotActive,
        ReplacementError.SessionExpired,
        -> ApproveAction.Candidates

        ReplacementError.UndoExpired,
        ReplacementError.FollowUpChangeExists,
        is ReplacementError.RouteUnavailable,
        ReplacementError.Unexpected,
        -> ApproveAction.Retry
    }

/** 승인 처리 중인지. 승인 중에는 뒤로 가기까지 잠근다(Figma 승인 진행 중 상태). */
private val PreviewUiState.isApproving: Boolean
    get() = (this as? PreviewUiState.Content)?.approving == true

/**
 * 값이 작아질수록 좋은 항목(이동 시간·거리)의 나아짐 판정. 값이 없거나 같으면 강조하지 않는다.
 *
 * 원값이 달라도 화면에 보이는 반올림 값이 같으면(`11.4km → 11.4km`) 강조하지 않는다. 사용자가 차이를 볼 수 없는데
 * `나아짐 ✓`을 붙이면 사실과 다르게 읽힌다(#593).
 */
internal fun comparisonTrend(before: Int?, after: Int?, beforeLabel: String?, afterLabel: String?): Boolean? =
    if (before == null || after == null || before == after || beforeLabel == afterLabel) null else after < before

/** 실패 원인별 안내 문구. 원인마다 다음 행동이 다르므로 문구도 나눈다(UI-004·UI-005). */
@get:StringRes
internal val ReplacementError.messageRes: Int
    get() = when (this) {
        ReplacementError.Network -> R.string.replacement_error_network
        ReplacementError.ScheduleChanged -> R.string.replacement_error_schedule_changed
        ReplacementError.PreviewExpired -> R.string.replacement_error_expired
        ReplacementError.PreviewSuperseded -> R.string.replacement_error_superseded
        ReplacementError.AlreadyApproved -> R.string.replacement_error_already_approved
        ReplacementError.AlreadyVisited -> R.string.replacement_error_already_visited
        ReplacementError.AlternativeUnavailable -> R.string.replacement_error_alternative_unavailable
        ReplacementError.DetectionNotActive -> R.string.replacement_error_detection_not_active
        ReplacementError.UndoExpired -> R.string.replacement_error_undo_expired
        ReplacementError.FollowUpChangeExists -> R.string.replacement_error_follow_up
        is ReplacementError.RouteUnavailable -> R.string.replacement_error_route
        ReplacementError.SessionExpired, ReplacementError.Unexpected -> R.string.replacement_error_unexpected
    }

internal const val TAG_APPROVE = "preview_approve"
internal const val TAG_OTHER_CANDIDATES = "preview_other_candidates"
internal const val TAG_MAP_SLOT = "preview_map"
internal const val TAG_CHANGE_SUMMARY = "preview_change_summary"
internal const val TAG_REASON = "preview_reason"
internal const val TAG_APPROVE_FAILURE = "preview_approve_failure"
internal const val TAG_EXPAND_MAP = "preview_expand_map"
internal const val TAG_FULL_MAP = "preview_full_map"
internal const val TAG_FULL_MAP_CLOSE = "preview_full_map_close"


private const val LOADING_INDICATOR_DELAY_MILLIS = 1_000L

/** Figma `RouteRecalculatingScreen` 실측(`w-28`, `strokeWidth 7`, `w-8`, 16px 아이콘, `spin-cw 1.2s`). 화면 전용이라 토큰이 아니다. */
private val RECALC_RING: Dp = 112.dp
private val RECALC_STROKE: Dp = 7.dp
private val RECALC_ICON_BOX: Dp = 32.dp
private val RECALC_ICON: Dp = 16.dp
private const val RECALC_ARC_DEGREES = 270f
private const val RECALC_SPIN_MILLIS = 1_200
internal const val TAG_RECALCULATING = "replacement_recalculating"
/** 승인 뒤 상태 실측(#736): 완료 상자 `w-12`·체크 22px, 단계 상자 `w-7`·체크 13px·원호 `w-4 border-2`·점 `w-3`, `spin-cw 0.8s`, 버튼 52. */
private val RECALC_DONE_BOX: Dp = 48.dp
private val RECALC_DONE_ICON: Dp = 22.dp
private val RECALC_STEP_BOX: Dp = 28.dp
private val RECALC_STEP_CHECK: Dp = 13.dp
private val RECALC_STEP_SPINNER: Dp = 16.dp
private val RECALC_STEP_STROKE: Dp = 2.dp
private val RECALC_STEP_DOT: Dp = 12.dp
private const val RECALC_STEP_SPIN_MILLIS = 800
private val RECALC_BUTTON_HEIGHT: Dp = 52.dp
internal const val TAG_RECALC_STEPS = "replacement_recalculating_steps"
internal const val TAG_RECALC_CONTINUE = "replacement_recalculating_continue"
/** 비교 화면 지도 높이. 전체 경로가 한눈에 들어오도록 Figma 190dp보다 키웠다(#660). */
private val MAP_HEIGHT: Dp = 240.dp

/** 가이드라인 10절 최소 터치 영역. */
private val MIN_TOUCH: Dp = 48.dp
private val EXPAND_ICON: Dp = 14.dp
private val ROW_MIN_HEIGHT: Dp = 44.dp
private val ROW_LABEL_WIDTH: Dp = 72.dp
private val LEGEND_SWATCH_WIDTH: Dp = 16.dp
private val LEGEND_SWATCH_HEIGHT: Dp = 2.dp
/** Figma 실측: `다른 후보 보기` 50dp, 비교 행 아이콘 12dp, 실패 박스 32dp 상자·15dp 아이콘·1dp 테두리. 화면 전용이라 토큰이 아니다. */
private val SECONDARY_BUTTON_HEIGHT: Dp = 50.dp
private val ROW_MARK: Dp = 12.dp
private val FAILURE_ICON_BOX: Dp = 32.dp
private val FAILURE_ICON: Dp = 15.dp
private val FAILURE_BORDER: Dp = 1.dp
