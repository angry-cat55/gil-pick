package com.gilpick.ui.component

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 이 화면이 보이는 동안 system bar 아이콘을 밝게(흰색) 그린다(#590).
 *
 * 앱 기본값은 밝은 배경 기준의 어두운 아이콘이다(`themes.xml`의 `windowLightStatusBar`,
 * `MainActivity`의 `enableEdgeToEdge`). 지도처럼 위아래가 어두운 화면은 그대로 두면 어두운 배경 위에
 * 어두운 아이콘이 되어 시각이 읽히지 않으므로, 그 화면이 화면에 있는 동안만 뒤집는다.
 * 창이 edge-to-edge라 상태 표시줄과 navigation bar 모두 화면 배경 위에 놓이므로 둘 다 바꾼다.
 *
 * 화면을 벗어나면 이전 값으로 되돌린다. 뒤로 가서 밝은 화면으로 돌아왔을 때 흰 아이콘이 남지 않게 한다.
 */
@Composable
fun LightSystemBarIcons() {
    val view = LocalView.current
    val window = LocalActivity.current?.window
    if (view.isInEditMode || window == null) return

    DisposableEffect(window, view) {
        val controller = WindowCompat.getInsetsController(window, view)
        val previousStatus = controller.isAppearanceLightStatusBars
        val previousNavigation = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.isAppearanceLightStatusBars = previousStatus
            controller.isAppearanceLightNavigationBars = previousNavigation
        }
    }
}
