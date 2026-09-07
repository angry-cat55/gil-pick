package com.gilpick.progress

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gilpick.R
import com.gilpick.itinerary.ItemStatus
import com.gilpick.route.distanceLabel
import com.gilpick.route.durationLabel
import com.gilpick.trip.KST
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 계약 값을 진행 화면 문구로 옮기는 규칙. F005 `RouteLabels`와 같은 역할이다.
 *
 * 색으로만 구분하지 않고(가이드라인 10절) 문구와 아이콘이 뜻을 전달한다. 이동시간·거리는 F005
 * [durationLabel]·[distanceLabel]을, 이동 수단은 F004 `labelRes`·`iconRes`를 그대로 쓴다.
 */

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN).withZone(KST)

/** ISO-8601 시각을 Figma 표기(`오후 2:35`)로 바꾼다. 서버 시각은 UTC이므로 KST로 옮긴다. */
fun timeLabel(iso: String): String = TIME_FORMAT.format(Instant.parse(iso))

/**
 * 도착 예정 시각과 지금의 차이 문구(UI-002). `· 12분 남았어요` 또는 `· 5분 지났어요`.
 *
 * 도착 예정 시각이 지나도 시각 자체는 바꾸지 않고 이 문구만 바뀐다.
 */
@Composable
fun remainingLabel(eta: Instant, now: Instant): String {
    val seconds = (eta.epochSecond - now.epochSecond).toInt()
    return if (seconds >= 0) {
        stringResource(R.string.progress_remaining, durationLabel(seconds))
    } else {
        stringResource(R.string.progress_overdue, durationLabel(-seconds))
    }
}

/** 상태 칩 문구(UI-004). `예정`은 F004에 없어 여기서 더한다. */
val ItemStatus.progressLabelRes: Int
    @StringRes get() = when (this) {
        ItemStatus.PLANNED -> R.string.progress_status_planned
        ItemStatus.EN_ROUTE -> R.string.itinerary_edit_status_en_route
        ItemStatus.ARRIVED -> R.string.itinerary_edit_status_arrived
        ItemStatus.COMPLETED -> R.string.itinerary_edit_status_completed
        ItemStatus.SKIPPED -> R.string.itinerary_edit_status_skipped
    }

/** 순서 원 안의 아이콘(Figma: 완료·도착 체크, 건너뜀 X, 이동 중 화살표). `예정`은 순서 번호라 `null`이다. */
val ItemStatus.progressIconRes: Int?
    @DrawableRes get() = when (this) {
        ItemStatus.PLANNED -> null
        ItemStatus.EN_ROUTE -> R.drawable.ic_lucide_chevron_right
        ItemStatus.ARRIVED, ItemStatus.COMPLETED -> R.drawable.ic_lucide_check
        ItemStatus.SKIPPED -> R.drawable.ic_lucide_x
    }

/** 조회 실패 원인별 안내 문구. 원인을 뭉뚱그리지 않는다(가이드라인 9절). */
val ProgressError.messageRes: Int
    @StringRes get() = when (this) {
        ProgressError.Network -> R.string.progress_error_network
        ProgressError.NotFound -> R.string.progress_error_not_found
        ProgressError.Forbidden -> R.string.progress_error_forbidden
        ProgressError.SessionExpired -> R.string.progress_error_session
        ProgressError.VersionConflict -> R.string.progress_error_conflict
        ProgressError.DayNotToday -> R.string.progress_error_not_today
        ProgressError.DayEmpty -> R.string.progress_error_day_empty
        ProgressError.DayNotStarted -> R.string.progress_error_not_started
        ProgressError.InvalidTransition -> R.string.progress_error_invalid_transition
        ProgressError.Unexpected -> R.string.progress_error_unexpected
    }
