package com.gilpick.notification

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gilpick.MainActivity
import com.gilpick.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM 수신 지점.
 *
 * manifest에 `com.google.firebase.MESSAGING_EVENT`로 등록돼 있어 앱이 실행 중이 아니어도 깨어난다.
 */
class GilpickMessagingService : FirebaseMessagingService() {

    /** FCM이 토큰을 새로 발급하면 DEV-001로 갱신한다. 로그인 전이면 worker가 자격 없음으로 끝난다. */
    override fun onNewToken(token: String) {
        FcmTokenSyncWorker.enqueue(applicationContext)
    }

    /**
     * data-only 메시지(T025·T030).
     *
     * 앱 화면이 보이는 중이면 시스템 알림도 인앱 표시도 하지 않는다(FR-029). 백그라운드면 채널 알림을
     * 띄우고, 탭 extras는 [PendingNotificationTarget]이 읽는 키로 옮겨 `MainActivity`가 유형별 화면으로
     * 가게 한다. 서버 계약 밖 payload(`title`·`body`·`type` 누락)는 무시한다.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        if (isAppInForeground()) return
        val push = PushNotification.fromData(message.data) ?: return
        show(applicationContext, push)
    }

    companion object {
        /** 시스템 알림 채널. `MainActivity`가 첫 진입에 만들고 이 서비스가 여기에 알림을 띄운다. */
        const val CHANNEL_ID = "gilpick_notifications"

        /**
         * 사용자가 앱 화면을 보고 있는지.
         *
         * 프로세스 importance가 `FOREGROUND`(활성 화면)면 포그라운드다. FCM이 프로세스를 깨워 배달하는
         * 경우는 `SERVICE` 이상이라 백그라운드로 본다. lifecycle-process 의존성 없이 판정한다.
         */
        fun isAppInForeground(): Boolean {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }

        /**
         * 채널 알림을 띄운다. 탭하면 `singleTask` [MainActivity]의 `onNewIntent`(실행 중) 또는
         * `onCreate`(종료 상태)에 extras가 도착한다. 알림 권한이 없으면 조용히 건너뛴다(FR-020).
         */
        fun show(context: Context, push: PushNotification) {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            val tap = Intent(context, MainActivity::class.java).apply {
                push.extras.forEach { (key, value) -> putExtra(key, value) }
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                push.id,
                tap,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(push.type?.iconRes ?: R.drawable.ic_lucide_map_pin)
                .setContentTitle(push.title)
                .setContentText(push.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(push.body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            try {
                manager.notify(push.id, notification)
            } catch (_: SecurityException) {
                // Android 13+에서 권한이 회수된 직후. 알림 목록 화면에는 그대로 남는다.
            }
        }
    }
}
