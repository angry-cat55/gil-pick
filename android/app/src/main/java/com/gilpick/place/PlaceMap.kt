package com.gilpick.place

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gilpick.ui.component.TAG_HEADER_BACK
import com.gilpick.R
import com.gilpick.route.NaverMapHost
import com.gilpick.ui.theme.LocalGilpickSpacing
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.overlay.Marker

/**
 * 장소 하나를 Naver 지도에 마커로 보여 준다(#479).
 *
 * F005 [NaverMapHost]를 그대로 써서 lifecycle·인증 실패(`지도를 표시할 수 없어요`)를 맡긴다.
 * 좌표가 없으면 지도 대신 안내를 둔다. 상세의 130dp 미리보기와 전체 화면이 같은 composable이다.
 *
 * 확대·축소·이동 제스처는 미리보기에서도 켠다(#503). 스크롤 화면 안에서도 지도 위에서 시작한 손가락 움직임은 지도
 * (`MapView`)가 받아 처리하므로 지도 영역에서는 지도 제스처가 우선하고, 지도 밖에서 시작하면 화면이 스크롤된다.
 *
 * @param zoomControls 확대 버튼을 보일지. 130dp 미리보기는 버튼이 지도를 가려 끄고, 전체 화면은 켠다.
 */
@Composable
fun PlaceMap(
    name: String,
    latitude: Double?,
    longitude: Double?,
    zoomControls: Boolean,
    modifier: Modifier = Modifier,
) {
    if (latitude == null || longitude == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(LocalGilpickSpacing.current.space4),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.place_map_no_coordinates),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val marker = remember { Marker() }
    NaverMapHost(
        modifier = modifier,
        description = stringResource(R.string.place_map_description, name),
        drawKey = Triple(name, latitude, longitude),
        onDispose = { marker.map = null },
    ) { map, _ ->
        map.uiSettings.isZoomControlEnabled = zoomControls
        map.uiSettings.setAllGesturesEnabled(true)
        val position = LatLng(latitude, longitude)
        marker.position = position
        marker.captionText = name
        marker.map = map
        map.moveCamera(CameraUpdate.scrollAndZoomTo(position, PLACE_ZOOM))
    }
}

/** `지도에서 보기`가 여는 전체 화면. 장소가 가운데에 마커로 있고 자유롭게 움직일 수 있다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceMapScreen(
    name: String,
    latitude: Double?,
    longitude: Double?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(text = name, style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.size(MIN_TOUCH).testTag(TAG_HEADER_BACK)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_arrow_left),
                        contentDescription = stringResource(R.string.place_detail_back),
                        modifier = Modifier.size(BACK_ICON),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
        PlaceMap(
            name = name,
            latitude = latitude,
            longitude = longitude,
            zoomControls = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private const val PLACE_ZOOM = 16.0
private val MIN_TOUCH = 48.dp
private val BACK_ICON = 18.dp
