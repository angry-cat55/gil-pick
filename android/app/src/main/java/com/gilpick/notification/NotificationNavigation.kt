package com.gilpick.notification

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.gilpick.R
import kotlinx.serialization.Serializable

/** 알림 목록 화면 route(T019). 여행 목록·진행 화면 헤더의 알림 벨과 대상 없는 푸시 탭이 연다. */
@Serializable
data object NotificationListRoute

/**
 * 감지 목록 화면 route(US6, T039).
 *
 * @property tripId 감지를 조회할 여행. F009 `AlternativeRepository.listDetections`를 재사용한다.
 */
@Serializable
data class VariableMonitorRoute(val tripId: String)

/**
 * 알림 destination을 app navigation graph에 등록한다(T015 골격).
 *
 * 화면 본문은 T019(알림 목록)·T039(감지 목록)가 채운다. 지금은 route와 콜백 계약만 고정한다.
 *
 * @param navController 뒤로 가기에 쓴다.
 * @param onSessionExpired 자격이 무효로 확정됐다. F001 재인증 흐름으로 넘긴다.
 * @param onOpenDetection 장소 변경 제안 알림 탭·감지 목록의 `대체 장소 보기`. `AlternativePlacesRoute`로 간다.
 * @param onOpenProgress 도착·출발·자동 처리 알림 탭. `ActiveTravelRoute`로 간다.
 */
@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.notificationGraph(
    navController: NavController,
    onSessionExpired: () -> Unit,
    onOpenDetection: (detectionId: String, tripId: String) -> Unit,
    onOpenProgress: (tripId: String, tripName: String) -> Unit,
) {
    composable<NotificationListRoute> {
        Placeholder(R.string.notification_list_title)
    }
    composable<VariableMonitorRoute> {
        Placeholder(R.string.notification_monitor_title)
    }
}

/** 화면이 붙기 전 자리 표시. 제목만 가운데에 둔다. */
@Composable
private fun Placeholder(titleRes: Int) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = stringResource(titleRes))
    }
}

/**
 * 시스템 알림 탭으로 전달된 대상(data-model.md 4.3).
 *
 * `MainActivity`가 intent extras에서 만들고, `NavHost` 구성 뒤 [route]가 고른 화면으로 이동한다.
 * 유형을 모르거나 없으면 알림 목록으로 간다.
 *
 * @property type 계약 밖 값이면 `null`.
 */
data class PendingNotificationTarget(
    val type: NotificationType?,
    val tripId: String?,
    val tripDayId: String? = null,
    val itemId: String? = null,
    val detectionId: String? = null,
    val transitionId: String? = null,
) {
    /** 이동할 화면. 대상을 특정할 수 없으면 `null`이라 호출자가 알림 목록을 연다. */
    val route: NotificationTarget?
        get() = when (type) {
            NotificationType.PLACE_CHANGE_SUGGESTION ->
                if (detectionId != null && tripId != null) NotificationTarget.Alternative(detectionId, tripId) else null

            NotificationType.ARRIVAL_CHECK,
            NotificationType.DEPARTURE_CHECK,
            NotificationType.ARRIVAL_AUTO_CONFIRMED,
            NotificationType.DEPARTURE_AUTO_CONFIRMED,
            -> tripId?.let { NotificationTarget.Progress(it) }

            null -> null
        }

    companion object {
        const val EXTRA_TYPE = "notif_type"
        const val EXTRA_TRIP_ID = "trip_id"
        const val EXTRA_TRIP_DAY_ID = "trip_day_id"
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_DETECTION_ID = "detection_id"
        const val EXTRA_TRANSITION_ID = "transition_id"

        /** extras에 [EXTRA_TYPE]이 없으면 알림 탭이 아니므로 `null`이다. */
        fun fromIntent(intent: Intent?): PendingNotificationTarget? =
            intent?.let { fromExtras { key -> it.getStringExtra(key) } }

        /** [fromIntent]의 순수 부분. 단위 test가 Android `Intent` 없이 검증한다. */
        fun fromExtras(get: (String) -> String?): PendingNotificationTarget? {
            val rawType = get(EXTRA_TYPE) ?: return null
            return PendingNotificationTarget(
                type = NotificationType.entries.firstOrNull { it.name == rawType },
                tripId = get(EXTRA_TRIP_ID),
                tripDayId = get(EXTRA_TRIP_DAY_ID),
                itemId = get(EXTRA_ITEM_ID),
                detectionId = get(EXTRA_DETECTION_ID),
                transitionId = get(EXTRA_TRANSITION_ID),
            )
        }
    }
}
