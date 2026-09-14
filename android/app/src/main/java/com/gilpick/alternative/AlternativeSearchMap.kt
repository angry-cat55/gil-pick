package com.gilpick.alternative

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.gilpick.R
import com.gilpick.route.BOUNDS_PADDING
import com.gilpick.route.NaverMapHost
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.NaverMap
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.OverlayImage

/**
 * 직접 검색 결과를 Naver 지도에 번호 마커로 그린다(Figma `MapSearchScreen`, spec UI-010, #450).
 *
 * 마커는 흰 원 + `primary` 2dp 테두리 + `primary` 번호이고, 선택된 결과는 `primary` 채움 + 흰 번호 +
 * 20% halo다(가이드라인 3절 "지도 위 색 사용"). 마커를 누르면 [onMarkerClick]으로 같은 선택 상태를 바꾼다.
 * 지도가 보여주는 정보(번호·이름)는 결과 시트가 같은 순서로 제공하므로 지도 없이도 화면은 성립한다.
 *
 * 기준 좌표 표식은 두지 않는다. ALT-002 응답에 기존 장소 좌표가 없기 때문이다(DETECT-002·ALT-001도 같다).
 *
 * @param results 결과. 좌표가 없는 결과는 건너뛴다(번호는 목록 순서 그대로).
 * @param selectedPlaceId 선택된 결과의 `placeId`. 결과 시트 행과 같은 값이다.
 */
@Composable
fun AlternativeSearchMap(
    results: List<AlternativeSearchItemDto>,
    selectedPlaceId: String?,
    onMarkerClick: (placeId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    val description = stringResource(R.string.alternative_search_map_description, results.size)
    val markers = remember { mutableListOf<Marker>() }
    val boundsPaddingPx = with(density) { BOUNDS_PADDING.roundToPx() }

    NaverMapHost(
        modifier = modifier,
        description = description,
        drawKey = results to selectedPlaceId,
        onDispose = { markers.detach() },
    ) { map, _ ->
        markers.detach()
        val bounds = LatLngBounds.Builder()
        results.forEachIndexed { index, item ->
            val lat = item.place.latitude ?: return@forEachIndexed
            val lng = item.place.longitude ?: return@forEachIndexed
            val latLng = LatLng(lat, lng)
            bounds.include(latLng)
            val selected = item.place.placeId == selectedPlaceId
            val sizePx = ((if (selected) SELECTED_MARKER_DP else MARKER_DP) * density.density).toInt()
            markers += Marker().apply {
                position = latLng
                icon = searchMarker(context, (index + 1).toString(), primary, selected, density.density)
                width = sizePx
                height = sizePx
                anchor = PointF(0.5f, 0.5f)
                captionText = item.place.name
                zIndex = if (selected) 1 else 0
                setOnClickListener {
                    onMarkerClick(item.place.placeId)
                    true
                }
                this.map = map
            }
        }
        when (markers.size) {
            0 -> Unit
            1 -> map.moveCamera(CameraUpdate.scrollAndZoomTo(markers[0].position, SINGLE_ZOOM))
            else -> map.moveCamera(CameraUpdate.fitBounds(bounds.build(), boundsPaddingPx))
        }
    }
}

private fun MutableList<Marker>.detach() {
    forEach { it.map = null }
    clear()
}

/**
 * 번호 마커 비트맵. 흰 원 + `primary` 2dp 테두리·번호, 선택 시 `primary` 채움·흰 번호에 20% halo를 두른다.
 * F005 `circleMarker`는 흰 테두리·색 채움 한 가지라 여기서 따로 만든다.
 */
private fun searchMarker(context: Context, label: String, primary: Int, selected: Boolean, density: Float): OverlayImage {
    val size = ((if (selected) SELECTED_MARKER_DP else MARKER_DP) * density).toInt()
    val circleSize = ((if (selected) SELECTED_CIRCLE_DP else MARKER_DP) * density).toInt()
    val inset = (size - circleSize) / 2
    val circle = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (selected) primary else AndroidColor.WHITE)
        setStroke((2 * density).toInt(), primary)
    }
    val background = if (selected) {
        val halo = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor((primary and 0x00FFFFFF) or (HALO_ALPHA shl 24))
        }
        LayerDrawable(arrayOf(halo, circle)).apply { setLayerInset(1, inset, inset, inset, inset) }
    } else {
        circle
    }
    val view = TextView(context).apply {
        text = label
        setTextColor(if (selected) AndroidColor.WHITE else primary)
        setTypeface(typeface, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, MARKER_TEXT_SP)
        gravity = Gravity.CENTER
        this.background = background
        layoutParams = android.view.ViewGroup.LayoutParams(size, size)
        measure(
            android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, size, size)
    }
    return OverlayImage.fromView(view)
}

/** Figma 실측: 마커 r=14(28dp), 선택 r=18(36dp), halo r=28(56dp), 번호 11sp. */
private const val MARKER_DP = 28
private const val SELECTED_CIRCLE_DP = 36
private const val SELECTED_MARKER_DP = 56
private const val MARKER_TEXT_SP = 11f
private const val HALO_ALPHA = 0x33
private const val SINGLE_ZOOM = 15.0
