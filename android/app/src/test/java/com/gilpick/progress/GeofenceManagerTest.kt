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

    /** #684: 다른 여행·날짜의 감지가 걸려 있으면 그 id를 몰라도 모두 풀고 이 여행으로 새로 건다. */
    @Test
    fun `다른 여행의 감지 문맥이 남아 있으면 모두 풀고 새로 등록한다`() = runTest {
        session.save("9a8b7c6d-0000-4000-8000-000000000684", "2026-09-07")

        manager.sync(TRIP_ID, DATE, listOf(arrival()))

        assertEquals(1, client.removedAll)
        assertEquals(listOf(listOf(arrival().geofenceId)), client.added)
        assertEquals(DetectionSession(TRIP_ID, DATE), session.current)
    }

    /** #684: 같은 여행·날짜의 재조회는 전부 풀지 않는다. 풀면 체류 판정이 처음부터 다시 시작된다. */
    @Test
    fun `같은 여행의 감지 문맥이면 전부 풀지 않는다`() = runTest {
        manager.sync(TRIP_ID, DATE, listOf(arrival()))

        GeofenceManager(client, session).sync(TRIP_ID, DATE, listOf(arrival()))

        assertEquals(0, client.removedAll)
    }

    /** #684: 아무것도 걸지 않은 인스턴스의 clear나 빈 목록은 다른 여행이 기억한 감지 문맥을 지우지 않는다. */
    @Test
    fun `등록한 적 없는 인스턴스는 다른 여행의 감지 문맥을 지우지 않는다`() = runTest {
        session.save("9a8b7c6d-0000-4000-8000-000000000684", "2026-09-07")

        manager.clear()
        manager.sync(TRIP_ID, DATE, emptyList())

        assertEquals(DetectionSession("9a8b7c6d-0000-4000-8000-000000000684", "2026-09-07"), session.current)
        assertEquals(0, client.removedAll)
    }

    @Test
    fun `등록 결과와 실패 원인을 로그로 남기고 좌표는 남기지 않는다`() = runTest {
        val lines = mutableListOf<String>()
        val logged = GeofenceManager(client, session, log = lines::add)

        logged.sync(TRIP_ID, DATE, listOf(arrival()))
        logged.sync(TRIP_ID, DATE, listOf(arrival()))
        client.failOnAdd = true
        logged.sync(TRIP_ID, DATE, listOf(arrival(), departure()))

        assertTrue(lines[0], lines[0].startsWith("sync date=$DATE: registered 1 (added=1 removed=0)"))
        assertTrue(lines[1], lines[1].contains("no change"))
        assertTrue(lines[2], lines[2].contains("register FAILED") && lines[2].contains("IllegalStateException: 등록 실패"))
        assertTrue(lines.none { it.contains("37.") || it.contains("126.") })
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
