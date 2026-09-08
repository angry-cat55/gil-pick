package com.gilpick.progress

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * F007 지오펜스 broadcast 수신 지점(T018).
 *
 * Play Services가 도착(`DWELL`)·출발(`EXIT`)·재진입(`ENTER`) 전이를 여기로 보낸다. OS가 프로세스를
 * 깨워 전달하므로 사용자가 앱을 열어 두지 않아도 이벤트를 받는다. 이것이 자동 감지를 지오펜스로
 * 구현한 이유다(`research.md` 1절).
 *
 * 받은 전이는 그대로 서버에 올리기만 한다. 후보를 만들지 말지, 기준을 충족하는지는 서버가
 * 판정한다. 앱이 같은 규칙을 복제하면 두 곳이 갈라지기 때문이다(`research.md` 4절).
 */
class GeofenceReceiver : BroadcastReceiver() {

    /**
     * 지오펜스 전이 broadcast를 받아 서버에 올린다.
     *
     * `goAsync`로 짧은 시간 동안 프로세스를 살려 network 호출을 마친다. 실패하면 이벤트는
     * 사라지지만, 사용자는 진행 화면에서 수동으로 도착·출발을 처리할 수 있다(constitution I).
     *
     * @param context receiver가 깨어난 context.
     * @param intent Play Services가 담은 전이 정보.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        val eventType = event.geofenceTransition.toProgressEventType() ?: return
        val triggering = event.triggeringGeofences.orEmpty()
        if (triggering.isEmpty()) return

        val location = event.triggeringLocation ?: return
        val session = sessionStore(context).current ?: return
        val repository = repositoryFactory(context)

        val pending = goAsync()
        scope.launch {
            try {
                triggering.forEach { geofence ->
                    val itemId = geofence.requestId.substringBefore(':')
                    repository.registerEvent(
                        tripId = session.tripId,
                        date = java.time.LocalDate.parse(session.date),
                        // 같은 전이가 중복 전달돼도 서버가 한 번만 처리하도록 내용에서 파생한다.
                        eventId = eventId(geofence.requestId, eventType, location.time),
                        eventType = eventType,
                        itemId = itemId,
                        geofenceId = geofence.requestId,
                        occurredAt = Instant.ofEpochMilli(location.time).toIsoString(),
                        location = EventLocationDto(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracyMeters = location.accuracy.toDouble(),
                        ),
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /**
         * broadcast 처리 중에만 사는 scope. receiver 인스턴스는 매번 새로 만들어지므로 여기 둔다.
         */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** 감지 중인 여행·날짜의 출처. test가 바꿀 수 있도록 열어 둔다. */
        internal var sessionStore: (Context) -> DetectionSessionStore = { PrefsDetectionSessionStore(it) }

        /** 이벤트를 올릴 repository. test가 바꿀 수 있도록 열어 둔다. */
        internal var repositoryFactory: (Context) -> DetectionRepository = { DetectionRepository.default(it) }

        /**
         * 같은 전이면 같은 `eventId`를 만든다.
         *
         * 지오펜스 broadcast는 OS가 중복 전달할 수 있고 통신 실패 재시도도 있다. 내용에서
         * 파생하면 호출자가 키를 보관하지 않아도 서버가 한 번만 처리한다(FR-004).
         */
        internal fun eventId(geofenceId: String, type: ProgressEventType, occurredAtMillis: Long): String =
            UUID.nameUUIDFromBytes("$geofenceId|${type.name}|$occurredAtMillis".toByteArray()).toString()
    }
}

/** Play Services 전이 상수를 계약의 이벤트 종류로 옮긴다. 그 밖의 전이는 쓰지 않는다. */
internal fun Int.toProgressEventType(): ProgressEventType? = when (this) {
    Geofence.GEOFENCE_TRANSITION_DWELL -> ProgressEventType.DWELL
    Geofence.GEOFENCE_TRANSITION_EXIT -> ProgressEventType.EXIT
    Geofence.GEOFENCE_TRANSITION_ENTER -> ProgressEventType.REENTER
    else -> null
}

/** 계약이 요구하는 ISO-8601 문자열(UTC)로 옮긴다. */
internal fun Instant.toIsoString(): String =
    DateTimeFormatter.ISO_INSTANT.format(atOffset(ZoneOffset.UTC))
