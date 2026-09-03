package com.gilpick.trip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.gilpick.R
import com.gilpick.ui.component.BadgeTone
import com.gilpick.ui.component.StatusBadge
import com.gilpick.ui.theme.LocalGilpickSpacing
import kotlinx.coroutines.delay

/**
 * 여행 상세 화면.
 *
 * pen `08. 여행 상세 화면` 가운데 **Summary(이름·기간·상태)와 AppBar**만 구현한다.
 * 같은 화면의 `Day`·`Step` 일정 목록과 `ActionBar`(`오늘 여행 시작`,
 * `여행 진행 화면 보기`)는 F004 일정·F005 진행 데이터가 있어야 그릴 수 있고 F002
 * 계약(`TripDto`)에 해당 값이 없다. 그 데이터가 생길 때까지 부분 화면이며 나머지는
 * 후속 feature에서 채운다.
 *
 * AppBar의 `ellipsis`·`triangle-alert` 액션도 만들지 않는다. 각각 수정·삭제(#106,
 * #107)와 F005 변수 감지에 해당하는데 지금 눌러서 할 수 있는 일이 없다. AGENTS.md
 * 6절이 pen에도 명세에도 없는 기능을 임의로 추가하지 않도록 정한다.
 *
 * 표시 단계는 `docs/design/ui-guidelines.md` 9절을 따른다. `empty`는 상세 조회에
 * 성립하지 않으므로 만들지 않는다(근거는 [TripDetailPhase]).
 *
 * @param state 현재 상세 상태.
 * @param onBack 이전 화면으로 돌아간다.
 * @param onRetry 실패한 조회를 다시 시도한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(
    state: TripDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalGilpickSpacing.current

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.trip_detail_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.size(MIN_TOUCH)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        // 아이콘 전용 버튼이므로 설명이 필수다(가이드라인 10절).
                        contentDescription = stringResource(R.string.trip_detail_back),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )

        Box(modifier = Modifier.weight(1f)) {
            when (val phase = state.phase) {
                TripDetailPhase.Loading -> LoadingState()

                is TripDetailPhase.Content -> Summary(
                    trip = phase.trip,
                    modifier = Modifier.padding(horizontal = spacing.space5),
                )

                is TripDetailPhase.Failed -> ErrorState(
                    error = phase.error,
                    onRetry = onRetry,
                    onBack = onBack,
                    modifier = Modifier.padding(horizontal = spacing.space5),
                )
            }
        }
    }
}

/**
 * 여행 요약.
 *
 * 이름·기간·상태는 US3 Acceptance Scenario 1이 요구하는 세 가지다. 상태는 색만으로
 * 구분하지 않도록 문구를 가진 [StatusBadge]로 표시한다(가이드라인 10절).
 */
@Composable
private fun Summary(trip: TripDto, modifier: Modifier = Modifier) {
    val spacing = LocalGilpickSpacing.current

    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(vertical = spacing.space4),
            verticalArrangement = Arrangement.spacedBy(spacing.space1),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = trip.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                StatusBadge(
                    label = stringResource(trip.status.detailLabelRes),
                    tone = trip.status.detailTone,
                )
            }
            Text(
                text = stringResource(R.string.trips_period, trip.startDate, trip.endDate),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.trips_day_count, trip.dayCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 조회 대기 표시. 1초를 넘길 때만 표시한다(가이드라인 9절). */
@Composable
private fun LoadingState() {
    var visible by remember { mutableStateOf(false) }
    val label = stringResource(R.string.trip_detail_loading)

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

/**
 * 실패 상태.
 *
 * 원인과 다음 행동을 함께 준다(가이드라인 9절). 다만 다음 행동이 원인마다 다르다.
 * 통신 실패와 알 수 없는 실패는 같은 요청을 다시 보내면 되지만, 없는 여행(`404`)과
 * 권한 없는 여행(`403`)은 몇 번을 다시 보내도 결과가 같다. 그 경우 재시도 대신
 * 목록으로 돌아가는 길을 준다.
 */
@Composable
private fun ErrorState(
    error: TripDetailError,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalGilpickSpacing.current
    val retryable = error == TripDetailError.NETWORK || error == TripDetailError.UNEXPECTED

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(spacing.space3, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(error.messageRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = if (retryable) onRetry else onBack,
            modifier = Modifier.heightIn(min = PRIMARY_BUTTON_HEIGHT),
        ) {
            Text(
                stringResource(
                    if (retryable) R.string.trips_retry else R.string.trip_detail_back_to_list,
                ),
            )
        }
    }
}

/** 실패 원인별 안내 문구. 원인을 뭉뚱그리지 않는다(가이드라인 9절). */
private val TripDetailError.messageRes: Int
    get() = when (this) {
        TripDetailError.NETWORK -> R.string.trip_detail_error_network
        TripDetailError.NOT_FOUND -> R.string.trip_detail_error_not_found
        TripDetailError.FORBIDDEN -> R.string.trip_detail_error_forbidden
        TripDetailError.UNEXPECTED -> R.string.trip_detail_error_unexpected
    }

/** 상태 뱃지 문구. 색 없이도 뜻이 통해야 한다. */
private val TripStatus.detailLabelRes: Int
    get() = when (this) {
        TripStatus.UPCOMING -> R.string.trips_status_upcoming
        TripStatus.IN_PROGRESS -> R.string.trips_status_in_progress
        TripStatus.COMPLETED -> R.string.trips_status_completed
    }

/** 목록 카드와 같은 규칙으로 진행 중인 여행만 강조한다. */
private val TripStatus.detailTone: BadgeTone
    get() = if (this == TripStatus.IN_PROGRESS) BadgeTone.ACCENT else BadgeTone.NEUTRAL

/** 가이드라인 9절: 1초를 넘길 때만 대기 표시를 띄운다. */
private const val LOADING_INDICATOR_DELAY_MILLIS = 1_000L

/** 가이드라인 5절·10절: 주요 CTA 52~56dp, 터치 영역 48dp 이상. */
private val PRIMARY_BUTTON_HEIGHT = Dp(56f)
private val MIN_TOUCH = Dp(48f)
