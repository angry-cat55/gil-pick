package com.gilpick.route

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.itinerary.TransportMode
import com.gilpick.itinerary.ItemStatus
import com.gilpick.ui.theme.GilpickTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T023: 날짜 경로 화면의 네 상태·마커와 목록 순서·이동수단 아이콘+문구·시간·거리·attribution·접근성 검증.
 *
 * `spec.md` UI-003(네 상태), UI-005(지도 밖 목록), UI-006(순서 번호), UI-007(48dp·아이콘+문구),
 * UI-008(360dp·최대 글자 배율), FR-019(정상 경로에 재계산 없음)이 대상이다. 상태 전이는
 * `RouteViewModelTest`가 다루므로 여기서는 상태를 직접 넣는다. 지도는 SDK 인증이 필요해 자리
 * 표시로 바꿔 끼우고, 실제 지도 동작은 T026 수동 검증이 맡는다.
 */
@RunWith(AndroidJUnit4::class)
class DayRouteScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loading은_1초_전에는_표시하지_않고_이후_대기_표시를_띄운다() {
        composeRule.mainClock.autoAdvance = false
        setScreen(RouteUiState.Loading)

        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithContentDescription("경로를 불러오는 중").assertIsNotDisplayed()

        composeRule.mainClock.advanceTimeBy(700)
        composeRule.onNodeWithContentDescription("경로를 불러오는 중").assertIsDisplayed()
    }

    @Test
    fun empty는_안내와_장소_추가와_돌아가기를_보여준다() {
        var added = 0
        var backs = 0
        setScreen(RouteUiState.Empty, onAddPlace = { added++ }, onBack = { backs++ })

        composeRule.onNodeWithText("2일차 경로").assertIsDisplayed()
        composeRule.onNodeWithText("5월 21일").assertIsDisplayed()
        composeRule.onNodeWithText("아직 담은 장소가 없어요").assertIsDisplayed()
        composeRule.onNodeWithText("장소 추가").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithText("돌아가기").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle {
            assertEquals(1, added)
            assertEquals(1, backs)
        }
    }

    @Test
    fun error는_조회_실패_원인과_다시_시도를_보여준다() {
        var retries = 0
        setScreen(RouteUiState.Error(RouteProblem.Request(RouteError.Network)), onRetry = { retries++ })

        composeRule.onNodeWithText("경로를 보여줄 수 없어요").assertIsDisplayed()
        composeRule.onNodeWithText("연결을 확인한 뒤 다시 시도해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithText("다시 시도").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun error는_계산_실패_원인을_code별로_구분해_보여준다() {
        setScreen(RouteUiState.Error(RouteProblem.Calculation(routeFailure(RouteFailureCodes.NOT_FOUND, retryable = false), 3)))

        composeRule.onNodeWithText("이동 경로를 찾지 못했어요. 장소 위치나 이동 수단을 확인해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithText("다시 시도").assertIsDisplayed()
    }

    @Test
    fun 계산_실패의_다시_시도는_48dp이며_누르면_재시도_콜백이_호출되고_loading_중에는_없다() {
        var retries = 0
        composeRule.mainClock.autoAdvance = false
        var state by mutableStateOf<RouteUiState>(RouteUiState.Error(RouteProblem.Calculation(routeFailure(), 3)))
        composeRule.setContent { GilpickTheme { Screen(state, onRetry = { retries++ }) } }

        composeRule.onNodeWithText("경로 서비스 응답이 늦어", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("다시 시도").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }

        // 재계산 요청 중(loading)에는 누를 버튼이 없어 중복 요청이 생기지 않는다.
        state = RouteUiState.Loading
        composeRule.mainClock.advanceTimeBy(1_200)
        composeRule.onNodeWithText("다시 시도").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("경로를 불러오는 중").assertIsDisplayed()
    }

    @Test
    fun 세션_만료는_다시_로그인을_보여준다() {
        var reauth = 0
        setScreen(RouteUiState.Error(RouteProblem.Request(RouteError.SessionExpired)), onReauthenticate = { reauth++ })

        composeRule.onNodeWithText("다시 로그인").performClick()
        composeRule.runOnIdle { assertEquals(1, reauth) }
    }

    @Test
    fun content는_지도와_같은_순서의_구간_목록과_합계와_attribution을_보여준다() {
        setScreen(RouteUiState.Content(readyRoute()))

        composeRule.onNodeWithText("2일차 경로").assertIsDisplayed()
        composeRule.onNodeWithText("5월 21일 · 3곳").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_MAP).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_SUMMARY).assertIsDisplayed()
        composeRule.onNodeWithText("총 이동 25분 · 4.2km").assertIsDisplayed()
        composeRule.onNodeWithText("출처: $TMAP_ATTRIBUTION · $ODSAY_ATTRIBUTION").assertIsDisplayed()

        // 구간 목록은 일정 순서와 같고, 이동 수단은 아이콘과 함께 문구로 구분된다.
        val first = composeRule.onNodeWithTag("${TAG_SEGMENT_PREFIX}1").assertIsDisplayed()
        val second = composeRule.onNodeWithTag("${TAG_SEGMENT_PREFIX}2").assertIsDisplayed()
        assertTrue(first.getBoundsInRoot().top < second.getBoundsInRoot().top)
        composeRule.onNodeWithContentDescription("1번째 구간, 경복궁에서 북촌한옥마을까지 도보 10분 800m").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("2번째 구간, 북촌한옥마을에서 인사동거리까지 대중교통 15분 3.4km").assertIsDisplayed()
        composeRule.onNodeWithText("도보 · 10분 · 800m").assertIsDisplayed()
        composeRule.onNodeWithText("대중교통 · 15분 · 3.4km").assertIsDisplayed()
    }

    @Test
    fun 시작된_날짜의_진행_표시는_구간_목록에_상태를_문구로_겹치고_없으면_계획만_보인다() {
        val marks = RouteMarks(
            start = listOf(126.97, 37.57),
            statuses = mapOf(ITEM_A to ItemStatus.COMPLETED, ITEM_B to ItemStatus.EN_ROUTE, ITEM_C to ItemStatus.PLANNED),
        )
        setScreen(RouteUiState.Content(readyRoute(), marks))

        composeRule.onNodeWithContentDescription("1번째 구간, 경복궁 완료에서 북촌한옥마을 이동 중까지 도보 10분 800m").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("2번째 구간, 북촌한옥마을 이동 중에서 인사동거리 예정까지 대중교통 15분 3.4km").assertIsDisplayed()
        composeRule.onAllNodesWithText("완료").assertCountEquals(1)
        composeRule.onAllNodesWithText("이동 중").assertCountEquals(2)
        composeRule.onAllNodesWithText("예정").assertCountEquals(1)
    }

    @Test
    fun 진행_표시가_없는_날짜는_기존_계획_표시_그대로다() {
        setScreen(RouteUiState.Content(readyRoute()))

        composeRule.onAllNodesWithText("완료").assertCountEquals(0)
        composeRule.onAllNodesWithText("예정").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("1번째 구간, 경복궁에서 북촌한옥마을까지 도보 10분 800m").assertIsDisplayed()
    }

    @Test
    fun 장소가_한_곳인_시작된_날짜는_장소_행에_상태를_겹친다() {
        val route = readyRoute().copy(
            markers = listOf(RouteMarkerDto(ITEM_A, 1, "경복궁", 37.5796, 126.977)),
            segments = emptyList(),
            totalDurationSeconds = 0,
            totalDistanceMeters = 0,
        )
        setScreen(RouteUiState.Content(route, RouteMarks(statuses = mapOf(ITEM_A to ItemStatus.ARRIVED))))

        composeRule.onNodeWithContentDescription("1번째 장소 경복궁 도착").assertIsDisplayed()
        composeRule.onNodeWithText("도착").assertIsDisplayed()
    }

    @Test
    fun 정상_content에는_재계산이나_후보_선택_버튼이_없다() {
        setScreen(RouteUiState.Content(readyRoute()))

        composeRule.onNodeWithText("다시 시도").assertDoesNotExist()
        composeRule.onNodeWithText("다시 계산").assertDoesNotExist()
        composeRule.onNodeWithText("다른 경로").assertDoesNotExist()
    }

    @Test
    fun 장소가_한_곳이면_구간_없이_합계_0과_장소_한_행을_보여준다() {
        val single = readyRoute().copy(
            totalDurationSeconds = 0,
            totalDistanceMeters = 0,
            markers = listOf(RouteMarkerDto(ITEM_A, 1, "경복궁", 37.5796, 126.977)),
            segments = emptyList(),
            providerAttributions = emptyList(),
        )
        setScreen(RouteUiState.Content(single))

        composeRule.onNodeWithText("5월 21일 · 1곳").assertIsDisplayed()
        composeRule.onNodeWithText("이동 구간 없음 · 총 이동 0분 · 0m").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("1번째 장소 경복궁").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_ATTRIBUTION).assertDoesNotExist()
    }

    @Test
    fun 혼합_이동수단_구간은_각각_아이콘과_문구로_구분된다() {
        val mixed = readyRoute().copy(
            segments = readyRoute().segments + RouteSegmentDto(
                sequence = 3, fromItemId = ITEM_C, toItemId = ITEM_A,
                transportMode = TransportMode.CAR, provider = RouteProvider.TMAP,
                durationSeconds = 3_660, distanceMeters = 12_345,
                geometry = RouteGeometryDto("LineString", listOf(listOf(126.9857, 37.5744), listOf(126.977, 37.5796))),
                providerAttribution = TMAP_ATTRIBUTION,
            ),
        )
        setScreen(RouteUiState.Content(mixed))

        composeRule.onNodeWithText("도보 · 10분 · 800m").assertIsDisplayed()
        composeRule.onNodeWithText("대중교통 · 15분 · 3.4km").assertIsDisplayed()
        composeRule.onNodeWithTag("${TAG_SEGMENT_PREFIX}3").performScrollTo()
        composeRule.onNodeWithText("자동차 · 1시간 1분 · 12.3km").assertIsDisplayed()
    }

    @Test
    fun 헤더_뒤로가기는_48dp_터치_영역과_설명을_가진다() {
        var backs = 0
        setScreen(RouteUiState.Content(readyRoute()), onBack = { backs++ })

        composeRule.onNodeWithContentDescription("뒤로 가기")
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun 화면_360dp_최대_글자_배율에서도_핵심_문구가_가로_스크롤_없이_보인다() {
        composeRule.setContent {
            GilpickTheme {
                Box(modifier = Modifier.width(360.dp)) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                        Screen(RouteUiState.Content(readyRoute()))
                    }
                }
            }
        }

        composeRule.onNodeWithText("2일차 경로").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_SUMMARY).assertIsDisplayed()
        val sheet = composeRule.onNodeWithTag(TAG_SHEET).getBoundsInRoot()
        assertTrue(sheet.right <= 360.dp)
        val row = composeRule.onNodeWithTag("${TAG_SEGMENT_PREFIX}1").assertIsDisplayed().getBoundsInRoot()
        assertTrue(row.right <= 360.dp)
    }

    private fun setScreen(
        state: RouteUiState,
        onBack: () -> Unit = {},
        onRetry: () -> Unit = {},
        onAddPlace: () -> Unit = {},
        onReauthenticate: () -> Unit = {},
    ) {
        composeRule.setContent {
            GilpickTheme { Screen(state, onBack, onRetry, onAddPlace, onReauthenticate) }
        }
    }

    @Composable
    private fun Screen(
        state: RouteUiState,
        onBack: () -> Unit = {},
        onRetry: () -> Unit = {},
        onAddPlace: () -> Unit = {},
        onReauthenticate: () -> Unit = {},
    ) {
        DayRouteScreen(
            state = state,
            dayNumber = 2,
            date = LocalDate.of(2026, 5, 21),
            onBack = onBack,
            onRetry = onRetry,
            onAddPlace = onAddPlace,
            onReauthenticate = onReauthenticate,
            map = { _, _, modifier -> Box(modifier = modifier.fillMaxSize().testTag(TAG_MAP)) },
        )
    }
}
