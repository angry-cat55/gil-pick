package com.gilpick.progress

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * F007 지오펜스 broadcast 수신 지점.
 *
 * Play Services가 도착(`DWELL`)·출발(`EXIT`)·재진입(`ENTER`) 전이를 이 receiver로 보낸다.
 * OS가 프로세스를 깨워 전달하므로 사용자가 앱을 열어 두지 않아도 이벤트를 받는다. 이것이
 * 자동 감지를 지오펜스로 구현한 이유다(`research.md` 1절).
 *
 * 지금은 manifest 등록과 build를 성립시키는 뼈대만 있다. 이벤트를 서버로 올리는 동작은
 * [GeofenceManager]와 함께 들어온다.
 */
class GeofenceReceiver : BroadcastReceiver() {

    /**
     * 지오펜스 전이 broadcast를 받는다.
     *
     * @param context receiver가 깨어난 context.
     * @param intent Play Services가 담은 전이 정보.
     */
    override fun onReceive(context: Context, intent: Intent) {
        // TODO(#262): 전이를 DWELL·EXIT·REENTER로 옮기고 client_event_id와 함께 PROG-003으로 전송한다.
    }
}
