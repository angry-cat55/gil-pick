package com.gilpick.progress

import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T012·T017: 1회 위치 취득의 권한·timeout·유효성 규칙 검증(research.md 결정 7, FR-004).
 *
 * Play Services 호출 자체는 [DeviceLocationProvider.create]가 조립하고 여기서는 다루지 않는다.
 */
class CurrentLocationProviderTest {

    private val now = Instant.parse("2026-09-08T01:00:00Z")
    private val fresh = RawLocation(latitude = 37.57, longitude = 126.97, accuracyMeters = 12f, occurredAt = now.minusSeconds(30).toEpochMilli())

    @Test
    fun `유효한 위치는 DTO로 바꿔 돌려준다`() = runTest {
        val provider = DeviceLocationProvider(hasPermission = { true }, fetch = { fresh }, now = { now })

        assertEquals(
            CurrentLocationDto(latitude = 37.57, longitude = 126.97, accuracyMeters = 12.0, occurredAt = "2026-09-08T00:59:30Z"),
            provider.current(),
        )
    }

    @Test
    fun `권한이 없으면 기기에 묻지 않고 null이다`() = runTest {
        var fetched = false
        val provider = DeviceLocationProvider(hasPermission = { false }, fetch = { fetched = true; fresh }, now = { now })

        assertNull(provider.current())
        assertEquals(false, fetched)
    }

    @Test
    fun `10초 안에 위치를 못 얻으면 null이다`() = runTest {
        val provider = DeviceLocationProvider(
            hasPermission = { true },
            fetch = { delay(DeviceLocationProvider.TIMEOUT.toMillis() + 1); fresh },
            now = { now },
        )

        assertNull(provider.current())
    }

    @Test
    fun `정확도 150m 위치는 보내지 않는다`() = runTest {
        val provider = DeviceLocationProvider(hasPermission = { true }, fetch = { fresh.copy(accuracyMeters = 150f) }, now = { now })

        assertNull(provider.current())
    }

    @Test
    fun `5분 지난 위치는 보내지 않는다`() = runTest {
        val stale = fresh.copy(occurredAt = now.minusSeconds(5 * 60).toEpochMilli())
        val provider = DeviceLocationProvider(hasPermission = { true }, fetch = { stale }, now = { now })

        assertNull(provider.current())
    }

    @Test
    fun `기기 호출이 실패하거나 위치가 없으면 null이다`() = runTest {
        assertNull(DeviceLocationProvider({ true }, { null }, { now }).current())
        assertNull(DeviceLocationProvider({ true }, { error("gps off") }, { now }).current())
    }
}
