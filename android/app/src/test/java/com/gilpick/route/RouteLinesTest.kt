package com.gilpick.route

import com.gilpick.itinerary.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** #687: 구간을 이동수단별 지도 선(색 종류·점선 여부)으로 나누는 규칙을 검증한다. */
class RouteLinesTest {

    private val segment = readyRoute().segments[0]

    @Test
    fun `도보 구간은 실선 도보 한 줄이다`() {
        val lines = routeLines(segment.copy(transportMode = TransportMode.WALK))

        assertEquals(listOf(RouteLineKind.WALK), lines.map { it.kind })
        assertEquals(false, lines.single().kind.dashed)
        assertEquals(segment.geometry.coordinates, lines.single().coordinates)
    }

    @Test
    fun `자동차 구간은 실선 자동차 한 줄이다`() {
        val lines = routeLines(segment.copy(transportMode = TransportMode.CAR))

        assertEquals(listOf(RouteLineKind.CAR), lines.map { it.kind })
    }

    @Test
    fun `대중교통 구간은 단계 순서대로 나누고 처음·환승·마지막 도보는 점선이다`() {
        val transit = segment.copy(
            transportMode = TransportMode.TRANSIT,
            steps = listOf(
                step(RouteStepType.WALK, 126.970),
                step(RouteStepType.BUS, 126.971),
                step(RouteStepType.WALK, 126.972),
                step(RouteStepType.SUBWAY, 126.973),
                step(RouteStepType.WALK, 126.974),
            ),
        )

        val lines = routeLines(transit)

        assertEquals(
            listOf(
                RouteLineKind.WALK_IN_TRANSIT,
                RouteLineKind.BUS,
                RouteLineKind.WALK_IN_TRANSIT,
                RouteLineKind.SUBWAY,
                RouteLineKind.WALK_IN_TRANSIT,
            ),
            lines.map { it.kind },
        )
        assertEquals(listOf(true, false, true, false, true), lines.map { it.kind.dashed })
        // 각 선은 자기 단계 형상을 그대로 쓴다.
        assertEquals(transit.steps.map { it.geometry!!.coordinates }, lines.map { it.coordinates })
    }

    @Test
    fun `단계 형상이 없는 예전 대중교통 구간은 한 줄로 그린다`() {
        val legacy = segment.copy(
            transportMode = TransportMode.TRANSIT,
            steps = listOf(step(RouteStepType.WALK, 126.970), step(RouteStepType.BUS, 126.971).copy(geometry = null)),
        )

        assertEquals(listOf(RouteLineKind.TRANSIT_UNSPLIT), routeLines(legacy).map { it.kind })
        assertEquals(listOf(RouteLineKind.TRANSIT_UNSPLIT), routeLines(legacy.copy(steps = emptyList())).map { it.kind })
    }

    @Test
    fun `도보는 실선과 점선이 같은 색이고 예전 대중교통은 자동차 색이다`() {
        val colors = RouteLineColors(walk = 1, bus = 2, subway = 3, car = 4)

        assertEquals(1, colors.of(RouteLineKind.WALK))
        assertEquals(1, colors.of(RouteLineKind.WALK_IN_TRANSIT))
        assertEquals(2, colors.of(RouteLineKind.BUS))
        assertEquals(3, colors.of(RouteLineKind.SUBWAY))
        assertEquals(4, colors.of(RouteLineKind.CAR))
        assertEquals(4, colors.of(RouteLineKind.TRANSIT_UNSPLIT))
    }

    private fun step(type: RouteStepType, longitude: Double) = RouteStepDto(
        type = type,
        durationSeconds = 60,
        distanceMeters = 80,
        geometry = RouteGeometryDto("LineString", listOf(listOf(longitude, 37.57), listOf(longitude + 0.001, 37.571))),
    )
}
