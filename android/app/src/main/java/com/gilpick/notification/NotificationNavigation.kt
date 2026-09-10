package com.gilpick.notification

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.gilpick.R
import com.gilpick.auth.AuthResult
import com.gilpick.trip.TripRepository
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
 * 알림 destination을 app navigation graph에 등록한다(T015 골격, T032 알림 목록).
 *
 * 감지 목록 화면 본문은 T039가 채운다.
 *
 * @param navController 뒤로 가기에 쓴다.
 * @param onSessionExpired 자격이 무효로 확정됐다. F001 재인증 흐름으로 넘긴다.
 * @param onOpenDetection 장소 변경 제안 알림 탭·감지 목록의 `대체 장소 보기`. `AlternativePlacesRoute`로 간다.
 * @param onOpenProgress 도착·출발·자동 처리 알림 탭. `ActiveTravelRoute`로 간다.
 * @param repository 알림 데이터 접근. UI test가 MockWebServer를 향한 repository로 바꿔 끼운다.
 * @param tripName 진행 알림 목적지 헤더의 여행명 조회. 기본은 F002 `TripRepository`다.
 */
fun NavGraphBuilder.notificationGraph(
    navController: NavController,
    onSessionExpired: () -> Unit,
    onOpenDetection: (detectionId: String, tripId: String) -> Unit,
    onOpenProgress: (tripId: String, tripName: String) -> Unit,
    repository: (Context) -> NotificationRepository = NotificationRepository::default,
    tripName: suspend (Context, tripId: String) -> String = ::tripNameOf,
) {
    composable<NotificationListRoute> { entry ->
        val context = LocalContext.current
        val factory = remember(entry) {
            NotificationListViewModel.factory(repository = repository(context), tripName = { tripName(context, it) })
        }
        val viewModel: NotificationListViewModel = viewModel(factory = factory)
        val state by viewModel.state.collectAsStateWithLifecycle()
        val open by viewModel.open.collectAsStateWithLifecycle()

        // 대상 화면에서 돌아올 때마다 다시 조회한다. 그 사이 서버에서 읽음·새 알림이 바뀌었을 수 있다.
        LifecycleResumeEffect(Unit) {
            viewModel.load()
            onPauseOrDispose {}
        }

        LaunchedEffect(open) {
            when (val target = open ?: return@LaunchedEffect) {
                is NotificationTarget.Alternative -> onOpenDetection(target.detectionId, target.tripId)
                is NotificationTarget.Progress -> onOpenProgress(target.tripId, target.tripName)
            }
            viewModel.consumeOpen()
        }

        NotificationListScreen(
            state = state,
            onBack = { navController.popBackStack() },
            onRetry = viewModel::load,
            onOpen = viewModel::open,
            onMarkAllRead = viewModel::markAllRead,
            onReauthenticate = onSessionExpired,
        )
    }
    composable<VariableMonitorRoute> {
        Placeholder(R.string.notification_monitor_title)
    }
}

/** 여행명을 F002 여행 조회로 받는다. 실패하면 빈 문자열이다. 푸시 딥링크(`MainActivity`)와 알림 목록 탭이 함께 쓴다. */
suspend fun tripNameOf(context: Context, tripId: String): String =
    (TripRepository.default(context).getTrip(tripId) as? AuthResult.Success)?.value?.name ?: ""

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

/**
 * FCM data-only payload(research R11). 서버 `dispatch.py`가 `type`·`notificationId`·`tripId`·선택 식별자·
 * `title`·`body`만 담는다(FR-006). [PendingNotificationTarget]의 거울로, 시스템 알림과 탭 extras를 만든다.
 *
 * @property id 시스템 알림·PendingIntent 식별자. 알림마다 달라야 서로 덮어쓰지 않는다.
 * @property type 계약 밖 값이면 `null`. 알림은 띄우되 탭하면 알림 목록으로 간다.
 * @property extras 탭 intent extras. [PendingNotificationTarget.fromExtras]가 그대로 읽는다.
 */
data class PushNotification(
    val id: Int,
    val type: NotificationType?,
    val title: String,
    val body: String,
    val extras: Map<String, String>,
) {
    companion object {
        private val EXTRA_KEYS = mapOf(
            "type" to PendingNotificationTarget.EXTRA_TYPE,
            "tripId" to PendingNotificationTarget.EXTRA_TRIP_ID,
            "tripDayId" to PendingNotificationTarget.EXTRA_TRIP_DAY_ID,
            "itemId" to PendingNotificationTarget.EXTRA_ITEM_ID,
            "detectionId" to PendingNotificationTarget.EXTRA_DETECTION_ID,
            "transitionId" to PendingNotificationTarget.EXTRA_TRANSITION_ID,
        )

        /** `type`·`title`·`body` 중 하나라도 없으면 우리 계약이 아니므로 `null`이다. */
        fun fromData(data: Map<String, String>): PushNotification? {
            val rawType = data["type"] ?: return null
            val title = data["title"] ?: return null
            val body = data["body"] ?: return null
            return PushNotification(
                id = (data["notificationId"] ?: rawType).hashCode(),
                type = NotificationType.entries.firstOrNull { it.name == rawType },
                title = title,
                body = body,
                extras = EXTRA_KEYS.mapNotNull { (from, to) -> data[from]?.let { to to it } }.toMap(),
            )
        }
    }
}
