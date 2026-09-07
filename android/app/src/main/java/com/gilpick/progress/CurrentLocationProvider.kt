package com.gilpick.progress

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 진행 시작 시 시작 요청에 실을 현재 위치를 한 번 얻는다(FR-004b).
 *
 * 위치는 시작을 막는 조건이 아니다. 권한이 없거나, 얻지 못했거나, 유효하지 않으면 `null`을
 * 돌려주고 시작은 위치 없이 진행한다(FR-020). ViewModel test는 이 interface를 fake로 바꾼다.
 */
fun interface CurrentLocationProvider {
    /** 유효한 현재 위치. 없으면 `null`. 10초 안에 끝난다. */
    suspend fun current(): CurrentLocationDto?
}

/**
 * 기기가 준 위치 한 점. [DeviceLocationProvider]가 Play Services `Location`에서 옮겨 담는다.
 *
 * @property accuracyMeters 68% 신뢰 반경(m).
 * @property occurredAt 위치를 얻은 시각(epoch millis).
 */
data class RawLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val occurredAt: Long,
)

/**
 * Play Services 위치 API로 현재 위치를 1회 얻는 [CurrentLocationProvider](research.md 결정 7).
 *
 * 세 판단(권한, 기기 호출, 유효성)을 생성자로 분리해 JVM test가 Android 없이 timeout·정확도·
 * 경과 시간 규칙을 검증한다(T012). 실제 조립은 [create]가 한다.
 *
 * @property hasPermission 앱 사용 중 위치 권한(FINE 또는 COARSE)이 있는지.
 * @property fetch 기기에 현재 위치를 한 번 요청한다. 실패는 `null` 또는 예외.
 * @property now 유효성 판단 기준 시각.
 */
class DeviceLocationProvider(
    private val hasPermission: () -> Boolean,
    private val fetch: suspend () -> RawLocation?,
    private val now: () -> Instant = Instant::now,
) : CurrentLocationProvider {

    override suspend fun current(): CurrentLocationDto? {
        if (!hasPermission()) return null
        val raw = withTimeoutOrNull(TIMEOUT.toMillis()) { runCatching { fetch() }.getOrNull() } ?: return null
        val occurredAt = Instant.ofEpochMilli(raw.occurredAt)
        // 정확도 100m 초과·2분 경과 위치는 보내지 않는다. 서버도 같은 기준으로 다시 검증한다(LOC-01).
        if (raw.accuracyMeters > MAX_ACCURACY_METERS) return null
        if (Duration.between(occurredAt, now()) > MAX_AGE) return null
        return CurrentLocationDto(
            latitude = raw.latitude,
            longitude = raw.longitude,
            accuracyMeters = raw.accuracyMeters.toDouble(),
            occurredAt = occurredAt.toString(),
        )
    }

    companion object {
        /** 이 시간 안에 위치를 못 얻으면 위치 없이 시작한다. */
        val TIMEOUT: Duration = Duration.ofSeconds(10)
        const val MAX_ACCURACY_METERS = 100f
        val MAX_AGE: Duration = Duration.ofMinutes(2)

        /** 앱 사용 중 위치 권한이 하나라도 있는지. 화면의 권한 요청 여부 판단에도 쓴다. */
        fun hasLocationPermission(context: Context): Boolean =
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }

        /** `FusedLocationProviderClient.getCurrentLocation(PRIORITY_HIGH_ACCURACY)`로 조립한다. */
        fun create(context: Context): DeviceLocationProvider {
            val appContext = context.applicationContext
            val client = LocationServices.getFusedLocationProviderClient(appContext)
            return DeviceLocationProvider(
                hasPermission = { hasLocationPermission(appContext) },
                fetch = {
                    // 권한은 hasPermission이 먼저 확인한다. 취소(timeout)되면 기기 요청도 함께 취소한다.
                    @SuppressLint("MissingPermission")
                    suspend fun request(): RawLocation? = suspendCancellableCoroutine { cont ->
                        val cancel = CancellationTokenSource()
                        cont.invokeOnCancellation { cancel.cancel() }
                        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancel.token)
                            .addOnSuccessListener { location ->
                                cont.resume(location?.let { RawLocation(it.latitude, it.longitude, it.accuracy, it.time) })
                            }
                            .addOnFailureListener { cont.resume(null) }
                            .addOnCanceledListener { if (cont.isActive) cont.resume(null) }
                    }
                    request()
                },
            )
        }
    }
}
