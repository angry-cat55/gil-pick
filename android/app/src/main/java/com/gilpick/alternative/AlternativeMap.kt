package com.gilpick.alternative

import android.graphics.PointF
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.gilpick.R
import com.gilpick.route.BOUNDS_PADDING
import com.gilpick.route.MARKER_SIZE_DP
import com.gilpick.route.NaverMapHost
import com.gilpick.route.Position
import com.gilpick.route.circleMarker
import com.gilpick.route.latitude
import com.gilpick.route.longitude
import com.gilpick.route.pillMarker
import com.gilpick.ui.theme.LocalGilpickColors
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.NaverMap
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.OverlayImage

/**
 * 기존 장소와 후보 위치를 Naver 지도에 그린다(T023, Figma `AlternativePlacesScreen` 지도, UI-004).
 *
 * F005 [NaverMapHost]·[circleMarker]·[pillMarker]를 그대로 쓴다. 기존 장소는 `warning` 색 알약형 `!`,
 * 후보는 순위 번호 원형 마커이며 카메라는 전부가 보이도록 맞춘다. 지도가 보여주는 정보는 아래
 * 목록이 같은 순위로 제공하므로 지도 없이도 화면은 성립한다.
 *
 * @param origin 기존 장소 좌표(`[경도, 위도]`). ALT-001·DETECT-002에 없어 화면이 `originPlaceId`로 따로
 *   조회하며(#660), 못 얻으면 `null`이고 후보만 그린다.
 * @param candidates 후보. 좌표가 없는 후보는 건너뛴다.
 * @param sheetFraction 후보 sheet가 덮는 화면 높이 비율. 그만큼 content padding을 둬 카메라 범위와 Naver
 *   로고가 sheet 아래에 숨지 않게 한다(F005 RouteMap과 같은 방식).
 */
@Composable
fun AlternativeMap(
    origin: Position?,
    candidates: List<AlternativeCandidateDto>,
    modifier: Modifier = Modifier,
    sheetFraction: Float = 0f,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val candidateColor = MaterialTheme.colorScheme.primary.toArgb()
    val warningColor = LocalGilpickColors.current.warning.toArgb()
    val description = stringResource(R.string.alternative_map_description, candidates.size)
    val markers = remember { mutableListOf<Marker>() }
    val markerSizePx = (MARKER_SIZE_DP * density.density).toInt()
    val boundsPaddingPx = with(density) { BOUNDS_PADDING.roundToPx() }
    // 카메라를 전체에 맞추는 일은 그릴 내용이 바뀌었을 때만 한다. sheet 높이가 바뀌어 다시 그릴 때도
    // 맞추면 사용자가 옮겨 둔 카메라가 되돌아간다(#651과 같은 이유).
    val fitted = remember { mutableStateOf<Any?>(null) }

    NaverMapHost(
        modifier = modifier,
        description = description,
        drawKey = Triple(origin, candidates, sheetFraction),
        onDispose = { markers.detach() },
    ) { map, size ->
        map.setContentPadding(0, 0, 0, (size.height * sheetFraction).toInt())
        val content = origin to candidates
        val fit = fitted.value != content
        fitted.value = content
        markers.detach()
        val bounds = LatLngBounds.Builder()
        origin?.let { position ->
            val latLng = LatLng(position.latitude, position.longitude)
            bounds.include(latLng)
            markers += map.marker(latLng, pillMarker(context, ORIGIN_LABEL, warningColor, density.density), sizePx = null)
        }
        candidates.forEach { candidate ->
            val lat = candidate.place.latitude ?: return@forEach
            val lng = candidate.place.longitude ?: return@forEach
            val latLng = LatLng(lat, lng)
            bounds.include(latLng)
            markers += map.marker(
                latLng,
                circleMarker(context, candidate.rank.toString(), candidateColor, density.density),
                sizePx = markerSizePx,
            ).apply { captionText = candidate.place.name }
        }
        if (fit) {
            when (markers.size) {
                0 -> Unit
                1 -> map.moveCamera(CameraUpdate.scrollAndZoomTo(markers[0].position, SINGLE_ZOOM))
                else -> map.moveCamera(CameraUpdate.fitBounds(bounds.build(), boundsPaddingPx))
            }
        }
    }
}

private fun MutableList<Marker>.detach() {
    forEach { it.map = null }
    clear()
}

/** 마커 하나를 지도에 올린다. `fromView`는 뷰를 다시 재므로 원형은 [sizePx]로 고정해야 찌그러지지 않는다. */
private fun NaverMap.marker(position: LatLng, icon: OverlayImage, sizePx: Int?) = Marker().apply {
    this.position = position
    this.icon = icon
    if (sizePx != null) {
        width = sizePx
        height = sizePx
    }
    anchor = PointF(0.5f, 0.5f)
    map = this@marker
}

private const val ORIGIN_LABEL = "!"
private const val SINGLE_ZOOM = 15.0
