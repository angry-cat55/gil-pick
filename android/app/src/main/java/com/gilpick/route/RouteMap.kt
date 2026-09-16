package com.gilpick.route

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gilpick.R
import androidx.activity.compose.LocalActivity
import com.gilpick.itinerary.ItemStatus
import com.gilpick.progress.DeviceLocationProvider
import com.gilpick.ui.theme.LocalGilpickColors
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.CameraAnimation
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.MapView
import com.naver.maps.map.NaverMap
import com.naver.maps.map.NaverMapSdk
import com.naver.maps.map.LocationTrackingMode
import com.naver.maps.map.util.FusedLocationSource
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.OverlayImage
import com.naver.maps.map.overlay.PathOverlay

/**
 * Naver 지도에 계획 경로를 그리는 얇은 adapter(`research.md` 결정 6, T026).
 *
 * `MapView`는 Activity lifecycle을 그대로 받아야 하므로 [LocalLifecycleOwner]의 이벤트를 전달한다.
 * overlay는 SDK 객체를 ViewModel에 두지 않고 여기서 [RouteDto]로부터 만들며, 경로가 바뀌거나 화면에
 * 다시 들어올 때 이전 overlay를 지도에서 떼어 중복이 남지 않게 한다.
 *
 * 지도 인증 key(`NCP_KEY_ID`)가 없거나 인증에 실패하면 지도 대신 안내 문구를 보인다. 경로 정보는
 * 구간 목록이 같은 순서로 제공하므로 지도 없이도 화면은 성립한다(UI-005).
 *
 * F006 진행 표시([marks])가 있으면 marker를 상태별로 바꾼다: 완료·도착은 초록 체크, 건너뜀은 회색 X, 이동 중은
 * 파란 번호, 남은 예정은 회색 번호. 시작 위치가 있으면 `시작 위치` marker를 더한다(F006 UI-011, T031). 색만으로
 * 구분하지 않도록 같은 정보를 구간 목록이 문구로 제공한다.
 *
 * @param route 그릴 경로. 마커는 [RouteDto.markers] 순서 번호로, 구간은 [RouteDto.segments]의 geometry로 그린다.
 * @param baseRoute 비교 대상으로 함께 그릴 기존 경로. 기본값 `null`이면 [route] 하나만 그려 이 인자가
 *   없던 때와 같다. 값이 있으면 [route] **아래에** 옅은 색 선으로 깔고 카메라 범위에 포함한다. F010
 *   변경 경로 미리보기가 기존 경로와 바뀔 경로를 함께 보이는 데 쓴다(F010 UI-001). 색만으로 구분되지
 *   않도록 어느 쪽이 기존인지는 화면이 문구 범례로 함께 알린다.
 * @param marks 진행 표시. 기본값 [RouteMarks.NONE]은 계획만 그린다.
 * @param sheetFraction 하단 sheet가 덮는 화면 높이 비율. 그만큼 content padding을 둬 카메라·로고가 sheet 아래에 숨지 않게 한다(UI-009).
 * @param focus 카메라를 옮길 대상(#618, #614). 기본값 `null`이면 이 인자가 없던 때와 같다. 값이 바뀌면 overlay는
 *   그대로 두고 카메라만 옮긴다. 같은 대상을 다시 골라도 옮기도록 [RouteFocus.tick]이 값을 구분한다.
 * @param myLocation `true`면 실시간 현재 위치 overlay를 켠다(#614). 위치 권한이 있을 때만 켜지고, 없으면 지도는
 *   그대로다. 시작 위치 marker(`시작 위치` 알약)와 달리 이 표시는 SDK 기본 현재 위치 점이다.
 */
@Composable
fun RouteMap(
    route: RouteDto,
    modifier: Modifier = Modifier,
    baseRoute: RouteDto? = null,
    marks: RouteMarks = RouteMarks.NONE,
    sheetFraction: Float = 0.45f,
    focus: RouteFocus? = null,
    myLocation: Boolean = false,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val gilpickColors = LocalGilpickColors.current
    val markerColor = MaterialTheme.colorScheme.primary.toArgb()
    val doneColor = gilpickColors.success.toArgb()
    val faintColor = gilpickColors.faint.toArgb()
    // 밝은 기본 지도에서 옅은 파랑은 도로와 구분이 안 돼 marker와 같은 primary로 그린다(#549).
    val pathColor = markerColor
    val description = stringResource(R.string.route_map_description, route.markers.size)
    val startLabel = stringResource(R.string.route_marker_start)
    val overlays = remember { RouteOverlays() }
    // 카메라를 경로 전체에 맞추는 일은 그릴 내용이 바뀌었을 때만 한다. sheet 높이가 바뀌어 다시 그릴 때도
    // 맞추면 사용자가 카드·내 위치로 옮겨 둔 카메라가 경로로 되돌아간다(#651).
    val fitted = remember { mutableStateOf<Any?>(null) }
    // 카드 선택 이동은 overlay를 다시 그리지 않아야 해서(다시 그리면 fitBounds로 되돌아간다) draw에서 지도를 붙잡아 둔다.
    var map by remember { mutableStateOf<NaverMap?>(null) }
    // 현재 위치 공급자. Play Services 위치를 SDK가 쓰는 형태로 감싼다. Activity가 없으면(테스트 등) 켜지 않는다.
    val activity = LocalActivity.current
    val locationSource = remember(activity) { activity?.let { FusedLocationSource(it, LOCATION_PERMISSION_REQUEST) } }

    // 권한이 이미 있으면 화면에 들어올 때부터 현재 위치 점을 보인다. NoFollow라 카메라는 건드리지 않는다(#614).
    LaunchedEffect(map, myLocation, locationSource) {
        val target = map ?: return@LaunchedEffect
        if (!myLocation || locationSource == null) return@LaunchedEffect
        if (!DeviceLocationProvider.hasLocationPermission(context)) return@LaunchedEffect
        target.locationSource = locationSource
        target.locationTrackingMode = LocationTrackingMode.NoFollow
    }

    // content padding이 sheet 높이만큼 잡혀 있어 scrollTo는 sheet 위 보이는 영역의 중앙으로 옮긴다.
    // 연속 선택은 새 이동이 앞선 animation을 대신해 마지막 선택 위치에서 멈춘다.
    LaunchedEffect(map, focus) {
        val target = map ?: return@LaunchedEffect
        when (focus) {
            null -> Unit
            is RouteFocus.Place -> route.markers.firstOrNull { it.itemId == focus.itemId }?.let { marker ->
                target.moveCamera(
                    CameraUpdate.scrollTo(LatLng(marker.latitude, marker.longitude))
                        .animate(CameraAnimation.Easing),
                )
            }
            // 화면이 이미 좌표를 확인해 넘겼으므로 SDK의 첫 위치를 기다리지 않고 바로 옮긴다(#651).
            // 추적도 함께 켜 현재 위치 점이 따라오게 하고, 화면에 들어올 때 위치를 못 켰던 경우에도
            // 이 누름에서 공급자가 다시 붙는다.
            is RouteFocus.MyLocation -> {
                if (locationSource != null) {
                    target.locationSource = locationSource
                    target.locationTrackingMode = LocationTrackingMode.Follow
                }
                target.moveCamera(
                    CameraUpdate.scrollTo(LatLng(focus.position.latitude, focus.position.longitude))
                        .animate(CameraAnimation.Easing),
                )
            }
        }
    }

    NaverMapHost(
        modifier = modifier,
        description = description,
        // sheet 높이가 바뀌면 content padding과 카메라 범위를 다시 맞춘다(#550).
        drawKey = listOf(route, baseRoute, marks, sheetFraction),
        onDispose = { overlays.clear() },
    ) { naverMap, size ->
        map = naverMap
        val bottomPadding = (size.height * sheetFraction).toInt()
        naverMap.setContentPadding(0, 0, 0, bottomPadding)
        val content = listOf(route, baseRoute, marks)
        overlays.show(
            fitCamera = fitted.value != content,
            map = naverMap,
            route = route,
            baseRoute = baseRoute,
            basePathColor = faintColor,
            marks = marks,
            markerIcon = { marker ->
                when (marks.statuses[marker.itemId]) {
                    ItemStatus.COMPLETED, ItemStatus.ARRIVED -> circleMarker(context, MARKER_CHECK, doneColor, density.density)
                    ItemStatus.SKIPPED -> circleMarker(context, MARKER_CROSS, faintColor, density.density)
                    ItemStatus.PLANNED -> circleMarker(context, marker.sequence.toString(), faintColor, density.density)
                    ItemStatus.EN_ROUTE, null -> circleMarker(context, marker.sequence.toString(), markerColor, density.density)
                }
            },
            startIcon = { pillMarker(context, startLabel, markerColor, density.density) },
            markerSizePx = (MARKER_SIZE_DP * density.density).toInt(),
            pathColor = pathColor,
            pathWidthPx = with(density) { PATH_WIDTH.roundToPx() },
            boundsPaddingPx = with(density) { BOUNDS_PADDING.roundToPx() },
        )
        fitted.value = content
    }
}

/**
 * Naver `MapView`의 lifecycle·인증 실패·크기 측정을 맡는 공용 바탕(F009 T023에서 [RouteMap]에서 분리).
 *
 * `MapView`는 Activity lifecycle을 그대로 받아야 하므로 [LocalLifecycleOwner]의 이벤트를 전달한다.
 * 지도 인증 key(`NCP_KEY_ID`)가 없거나 인증에 실패하면 지도 대신 안내 문구를 보인다. 지도 정보는
 * 목록이 같은 순서로 제공하므로 지도 없이도 화면은 성립한다(F005 UI-005, F009 UI-004).
 *
 * @param drawKey 이 값이 바뀌면 [draw]를 다시 부른다. 지도·크기가 바뀔 때도 다시 부른다.
 * @param onDispose 화면을 떠날 때 올린 overlay를 떼는 자리.
 * @param draw 지도와 측정된 크기로 overlay를 그리고 카메라를 맞춘다. 크기가 0이면 부르지 않는다.
 */
@Composable
internal fun NaverMapHost(
    modifier: Modifier,
    description: String,
    drawKey: Any?,
    onDispose: () -> Unit,
    draw: (NaverMap, IntSize) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // key가 없으면 SDK가 ClientUnspecifiedException을 던진다. 지도만 포기하고 나머지는 그대로 둔다.
    val mapView = remember { runCatching { MapView(context) }.getOrNull() }
    var naverMap by remember { mutableStateOf<NaverMap?>(null) }
    var authFailed by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    if (mapView == null) {
        MapUnavailable(modifier)
        return
    }

    DisposableEffect(lifecycleOwner, mapView) {
        var destroyed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    destroyed = true
                    mapView.onDestroy()
                }

                else -> Unit
            }
        }
        val sdk = NaverMapSdk.getInstance(context)
        val previousListener = sdk.onAuthFailedListener
        sdk.onAuthFailedListener = NaverMapSdk.OnAuthFailedListener { authFailed = true }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sdk.onAuthFailedListener = previousListener
            onDispose()
            // 화면을 떠나면 lifecycle은 살아 있어도 지도는 버린다. 이미 파괴됐으면 두 번 부르지 않는다.
            if (!destroyed) mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.uiSettings.isZoomControlEnabled = true
            naverMap = map
        }
    }

    // 내용·지도·크기 중 하나라도 바뀌면 overlay를 다시 그리고 카메라를 맞춘다.
    LaunchedEffect(naverMap, drawKey, size) {
        val map = naverMap ?: return@LaunchedEffect
        if (size == IntSize.Zero) return@LaunchedEffect
        draw(map, size)
    }

    if (authFailed) {
        MapUnavailable(modifier)
        return
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier
            .onSizeChanged { size = it }
            .semantics { contentDescription = description }
            .testTag(TAG_MAP),
    )
}

/**
 * 지도 카메라를 옮길 대상.
 *
 * 사용자가 지도를 직접 옮긴 뒤 같은 대상을 다시 골라도 이동해야 하므로, 고를 때마다 올라가는 [tick]으로
 * 연속 선택을 구분한다.
 */
sealed interface RouteFocus {
    val tick: Int

    /** 장소 순서 카드로 고른 장소(#618). */
    data class Place(val itemId: String, override val tick: Int) : RouteFocus

    /**
     * `내 위치로 이동` 버튼(#614). 화면이 확인한 현재 위치로 옮기고 사용자가 지도를 움직일 때까지 따라간다.
     *
     * @property position 옮길 자리(`[경도, 위도]`). 위치를 못 얻으면 이 값을 만들 수 없으므로 화면이
     *   지도를 옮기는 대신 안내를 띄운다(#651).
     */
    data class MyLocation(val position: Position, override val tick: Int) : RouteFocus
}

/** 지도를 그릴 수 없을 때의 자리 표시. 어두운 바탕에 문구만 둔다. */
@Composable
private fun MapUnavailable(modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LocalGilpickColors.current.darkMap)
            .testTag(TAG_MAP),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.route_map_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.5f),
        )
    }
}

/** 지도에 올린 overlay 묶음. 다시 그리기 전에 전부 떼어 lifecycle 재진입 시 중복이 없게 한다. */
private class RouteOverlays {
    private val markers = mutableListOf<Marker>()
    private val paths = mutableListOf<PathOverlay>()

    fun clear() {
        markers.forEach { it.map = null }
        paths.forEach { it.map = null }
        markers.clear()
        paths.clear()
    }

    /** @param fitCamera 카메라를 경로 전체 범위로 맞출지. `false`면 overlay만 다시 그리고 카메라는 그대로 둔다. */
    fun show(
        fitCamera: Boolean,
        map: NaverMap,
        route: RouteDto,
        baseRoute: RouteDto?,
        basePathColor: Int,
        marks: RouteMarks,
        markerIcon: (RouteMarkerDto) -> OverlayImage,
        startIcon: () -> OverlayImage,
        markerSizePx: Int,
        pathColor: Int,
        pathWidthPx: Int,
        boundsPaddingPx: Int,
    ) {
        clear()
        val bounds = LatLngBounds.Builder()
        // 기존 경로를 먼저 깔아 변경 경로가 위에 오게 한다. 마커는 변경 경로 것만 둬서 같은
        // 장소에 두 마커가 겹치지 않게 한다(F010 UI-001).
        baseRoute?.segments?.forEach { segment ->
            val coords = segmentPath(segment, baseRoute.markers).map { LatLng(it.latitude, it.longitude) }
            coords.forEach { bounds.include(it) }
            if (coords.size < 2) return@forEach
            paths += PathOverlay().apply {
                this.coords = coords
                color = basePathColor
                outlineColor = AndroidColor.TRANSPARENT
                width = pathWidthPx
                this.map = map
            }
        }
        marks.start?.let { start ->
            val position = LatLng(start.latitude, start.longitude)
            bounds.include(position)
            markers += Marker().apply {
                this.position = position
                icon = startIcon()
                anchor = android.graphics.PointF(0.5f, 0.5f)
                this.map = map
            }
        }
        route.markers.forEach { marker ->
            val position = LatLng(marker.latitude, marker.longitude)
            bounds.include(position)
            markers += Marker().apply {
                this.position = position
                icon = markerIcon(marker)
                // fromView는 뷰를 wrap_content로 다시 재므로 마커 크기를 직접 고정해야 원이 찌그러지지 않는다.
                width = markerSizePx
                height = markerSizePx
                anchor = android.graphics.PointF(0.5f, 0.5f)
                // caption은 SDK 기본값(검은 글자·흰 halo)이 밝은 지도에서 읽힌다(#549).
                captionText = marker.name
                this.map = map
            }
        }
        route.segments.forEach { segment ->
            val coords = segmentPath(segment, route.markers).map { LatLng(it.latitude, it.longitude) }
            coords.forEach { bounds.include(it) }
            // 구간 형상은 두 점 이상이 계약이지만, 어긋난 응답으로 앱이 죽지 않게 한 번 더 지킨다.
            if (coords.size < 2) return@forEach
            paths += PathOverlay().apply {
                this.coords = coords
                color = pathColor
                outlineColor = AndroidColor.TRANSPARENT
                width = pathWidthPx
                this.map = map
            }
        }
        if (!fitCamera) return
        // 기존 경로를 함께 그릴 때는 한 장소로 확대하지 않는다. 두 경로가 모두 보여야 비교가 된다.
        if (route.markers.size == 1 && marks.start == null && baseRoute == null) {
            map.moveCamera(CameraUpdate.scrollAndZoomTo(LatLng(route.markers[0].latitude, route.markers[0].longitude), SINGLE_PLACE_ZOOM))
        } else if (route.markers.isNotEmpty()) {
            map.moveCamera(CameraUpdate.fitBounds(bounds.build(), boundsPaddingPx))
        }
    }
}

/**
 * 구간 선을 이룰 좌표(#551). 제공자 경로는 도로·정류장에 맞춰 시작하고 끝나 장소 좌표와 떨어져 있을 수 있다
 * (실측: Kakao 대중교통 양 끝이 장소에서 약 180m). 출발·도착 장소 좌표를 양 끝에 이어 선이 마커에서 마커까지 닿게 한다.
 * 이미 같은 점이면 다시 넣지 않는다.
 */
internal fun segmentPath(segment: RouteSegmentDto, markers: List<RouteMarkerDto>): List<Position> {
    val coords = segment.geometry.coordinates
    val from = markers.firstOrNull { it.itemId == segment.fromItemId }?.let { listOf(it.longitude, it.latitude) }
    val to = markers.firstOrNull { it.itemId == segment.toItemId }?.let { listOf(it.longitude, it.latitude) }
    return buildList {
        if (from != null && from != coords.firstOrNull()) add(from)
        addAll(coords)
        if (to != null && to != coords.lastOrNull()) add(to)
    }
}

/** 순서 번호 또는 상태 기호를 담은 원형 마커 아이콘. 번호는 목록의 순서 번호와 같은 값이다(UI-006). F009 후보 순위에도 쓴다. */
internal fun circleMarker(context: Context, label: String, color: Int, density: Float): OverlayImage {
    val sizePx = (MARKER_SIZE_DP * density).toInt()
    return OverlayImage.fromView(markerView(context, label, color, density, sizePx, sizePx, GradientDrawable.OVAL))
}

/** `시작 위치` 알약형 마커. 글자가 원에 들어가지 않아 너비만 넓힌다. F009 기존 장소 `!` 표시에도 쓴다. */
internal fun pillMarker(context: Context, label: String, color: Int, density: Float): OverlayImage {
    val heightPx = (MARKER_SIZE_DP * density).toInt()
    val widthPx = (START_MARKER_WIDTH_DP * density).toInt()
    return OverlayImage.fromView(markerView(context, label, color, density, widthPx, heightPx, GradientDrawable.RECTANGLE))
}

private fun markerView(context: Context, label: String, color: Int, density: Float, widthPx: Int, heightPx: Int, shapeType: Int) =
    TextView(context).apply {
        text = label
        setTextColor(AndroidColor.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, MARKER_TEXT_SP)
        gravity = Gravity.CENTER
        minimumWidth = widthPx
        minimumHeight = heightPx
        background = GradientDrawable().apply {
            shape = shapeType
            cornerRadius = heightPx / 2f
            setColor(color)
            setStroke((2 * density).toInt(), AndroidColor.WHITE)
        }
        layoutParams = android.view.ViewGroup.LayoutParams(widthPx, heightPx)
        measure(
            android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(heightPx, android.view.View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, widthPx, heightPx)
    }

/** [FusedLocationSource]가 스스로 권한을 물을 때 쓰는 code. 화면이 먼저 요청하므로 실제로는 쓰이지 않는다. */
/** 위치 권한 요청 code. `FusedLocationSource`가 Activity 결과를 되돌려받는 데 쓴다. */
internal const val LOCATION_PERMISSION_REQUEST = 1_614

internal const val MARKER_SIZE_DP = 28
private const val START_MARKER_WIDTH_DP = 52
private const val MARKER_TEXT_SP = 12f
private const val MARKER_CHECK = "\u2713"
private const val MARKER_CROSS = "\u2715"
private const val SINGLE_PLACE_ZOOM = 15.0
private val PATH_WIDTH = 5.dp
internal val BOUNDS_PADDING = 48.dp
