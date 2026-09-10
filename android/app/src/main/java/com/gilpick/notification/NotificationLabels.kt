package com.gilpick.notification

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gilpick.R
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
