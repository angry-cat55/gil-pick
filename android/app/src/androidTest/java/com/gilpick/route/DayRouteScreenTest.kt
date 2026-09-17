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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
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
import com.gilpick.ui.component.TAG_SHEET_HANDLE
import com.gilpick.ui.theme.GilpickTheme
import java.time.LocalDate
import com.gilpick.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /** #685: 가까운 거리에서 도보 경로까지 없으면 전용 제목과 본문으로 안내한다. 먼 거리의 기존 안내는 그대로다. */
    @Test
    fun 가까운_거리에서_도보_경로도_없으면_전용_안내를_보여준다() {
        setScreen(RouteUiState.Error(RouteProblem.Calculation(routeFailure(RouteFailureCodes.SHORT_DISTANCE_NOT_FOUND, retryable = false), 3)))

        composeRule.onNodeWithText("길찾기 결과가 없습니다").assertIsDisplayed()
        composeRule.onNodeWithText("가까운 거리는 도보 길찾기를 이용해주세요").assertIsDisplayed()
    }

    /** #685: 도보로 대체된 구간만 구간 정보 아래에 인라인 안내를 보인다. 오류가 아니라 정상 경로다. */
    @Test
    fun 도보로_대체된_구간에만_인라인_안내를_보여준다() {
        val route = readyRoute().let { base ->
            base.copy(segments = base.segments.mapIndexed { index, segment -> if (index == 0) segment.copy(isWalkingFallback = true) else segment })
        }
        setScreen(RouteUiState.Content(route))

        composeRule.onNodeWithTag("${TAG_SEGMENT_WALKING_FALLBACK_PREFIX}1", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("${TAG_SEGMENT_WALKING_FALLBACK_PREFIX}2", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("1번째 구간, 경복궁에서 북촌한옥마을까지 도보 10분 800m, 가까운 거리는 도보를 이용하세요").assertExists()
        composeRule.onNodeWithText("길찾기 결과가 없습니다").assertDoesNotExist()
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
        // 범례 1 + 장소 카드 1 + 구간 목록(완료 1·이동 중 2·예정 1)
        composeRule.onAllNodesWithText("완료").assertCountEquals(3)
        composeRule.onAllNodesWithText("이동 중").assertCountEquals(4)
        composeRule.onAllNodesWithText("예정").assertCountEquals(3)
        composeRule.onNodeWithContentDescription("1번째 장소 경복궁 완료").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("2번째 장소 북촌한옥마을 이동 중").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("3번째 장소 인사동거리 예정").assertIsDisplayed()
        composeRule.onNodeWithText("지도 이동 가능").assertIsDisplayed()
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
        composeRule.onNodeWithTag("${TAG_SEGMENT_PREFIX}1").assertDoesNotExist()
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
        // 장소 카드는 항상 보이고, 구간 목록은 세로 스크롤로 닿는다(가로 스크롤 없음).
        val card = composeRule.onNodeWithTag("${TAG_MARKER_PREFIX}1").assertIsDisplayed().getBoundsInRoot()
        assertTrue(card.right <= 360.dp)
        // 글자 2.0배에서는 `북촌한옥마을`만 두 줄이 되지만 카드 높이는 행 안에서 같다(#618).
        val cardHeights = (1..3).map { composeRule.onNodeWithTag("$TAG_MARKER_PREFIX$it").getBoundsInRoot().let { b -> b.bottom - b.top } }
        assertTrue("heights=$cardHeights", cardHeights.all { it == cardHeights[0] })
        val row = composeRule.onNodeWithTag("${TAG_SEGMENT_PREFIX}1").performScrollTo().assertIsDisplayed().getBoundsInRoot()
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

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun sheet는_끌거나_손잡이로_접고_펼치며_지도_비율이_따라간다() {
        var fraction = -1f
        composeRule.setContent {
            GilpickTheme {
                DayRouteScreen(
                    state = RouteUiState.Content(readyRoute()),
                    dayNumber = 2,
                    date = LocalDate.of(2026, 5, 21),
                    onBack = {},
                    onRetry = {},
                    onAddPlace = {},
                    onReauthenticate = {},
                    map = { _, _, sheetFraction, _, modifier ->
                        fraction = sheetFraction
                        Box(modifier = modifier.fillMaxSize().testTag(TAG_MAP))
                    },
                )
            }
        }
        val handle = composeRule.onNodeWithTag(TAG_SHEET_HANDLE)
        fun sheetHeight() = composeRule.onNodeWithTag(TAG_SHEET).getBoundsInRoot().let { it.bottom - it.top }
        composeRule.waitForIdle()
        handle.assertHeightIsAtLeast(48.dp).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "기본"))
        val defaultHeight = sheetHeight()
        val defaultFraction = fraction
        assertTrue("default=$defaultFraction", defaultFraction in 0.01f..0.45f)

        // 아래로 끌면 합계만 남고 가려진 구간 목록은 접근성 트리에서도 빠진다.
        handle.performTouchInput { swipeDown(startY = centerY, endY = centerY + 2_000f) }
        composeRule.waitForIdle()
        handle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "접힘"))
        composeRule.onNodeWithTag(TAG_SUMMARY).assertIsDisplayed()
        composeRule.onNodeWithTag("${TAG_SEGMENT_PREFIX}1").assertDoesNotExist()
        assertTrue(sheetHeight() < defaultHeight)
        assertTrue("collapsed=$fraction default=$defaultFraction", fraction < defaultFraction)

        // 손잡이를 누르면 한 단계 펼쳐 기본으로 돌아온다.
        handle.performClick()
        composeRule.waitForIdle()
        handle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "기본"))
        composeRule.onNodeWithTag("${TAG_SEGMENT_PREFIX}1").assertExists()
        assertEquals(defaultFraction, fraction, 0.01f)

        // 끌기가 어려우면 접근성 action으로 펼치고 접는다.
        handle.performCustomAccessibilityActionWithLabel("펼치기")
        composeRule.waitForIdle()
        handle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "펼침"))
        handle.performCustomAccessibilityActionWithLabel("접기")
        handle.performCustomAccessibilityActionWithLabel("접기")
        composeRule.waitForIdle()
        handle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "접힘"))

        // 조금만 끌면 그대로, 충분히 끌어 올리면 한 단계 펼친다.
        handle.performTouchInput { swipeUp(startY = centerY, endY = centerY - 24.dp.toPx(), durationMillis = 1_000) }
        composeRule.waitForIdle()
        handle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "접힘"))
        handle.performTouchInput { swipeUp(startY = centerY, endY = centerY - 150.dp.toPx(), durationMillis = 1_000) }
        composeRule.waitForIdle()
        handle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "기본"))
    }

    @Test
    fun 장소_카드를_누르면_같은_장소를_지도_이동_대상으로_넘기고_다시_눌러도_다시_넘긴다() {
        var focus: RouteFocus? = null
        composeRule.setContent {
            GilpickTheme {
                DayRouteScreen(
                    state = RouteUiState.Content(readyRoute()),
                    dayNumber = 2,
                    date = LocalDate.of(2026, 5, 21),
                    onBack = {},
                    onRetry = {},
                    onAddPlace = {},
                    onReauthenticate = {},
                    map = { _, _, _, selected, modifier ->
                        focus = selected
                        Box(modifier = modifier.fillMaxSize().testTag(TAG_MAP))
                    },
                )
            }
        }

        composeRule.onNodeWithTag("${TAG_MARKER_PREFIX}2").performClick()
        composeRule.runOnIdle { assertEquals(ITEM_B, (focus as? RouteFocus.Place)?.itemId) }
        composeRule.onNodeWithTag("${TAG_MARKER_PREFIX}3").performClick()
        composeRule.runOnIdle { assertEquals(ITEM_C, (focus as? RouteFocus.Place)?.itemId) }

        // 같은 카드를 다시 눌러도(직접 지도를 옮긴 뒤) 값이 달라져 지도가 다시 이동한다.
        val before = focus
        composeRule.onNodeWithTag("${TAG_MARKER_PREFIX}3").performClick()
        composeRule.runOnIdle {
            assertEquals(ITEM_C, (focus as? RouteFocus.Place)?.itemId)
            assertTrue("before=$before after=$focus", focus != before)
        }
    }

    @Test
    fun 내_위치_버튼은_48dp_설명을_갖고_권한이_있으면_지도를_현재_위치로_보낸다() {
        var focus: RouteFocus? = null
        composeRule.setContent {
            GilpickTheme {
                DayRouteScreen(
                    state = RouteUiState.Content(readyRoute()),
                    dayNumber = 2,
                    date = LocalDate.of(2026, 5, 21),
                    onBack = {},
                    onRetry = {},
                    onAddPlace = {},
                    onReauthenticate = {},
                    // 실제 권한을 바꾸면 instrumentation process가 죽으므로 권한 판단만 바꿔 끼운다.
                    hasLocationPermission = { true },
                    currentLocation = { listOf(127.0, 37.5) },
                    map = { _, _, _, selected, modifier ->
                        focus = selected
                        Box(modifier = modifier.fillMaxSize().testTag(TAG_MAP))
                    },
                )
            }
        }

        composeRule.onNodeWithTag(TAG_MY_LOCATION)
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("내 위치로 이동")))
        // 안내 문구는 옮기지 못했을 때만 나온다.
        composeRule.onNodeWithTag(TAG_MY_LOCATION_NOTICE).assertDoesNotExist()

        composeRule.onNodeWithTag(TAG_MY_LOCATION).performClick()
        composeRule.runOnIdle {
            val target = focus as? RouteFocus.MyLocation
            assertEquals(listOf(127.0, 37.5), target?.position)
        }

        // 지도를 직접 움직인 뒤 다시 눌러도 새 이동 요청이 간다.
        val before = focus
        composeRule.onNodeWithTag(TAG_MY_LOCATION).performClick()
        composeRule.runOnIdle { assertTrue("before=$before after=$focus", focus is RouteFocus.MyLocation && focus != before) }
    }

    @Test
    fun 위치를_얻지_못하면_지도를_옮기지_않고_이유를_알린다() {
        var focus: RouteFocus? = null
        composeRule.setContent {
            GilpickTheme {
                DayRouteScreen(
                    state = RouteUiState.Content(readyRoute()),
                    dayNumber = 2,
                    date = LocalDate.of(2026, 5, 21),
                    onBack = {},
                    onRetry = {},
                    onAddPlace = {},
                    onReauthenticate = {},
                    hasLocationPermission = { true },
                    // 위치 서비스가 꺼져 있거나 신호가 없는 기기.
                    currentLocation = { null },
                    map = { _, _, _, selected, modifier ->
                        focus = selected
                        Box(modifier = modifier.fillMaxSize().testTag(TAG_MAP))
                    },
                )
            }
        }

        composeRule.onNodeWithTag(TAG_MY_LOCATION).performClick()
        composeRule.runOnIdle { assertNull("focus=$focus", focus) }
        composeRule.onNodeWithTag(TAG_MY_LOCATION_NOTICE).assertIsDisplayed()
        composeRule.onNodeWithText("현재 위치를 확인할 수 없어요. 위치 서비스를 켜고 다시 시도해 주세요").assertIsDisplayed()
    }

    @Test
    fun 내_위치_버튼_권한_거부_안내는_다음_행동을_알린다() {
        composeRule.setContent { GilpickTheme { MyLocationButton(notice = R.string.route_my_location_denied, onClick = {}) } }

        composeRule.onNodeWithTag(TAG_MY_LOCATION_NOTICE).assertIsDisplayed()
        composeRule.onNodeWithText("위치 권한을 허용하면 현재 위치를 볼 수 있어요").assertIsDisplayed()
    }

    @Test
    fun 내_위치_버튼은_경로_정보_sheet와_겹치지_않는다() {
        setScreen(RouteUiState.Content(readyRoute()))

        val button = composeRule.onNodeWithTag(TAG_MY_LOCATION).assertIsDisplayed().getBoundsInRoot()
        val sheet = composeRule.onNodeWithTag(TAG_SHEET).getBoundsInRoot()
        assertTrue("button=$button sheet=$sheet", button.bottom <= sheet.top)
    }

    @Test
    fun 장소_카드는_긴_이름이_줄바꿈돼도_같은_행에서_높이가_같고_48dp_버튼이다() {
        val longNames = readyRoute().copy(
            markers = listOf(
                RouteMarkerDto(ITEM_A, 1, "경복궁", 37.5796, 126.977),
                RouteMarkerDto(ITEM_B, 2, "북촌한옥마을", 37.5826, 126.9831),
                RouteMarkerDto(ITEM_C, 3, "대학로자유극장 소극장 무대", 37.5744, 126.9857),
            ),
            segments = emptyList(),
        )
        composeRule.setContent {
            GilpickTheme { Box(modifier = Modifier.width(360.dp)) { Screen(RouteUiState.Content(longNames)) } }
        }

        fun card(sequence: Int) = composeRule.onNodeWithTag("$TAG_MARKER_PREFIX$sequence").getBoundsInRoot()
        val heights = (1..3).map { card(it).let { bounds -> bounds.bottom - bounds.top } }
        // 세 번째 이름은 카드 폭(약 112dp)에서 두 줄 이상이 된다. 그래도 세 카드 높이와 하단이 같아야 한다.
        assertTrue("heights=$heights", heights.all { it == heights[0] })
        assertTrue("bottoms=${(1..3).map { card(it).bottom }}", (1..3).all { card(it).bottom == card(1).bottom })

        composeRule.onNodeWithTag("${TAG_MARKER_PREFIX}1")
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
        composeRule.onNodeWithTag("${TAG_MARKER_PREFIX}1")
            .assert(SemanticsMatcher("지도에서 보기 동작 설명") { node -> node.config[SemanticsActions.OnClick].label == "지도에서 보기" })
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
            map = { _, _, _, _, modifier -> Box(modifier = modifier.fillMaxSize().testTag(TAG_MAP)) },
        )
    }
}
