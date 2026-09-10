package com.gilpick.notification

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gilpick.R
import com.gilpick.alternative.CongestionLevel
import com.gilpick.alternative.PrecipitationType
import com.gilpick.alternative.UnavailableReason
import com.gilpick.alternative.VariableVerdictsDto
import com.gilpick.alternative.clockLabel
import com.gilpick.progress.timeLabel
import com.gilpick.trip.KST
import java.time.Instant
import java.time.OffsetDateTime

/**
 * 계약 값을 알림 목록 문구·아이콘·목적지로 옮기는 규칙. F009 `AlternativeLabels`와 같은 역할이다.
 *
 * 시각은 서버 offset과 무관하게 KST로 읽는다(UI-001).
 */

/** 날짜 구간 헤더 문구. */
val DateBucket.labelRes: Int
    @StringRes get() = when (this) {
        DateBucket.TODAY -> R.string.notification_group_today
        DateBucket.YESTERDAY -> R.string.notification_group_yesterday
        DateBucket.EARLIER -> R.string.notification_group_earlier
    }

/** [createdAt]이 [now] 기준 KST로 오늘·어제·그 이전 중 어디인지. */
fun dateBucket(createdAt: String, now: Instant): DateBucket {
    val created = OffsetDateTime.parse(createdAt).toInstant().atZone(KST).toLocalDate()
    val today = now.atZone(KST).toLocalDate()
    return when {
        !created.isBefore(today) -> DateBucket.TODAY
        created == today.minusDays(1) -> DateBucket.YESTERDAY
        else -> DateBucket.EARLIER
    }
}

/**
 * 행의 상대 시각 문구(Figma: `3분 전`·`12분 전`·`오전 11:32`).
 *
 * 1분 미만은 `방금`, 1시간 미만은 `N분 전`, 그 뒤는 F006 [timeLabel] 시각 표기다.
 */
@Composable
fun relativeTimeLabel(createdAt: String, now: Instant): String {
    val minutes = (now.epochSecond - OffsetDateTime.parse(createdAt).toInstant().epochSecond) / 60
    return when {
        minutes < 1 -> stringResource(R.string.notification_time_just_now)
        minutes < 60 -> stringResource(R.string.notification_time_minutes_ago, minutes)
        else -> timeLabel(createdAt)
    }
}

/** 행 앞 아이콘(Figma `NotifIcon`: 제안은 핀, 도착·출발 확인은 위치, 자동 처리는 체크). */
val NotificationType.iconRes: Int
    @DrawableRes get() = when (this) {
        NotificationType.PLACE_CHANGE_SUGGESTION,
        NotificationType.ARRIVAL_CHECK,
        NotificationType.DEPARTURE_CHECK,
        -> R.drawable.ic_lucide_map_pin

        NotificationType.ARRIVAL_AUTO_CONFIRMED,
        NotificationType.DEPARTURE_AUTO_CONFIRMED,
        -> R.drawable.ic_lucide_check
    }

/**
 * 알림 유형별 탭 목적지(FR-018).
 *
 * 장소 변경 제안인데 `detectionId`가 없으면(계약 위반) 진행 화면으로 보낸다. 그 화면이 대상
 * 없음을 문구로 안내한다(FR-016).
 */
val NotificationItemDto.target: NotificationTarget
    get() = when (type) {
        NotificationType.PLACE_CHANGE_SUGGESTION ->
            detectionId?.let { NotificationTarget.Alternative(detectionId = it, tripId = tripId) }
                ?: NotificationTarget.Progress(tripId)

        NotificationType.ARRIVAL_CHECK,
        NotificationType.DEPARTURE_CHECK,
        NotificationType.ARRIVAL_AUTO_CONFIRMED,
        NotificationType.DEPARTURE_AUTO_CONFIRMED,
        -> NotificationTarget.Progress(tripId)
    }

/** 목록 항목을 행 모델로 옮긴다. */
fun NotificationItemDto.toUi(): NotifItemUi = NotifItemUi(
    id = notificationId,
    type = type,
    title = title,
    body = body,
    createdAt = createdAt,
    unread = !read,
    target = target,
)

/** 최신순 목록을 `오늘`/`어제`/`그 이전` 구간으로 나눈다. 빈 구간은 만들지 않는다. */
fun List<NotificationItemDto>.toGroups(now: Instant): List<NotifGroup> =
    groupBy { dateBucket(it.createdAt, now) }
        .toSortedMap()
        .map { (bucket, items) -> NotifGroup(bucket = bucket, items = items.map { it.toUi() }) }

/** 목록 조회 실패 원인 문구(`error` 상태 본문). */
val NotificationError.messageRes: Int
    @StringRes get() = when (this) {
        NotificationError.Network -> R.string.notification_error_network
        NotificationError.NotFound -> R.string.notification_error_not_found
        NotificationError.Forbidden -> R.string.notification_error_forbidden
        NotificationError.SessionExpired -> R.string.notification_error_session
        NotificationError.Unexpected -> R.string.notification_error_unexpected
    }

/**
 * 감지 카드의 변수 한 줄(Figma `details`·`infoNote`, UI-008).
 *
 * @property value 판정 값 문구. 평가되지 않은 변수는 `null`이고 [note]가 제외 사유다.
 * @property risk 위험으로 판정됐다. 문구 색만 바뀌고 값은 계약 그대로다.
 * @property note 제외 사유. 계약의 `unavailableReason`을 문구로 옮긴 것이며 값을 지어내지 않는다.
 */
data class VariableRow(
    @DrawableRes val icon: Int,
    val label: String,
    val value: String?,
    val risk: Boolean,
    val note: String?,
)

/** DETECT-002 변수별 판정을 카드 줄 세 개(혼잡도·강수 예보·운영 종료)로 옮긴다. */
@Composable
fun VariableVerdictsDto.variableRows(): List<VariableRow> {
    val congestionLabel = stringResource(R.string.monitor_variable_congestion)
    val weatherLabel = stringResource(R.string.monitor_variable_weather)
    val hoursLabel = stringResource(R.string.monitor_variable_hours)
    val unknown = stringResource(R.string.monitor_value_unknown)

    val congestionValue = congestion.level?.let { stringResource(it.labelRes) } ?: unknown
    val weatherValue = listOfNotNull(
        weather.precipitationType?.let { stringResource(it.labelRes) },
        weather.precipitationProbability?.let { stringResource(R.string.monitor_weather_probability, it) },
    ).joinToString(" ").ifEmpty { unknown }
    val hoursValue = operatingHours.closesAt?.let { closesAt ->
        if (operatingHours.closingSoon == true) stringResource(R.string.alternative_closing_soon, clockLabel(closesAt))
        else stringResource(R.string.alternative_closes_at, clockLabel(closesAt))
    } ?: unknown

    return listOf(
        VariableRow(
            icon = R.drawable.ic_lucide_users,
            label = congestionLabel,
            value = congestionValue.takeIf { congestion.available },
            risk = congestion.crowded == true,
            note = excludedNote(congestion.available, congestion.unavailableReason, congestionLabel),
        ),
        VariableRow(
            icon = R.drawable.ic_lucide_cloud_drizzle,
            label = weatherLabel,
            value = weatherValue.takeIf { weather.available },
            risk = weather.atRisk == true,
            note = excludedNote(weather.available, weather.unavailableReason, weatherLabel),
        ),
        VariableRow(
            icon = R.drawable.ic_lucide_clock,
            label = hoursLabel,
            value = hoursValue.takeIf { operatingHours.available },
            risk = operatingHours.closingSoon == true,
            note = excludedNote(operatingHours.available, operatingHours.unavailableReason, hoursLabel),
        ),
    )
}

/** 제외된 변수의 사유 문구. 평가된 변수는 `null`이다. */
@Composable
private fun excludedNote(available: Boolean, reason: UnavailableReason?, label: String): String? {
    if (available) return null
    return when (reason) {
        UnavailableReason.NO_FORECAST -> stringResource(R.string.monitor_excluded_no_forecast)
        UnavailableReason.NOT_IN_SUPPORT_AREA -> stringResource(R.string.monitor_excluded_not_in_area, label)
        UnavailableReason.HOURS_UNKNOWN -> stringResource(R.string.monitor_excluded_hours_unknown)
        UnavailableReason.INDOOR -> stringResource(R.string.monitor_excluded_indoor)
        UnavailableReason.TIMEOUT -> stringResource(R.string.monitor_excluded_timeout, label)
        null -> stringResource(R.string.monitor_excluded_unknown, label)
    }
}

private val CongestionLevel.labelRes: Int
    @StringRes get() = when (this) {
        CongestionLevel.RELAXED -> R.string.monitor_congestion_relaxed
        CongestionLevel.NORMAL -> R.string.monitor_congestion_normal
        CongestionLevel.SLIGHTLY_CROWDED -> R.string.monitor_congestion_slightly_crowded
        CongestionLevel.CROWDED -> R.string.monitor_congestion_crowded
    }

private val PrecipitationType.labelRes: Int
    @StringRes get() = when (this) {
        PrecipitationType.NONE -> R.string.monitor_weather_none
        PrecipitationType.RAIN -> R.string.monitor_weather_rain
        PrecipitationType.RAIN_SNOW -> R.string.monitor_weather_rain_snow
        PrecipitationType.SNOW -> R.string.monitor_weather_snow
        PrecipitationType.SHOWER -> R.string.monitor_weather_shower
    }

/** 정렬 기준 문구(Figma 토글 라벨). */
val DetectionSort.labelRes: Int
    @StringRes get() = when (this) {
        DetectionSort.TIME -> R.string.monitor_sort_time
        DetectionSort.RISK -> R.string.monitor_sort_risk
    }
