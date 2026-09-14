package com.gilpick.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gilpick.R

/**
 * 앱 전체가 공유하는 테마.
 *
 * 값의 기준은 Figma Make `Design UI from Reference`(사본 `docs/design/figma-make`)이며
 * `docs/design/ui-guidelines.md` 3~6절이 그 값을 표로 정리한다. 이 파일은 그 실행 가능한
 * 버전이다. 둘이 어긋나면 Figma를 다시 읽고 문서와 여기를 함께 맞춘다.
 *
 * 화면 코드는 색상·간격·곡률 리터럴을 쓰지 않고 여기서 읽는다.
 */
@Composable
fun GilpickTheme(content: @Composable () -> Unit) {
    // 다크 팔레트는 아직 확정되지 않았다(Figma는 로그인·일자 경로만 어두운 배경). 확정 전까지 라이트만 제공한다.
    // dynamic color는 쓰지 않는다. 기기 배경색이 브랜드색을 바꾸면 검증한 대비가 무효가 된다.
    CompositionLocalProvider(
        LocalGilpickColors provides GilpickLightColors,
        LocalGilpickSpacing provides GilpickSpacing(),
        LocalGilpickSizing provides GilpickSizing(),
        LocalGilpickRadius provides GilpickRadius(),
        LocalGilpickShadows provides GilpickShadows(),
    ) {
        MaterialTheme(
            colorScheme = GilpickColorScheme,
            typography = GilpickTypography,
            content = content,
        )
    }
}

/**
 * Material 3 `ColorScheme`에 대응 역할이 있는 색. 가이드라인 3절 표와 같다.
 *
 * Figma 값을 조정 없이 그대로 쓴다. 대비 미달 조합(가이드라인 3절 판정)은 문구·아이콘 병기로 보완한다.
 */
private val GilpickColorScheme = lightColorScheme(
    primary = Color(0xFF3B7BF8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEBF2FF),
    onPrimaryContainer = Color(0xFF3B7BF8),
    secondary = Color(0xFF6B7280),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F6FB),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF4F6FB),
    onSurfaceVariant = Color(0xFF6B7280),
    error = Color(0xFFEF4444),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEF2F2),
    onErrorContainer = Color(0xFFEF4444),
    outline = Color(0xFF6B7280),
    outlineVariant = Color(0xFFE2E8F0),
    scrim = Color(0xFF000000),
)

/**
 * Material 3 `ColorScheme`에 자리가 없는 색(가이드라인 3절).
 *
 * @property muted 주소·기간·설명, 섹션 라벨, placeholder. 흰 배경 대비 2.56:1이라 보조 정보 전용.
 * @property faint 예정 항목, chevron, 이미지 대체 배경. 장식·비활성 전용.
 * @property surfaceTint 읽지 않은 알림, 선택된 목록 행.
 * @property primaryDark 주버튼 gradient 끝(135°), 안내 글자.
 * @property primarySoft 안내 배너 배경.
 * @property primaryLight 배너 안 출처 글자, 어두운 지도 위 강조.
 * @property success 여행 중·운영 중·완료. [successContainer]는 상태 칩 배경.
 * @property successDark 성공 gradient 버튼 끝(135°, 가이드라인 3절 "버튼 gradient").
 * @property warning 변수 감지, 마감 임박. [warningContainer] 배너 배경, [onWarningContainer] 배너 제목.
 * @property warningDark 경고 gradient 버튼 끝(135°, 가이드라인 3절 "버튼 gradient").
 * @property amber 낮은 위험 단계.
 * @property info 계획 ETA와 일반 안내(= primaryDark).
 * @property star 평점 `★`.
 * @property dark 로그인 배경. [onDarkMuted]는 그 위 보조 글자.
 * @property darkMap 일자 경로 화면 배경. 지도 뒤와 그 위 sheet의 바탕이다.
 * @property toast 하단 toast 배경.
 * @property kakao 카카오 브랜드 가이드가 정한 버튼 배경. 앱 팔레트와 무관하게 고정이다.
 * @property onKakao 카카오 버튼 라벨.
 * @property headerIcon 헤더 알림·설정 아이콘과 헤더 오른쪽 보조 행동 아이콘(가이드라인 7절 "헤더 아이콘 색", D7).
 *   `onSurfaceVariant`와 같은 값이다. 흰 배경 4.84:1, `#F4F6FB` 위 4.47:1. 뒤로 가기·닫기는 `onSurface`를 쓴다.
 */
data class GilpickColors(
    val muted: Color,
    val faint: Color,
    val surfaceTint: Color,
    val primaryDark: Color,
    val primarySoft: Color,
    val primaryLight: Color,
    val success: Color,
    val successContainer: Color,
    val successDark: Color,
    val warning: Color,
    val warningDark: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val amber: Color,
    val info: Color,
    val star: Color,
    val dark: Color,
    val darkMap: Color,
    val onDarkMuted: Color,
    val toast: Color,
    val kakao: Color,
    val onKakao: Color,
    val headerIcon: Color,
)

private val GilpickLightColors = GilpickColors(
    muted = Color(0xFF94A3B8),
    faint = Color(0xFFCBD5E1),
    surfaceTint = Color(0xFFF8FBFF),
    primaryDark = Color(0xFF2457C5),
    primarySoft = Color(0xFFEFF6FF),
    primaryLight = Color(0xFF93C5FD),
    success = Color(0xFF10B981),
    successContainer = Color(0xFFECFDF5),
    successDark = Color(0xFF059669),
    warning = Color(0xFFF97316),
    warningDark = Color(0xFFEA580C),
    warningContainer = Color(0xFFFFF7ED),
    onWarningContainer = Color(0xFF92400E),
    amber = Color(0xFFF59E0B),
    info = Color(0xFF2457C5),
    star = Color(0xFFFBBF24),
    dark = Color(0xFF0B1120),
    darkMap = Color(0xFF0F1A2E),
    onDarkMuted = Color(0xFF8BA3C7),
    toast = Color(0xEB111827),
    kakao = Color(0xFFFEE500),
    onKakao = Color(0xFF111827),
    headerIcon = Color(0xFF6B7280),
)

/** 4dp 배수 간격. Figma Tailwind 단위(1 = 4px) 그대로다. 용도는 가이드라인 5절에 있다. */
data class GilpickSpacing(
    val space1: Dp = 4.dp,
    val space2: Dp = 8.dp,
    val space3: Dp = 12.dp,
    val space4: Dp = 16.dp,
    val space5: Dp = 20.dp,
    val space6: Dp = 24.dp,
    val space8: Dp = 32.dp,
)

/**
 * 간격 스케일에 없는 고정 크기(가이드라인 5절).
 *
 * Figma 빈 상태: 64dp `background` 상자 안 28dp `faint` 아이콘.
 *
 * @property emptyBottomPadding 빈 상태 아래 여백. 세로 중앙보다 살짝 위로 올려 앱바와 균형을 맞춘다.
 * @property groupDot 목록 그룹 헤더 앞의 상태 점 지름.
 */
data class GilpickSizing(
    val emptyIconCircle: Dp = 64.dp,
    val emptyIcon: Dp = 28.dp,
    val emptyBottomPadding: Dp = 60.dp,
    val groupDot: Dp = 8.dp,
)

/** 곡률(가이드라인 6절). 목록 행은 곡률 없이 구분선으로 잇고 카드·버튼·sheet에만 쓴다. */
data class GilpickRadius(
    /** 작은 배지(`rounded-md`). */
    val xs: Dp = 6.dp,
    /** 상태 칩(`rounded-lg`). */
    val sm: Dp = 8.dp,
    /** 버튼, 입력창, 아이콘 버튼, 썸네일, 칩(`rounded-xl`). */
    val md: Dp = 12.dp,
    /** 카드, 지도 미리보기, 큰 버튼(`rounded-2xl`). */
    val lg: Dp = 16.dp,
    /** 큰 카드, dialog, bottom sheet 상단(`rounded-3xl`). */
    val xl: Dp = 24.dp,
    /** 선택 sheet 상단(`rounded-t-[32px]`). */
    val sheet: Dp = 32.dp,
)

/**
 * 그림자(가이드라인 6절 "그림자" 표). 표에서 값이 있는 행마다 토큰 하나다.
 *
 * CSS `box-shadow: x y blur spread rgba(r,g,b,a)`를 조정 없이 옮긴다.
 * - px → dp 1:1(간격·곡률과 같은 Figma 단위). `blur` → [Shadow.radius], `spread` → [Shadow.spread],
 *   `x y` → [Shadow.offset].
 * - `rgba`의 alpha는 [Shadow.color]의 alpha에 넣고 [Shadow.alpha]는 1로 둔다.
 * - 쉼표로 이어진 여러 겹은 CSS 순서대로 [List]에 담는다. 화면은 `Modifier.dropShadow`를 겹 순서대로 적용한다.
 * - 모달 bottom sheet는 그림자 없이 scrim만 쓰므로 토큰이 없다.
 */
data class GilpickShadows(
    /** 흰 카드. `0 1px 4px rgba(0,0,0,0.06)` */
    val card: List<Shadow> = listOf(css(y = 1, blur = 4, color = Black.copy(alpha = 0.06f))),
    /** 다음 장소 카드. `0 4px 20px rgba(0,0,0,0.08)` */
    val floatingCard: List<Shadow> = listOf(css(y = 4, blur = 20, color = Black.copy(alpha = 0.08f))),
    /** gradient 주버튼. `0 4px 16px rgba(59,123,248,0.3)` */
    val primaryButton: List<Shadow> = listOf(css(y = 4, blur = 16, color = Primary.copy(alpha = 0.3f))),
    /** 새 여행 만들기 FAB. `0 8px 28px rgba(59,123,248,0.5)` */
    val fab: List<Shadow> = listOf(css(y = 8, blur = 28, color = Primary.copy(alpha = 0.5f))),
    /** 비모달 sheet(지도 위, `MapSearchScreen` 결과 sheet). `0 -4px 24px rgba(0,0,0,0.12)` */
    val sheetOverMap: List<Shadow> = listOf(css(y = -4, blur = 24, color = Black.copy(alpha = 0.12f))),
    /** 지도 아래 겹친 sheet(`AlternativePlacesScreen` 후보 sheet). `0 -4px 20px rgba(0,0,0,0.08)` */
    val sheetBelowMap: List<Shadow> = listOf(css(y = -4, blur = 20, color = Black.copy(alpha = 0.08f))),
    /** dialog(Tailwind `shadow-2xl`). `0 25px 50px -12px rgba(0,0,0,0.25)`. scrim `rgba(0,0,0,0.5)`은 별도다. */
    val dialog: List<Shadow> = listOf(css(y = 25, blur = 50, spread = -12, color = Black.copy(alpha = 0.25f))),
    /** 드롭다운 메뉴. `0 8px 32px rgba(0,0,0,0.18)` */
    val dropdownMenu: List<Shadow> = listOf(css(y = 8, blur = 32, color = Black.copy(alpha = 0.18f))),
    /** 목록 안 작은 주 행동 버튼(TOP 후보 `경로 비교`). `0 2px 8px rgba(59,123,248,0.3)` */
    val listPrimaryAction: List<Shadow> = listOf(css(y = 2, blur = 8, color = Primary.copy(alpha = 0.3f))),
    /** 달력 시작·종료일 선택 원. `0 2px 8px rgba(59,123,248,0.35)` */
    val calendarSelected: List<Shadow> = listOf(css(y = 2, blur = 8, color = Primary.copy(alpha = 0.35f))),
    /** 지도 위 떠 있는 버튼. `0 2px 8px rgba(0,0,0,0.15)` */
    val mapFloatingButton: List<Shadow> = listOf(css(y = 2, blur = 8, color = Black.copy(alpha = 0.15f))),
    /** 체류 시간 `−` 버튼(시트). `0 2px 6px rgba(0,0,0,0.08)` */
    val stayMinusSheet: List<Shadow> = listOf(css(y = 2, blur = 6, color = Black.copy(alpha = 0.08f))),
    /** 체류 시간 `−` 버튼(dialog). `0 2px 8px rgba(0,0,0,0.1)` */
    val stayMinusDialog: List<Shadow> = listOf(css(y = 2, blur = 8, color = Black.copy(alpha = 0.1f))),
    /** 체류 시간 dialog `+` 버튼. `0 4px 12px rgba(59,123,248,0.3)` */
    val stayPlusDialog: List<Shadow> = listOf(css(y = 4, blur = 12, color = Primary.copy(alpha = 0.3f))),
    /** 진행 중 여행 카드. `0 0 0 2px #3B7BF8, 0 2px 12px rgba(59,123,248,0.12)` — 첫 겹은 2dp 강조 테두리다. */
    val activeTripCard: List<Shadow> = listOf(
        css(spread = 2, color = Primary),
        css(y = 2, blur = 12, color = Primary.copy(alpha = 0.12f)),
    ),
    /** 지도 위 작은 버튼(Tailwind `shadow-md`). `0 4px 6px -1px rgba(0,0,0,0.1), 0 2px 4px -2px rgba(0,0,0,0.1)` */
    val mapSmallButton: List<Shadow> = listOf(
        css(y = 4, blur = 6, spread = -1, color = Black.copy(alpha = 0.1f)),
        css(y = 2, blur = 4, spread = -2, color = Black.copy(alpha = 0.1f)),
    ),
    /** 토글 thumb(Tailwind `shadow-sm`). `0 1px 3px 0 rgba(0,0,0,0.1), 0 1px 2px -1px rgba(0,0,0,0.1)` */
    val toggleThumb: List<Shadow> = listOf(
        css(y = 1, blur = 3, color = Black.copy(alpha = 0.1f)),
        css(y = 1, blur = 2, spread = -1, color = Black.copy(alpha = 0.1f)),
    ),
)

private val Black = Color(0xFF000000)
private val Primary = Color(0xFF3B7BF8)

/** CSS `box-shadow` 한 겹을 px 값 그대로 [Shadow]로 옮긴다. x 오프셋은 표에 쓰는 행이 없어 0이다. */
private fun css(y: Int = 0, blur: Int = 0, spread: Int = 0, color: Color): Shadow =
    Shadow(radius = blur.dp, color = color, spread = spread.dp, offset = DpOffset(0.dp, y.dp))

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

val LocalGilpickSizing = compositionLocalOf<GilpickSizing> {
    error("GilpickTheme 안에서만 사용할 수 있습니다")
}

val LocalGilpickRadius = compositionLocalOf<GilpickRadius> {
    error("GilpickTheme 안에서만 사용할 수 있습니다")
}

val LocalGilpickShadows = compositionLocalOf<GilpickShadows> {
    error("GilpickTheme 안에서만 사용할 수 있습니다")
}

/**
 * Figma `index.css`의 `Outfit, 'Noto Sans KR'`: 숫자·라틴은 Outfit(가변 폰트, OFL —
 * `app/OFL-Outfit.txt`), 한글은 시스템 폰트. 가이드라인 4절.
 */
val Outfit: FontFamily = FontFamily(
    listOf(FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold, FontWeight.Black).map { weight ->
        // 가변 폰트는 wght 축을 직접 지정해야 한다. 없으면 파일 기본 굵기(Thin)로 그려진다.
        Font(R.font.outfit, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    },
)

/**
 * 문자열에 맞는 폰트. Outfit 패밀리에 한글을 맡기면 fallback 글리프에 굵기가 적용되지
 * 않아 얇게 나오므로, 한글이 하나라도 있으면 시스템 폰트를 고른다.
 */
fun String.displayFont(): FontFamily =
    if (any { it in '가'..'힣' || it in 'ㄱ'..'ㆎ' }) FontFamily.Default else Outfit

/**
 * 가이드라인 4절의 타입 스케일을 Material 3 role에 대응시킨다.
 *
 * 패밀리는 지정하지 않는다(시스템 폰트). 숫자·라틴 강조에는 화면에서 [displayFont]를 붙인다.
 */
private val GilpickTypography = Typography(
    // Display 28/36 900 — 여행 중 ETA
    displaySmall = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black),
    // Hero title 26/34 900 — 내 여행 제목, 장소 상세 이름
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    // Status title 24/32 900 — 헤더 없는 전체 화면 안내·오류 제목(`ErrorScreen`, D6)
    headlineLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Black),
    // Screen title 22/30 900 — 다음 장소, 여행 상세 제목
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Black),
    // Sheet title 20/28 900
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black),
    // Page title 18/26 900 — 헤더 제목
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Black),
    // Card title 15/22 700 — 장소명, 여행명
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
    // Body 14/20 500 — 정보 값, 목록 항목
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    // Supporting 13/18 400 — 주소, 기간, 설명
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    // Label 12/16 500 — 상태 라벨, 시간
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    // Button 15/20 700 — 주버튼
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
    // Button secondary 14/20 600 — 보조 버튼, 칩
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    // Caption 11/14 700 — 섹션 라벨, 상태 칩, stats 라벨
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.55.sp),
)
