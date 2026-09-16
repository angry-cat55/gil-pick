package com.gilpick.progress

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.itinerary.DayItineraryDto
import com.gilpick.itinerary.RouteStatus
import com.gilpick.ui.component.TAG_HEADER_BACK
import com.gilpick.alternative.DetectionListItemDto
import com.gilpick.alternative.DetectionStatus
import com.gilpick.alternative.DetectionType
import com.gilpick.itinerary.ItemStatus
import com.gilpick.route.ITEM_A
import com.gilpick.route.ITEM_B
import com.gilpick.route.ITEM_C
import com.gilpick.ui.theme.GilpickTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.absoluteValue

/**
 * T020·T025·T033·T036: 진행 화면의 네 상태·헤더·다음 장소 카드 세 모양·지도 자리·일정 목록·`장소 추가`·접근성 기본
 * 케이스, 전환 행동(요청 중 비활성·진행 표시, 실패 안내와 `다시 시도`, 마지막 장소의 출발 행동 부재), 상태 수정
 * 시트(UI-003: 상태별 행동만, 오늘 아님·시작 전에는 없음), 다른 날짜 조회(UI-005: 카드·행동·시트 부재, 안내와
 * `오늘로 돌아가기`).
 *
 * `spec.md` UI-001(구성), UI-002(카드·`N분 지났어요`), UI-004(상태 문구+아이콘, 실제 시각/ETA/`정보 없음`),
 * UI-006(당일 완료), UI-008(네 상태·요청 중 유지), UI-009(48dp), UI-011(지도 자리)이 대상이다. 상태 전이는
 * `ProgressViewModelTest`가 다루므로 여기서는 상태를 직접 넣는다. 지도는 SDK 인증이 필요해 자리 표시로
 * 바꿔 끼운다.
 */
@RunWith(AndroidJUnit4::class)
class ActiveTravelScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loading은_1초_전에는_표시하지_않고_이후_대기_표시를_띄운다() {
        composeRule.mainClock.autoAdvance = false
        setScreen(ProgressUiState.Loading)

        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithContentDescription("진행 현황을 불러오는 중").assertIsNotDisplayed()

        composeRule.mainClock.advanceTimeBy(700)
        composeRule.onNodeWithContentDescription("진행 현황을 불러오는 중").assertIsDisplayed()
        // 여행명은 조회 전에도 보인다.
        composeRule.onNodeWithText("서울 여행").assertIsDisplayed()
    }

    @Test
    fun empty는_안내와_장소_추가를_보여준다() {
        var added = 0
        setScreen(ProgressUiState.Empty, onAddPlace = { added++ })

        composeRule.onNodeWithText("오늘 일정에 장소가 없어요").assertIsDisplayed()
        composeRule.onNodeWithText("장소 추가").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, added) }
    }

    @Test
    fun error는_원인과_다시_시도를_보여준다() {
        var retries = 0
        setScreen(ProgressUiState.Error(ProgressError.Network), onRetry = { retries++ })

        composeRule.onNodeWithText("진행 현황을 보여줄 수 없어요").assertIsDisplayed()
        composeRule.onNodeWithText("연결을 확인한 뒤 다시 시도해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithText("다시 시도").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun 세션_만료는_다시_로그인을_보여준다() {
        var reauth = 0
        setScreen(ProgressUiState.Error(ProgressError.SessionExpired), onReauthenticate = { reauth++ })
        composeRule.onNodeWithText("다시 로그인").performClick()
        composeRule.runOnIdle { assertEquals(1, reauth) }
    }

    @Test
    fun 이동_중_content는_헤더와_다음_장소_카드와_지도와_목록과_장소_추가를_보여준다() {
        var arrives = 0
        var skips = 0
        var routes = mutableListOf<Pair<String, Int>>()
        var added = 0
        setScreen(content(), onArrive = { arrives++ }, onSkip = { skips++ }, onOpenRoute = { d, n -> routes += d to n }, onAddPlace = { added++ })

        // 헤더: `여행 중`, `N일차 · x/y 완료`, 여행명, 날짜 진행 표시.
        composeRule.onNodeWithText("여행 중").assertIsDisplayed()
        composeRule.onNodeWithText("2일차 · 1/3 완료").assertIsDisplayed()
        composeRule.onNodeWithText("서울 여행").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("1일차 9월 7일").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("2일차 9월 8일").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("3일차 9월 9일").assertIsDisplayed()

        // 다음 장소 카드: 장소명, 예상 도착, 남은 시간, 이전 장소에서의 이동, 두 행동(48dp).
        composeRule.onNodeWithTag(TAG_CARD_NEXT).assertIsDisplayed()
        composeRule.onNodeWithText("다음 장소").assertIsDisplayed()
        cardText(TAG_CARD_NEXT, "북촌한옥마을").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_ETA).assertTextEquals("오후 2:20")
        composeRule.onNodeWithText("· 12분 남았어요").assertIsDisplayed()
        composeRule.onNodeWithText("경복궁에서 대중교통 20분 · 3.4km").assertIsDisplayed()
        composeRule.onNodeWithText("도착했어요").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithText("건너뛰기").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle {
            assertEquals(1, arrives)
            assertEquals(1, skips)
        }

        // 지도 자리와 `경로 보기`.
        composeRule.onNodeWithTag(TAG_MAP_SLOT).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_FAKE_MAP).assertIsDisplayed()
        composeRule.onNodeWithText("경로 보기").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(listOf(PROGRESS_DATE to 2), routes) }

        // 일정 목록: 상태 문구, 처리된 장소는 실제 시각, 남은 장소는 ETA 또는 `정보 없음`, 순서 유지.
        composeRule.onNodeWithText("2일차 일정").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("9월 8일 · 3곳").assertIsDisplayed()
        val first = composeRule.onNodeWithContentDescription("1번째 장소 경복궁, 완료, 오후 2:00 방문 완료").performScrollTo().assertIsDisplayed()
        val second = composeRule.onNodeWithContentDescription("2번째 장소 북촌한옥마을, 이동 중, 오후 2:20 도착 예정").performScrollTo().assertIsDisplayed()
        val third = composeRule.onNodeWithContentDescription("3번째 장소 인사동거리, 예정, 정보 없음").performScrollTo().assertIsDisplayed()
        assertTrue(first.getBoundsInRoot().top < second.getBoundsInRoot().top)
        assertTrue(second.getBoundsInRoot().top < third.getBoundsInRoot().top)
        // 다음 장소로의 이동수단 문구(아이콘 병기).
        composeRule.onNodeWithText("대중교통 20분").assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_ADD_PLACE).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, added) }
    }

    @Test
    fun 도착_예정_시각이_지나면_N분_지났어요를_보이고_시각은_그대로다() {
        setScreen(content(now = NOW_AFTER_ETA))

        composeRule.onNodeWithTag(TAG_ETA).assertTextEquals("오후 2:20")
        composeRule.onNodeWithText("· 5분 지났어요").assertIsDisplayed()
        composeRule.onNodeWithText("· 12분 남았어요").assertDoesNotExist()
    }

    @Test
    fun ETA와_이동_정보가_없으면_정보_없음이다() {
        setScreen(content(progress = movingWithoutEtaProgress()))

        composeRule.onNodeWithTag(TAG_ETA).assertIsDisplayed().assertTextEquals("정보 없음")
        composeRule.onNodeWithTag(TAG_REMAINING).assertDoesNotExist()
        composeRule.onNodeWithText("이동 정보 없음").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("2번째 장소 북촌한옥마을, 이동 중, 정보 없음").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun 도착_content는_현재_장소_카드와_다음_장소로_출발만_보여준다() {
        var departs = 0
        setScreen(content(progress = arrivedProgress()), onDepart = { departs++ })

        composeRule.onNodeWithTag(TAG_CARD_ARRIVED).assertIsDisplayed()
        composeRule.onNodeWithText("현재 장소").assertIsDisplayed()
        cardText(TAG_CARD_ARRIVED, "북촌한옥마을").assertIsDisplayed()
        cardText(TAG_CARD_ARRIVED, "오후 2:18 도착").assertIsDisplayed()
        composeRule.onNodeWithText("체류 예정 90분").assertIsDisplayed()
        composeRule.onNodeWithText("다음 장소로 출발").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithText("도착했어요").assertDoesNotExist()
        composeRule.onNodeWithText("건너뛰기").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, departs) }

        // #591: 도착(출발 전) 장소는 완료로 세지 않는다.
        composeRule.onNodeWithText("2일차 · 1/3 완료").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("2번째 장소 북촌한옥마을, 도착, 오후 2:18 도착").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("3번째 장소 인사동거리, 예정, 오후 4:00 도착 예정").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun 당일_완료_content는_완료_표시만_보이고_출발_행동이_없다() {
        setScreen(content(progress = allDoneProgress()))

        composeRule.onNodeWithTag(TAG_CARD_ALL_DONE).assertIsDisplayed()
        composeRule.onNodeWithText("오늘 일정을 모두 마쳤어요").assertIsDisplayed()
        composeRule.onNodeWithText("2곳 방문 · 마지막 도착 오후 3:30").assertIsDisplayed()
        composeRule.onNodeWithText("다음 장소로 출발").assertDoesNotExist()
        composeRule.onNodeWithText("도착했어요").assertDoesNotExist()
        // #591: 건너뛴 장소는 분모에서 빼고, 당일 완료면 마지막 도착 장소도 방문으로 센다. 카드의 `2곳 방문`과 같다.
        composeRule.onNodeWithText("2일차 · 2/2 완료").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("2번째 장소 북촌한옥마을, 건너뜀, 건너뜀").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun 시작_전_날짜는_카드_없이_목록만_보인다() {
        setScreen(content(progress = notStartedProgress()))

        composeRule.onNodeWithTag(TAG_CARD_NEXT).assertDoesNotExist()
        composeRule.onNodeWithTag(TAG_CARD_ARRIVED).assertDoesNotExist()
        composeRule.onNodeWithText("2일차 · 0/3 완료").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("1번째 장소 경복궁, 예정, 정보 없음").assertIsDisplayed()
    }

    @Test
    fun 경로가_없는_날짜는_지도_대신_안내를_두고_경로_보기는_남긴다() {
        setScreen(content(days = overviewDays(todayItinerary(route = null))))

        composeRule.onNodeWithTag(TAG_FAKE_MAP).assertDoesNotExist()
        composeRule.onNodeWithText("경로를 아직 계산하지 못했어요").assertIsDisplayed()
        composeRule.onNodeWithText("경로 보기").assertIsDisplayed()
    }

    // ---- T025: 전환 ----

    @Test
    fun 요청_중에는_내용을_유지한_채_카드_행동이_비활성이고_요청한_버튼에_진행_표시가_보인다() {
        var arrives = 0
        var skips = 0
        setScreen(
            content(progress = movingProgress()).copy(pendingAction = ProgressAction(ITEM_B, ItemStatus.ARRIVED)),
            onArrive = { arrives++ },
            onSkip = { skips++ },
        )

        composeRule.onNodeWithTag(TAG_CARD_NEXT).assertIsDisplayed()
        cardText(TAG_CARD_NEXT, "북촌한옥마을").assertIsDisplayed()
        composeRule.onNodeWithText("도착했어요").assertIsNotEnabled()
        composeRule.onNodeWithText("건너뛰기").assertIsNotEnabled()
        // 요청한 버튼(gradient)은 처리 중 설명을 갖고, spinner는 장식이다(공통 GradientButton).
        composeRule.onNodeWithContentDescription("처리 중").assertIsDisplayed()
        composeRule.onNodeWithText("도착했어요").performClick()
        composeRule.onNodeWithText("건너뛰기").performClick()
        composeRule.runOnIdle {
            assertEquals(0, arrives)
            assertEquals(0, skips)
        }
    }

    @Test
    fun 도착_카드도_요청_중에는_출발이_비활성이다() {
        var departs = 0
        setScreen(content(progress = arrivedProgress()).copy(pendingAction = ProgressAction(ITEM_B, ItemStatus.COMPLETED)), onDepart = { departs++ })

        composeRule.onNodeWithText("다음 장소로 출발").assertIsNotEnabled().performClick()
        composeRule.onNodeWithContentDescription("처리 중").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, departs) }
    }

    @Test
    fun 전환_실패는_내용을_유지한_채_원인과_다시_시도와_닫기를_보여준다() {
        var retries = 0
        var dismissed = 0
        setScreen(
            content().copy(actionError = ProgressActionFailure(ProgressAction(ITEM_B, ItemStatus.ARRIVED), ProgressError.Network)),
            onRetryAction = { retries++ },
            onDismissActionError = { dismissed++ },
        )

        composeRule.onNodeWithTag(TAG_CARD_NEXT).assertIsDisplayed()
        composeRule.onNodeWithText("도착했어요").assertIsEnabled()
        composeRule.onNodeWithTag(TAG_ACTION_ERROR).assertIsDisplayed()
        composeRule.onNodeWithText("연결을 확인한 뒤 다시 시도해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithText("다시 시도").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithText("닫기").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, dismissed)
        }
    }

    @Test
    fun version_충돌_실패는_다시_시도_없이_안내만_보인다() {
        setScreen(content().copy(actionError = ProgressActionFailure(ProgressAction(ITEM_B, ItemStatus.ARRIVED), ProgressError.VersionConflict)))

        composeRule.onNodeWithText("진행 상태가 다른 곳에서 바뀌었어요. 최신 현황을 다시 불러옵니다.").assertIsDisplayed()
        composeRule.onNodeWithText("다시 시도").assertDoesNotExist()
        composeRule.onNodeWithText("닫기").assertIsDisplayed()
    }

    @Test
    fun 남은_장소가_없는_도착_카드에는_출발_행동이_없다() {
        setScreen(content(progress = arrivedProgress().copy(nextItemId = null)))

        composeRule.onNodeWithTag(TAG_CARD_ARRIVED).assertIsDisplayed()
        composeRule.onNodeWithText("다음 장소로 출발").assertDoesNotExist()
    }

    // ---- T033: 상태 수정 시트 ----

    @Test
    fun 장소_행을_누르면_상태별_행동만_있는_시트가_열리고_행동은_목표_상태로_옮겨진다() {
        val actions = mutableListOf<Pair<String, ItemStatus>>()
        setScreen(content(progress = allDoneProgress()), onStatusAction = { id, status -> actions += id to status })

        // 완료: `완료 취소`·`건너뛰기`.
        row(1).performScrollTo().performClick()
        composeRule.onNodeWithTag(TAG_STATUS_SHEET).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("경복궁 상태 수정").assertIsDisplayed()
        composeRule.onNodeWithText("완료 취소").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("건너뛰기").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("도착으로 변경").assertDoesNotExist()
        composeRule.onNodeWithText("건너뛰기 취소").assertDoesNotExist()
        composeRule.onNodeWithText("완료 취소").performClick()
        composeRule.onNodeWithTag(TAG_STATUS_SHEET).assertDoesNotExist()

        // 건너뜀: `건너뛰기 취소`만.
        row(2).performScrollTo().performClick()
        composeRule.onNodeWithText("건너뛰기 취소").assertIsDisplayed()
        composeRule.onNodeWithText("건너뛰기").assertDoesNotExist()
        composeRule.onNodeWithText("건너뛰기 취소").performClick()

        // 도착: `건너뛰기`만. 완료된 날짜에서도 시트가 열린다(UI-006).
        row(3).performScrollTo().performClick()
        composeRule.onNodeWithText("건너뛰기").assertIsDisplayed()
        composeRule.onNodeWithText("완료 취소").assertDoesNotExist()
        composeRule.onNodeWithText("건너뛰기").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(ITEM_A to ItemStatus.ARRIVED, ITEM_B to ItemStatus.PLANNED, ITEM_C to ItemStatus.SKIPPED), actions)
        }
    }

    @Test
    fun 예정_이동_중_행은_도착으로_변경과_건너뛰기를_보이고_취소하면_닫힌다() {
        val actions = mutableListOf<Pair<String, ItemStatus>>()
        setScreen(content(), onStatusAction = { id, status -> actions += id to status })

        row(3).performScrollTo().performClick()
        composeRule.onNodeWithText("도착으로 변경").assertIsDisplayed()
        composeRule.onNodeWithText("취소").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithTag(TAG_STATUS_SHEET).assertDoesNotExist()

        row(2).performScrollTo().performClick()
        composeRule.onNodeWithText("도착으로 변경").performClick()
        composeRule.runOnIdle { assertEquals(listOf(ITEM_B to ItemStatus.ARRIVED), actions) }
    }

    @Test
    fun 시작_전_날짜에서는_행을_눌러도_시트가_열리지_않는다() {
        setScreen(content(progress = notStartedProgress()))

        row(1).performClick()
        composeRule.onNodeWithTag(TAG_STATUS_SHEET).assertDoesNotExist()
    }

    // ---- T036: 다른 날짜 조회 ----

    @Test
    fun 다른_날짜를_보면_카드와_행동과_시트가_없고_안내와_오늘로_돌아가기가_있다() {
        var returned = 0
        val selected = mutableListOf<LocalDate>()
        setScreen(
            content(days = threeDays()).copy(viewingDate = LocalDate.parse("2026-09-07")),
            onSelectDate = { selected += it },
            onReturnToToday = { returned++ },
        )

        composeRule.onNodeWithTag(TAG_VIEWING_BANNER).assertIsDisplayed()
        composeRule.onNodeWithText("1일차 · 지난 일정").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_CARD_NEXT).assertDoesNotExist()
        composeRule.onNodeWithText("도착했어요").assertDoesNotExist()
        // 헤더의 오늘 요약은 그대로다.
        composeRule.onNodeWithText("2일차 · 1/3 완료").assertIsDisplayed()
        composeRule.onNodeWithText("1일차 일정").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("1번째 장소 창덕궁, 완료").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(TAG_STATUS_SHEET).assertDoesNotExist()

        composeRule.onNodeWithText("오늘로 돌아가기").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithContentDescription("3일차 9월 9일").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle {
            assertEquals(1, returned)
            assertEquals(listOf(LocalDate.parse("2026-09-09")), selected)
        }
    }

    @Test
    fun 예정_날짜는_예정_일정_안내와_상태_없는_행을_보인다() {
        setScreen(content(days = threeDays()).copy(viewingDate = LocalDate.parse("2026-09-09")))

        composeRule.onNodeWithText("3일차 · 예정 일정").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("1번째 장소 남산타워").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("예정").assertDoesNotExist()
    }

    // ---- #509: 헤더 편집, #554: 뒤로 가기 없음 ----

    @Test
    fun 헤더_편집은_오늘과_예정_날짜에서_보고_있는_날짜로_가고_지난_날짜는_비활성과_사유를_보인다() {
        val edited = mutableListOf<String>()
        var state by mutableStateOf<ProgressUiState>(content(days = threeDays()))
        composeRule.setContent {
            GilpickTheme {
                ActiveTravelScreen(
                    state = state, tripName = "서울 여행", onRetry = {}, onAddPlace = {}, onOpenRoute = { _, _ -> },
                    onReauthenticate = {}, onEdit = { edited += it },
                    map = { _, _, modifier -> FakeMap(modifier) },
                )
            }
        }

        composeRule.onNodeWithTag(TAG_EDIT).assertHeightIsAtLeast(48.dp).assertIsEnabled().performClick()
        composeRule.runOnIdle { state = content(days = threeDays()).copy(viewingDate = LocalDate.parse("2026-09-09")) }
        composeRule.onNodeWithTag(TAG_EDIT).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(listOf("2026-09-08", "2026-09-09"), edited) }

        composeRule.runOnIdle { state = content(days = threeDays()).copy(viewingDate = LocalDate.parse("2026-09-07")) }
        composeRule.onNodeWithTag(TAG_EDIT).assertIsNotEnabled()
        composeRule.onNodeWithText("지난 날짜의 일정은 편집할 수 없어요").assertIsDisplayed()

    }

    @Test
    fun 헤더에_뒤로_가기가_없다() {
        // 하단 `내 여행` 탭으로 나가므로 진입 경로와 관계없이 ←를 두지 않는다(#554).
        setScreen(content())

        composeRule.onNodeWithTag(TAG_HEADER_BACK).assertDoesNotExist()
    }

    // ---- T027: F009 변수 경고 배너(UI-001, quickstart AND 3) ----

    @Test
    fun ACTIVE_감지가_있으면_ETA가_이른_하나만_배너에_보이고_탭하면_그_감지로_대체_장소를_연다() {
        val opened = mutableListOf<String>()
        setScreen(content().copy(activeDetections = listOf(laterDetection(), insadongDetection())), onOpenAlternatives = { opened += it })

        composeRule.onNodeWithTag(TAG_VARIABLE_BANNER).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        // 장소명과 사유는 각자의 줄이다. 붙여 쓰면 좁은 배너에서 사유 중간이 꺾인다(#676).
        composeRule.onNodeWithTag(TAG_VARIABLE_BANNER).assertTextContains("인사동거리")
        composeRule.onNodeWithTag(TAG_VARIABLE_BANNER).assertTextContains("지금 매우 혼잡해요")
        composeRule.onNodeWithText("인사동거리 지금 매우 혼잡해요").assertDoesNotExist()
        composeRule.onNodeWithText("오후 4:00 도착 예정 · 5분 전 감지").assertIsDisplayed()
        composeRule.onNodeWithText("오후 강수 예보").assertDoesNotExist()
        // 배너가 카드를 밀어내지 않는다.
        composeRule.onNodeWithTag(TAG_CARD_NEXT).assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_VARIABLE_BANNER).performClick()
        composeRule.runOnIdle { assertEquals(listOf("det-insadong"), opened) }
    }

    @Test
    fun 당일이_완료됐으면_감지가_있어도_배너가_없다() {
        setScreen(content(progress = allDoneProgress()).copy(activeDetections = listOf(insadongDetection())))

        composeRule.onNodeWithTag(TAG_VARIABLE_BANNER).assertDoesNotExist()
        composeRule.onNodeWithTag(TAG_CARD_ALL_DONE).assertIsDisplayed()
    }

    @Test
    fun 다른_날짜를_보는_동안에는_배너가_없다() {
        setScreen(content(days = threeDays()).copy(viewingDate = LocalDate.parse("2026-09-07"), activeDetections = listOf(insadongDetection())))

        composeRule.onNodeWithTag(TAG_VARIABLE_BANNER).assertDoesNotExist()
        composeRule.onNodeWithText("오늘로 돌아가기").assertIsDisplayed()
    }

    @Test
    fun 감지가_없거나_조회에_실패하면_배너_없이_나머지_진행_화면이_정상이다() {
        setScreen(content())

        composeRule.onNodeWithTag(TAG_VARIABLE_BANNER).assertDoesNotExist()
        composeRule.onNodeWithTag(TAG_CARD_NEXT).assertIsDisplayed()
        composeRule.onNodeWithText("2일차 · 1/3 완료").assertIsDisplayed()
    }

    private fun row(sequence: Int) = composeRule.onNodeWithTag("$TAG_ROW_PREFIX$sequence")

    // ---- #553: 대중교통 상세 단계 ----

    @Test
    fun 이동_중_대중교통_구간은_카드에_수단별_출발_도착_줄을_보인다() {
        setScreen(content(days = transitStepsDays()))

        composeRule.onNodeWithTag(TAG_TRANSIT_STEPS).assertIsDisplayed()
        // #653: 한 줄 = 한 단계. 수단(노선), 출발 → 도착, 소요시간이 같은 줄에 온다.
        listOf(
            "도보: 경복궁 → 경복궁역 · 4분",
            "지하철 3호선: 경복궁역 → 종로3가역 · 5분",
            "지하철 1호선: 종로3가역 → 종각역 · 4분",
            "도보: 종각역 → 북촌한옥마을 · 6분",
        ).forEach { cardText(TAG_CARD_NEXT, it).assertIsDisplayed() }
        // 화살표를 기호로 읽지 않도록 줄마다 문장 설명을 준다. 카드와 일정 목록 두 곳에 같은 줄이 있다.
        composeRule.onAllNodesWithContentDescription("경복궁역에서 종로3가역까지 지하철 3호선 5분").assertCountEquals(2)
        // 합계 문구는 그대로 남는다.
        cardText(TAG_CARD_NEXT, "경복궁에서 대중교통 20분 · 3.4km").assertIsDisplayed()
    }

    @Test
    fun 구간에_상세_단계가_없으면_합계만_보인다() {
        setScreen(content())

        composeRule.onNodeWithText("경복궁에서 대중교통 20분 · 3.4km").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_TRANSIT_STEPS).assertDoesNotExist()
        // #595: 일정 목록에도 단계가 없다.
        composeRule.onNodeWithTag("${TAG_ROW_TRANSIT_STEPS_PREFIX}1").assertDoesNotExist()
    }

    /** #595: 일정 목록의 대중교통 구간 행에도 카드와 같은 문구·순서로 단계를 보인다. */
    @Test
    fun 일정_목록의_대중교통_구간도_같은_수단별_줄을_보인다() {
        setScreen(content(days = transitStepsDays()))

        composeRule.onNodeWithTag("${TAG_ROW_TRANSIT_STEPS_PREFIX}1").performScrollTo().assertIsDisplayed()
        // 카드와 같은 문구·순서다(#653).
        listOf(
            "도보: 경복궁 → 경복궁역 · 4분",
            "지하철 3호선: 경복궁역 → 종로3가역 · 5분",
            "지하철 1호선: 종로3가역 → 종각역 · 4분",
            "도보: 종각역 → 북촌한옥마을 · 6분",
        ).forEach { line ->
            composeRule.onNode(hasText(line) and hasAnyAncestor(hasTestTag("${TAG_ROW_TRANSIT_STEPS_PREFIX}1"))).assertExists()
        }
        // 단계가 없는 구간은 합계만 보인다.
        composeRule.onNodeWithTag("${TAG_ROW_TRANSIT_STEPS_PREFIX}2").assertDoesNotExist()
    }

    /** #595: 이미 도착한 장소로 가는 지난 구간도 숨기지 않고(흐리게) 보인다. */
    @Test
    fun 지난_대중교통_구간도_일정_목록에서_단계를_볼_수_있다() {
        setScreen(content(progress = arrivedProgress(), days = transitStepsDays()))

        composeRule.onNodeWithTag("${TAG_ROW_TRANSIT_STEPS_PREFIX}1").performScrollTo().assertIsDisplayed()
        composeRule.onNode(
            hasText("지하철 1호선: 종로3가역 → 종각역 · 4분") and hasAnyAncestor(hasTestTag("${TAG_ROW_TRANSIT_STEPS_PREFIX}1")),
        ).assertExists()
    }

    /** 카드 안의 문구. 같은 장소명·시각이 아래 목록 행에도 있어 카드로 좁혀 찾는다. */
    private fun cardText(cardTag: String, text: String) =
        composeRule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(cardTag)))

    /** #653: 서버가 정류장 이름을 채우지 않은 단계는 구간 양 끝 장소명으로 메운다. */
    @Test
    fun 정류장_이름이_없으면_구간_양_끝_장소로_채운다() {
        setScreen(content(days = namelessTransitStepsDays()))

        cardText(TAG_CARD_NEXT, "버스 7016: 경복궁 → 북촌한옥마을 · 10분").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("경복궁에서 북촌한옥마을까지 버스 7016 10분").assertCountEquals(2)
    }

    // ---- #652: 날짜 진행바 정렬 ----

    /** 채움 끝이 오늘 점 중심에 맞아야 한다. 여행 일수가 늘수록 예전 비율 계산은 더 크게 어긋났다. */
    @Test
    fun 진행바_채움_끝이_오늘_점_중심에_맞는다() {
        var days by mutableStateOf(daysOf(2, todayIndex = 1))
        setDays { days }

        listOf(2 to 1, 3 to 1, 5 to 2, 7 to 3).forEach { (total, todayIndex) ->
            composeRule.runOnIdle { days = daysOf(total, todayIndex) }
            composeRule.waitForIdle()

            val fill = composeRule.onNodeWithTag(TAG_DAY_PROGRESS_FILL).getBoundsInRoot()
            val dot = composeRule.onNodeWithContentDescription(dotDescription(todayIndex, todayIndex)).getBoundsInRoot()
            val dotCenter = dot.left + (dot.right - dot.left) / 2

            assertTrue("일수 $total: 채움 끝 ${fill.right}, 오늘 점 중심 $dotCenter", (fill.right - dotCenter).value.absoluteValue <= 2f)
        }
    }

    /** 오늘이 여행 기간 밖이면: 시작 전은 채우지 않고, 끝난 뒤는 마지막 점 중심까지 채운다. */
    @Test
    fun 오늘이_여행_기간_밖이면_정해진_규칙대로_채운다() {
        var days by mutableStateOf(daysOf(3, todayIndex = 0, start = "2026-09-20"))
        setDays { days }

        // 시작 전: 채움 폭이 0이다.
        val empty = composeRule.onNodeWithTag(TAG_DAY_PROGRESS_FILL).getBoundsInRoot()
        assertEquals(empty.left, empty.right)

        composeRule.runOnIdle { days = daysOf(3, todayIndex = 0, start = "2026-08-01") }
        composeRule.waitForIdle()

        // 끝난 뒤: 마지막 점 중심까지 채운다.
        val fill = composeRule.onNodeWithTag(TAG_DAY_PROGRESS_FILL).getBoundsInRoot()
        val last = composeRule.onNodeWithContentDescription("3일차 8월 3일").getBoundsInRoot()
        val lastCenter = last.left + (last.right - last.left) / 2
        assertTrue("채움 끝 ${fill.right}, 마지막 점 중심 $lastCenter", (fill.right - lastCenter).value.absoluteValue <= 2f)
    }

    /** #652: 확대/축소 컨트롤(오른쪽 아래)과 겹치지 않게 `경로 보기`는 지도 위쪽에 둔다. */
    @Test
    fun 경로_보기_버튼은_지도_아래쪽_컨트롤_자리를_비운다() {
        setScreen(content())

        val slot = composeRule.onNodeWithTag(TAG_MAP_SLOT).getBoundsInRoot()
        val button = composeRule.onNodeWithText("경로 보기").getBoundsInRoot()
        val slotCenterY = slot.top + (slot.bottom - slot.top) / 2

        assertTrue("버튼이 지도 위쪽 절반에 있어야 한다", button.bottom < slotCenterY)
        composeRule.onNodeWithText("경로 보기").assertHeightIsAtLeast(48.dp)
    }

    /** 날짜만 바꿔 가며 볼 수 있게 개요를 상태로 받는다. `setContent`는 test마다 한 번만 부를 수 있다. */
    private fun setDays(days: () -> List<DayItineraryDto>) {
        composeRule.setContent {
            GilpickTheme {
                ActiveTravelScreen(
                    state = content(days = days()),
                    tripName = "서울 여행",
                    onRetry = {},
                    onAddPlace = {},
                    onOpenRoute = { _, _ -> },
                    onReauthenticate = {},
                    onArrive = {},
                    onSkip = {},
                    onDepart = {},
                    onRetryAction = {},
                    onDismissActionError = {},
                    onStatusAction = { _, _ -> },
                    onSelectDate = {},
                    onReturnToToday = {},
                    onOpenAlternatives = {},
                    map = { _, _, modifier -> FakeMap(modifier) },
                )
            }
        }
    }

    /** 오늘(9/8)을 [todayIndex]에 둔 [total]일 여행 개요. [start]를 주면 오늘이 여행 기간 밖이 된다. */
    private fun daysOf(
        total: Int,
        todayIndex: Int,
        start: String = LocalDate.parse(PROGRESS_DATE).minusDays(todayIndex.toLong()).toString(),
    ) = (0 until total).map { index ->
        DayItineraryDto(
            date = LocalDate.parse(start).plusDays(index.toLong()).toString(),
            dayNumber = index + 1,
            version = 0,
            routeStatus = RouteStatus.NOT_CALCULATED,
            items = emptyList(),
            route = null,
        )
    }

    private fun dotDescription(index: Int, todayIndex: Int): String {
        val date = LocalDate.parse(PROGRESS_DATE).minusDays(todayIndex.toLong()).plusDays(index.toLong())
        return "${index + 1}일차 ${date.monthValue}월 ${date.dayOfMonth}일"
    }

    private fun setScreen(
        state: ProgressUiState,
        onRetry: () -> Unit = {},
        onAddPlace: () -> Unit = {},
        onOpenRoute: (String, Int) -> Unit = { _, _ -> },
        onReauthenticate: () -> Unit = {},
        onArrive: () -> Unit = {},
        onSkip: () -> Unit = {},
        onDepart: () -> Unit = {},
        onRetryAction: () -> Unit = {},
        onDismissActionError: () -> Unit = {},
        onStatusAction: (String, ItemStatus) -> Unit = { _, _ -> },
        onSelectDate: (LocalDate) -> Unit = {},
        onReturnToToday: () -> Unit = {},
        onOpenAlternatives: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            GilpickTheme {
                ActiveTravelScreen(
                    state = state,
                    tripName = "서울 여행",
                    onRetry = onRetry,
                    onAddPlace = onAddPlace,
                    onOpenRoute = onOpenRoute,
                    onReauthenticate = onReauthenticate,
                    onArrive = onArrive,
                    onSkip = onSkip,
                    onDepart = onDepart,
                    onRetryAction = onRetryAction,
                    onDismissActionError = onDismissActionError,
                    onStatusAction = onStatusAction,
                    onSelectDate = onSelectDate,
                    onReturnToToday = onReturnToToday,
                    onOpenAlternatives = onOpenAlternatives,
                    map = { _, _, modifier -> FakeMap(modifier) },
                )
            }
        }
    }

    @Composable
    private fun FakeMap(modifier: Modifier) {
        Box(modifier = modifier.fillMaxSize().testTag(TAG_FAKE_MAP))
    }

    private companion object {
        const val TAG_FAKE_MAP = "fake_map"
    }
}
