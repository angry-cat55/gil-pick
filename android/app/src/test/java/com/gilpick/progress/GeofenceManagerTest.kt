package com.gilpick.progress

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T013: [GeofenceManager]가 서버가 준 감지 대상과 등록 상태의 **차이만** 반영하는지 검증한다.
 *
 * 앱이 무엇을 감지할지 직접 계산하지 않는다는 것이 핵심이다(`research.md` 4절). 이 test는
 * 목록을 그대로 받아 등록·해제로 옮기는 부분만 본다.
 */
class GeofenceManagerTest {

    private val client = RecordingGeofenceClient()
    private val session = FakeDetectionSessionStore()
    private val manager = GeofenceManager(client, session)

    @Test
    fun `처음 받은 대상은 모두 등록한다`() = runTest {
        val changed = manager.sync(TRIP_ID, DATE, listOf(arrival(), departure()))

        assertTrue(changed)
        assertEquals(listOf(listOf(arrival().geofenceId, departure().geofenceId)), client.added)
        assertEquals(emptyList<List<String>>(), client.removed)
    }

    @Test
    fun `같은 목록을 다시 받으면 재등록하지 않는다`() = runTest {
        manager.sync(TRIP_ID, DATE, listOf(arrival()))
        client.clearLog()

        val changed = manager.sync(TRIP_ID, DATE, listOf(arrival()))

        // 다시 걸면 OS가 체류 판정을 처음부터 세어 이미 머무는 중인 사용자의 시간이 초기화된다.
        assertFalse(changed)
        assertEquals(emptyList<List<String>>(), client.added)
        assertEquals(emptyList<List<String>>(), client.removed)
    }

    @Test
    fun `대상이 바뀌면 사라진 것만 해제하고 새 것만 등록한다`() = runTest {
        manager.sync(TRIP_ID, DATE, listOf(arrival(), departure()))
        client.clearLog()

        manager.sync(TRIP_ID, DATE, listOf(departure(), arrival(itemId = ITEM_B)))

        assertEquals(listOf(listOf(arrival().geofenceId)), client.removed)
        assertEquals(listOf(listOf(arrival(itemId = ITEM_B).geofenceId)), client.added)
    }

    @Test
    fun `같은 id라도 반경이나 좌표가 바뀌면 다시 등록한다`() = runTest {
        manager.sync(TRIP_ID, DATE, listOf(arrival()))
        client.clearLog()

        manager.sync(TRIP_ID, DATE, listOf(arrival().copy(radiusMeters = 500)))

        assertEquals(listOf(listOf(arrival().geofenceId)), client.added)
    }

    @Test
    fun `목록이 비면 등록된 것을 모두 해제한다`() = runTest {
        // 당일 완료 시 서버가 빈 목록을 준다(FR-022).
        manager.sync(TRIP_ID, DATE, listOf(arrival(), departure()))
        client.clearLog()

        val changed = manager.sync(TRIP_ID, DATE, emptyList())

        assertTrue(changed)
        assertEquals(listOf(listOf(arrival().geofenceId, departure().geofenceId)), client.removed)
        assertEquals(emptyList<List<String>>(), client.added)
    }

    @Test
    fun `등록하면 감지 중인 여행과 날짜를 기억한다`() = runTest {
        manager.sync(TRIP_ID, DATE, listOf(arrival()))

        // broadcast는 앱이 살아 있지 않아도 오므로 receiver가 읽을 곳이 필요하다.
        assertEquals(DetectionSession(TRIP_ID, DATE), session.current)
    }

    @Test
    fun `목록이 비면 기억한 여행과 날짜도 지운다`() = runTest {
        manager.sync(TRIP_ID, DATE, listOf(arrival()))

        manager.sync(TRIP_ID, DATE, emptyList())

        assertNull(session.current)
    }

    @Test
    fun `clear는 등록된 것을 모두 해제하고 기억도 지운다`() = runTest {
        manager.sync(TRIP_ID, DATE, listOf(arrival(), departure()))
        client.clearLog()

        manager.clear()

        assertEquals(listOf(listOf(arrival().geofenceId, departure().geofenceId)), client.removed)
        assertNull(session.current)
    }

    @Test
    fun `등록에 실패해도 예외를 밖으로 던지지 않는다`() = runTest {
        // 자동 감지만 꺼지고 F006 수동 진행은 그대로 동작해야 한다(FR-024, constitution I).
        client.failOnAdd = true

        val changed = manager.sync(TRIP_ID, DATE, listOf(arrival()))

        assertFalse(changed)
    }

    @Test
    fun `등록에 실패한 뒤 다음 조회에서 다시 시도한다`() = runTest {
        client.failOnAdd = true
        manager.sync(TRIP_ID, DATE, listOf(arrival()))
        client.failOnAdd = false
        client.clearLog()

        val changed = manager.sync(TRIP_ID, DATE, listOf(arrival()))

        assertTrue(changed)
        assertEquals(listOf(listOf(arrival().geofenceId)), client.added)
    }

    private fun arrival(itemId: String = ITEM_A) = DetectionTargetDto(
        itemId = itemId,
        kind = DetectionKind.ARRIVAL,
        geofenceId = "$itemId:ARRIVAL",
        latitude = 37.5825,
        longitude = 126.9830,
        radiusMeters = 300,
        dwellMinutes = 5,
    )

    private fun departure(itemId: String = ITEM_A) = DetectionTargetDto(
        itemId = itemId,
        kind = DetectionKind.DEPARTURE,
        geofenceId = "$itemId:DEPARTURE",
        latitude = 37.5825,
        longitude = 126.9830,
        radiusMeters = 400,
        dwellMinutes = null,
    )

    private companion object {
        const val ITEM_A = "9c8b7a6f-5e4d-4c3b-8a29-18f7e6d5c4b3"
        const val ITEM_B = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
    }
}

/** 등록·해제 호출만 기록하는 [GeofenceClient]. Play Services 없이 차이 계산을 검증한다. */
private class RecordingGeofenceClient : GeofenceClient {

    val added = mutableListOf<List<String>>()
    val removed = mutableListOf<List<String>>()
    var failOnAdd = false

    override suspend fun add(targets: List<DetectionTargetDto>) {
        if (failOnAdd) throw IllegalStateException("등록 실패")
        added += targets.map { it.geofenceId }
    }

    override suspend fun remove(geofenceIds: List<String>) {
        removed += geofenceIds
    }

    fun clearLog() {
        added.clear()
        removed.clear()
    }
}

/** `SharedPreferences` 없이 같은 계약을 흉내 내는 저장소. */
private class FakeDetectionSessionStore : DetectionSessionStore {
    private var value: DetectionSession? = null
    override val current: DetectionSession? get() = value
    override fun save(tripId: String, date: String) {
        value = DetectionSession(tripId, date)
    }

    override fun clear() {
        value = null
    }
}
