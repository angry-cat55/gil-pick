package com.gilpick.progress

import android.Manifest
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #422: Google Play 에뮬레이터에서 실제 Play Services에 지오펜스를 등록·해제해 보고 단계 로그(`GilpickGeofence`)를 남긴다.
 *
 * 위치 권한(앱 사용 중 + 항상 허용)을 계측 권한으로 부여한 뒤 대상 1개를 등록한다. 등록되면 감지 session이
 * 저장되고 `clear`로 지워진다. 실패하면 로그에 원인 코드(`GEOFENCE_NOT_AVAILABLE` 등)가 남는다.
 * 확인: `adb logcat -d -s GilpickGeofence`.
 */
@RunWith(AndroidJUnit4::class)
class GeofenceRegistrationLogTest {

    @Test
    fun 권한이_있으면_Play_Services에_등록되고_session이_저장되며_clear로_지워진다() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        val lines = mutableListOf<String>()
        val session = PrefsDetectionSessionStore(context)
        val manager = GeofenceManager(PlayServicesGeofenceClient(context), session) { line ->
            lines += line
            logDetection(line)
        }
        val itemId = "9c8b7a6f-5e4d-4c3b-8a29-18f7e6d5c4b3"
        val tripId = "3f1d2c4b-5a6e-4f70-8a91-b2c3d4e5f607"
        val date = "2026-09-14"
        val target = DetectionTargetDto(
            itemId = itemId,
            geofenceId = "$itemId:ARRIVAL",
            kind = DetectionKind.ARRIVAL,
            latitude = 37.5796,
            longitude = 126.977,
            radiusMeters = 100,
            dwellMinutes = 5,
        )

        val registered = runBlocking { manager.sync(tripId, date, listOf(target)) }

        assertTrue(lines.joinToString("\n"), registered)
        assertEquals(DetectionSession(tripId, date), session.current)
        assertNotNull(lines.singleOrNull { it.contains("registered 1") })

        runBlocking { manager.clear() }

        assertNull(session.current)
    }
}
