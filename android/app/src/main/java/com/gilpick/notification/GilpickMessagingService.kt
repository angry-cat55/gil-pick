package com.gilpick.notification

import com.google.firebase.messaging.FirebaseMessagingService

/**
 * FCM 수신 지점(T004 골격).
 *
 * 새 토큰 동기화(DEV-001, T017)와 data 메시지의 시스템 알림 표시(T024)는 뒤 task가 채운다.
 * manifest에 `com.google.firebase.MESSAGING_EVENT`로 등록돼 있어 앱이 실행 중이 아니어도 깨어난다.
 */
class GilpickMessagingService : FirebaseMessagingService() {
    companion object {
        /** 시스템 알림 채널. `MainActivity`가 첫 진입에 만들고 이 서비스가 여기에 알림을 띄운다. */
        const val CHANNEL_ID = "gilpick_notifications"
    }
}
