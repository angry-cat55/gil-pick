package com.gilpick.route

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gilpick.R
import java.time.LocalDate
import java.util.Locale

/**
 * 계약 값을 경로 화면 문구로 옮기는 규칙. F004 `ItineraryLabels`와 같은 역할이다.
 *
 * 색으로만 구분하지 않고(가이드라인 10절) 문구가 뜻을 전달한다. 이동 수단 문구·아이콘은
 * F004 [com.gilpick.itinerary.labelRes]·[com.gilpick.itinerary.iconRes]를 그대로 쓴다.
 */

/** 이동시간 문구. `0분`, `25분`, `1시간`, `1시간 5분`. 60초 미만은 올림해 `1분`으로 둔다(0초만 `0분`). */
@Composable
fun durationLabel(seconds: Int): String {
    val minutes = if (seconds == 0) 0 else maxOf(1, (seconds + 30) / 60)
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> stringResource(R.string.route_duration_minutes, minutes)
        rest == 0 -> stringResource(R.string.route_duration_hours, hours)
        else -> stringResource(R.string.route_duration_hours_minutes, hours, rest)
    }
}

/** 이동거리 문구. 1km 미만은 `800m`, 이상은 소수 첫째 자리 `3.4km`. */
@Composable
fun distanceLabel(meters: Int): String = if (meters < 1_000) {
    stringResource(R.string.route_distance_meters, meters)
} else {
    stringResource(R.string.route_distance_kilometers, String.format(Locale.ROOT, "%.1f", meters / 1_000.0))
}

/** Figma `DayRouteScreen` 부제의 날짜 표기(`5월 21일`). */
val LocalDate.routeDateLabel: String
    get() = "${monthValue}월 ${dayOfMonth}일"

/** 조회 요청 실패 원인별 안내 문구. 원인을 뭉뚱그리지 않는다(가이드라인 9절). */
val RouteError.messageRes: Int
    @StringRes get() = when (this) {
        RouteError.Network -> R.string.route_error_network
        RouteError.NotFound -> R.string.route_error_not_found
        RouteError.Forbidden -> R.string.route_error_forbidden
        RouteError.SessionExpired -> R.string.route_error_session
        RouteError.VersionConflict -> R.string.route_error_conflict
        RouteError.NotFailed -> R.string.route_error_not_failed
        RouteError.Unexpected -> R.string.route_error_unexpected
    }

/** 경로 계산 최종 실패 code별 안내 문구. 모르는 code는 일반 실패로 안내한다. */
val RouteFailureDto.messageRes: Int
    @StringRes get() = when (code) {
        RouteFailureCodes.PROVIDER_TIMEOUT -> R.string.route_failure_timeout
        RouteFailureCodes.PROVIDER_RATE_LIMITED -> R.string.route_failure_rate_limited
        RouteFailureCodes.PROVIDER_UNAVAILABLE -> R.string.route_failure_unavailable
        RouteFailureCodes.NOT_FOUND -> R.string.route_failure_not_found
        RouteFailureCodes.INVALID_RESULT -> R.string.route_failure_invalid
        else -> R.string.route_failure_unknown
    }

/** `error` 상태의 원인 문구. */
val RouteProblem.messageRes: Int
    @StringRes get() = when (this) {
        is RouteProblem.Request -> error.messageRes
        is RouteProblem.Calculation -> failure.messageRes
        RouteProblem.Stale -> R.string.route_error_stale
    }
