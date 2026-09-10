package com.gilpick.notification

import com.google.firebase.messaging.FirebaseMessagingService

/**
 * FCM 수신 지점.
 *
 * data 메시지의 시스템 알림 표시(T024)는 뒤 task가 채운다.
 * manifest에 `com.google.firebase.MESSAGING_EVENT`로 등록돼 있어 앱이 실행 중이 아니어도 깨어난다.
 */
class GilpickMessagingService : FirebaseMessagingService() {

    /** FCM이 토큰을 새로 발급하면 DEV-001로 갱신한다. 로그인 전이면 worker가 자격 없음으로 끝난다. */
    override fun onNewToken(token: String) {
        FcmTokenSyncWorker.enqueue(applicationContext)
    }
    companion object {
        /** 시스템 알림 채널. `MainActivity`가 첫 진입에 만들고 이 서비스가 여기에 알림을 띄운다. */
        const val CHANNEL_ID = "gilpick_notifications"
    }
}
