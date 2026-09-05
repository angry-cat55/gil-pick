package com.gilpick.trip

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #151: 목록을 상태별 세 그룹으로 나누는 규칙 검증.
 *
 * 화면이 아니라 [groupTrips]만 본다. 그룹 나누기는 상태 계층이 소유하고 화면은 나뉜
 * 결과를 그리기만 하므로, 규칙이 맞는지는 여기서 확인하는 편이 빠르고 정확하다.
 *
 * 개수는 [TripGroupSection.count]에서만 나온다. 나중에 서버가 그룹별 개수를 내려주면
 * 그 프로퍼티만 바뀌고 이 test의 기대값은 그대로 쓸 수 있다.
 */
class TripGroupTest {

    // --- 1. 세 그룹으로 나눈다 ---

    @Test
    fun `상태가 섞인 목록을 세 그룹으로 나눈다`() {
        val sections = groupTrips(
            listOf(
                trip("t1", TripStatus.IN_PROGRESS),
                trip("t2", TripStatus.UPCOMING),
                trip("t3", TripStatus.COMPLETED),
            ),
        )

        assertEquals(
            listOf(TripGroup.IN_PROGRESS, TripGroup.UPCOMING, TripGroup.COMPLETED),
            sections.map { it.group },
        )
    }

    @Test
    fun `서버가 섞어 보내도 여행 중 예정 완료 순으로 세운다`() {
        // FR-005는 서버가 정렬해 준다고 정하지만 그 순서에 기대지 않는다. 정렬이 바뀌어도
        // 화면 구성은 유지되어야 한다.
        val sections = groupTrips(
            listOf(
                trip("t1", TripStatus.COMPLETED),
                trip("t2", TripStatus.UPCOMING),
                trip("t3", TripStatus.IN_PROGRESS),
            ),
        )

        assertEquals(
            listOf(TripGroup.IN_PROGRESS, TripGroup.UPCOMING, TripGroup.COMPLETED),
            sections.map { it.group },
        )
    }

    @Test
    fun `그룹 안에서는 받은 순서를 그대로 지킨다`() {
        val sections = groupTrips(
            listOf(
                trip("t1", TripStatus.UPCOMING),
                trip("t2", TripStatus.UPCOMING),
                trip("t3", TripStatus.UPCOMING),
            ),
        )

        assertEquals(listOf("t1", "t2", "t3"), sections.single().trips.map { it.tripId })
    }

    // --- 2. 빈 그룹은 뺀다 ---

    @Test
    fun `비어 있는 그룹은 구획을 만들지 않는다`() {
        val sections = groupTrips(
            listOf(
                trip("t1", TripStatus.UPCOMING),
                trip("t2", TripStatus.COMPLETED),
            ),
        )

        // 진행 중 여행이 없으므로 그 구획은 아예 없다. 화면에 헤더만 남지 않게 하는 근거다.
        assertEquals(listOf(TripGroup.UPCOMING, TripGroup.COMPLETED), sections.map { it.group })
    }

    @Test
    fun `여행이 하나도 없으면 구획도 없다`() {
        assertEquals(emptyList<TripGroupSection>(), groupTrips(emptyList()))
    }

    @Test
    fun `한 상태만 있으면 구획도 하나다`() {
        val sections = groupTrips(listOf(trip("t1", TripStatus.IN_PROGRESS)))

        assertEquals(1, sections.size)
        assertEquals(TripGroup.IN_PROGRESS, sections.single().group)
    }

    // --- 3. 개수 ---

    @Test
    fun `구획은 자기 그룹의 여행 수를 센다`() {
        val sections = groupTrips(
            listOf(
                trip("t1", TripStatus.IN_PROGRESS),
                trip("t2", TripStatus.UPCOMING),
                trip("t3", TripStatus.UPCOMING),
                trip("t4", TripStatus.COMPLETED),
                trip("t5", TripStatus.COMPLETED),
                trip("t6", TripStatus.COMPLETED),
            ),
        )

        assertEquals(mapOf(
            TripGroup.IN_PROGRESS to 1,
            TripGroup.UPCOMING to 2,
            TripGroup.COMPLETED to 3,
        ), sections.associate { it.group to it.count })
    }

    @Test
    fun `상태 필터가 걸리면 그 상태의 구획만 남고 개수도 그만큼이다`() {
        // 상태 필터는 서버가 적용한다. 앱은 걸러진 목록을 받으므로 구획도 하나만 나온다.
        val filtered = listOf(
            trip("t4", TripStatus.COMPLETED),
            trip("t5", TripStatus.COMPLETED),
        )

        val sections = groupTrips(filtered)

        assertEquals(1, sections.size)
        assertEquals(TripGroup.COMPLETED, sections.single().group)
        assertEquals(2, sections.single().count)
    }

    @Test
    fun `추가 페이지를 이어 받으면 같은 구획의 개수가 늘어난다`() {
        // loadMore는 받은 페이지를 기존 목록 뒤에 붙인다. 구획은 다시 만들어지고 개수만
        // 늘어난다. 같은 그룹의 구획이 둘로 갈라지지 않아야 한다.
        val firstPage = listOf(trip("t1", TripStatus.UPCOMING))
        val secondPage = listOf(trip("t2", TripStatus.UPCOMING), trip("t3", TripStatus.UPCOMING))

        val sections = groupTrips(firstPage + secondPage)

        assertEquals(1, sections.size)
        assertEquals(3, sections.single().count)
    }

    private fun trip(id: String, status: TripStatus) = TripDto(
        tripId = id,
        name = "여행 $id",
        startDate = "2026-09-01",
        endDate = "2026-09-03",
        status = status,
        dayCount = 3,
        version = 1,
    )
}
