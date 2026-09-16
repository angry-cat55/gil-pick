package com.gilpick.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gilpick.R
import kotlinx.coroutines.flow.filterNotNull

/** 지도 위 sheet의 높이 단계(#550). 접힘은 윗부분만, 기본·펼침은 화면 높이 비율이다. */
enum class SheetAnchor { COLLAPSED, DEFAULT, EXPANDED }

/**
 * 지도 위에 겹치는 sheet의 높이 상태(F005 경로 화면, F009 후보 목록 공용).
 *
 * 윗부분을 끌면 높이가 손가락을 따라가고 놓으면 끈 방향의 다음 단계에 붙는다. 접힘 높이는 화면이
 * [onTopMeasured]로 알려 주는 윗부분 높이라 내용에 따라 달라진다. 멈춘 높이 비율([fraction])만 지도에
 * 넘겨 카메라·SDK control이 sheet 아래 숨지 않게 한다 — 매 frame 넘기면 카메라가 계속 다시 맞춰진다.
 */
@Stable
class SheetDragState internal constructor(
    private val anchorState: MutableState<SheetAnchor>,
    private val maxPx: Float,
    private val defaultFraction: Float,
    private val expandedFraction: Float,
    internal val flingPx: Float,
    internal val slopPx: Float,
) {
    /** 현재 단계. 손잡이 누름·접근성 action·끌기 종료가 바꾼다. 회전·복귀에도 남는다. */
    var anchor: SheetAnchor
        get() = anchorState.value
        set(value) {
            anchorState.value = value
        }

    /** 끄는 중의 높이(px). `null`이면 단계 높이를 쓴다. */
    internal var dragPx by mutableStateOf<Float?>(null)
    internal var collapsedPx by mutableFloatStateOf(0f)
    internal var sheetPx by mutableIntStateOf(0)
    internal var dragStartPx by mutableFloatStateOf(0f)

    /** 애니메이션을 거친 현재 높이(px). [rememberSheetDragState]가 채운다. */
    internal var animated: State<Float> = mutableFloatStateOf(0f)

    /** 지도에 넘길, 멈춘 sheet 높이 비율. */
    var fraction by mutableFloatStateOf(defaultFraction)
        internal set

    internal fun anchorPx(value: SheetAnchor) = when (value) {
        SheetAnchor.COLLAPSED -> collapsedPx
        SheetAnchor.DEFAULT -> maxPx * defaultFraction
        SheetAnchor.EXPANDED -> maxPx * expandedFraction
    }

    internal val targetPx: Float get() = dragPx ?: anchorPx(anchor)

    /** 접힌 채 멈춰 있다. 가려진 내용을 접근성 트리에서도 뺄 때 쓴다. */
    val collapsed: Boolean get() = anchor == SheetAnchor.COLLAPSED && dragPx == null

    /** 현재 높이. sheet에 `heightIn(max = ...)`으로 준다. */
    val height: Dp
        @Composable get() = with(LocalDensity.current) { animated.value.toDp() }

    /** 윗부분(손잡이·요약) 높이를 알린다. 접힘 높이가 된다. */
    fun onTopMeasured(px: Int) {
        collapsedPx = px.toFloat()
    }

    internal fun onDragStopped(velocity: Float) {
        // 내용이 짧으면 보이는 높이가 단계 높이보다 낮아 "가까운 단계"가 어긋난다. 그래서 시작 단계에서
        // 끈 방향으로 한 단계 옮기고, 기본 높이를 넘겨 멀리 끌었으면 끝 단계까지 간다.
        val current = dragPx ?: sheetPx.toFloat()
        val moved = current - dragStartPx
        val defaultPx = anchorPx(SheetAnchor.DEFAULT)
        anchor = when {
            // 화면 좌표는 아래가 +라 위로 밀면 velocity가 음수, 높이 변화(moved)는 양수다.
            velocity < -flingPx || moved > slopPx -> when (anchor) {
                SheetAnchor.COLLAPSED -> if (current > defaultPx + slopPx) SheetAnchor.EXPANDED else SheetAnchor.DEFAULT
                else -> SheetAnchor.EXPANDED
            }
            velocity > flingPx || moved < -slopPx -> when (anchor) {
                SheetAnchor.EXPANDED -> if (current < defaultPx - slopPx) SheetAnchor.COLLAPSED else SheetAnchor.DEFAULT
                else -> SheetAnchor.COLLAPSED
            }
            else -> anchor
        }
        dragPx = null
    }
}

/**
 * [SheetDragState]를 만들고 높이 애니메이션·지도 비율 갱신을 건다.
 *
 * @param maxPx 화면(지도) 높이. `BoxWithConstraints`의 `constraints.maxHeight`다.
 * @param defaultFraction 기본 단계 높이 비율. 나머지는 지도 조작 영역이다(UI-009).
 * @param expandedFraction 펼침 단계 높이 비율. 펼쳐도 지도 윗부분은 남긴다.
 */
@Composable
fun rememberSheetDragState(
    maxPx: Float,
    defaultFraction: Float = 0.45f,
    expandedFraction: Float = 0.85f,
): SheetDragState {
    val density = LocalDensity.current
    val flingPx = with(density) { SHEET_FLING_VELOCITY.toPx() }
    val slopPx = with(density) { SHEET_STEP_DISTANCE.toPx() }
    val anchorState = rememberSaveable { mutableStateOf(SheetAnchor.DEFAULT) }
    val state = remember(maxPx, defaultFraction, expandedFraction) {
        SheetDragState(anchorState, maxPx, defaultFraction, expandedFraction, flingPx, slopPx)
    }
    state.animated = animateFloatAsState(
        targetValue = state.targetPx,
        animationSpec = if (state.dragPx != null) snap() else spring(stiffness = Spring.StiffnessMediumLow),
        label = "sheetHeight",
    )
    LaunchedEffect(state) {
        snapshotFlow { if (state.dragPx == null && state.animated.value == state.anchorPx(state.anchor)) state.sheetPx else null }
            .filterNotNull()
            .collect { if (maxPx > 0) state.fraction = it / maxPx }
    }
    return state
}

/** sheet 윗부분에 붙여 끌기를 받는다. */
@Composable
fun SheetDragState.dragModifier(): Modifier {
    val dragState = rememberDraggableState { delta ->
        dragPx = ((dragPx ?: sheetPx.toFloat()) - delta).coerceIn(collapsedPx, anchorPx(SheetAnchor.EXPANDED))
    }
    return Modifier.draggable(
        state = dragState,
        orientation = Orientation.Vertical,
        onDragStarted = { dragStartPx = sheetPx.toFloat() },
        onDragStopped = { velocity -> onDragStopped(velocity) },
    )
}

/** sheet 전체 높이를 잰다. 끌기 계산이 실제 높이를 기준으로 하도록 sheet 바깥 modifier에 붙인다. */
fun Modifier.measureSheet(state: SheetDragState): Modifier = onSizeChanged { state.sheetPx = it.height }

/**
 * 손잡이(40dp 막대, 터치 48dp). 누르면 한 단계 펼치고 펼침에서는 기본으로 돌린다. 끌기가 어려운
 * 사용자를 위해 현재 단계를 상태로 읽어 주고 `펼치기`·`접기` action을 둔다(가이드라인 10절, #550).
 *
 * @param color 막대 색. 어두운 sheet는 흰 20%, 밝은 sheet는 `outlineVariant`다.
 */
@Composable
fun SheetHandle(
    anchor: SheetAnchor,
    onAnchorChange: (SheetAnchor) -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    barHeight: Dp = 4.dp,
) {
    val label = stringResource(R.string.route_sheet_handle)
    val state = stringResource(
        when (anchor) {
            SheetAnchor.COLLAPSED -> R.string.route_sheet_collapsed
            SheetAnchor.DEFAULT -> R.string.route_sheet_default
            SheetAnchor.EXPANDED -> R.string.route_sheet_expanded
        },
    )
    val expandLabel = stringResource(R.string.route_sheet_expand)
    val collapseLabel = stringResource(R.string.route_sheet_collapse)
    val up = SheetAnchor.entries.getOrNull(anchor.ordinal + 1)
    val down = SheetAnchor.entries.getOrNull(anchor.ordinal - 1)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(HANDLE_TOUCH)
            .clickable(role = Role.Button) { onAnchorChange(up ?: SheetAnchor.DEFAULT) }
            .semantics {
                contentDescription = label
                stateDescription = state
                customActions = listOfNotNull(
                    up?.let { CustomAccessibilityAction(expandLabel) { onAnchorChange(it); true } },
                    down?.let { CustomAccessibilityAction(collapseLabel) { onAnchorChange(it); true } },
                )
            }
            .testTag(TAG_SHEET_HANDLE),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(HANDLE_WIDTH)
                .height(barHeight)
                .clip(CircleShape)
                .background(color),
        )
    }
}

const val TAG_SHEET_HANDLE = "route_sheet_handle"

private val HANDLE_TOUCH: Dp = 48.dp
private val HANDLE_WIDTH: Dp = 40.dp

/** 이보다 빠르게 밀거나 멀리 끌면 그 방향의 다음 단계로 간다. 못 미치면 원래 단계로 돌아간다. */
private val SHEET_FLING_VELOCITY: Dp = 400.dp
private val SHEET_STEP_DISTANCE: Dp = 48.dp
