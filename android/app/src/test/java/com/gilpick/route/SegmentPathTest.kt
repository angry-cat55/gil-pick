package com.gilpick.route

import org.junit.Assert.assertEquals
import org.junit.Test

/** #551: 구간 선이 출발·도착 장소 마커까지 이어지는지 검증. */
class SegmentPathTest {

    private val route = readyRoute()

    @Test
    fun `제공자 경로 양 끝이 장소와 다르면 장소 좌표를 앞뒤에 잇는다`() {
        val segment = route.segments[0].copy(
            geometry = RouteGeometryDto("LineString", listOf(listOf(126.978, 37.5790), listOf(126.982, 37.5820))),
        )

        val path = segmentPath(segment, route.markers)

        assertEquals(
            listOf(listOf(126.977, 37.5796), listOf(126.978, 37.5790), listOf(126.982, 37.5820), listOf(126.9831, 37.5826)),
            path,
        )
    }

    @Test
    fun `양 끝이 이미 장소 좌표면 그대로 둔다`() {
        val segment = route.segments[0]

        assertEquals(segment.geometry.coordinates, segmentPath(segment, route.markers))
    }
}
