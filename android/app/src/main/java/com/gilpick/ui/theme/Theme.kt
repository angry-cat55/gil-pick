package com.gilpick.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 앱 전체가 공유하는 테마.
 *
 * 값의 기준은 `docs/design/ui-guidelines.md`이며 이 파일은 그 실행 가능한 버전이다.
 * 둘이 어긋나면 문서를 고치고 여기를 맞춘다.
 *
 * 화면 코드는 색상·간격·곡률 리터럴을 쓰지 않고 여기서 읽는다. 그래야 나중에 시각
 * 방향을 바꿀 때 이 파일만 고치면 된다.
 */
@Composable
fun GilpickTheme(content: @Composable () -> Unit) {
    // 다크 팔레트는 아직 확정되지 않았다. 확정 전까지 라이트만 제공한다.
    // dynamic color는 쓰지 않는다. 기기 배경색이 브랜드색을 바꾸면 검증한 대비가 무효가 된다.
    CompositionLocalProvider(
        LocalGilpickColors provides GilpickLightColors,
        LocalGilpickSpacing provides GilpickSpacing(),
        LocalGilpickRadius provides GilpickRadius(),
    ) {
        MaterialTheme(
            colorScheme = GilpickColorScheme,
            typography = GilpickTypography,
            content = content,
        )
    }
}

/**
 * Material 3 `ColorScheme`에 대응 역할이 있는 색.
 *
 * 대비 검증 수치는 가이드라인 3절에 있다. 값을 바꾸면 수치를 다시 계산해 문서를 갱신한다.
 */
private val GilpickColorScheme = lightColorScheme(
    primary = Color(0xFF0A7268),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6F3EE),
    onPrimaryContainer = Color(0xFF0A7268),
    secondary = Color(0xFF526A64),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFAFBF9),
    onBackground = Color(0xFF18201E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF18201E),
    surfaceVariant = Color(0xFFF1F4F2),
    onSurfaceVariant = Color(0xFF64706D),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFF7F8C88),
    outlineVariant = Color(0xFFD8DEDB),
)

/**
 * Material 3 `ColorScheme`에 자리가 없는 색.
 *
 * @property warning 영업 종료 임박처럼 확인이 필요한 상태.
 * @property success 도착·완료.
 * @property info 계획 ETA와 일반 안내.
 * @property kakao 카카오 브랜드 가이드가 정한 버튼 배경. 앱 팔레트와 무관하게 고정이다.
 * @property onKakao 카카오 버튼 라벨.
 */
data class GilpickColors(
    val warning: Color,
    val success: Color,
    val info: Color,
    val kakao: Color,
    val onKakao: Color,
)

private val GilpickLightColors = GilpickColors(
    warning = Color(0xFFA15C00),
    success = Color(0xFF287A47),
    info = Color(0xFF36618E),
    kakao = Color(0xFFFEE500),
    onKakao = Color(0xFF191919),
)

/** 4dp 배수 간격. 값의 용도는 가이드라인 5절에 있다. */
data class GilpickSpacing(
    val space1: Dp = 4.dp,
    val space2: Dp = 8.dp,
    val space3: Dp = 12.dp,
    val space4: Dp = 16.dp,
    val space5: Dp = 20.dp,
    val space6: Dp = 24.dp,
)

/** 곡률. 목록 안에서는 드러내지 않고 큰 컨테이너와 플로팅 요소에만 쓴다. */
data class GilpickRadius(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
)

/**
 * 테마 밖에서 읽으면 즉시 실패한다.
 *
 * 기본값을 주면 테마를 빠뜨렸을 때 조용히 다른 색이 나온다. 그 편이 찾기 더 어렵다.
 */
val LocalGilpickColors = compositionLocalOf<GilpickColors> {
    error("GilpickTheme 안에서만 사용할 수 있습니다")
}

val LocalGilpickSpacing = compositionLocalOf<GilpickSpacing> {
    error("GilpickTheme 안에서만 사용할 수 있습니다")
}

val LocalGilpickRadius = compositionLocalOf<GilpickRadius> {
    error("GilpickTheme 안에서만 사용할 수 있습니다")
}

/**
 * 가이드라인 4절의 타입 스케일을 Material 3 role에 대응시킨다.
 *
 * 별도 폰트를 번들하지 않고 시스템 기본을 쓴다. 한글은 기기 기본 폰트로 렌더링된다.
 * 최소 크기는 12sp이며 그 아래는 쓰지 않는다.
 */
private val GilpickTypography = Typography(
    // Display 28/36 700 — 로그인, 여행 완료처럼 드문 강조
    displaySmall = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    // Screen title 22/30 700
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    // ETA emphasis 24/30 700
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    // Section title 18/26 700
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    // Card title 16/24 600
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    // Body 15/22 400
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    // Supporting 13/18 400
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    // Label 14/20 600
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    // Caption 12/16 500
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)
