package com.gilpick.progress

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 지오펜스 등록·해제 수단. 실제 구현은 Play Services이고 test는 기록만 하는 대역을 쓴다.
 *
 * [GeofenceManager]가 계산한 차이만 이 인터페이스로 나간다. 무엇을 감지할지는 서버가 정하고
 * 앱은 등록만 한다(`research.md` 4절).
 */
interface GeofenceClient {

    /** 대상들을 등록한다. 이미 같은 `geofenceId`가 있으면 덮어쓴다. */
    suspend fun add(targets: List<DetectionTargetDto>)

    /** `geofenceId`로 해제한다. */
    suspend fun remove(geofenceIds: List<String>)
}

/**
 * 서버가 준 감지 대상과 실제 등록 상태를 맞춘다(T018).
 *
 * 진행 조회(PROG-001) 응답이 올 때마다 [sync]를 부르면 **차이만** 등록·해제한다. 같은 목록이
 * 다시 와도 재등록하지 않는다. 지오펜스를 매번 지웠다 다시 걸면 OS가 진입 판정을 처음부터
 * 다시 시작해, 이미 반경 안에 머무는 중이던 사용자의 체류 시간이 초기화되기 때문이다.
 *
 * 등록에 실패해도 예외를 밖으로 던지지 않는다. 자동 감지가 꺼질 뿐 F006 수동 진행은 그대로
 * 동작해야 한다(FR-024, constitution I).
 *
 * @property client 실제 등록 수단.
 * @property session 감지 중인 여행·날짜를 기억한다. broadcast가 왔을 때 어느 날짜의 이벤트인지
 *   알아야 서버로 올릴 수 있는데, receiver는 화면·ViewModel 없이 깨어나기 때문이다.
 */
class GeofenceManager(
    private val client: GeofenceClient,
    private val session: DetectionSessionStore,
) {

    /** 지금 등록돼 있다고 보는 대상. `geofenceId`로 식별한다. */
    private var registered: Map<String, DetectionTargetDto> = emptyMap()

    /**
     * 서버가 준 목록과 현재 등록 상태를 맞춘다.
     *
     * @param tripId 감지 중인 여행.
     * @param date 감지 중인 날짜(`yyyy-MM-dd`).
     * @param targets 지금 감지해야 할 대상 전부. 빈 목록이면 등록된 것을 모두 해제한다(FR-022).
     * @return 등록·해제가 실제로 일어났으면 `true`. 차이가 없어 아무 것도 하지 않았으면 `false`.
     */
    suspend fun sync(tripId: String, date: String, targets: List<DetectionTargetDto>): Boolean {
        val next = targets.associateBy { it.geofenceId }
        val toRemove = registered.keys - next.keys
        // 값이 바뀐 대상도 다시 등록한다. 반경이나 좌표가 달라졌을 수 있다.
        val toAdd = next.values.filter { registered[it.geofenceId] != it }

        if (toRemove.isEmpty() && toAdd.isEmpty()) return false

        return try {
            if (toRemove.isNotEmpty()) client.remove(toRemove.toList())
            if (toAdd.isNotEmpty()) client.add(toAdd)
            registered = next
            if (next.isEmpty()) session.clear() else session.save(tripId, date)
            true
        } catch (e: Exception) {
            // 등록 실패는 자동 감지만 끄고 진행을 막지 않는다. 다음 조회에서 다시 시도한다.
            registered = emptyMap()
            false
        }
    }

    /** 등록된 대상을 모두 해제한다. 진행 화면을 벗어나거나 권한을 잃었을 때 부른다. */
    suspend fun clear() {
        val ids = registered.keys.toList()
        registered = emptyMap()
        session.clear()
        if (ids.isNotEmpty()) runCatching { client.remove(ids) }
    }
}

/**
 * 감지 중인 여행·날짜를 담는 저장소.
 *
 * 지오펜스 broadcast는 앱이 살아 있지 않아도 도착하므로, receiver가 메모리 상태에 기댈 수 없다.
 * 등록할 때 기억해 두고 broadcast에서 읽는다. 대역을 끼울 수 있도록 interface로 둔다.
 */
interface DetectionSessionStore {

    /** 감지 중인 여행·날짜. 감지하고 있지 않으면 `null`이다. */
    val current: DetectionSession?

    /** 감지를 시작한 여행·날짜를 기억한다. */
    fun save(tripId: String, date: String)

    /** 기억한 여행·날짜를 지운다. 감지를 모두 해제했을 때 부른다. */
    fun clear()
}

/** `SharedPreferences`에 기억하는 기본 구현. 프로세스가 죽어도 남아야 하기 때문이다. */
class PrefsDetectionSessionStore(context: Context) : DetectionSessionStore {

    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override val current: DetectionSession?
        get() {
            val tripId = prefs.getString(KEY_TRIP_ID, null) ?: return null
            val date = prefs.getString(KEY_DATE, null) ?: return null
            return DetectionSession(tripId = tripId, date = date)
        }

    override fun save(tripId: String, date: String) {
        prefs.edit().putString(KEY_TRIP_ID, tripId).putString(KEY_DATE, date).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_TRIP_ID).remove(KEY_DATE).apply()
    }

    private companion object {
        const val FILE_NAME = "gilpick_detection_session"
        const val KEY_TRIP_ID = "tripId"
        const val KEY_DATE = "date"
    }
}

/** 감지 중인 여행과 날짜. */
data class DetectionSession(val tripId: String, val date: String)

/**
 * Play Services Geofencing API를 쓰는 [GeofenceClient].
 *
 * 도착 대상은 `DWELL`(체류)로, 출발 대상은 `EXIT`와 `ENTER`(재진입 취소)로 등록한다.
 * `DWELL`의 `loiteringDelay`가 "반경 안에서 N분 머무름"을 그대로 표현하므로 앱이 진입 시각을
 * 직접 세지 않는다(`research.md` 1절).
 */
class PlayServicesGeofenceClient(context: Context) : GeofenceClient {

    private val appContext = context.applicationContext
    private val client: GeofencingClient = LocationServices.getGeofencingClient(appContext)

    private val pendingIntent: PendingIntent
        get() = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            Intent(appContext, GeofenceReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    // 호출 전에 백그라운드 위치 권한을 확인한다. 없으면 GeofenceManager가 등록을 시도하지 않는다.
    @SuppressLint("MissingPermission")
    override suspend fun add(targets: List<DetectionTargetDto>) {
        val request = GeofencingRequest.Builder()
            // 등록 시점에 이미 반경 안이면 즉시 알리지 않는다. 등록 자체가 도착을 뜻하지 않기 때문이다.
            .setInitialTrigger(0)
            .addGeofences(targets.map { it.toGeofence() })
            .build()
        client.addGeofences(request, pendingIntent).await()
    }

    override suspend fun remove(geofenceIds: List<String>) {
        client.removeGeofences(geofenceIds).await()
    }

    private fun DetectionTargetDto.toGeofence(): Geofence = Geofence.Builder()
        .setRequestId(geofenceId)
        .setCircularRegion(latitude, longitude, radiusMeters.toFloat())
        .setExpirationDuration(Geofence.NEVER_EXPIRE)
        .apply {
            if (kind == DetectionKind.ARRIVAL) {
                setTransitionTypes(Geofence.GEOFENCE_TRANSITION_DWELL)
                setLoiteringDelay(TimeUnit.MINUTES.toMillis((dwellMinutes ?: 0).toLong()).toInt())
            } else {
                setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT or Geofence.GEOFENCE_TRANSITION_ENTER)
            }
        }
        .build()

    private companion object {
        const val REQUEST_CODE = 7001
    }
}

/** Play Services `Task`를 coroutine으로 기다린다. 실패는 예외로 던져 호출자가 다루게 한다. */
internal suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
    }
