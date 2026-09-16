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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gilpick.R
import androidx.activity.compose.LocalActivity
import com.gilpick.route.BOUNDS_PADDING
import com.gilpick.route.LOCATION_PERMISSION_REQUEST
import com.gilpick.route.NaverMapHost
import com.gilpick.route.RouteFocus
import com.gilpick.route.latitude
import com.gilpick.route.longitude
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.CameraAnimation
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.LocationTrackingMode
import com.naver.maps.map.util.FusedLocationSource
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
 * @param focus `내 위치로 이동`이 확인한 현재 위치(#660). 값이 바뀌면 표식은 그대로 두고 카메라만 옮긴다.
 *   같은 자리를 다시 눌러도 옮기도록 [RouteFocus.tick]이 연속 누름을 구분한다.
 */
@Composable
fun AlternativeSearchMap(
    results: List<AlternativeSearchItemDto>,
    selectedPlaceId: String?,
    onMarkerClick: (placeId: String) -> Unit,
    modifier: Modifier = Modifier,
    focus: RouteFocus.MyLocation? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    val description = stringResource(R.string.alternative_search_map_description, results.size)
    val markers = remember { mutableListOf<Marker>() }
    val boundsPaddingPx = with(density) { BOUNDS_PADDING.roundToPx() }
    val topInsetPx = with(density) { MAP_TOP_INSET.roundToPx() }
    // 카메라만 옮기는 이동은 overlay를 다시 그리지 않아야 해서(다시 그리면 fitBounds로 되돌아간다) 지도를 붙잡아 둔다.
    var map by remember { mutableStateOf<NaverMap?>(null) }
    val fitted = remember { mutableStateOf<Any?>(null) }
    val activity = LocalActivity.current
    val locationSource = remember(activity) { activity?.let { FusedLocationSource(it, LOCATION_PERMISSION_REQUEST) } }

    // 화면이 이미 좌표를 확인해 넘겼으므로 SDK의 첫 위치를 기다리지 않고 바로 옮긴다(F005 RouteMap과 같다).
    LaunchedEffect(map, focus) {
        val target = map ?: return@LaunchedEffect
        val position = focus?.position ?: return@LaunchedEffect
        if (locationSource != null) {
            target.locationSource = locationSource
            target.locationTrackingMode = LocationTrackingMode.NoFollow
        }
        target.moveCamera(
            CameraUpdate.scrollAndZoomTo(LatLng(position.latitude, position.longitude), MY_LOCATION_ZOOM)
                .animate(CameraAnimation.Easing),
        )
    }

    NaverMapHost(
        modifier = modifier,
        description = description,
        drawKey = results to selectedPlaceId,
        onDispose = { markers.detach() },
    ) { naverMap, size ->
        map = naverMap
        val fit = fitted.value != results
        fitted.value = results
        // 하단 결과 시트(최대 55%)와 위 검색창만큼 content padding을 둬 카메라 범위와 Naver 로고가 시트·검색창 아래에
        // 숨지 않게 한다(UI-010, F005 RouteMap과 같은 방식). 시트가 그보다 낮으면 로고가 시트 위에 조금 떠 있다.
        naverMap.setContentPadding(0, topInsetPx, 0, (size.height * SHEET_MAX_FRACTION).toInt())
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
                this.map = naverMap
            }
        }
        // 결과가 바뀌었을 때만 카메라를 맞춘다. 선택이 바뀌어 다시 그릴 때도 맞추면 `내 위치로 이동`으로
        // 옮겨 둔 카메라가 결과 전체로 되돌아간다(#660).
        if (fit) {
            when (markers.size) {
                0 -> Unit
                1 -> naverMap.moveCamera(CameraUpdate.scrollAndZoomTo(markers[0].position, SINGLE_ZOOM))
                else -> naverMap.moveCamera(CameraUpdate.fitBounds(bounds.build(), boundsPaddingPx))
            }
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
        // OverlayImage.fromView는 view를 다시 wrap_content로 재는다. 최소 크기가 없으면 글자 크기의 비트맵이
        // 만들어져 마커 크기로 늘어나며 원이 사라진다(T047 실기기 확인). F005 markerView와 같은 처리다.
        minimumWidth = size
        minimumHeight = size
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

/** `내 위치로 이동`이 맞추는 배율. F005 지도의 단일 지점 배율과 같다. */
private const val MY_LOCATION_ZOOM = 15.0

/** 떠 있는 검색창이 덮는 위쪽 높이(상태 표시줄 + 16 + 52). */
private val MAP_TOP_INSET = 96.dp
