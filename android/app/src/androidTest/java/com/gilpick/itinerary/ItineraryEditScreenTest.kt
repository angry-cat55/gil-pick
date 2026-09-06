package com.gilpick.itinerary

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.place.PlaceCategory
import com.gilpick.ui.theme.GilpickTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T013·T017·T020: 편집 화면의 네 상태·상호작용·편집 조작·접근성 검증.
 *
 * `spec.md` UI-001(구성), UI-002(행·처리 상태), UI-003(체류 시간 대화상자), UI-004(이동 수단 시트),
 * UI-005(취소 확인), UI-007(네 상태·저장 중 비활성), UI-008(손잡이·버튼), UI-009(48dp·아이콘 설명),
 * FR-017(처리된 항목 잠금), FR-018(도착 시각 미표시), FR-021(10곳 비활성)이 대상이다. 상태 전이는
 * `ItineraryEditViewModelTest`가 다루므로 여기서는 상태를 직접 넣고 화면이 무엇을 보여 주고
 * 무엇을 호출하는지만 본다.
 */
@RunWith(AndroidJUnit4::class)
class ItineraryEditScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loading은_1초_전에는_표시하지_않고_이후_대기_표시를_띄운다() {
        composeRule.mainClock.autoAdvance = false
        setScreen(state(phase = ItineraryEditPhase.Loading))

        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithContentDescription("일정을 불러오는 중").assertIsNotDisplayed()

        composeRule.mainClock.advanceTimeBy(700)
        composeRule.onNodeWithContentDescription("일정을 불러오는 중").assertIsDisplayed()
    }

    @Test
    fun empty는_안내와_장소_추가를_보여준다() {
        var added = 0
        setScreen(state(), onAddPlace = { added++ })

        composeRule.onNodeWithText("일정 편집").assertIsDisplayed()
        composeRule.onNodeWithText("9월 8일 방문 장소").assertIsDisplayed()
        composeRule.onNodeWithText("1일차 · 0곳").assertIsDisplayed()
        composeRule.onNodeWithText("아직 방문 장소가 없어요").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("장소 추가").performClick()
        composeRule.runOnIdle { assertEquals(1, added) }
    }

    @Test
    fun error는_원인과_다시_시도를_보여준다() {
        var retries = 0
        setScreen(state(phase = ItineraryEditPhase.Failed(ItineraryError.Network)), onRetry = { retries++ })

        composeRule.onNodeWithText("연결을 확인한 뒤 다시 시도해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithText("다시 시도").performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun 세션_만료는_다시_로그인으로_잇는다() {
        var reauth = 0
        setScreen(state(phase = ItineraryEditPhase.Failed(ItineraryError.SessionExpired)), onReauthenticate = { reauth++ })

        composeRule.onNodeWithText("다시 로그인").performClick()
        composeRule.runOnIdle { assertEquals(1, reauth) }
    }

    @Test
    fun content는_순서_장소명_체류_시간_이동_수단을_보여주고_도착_시각은_없다() {
        setScreen(
            state(
                draft = listOf(
                    draft("경복궁", stayMinutes = 90, transportToNext = TransportMode.WALK),
                    draft("북촌한옥마을", stayMinutes = 60),
                ),
            ),
        )

        composeRule.onNodeWithText("1일차 · 2곳").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("1번째").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("2번째").assertIsDisplayed()
        composeRule.onNodeWithText("경복궁").assertIsDisplayed()
        composeRule.onNodeWithText("90분").assertIsDisplayed()
        composeRule.onNodeWithText("도보").assertIsDisplayed()
        composeRule.onNodeWithText("북촌한옥마을").assertIsDisplayed()
        composeRule.onNodeWithText("60분").assertIsDisplayed()
        // 경로가 계산되기 전이라 `09:00` 같은 도착 시각을 지어내지 않는다(FR-018).
        composeRule.onNodeWithText("09:00", substring = true).assertDoesNotExist()
    }

    @Test
    fun 날짜_탭은_선택_상태를_알리고_누르면_그_날짜를_고른다() {
        var selected: LocalDate? = null
        setScreen(state(), onSelectDate = { selected = it })

        composeRule.onNodeWithContentDescription("8일 화").assertIsSelected()
        composeRule.onNodeWithContentDescription("9일 수").performClick()
        composeRule.runOnIdle { assertEquals(LocalDate.of(2026, 9, 9), selected) }
    }

    @Test
    fun 저장_중에는_저장이_비활성화되고_진행_중임을_알린다() {
        var saves = 0
        setScreen(state(saving = true), onSave = { saves++ })

        composeRule.onNodeWithContentDescription("저장 중").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("저장").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, saves) }
    }

    @Test
    fun 저장_실패는_초안_위에_원인을_보여주고_저장이_재시도다() {
        var saves = 0
        setScreen(state(draft = listOf(draft("경복궁")), saveError = ItineraryError.Network), onSave = { saves++ })

        composeRule.onNodeWithText("저장하지 못했어요. 연결을 확인한 뒤 다시 시도해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithText("경복궁").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("저장").performClick()
        composeRule.runOnIdle { assertEquals(1, saves) }
    }

    @Test
    fun 닫기는_확인_없이_ViewModel에_맡긴다() {
        var closes = 0
        setScreen(state(), onClose = { closes++ })

        composeRule.onNodeWithContentDescription("닫기").performClick()
        composeRule.runOnIdle { assertEquals(1, closes) }
        composeRule.onNodeWithText("편집을 취소할까요?").assertDoesNotExist()
    }

    @Test
    fun 취소_확인은_계속_편집과_취소하고_나가기를_호출한다() {
        var keeps = 0
        var discards = 0
        setScreen(state(dialog = EditDialog.Discard(null)), onDismissDialog = { keeps++ }, onConfirmDiscard = { discards++ })
        composeRule.onNodeWithText("편집을 취소할까요?").assertIsDisplayed()
        composeRule.onNodeWithText("저장하지 않은 변경 사항은 사라집니다").assertIsDisplayed()
        composeRule.onNodeWithText("계속 편집").performClick()
        composeRule.onNodeWithText("취소하고 나가기").performClick()
        composeRule.runOnIdle {
            assertEquals(1, keeps)
            assertEquals(1, discards)
        }
    }

    @Test
    fun 열_곳이면_장소_추가가_비활성화되고_상한을_안내한다() {
        setScreen(state(draft = (1..10).map { draft("장소 $it") }))

        composeRule.onNodeWithContentDescription("장소 추가").assertIsNotEnabled()
        // 10곳이면 버튼과 안내가 화면 아래로 밀려나므로 스크롤해서 확인한다.
        composeRule.onNodeWithText("하루 최대 10곳").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun 추가_거부_안내는_snackbar로_보여주고_닫힘을_알린다() {
        var shown = false
        setScreen(state(notice = EditNotice.NO_COORDINATES), onNoticeShown = { shown = true })

        composeRule.onNodeWithText("위치 정보가 없는 장소는 일정에 추가할 수 없어요").assertIsDisplayed()
        composeRule.waitUntil(6_000) { shown }
        assertTrue(shown)
    }

    @Test
    fun 터치_영역은_48dp_이상이고_아이콘_버튼에_설명이_있다() {
        setScreen(state(draft = listOf(draft("경복궁"))))

        composeRule.onNodeWithContentDescription("닫기").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("8일 화").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("장소 추가").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("저장").assertHeightIsAtLeast(48.dp)
    }

    // --- US2 편집 조작(T020·T022·T023): UI-002·003·004·008·009 ---

    @Test
    fun 행은_손잡이_이동_삭제_변경을_제공하고_첫_행_위와_마지막_행_아래는_비활성이다() {
        val calls = mutableListOf<String>()
        setScreen(
            state(draft = listOf(draft("경복궁", transportToNext = TransportMode.WALK), draft("북촌한옥마을"))),
            onEditStay = { calls += "stay:$it" },
            onChangeTransport = { calls += "transport:$it" },
            onRemove = { calls += "remove:$it" },
            onMove = { from, to -> calls += "move:$from>$to" },
        )

        composeRule.onNodeWithContentDescription("경복궁 순서 변경 손잡이").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("경복궁 위로 이동").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("북촌한옥마을 아래로 이동").assertIsNotEnabled()
        // 마지막 항목에는 다음 구간이 없으므로 `변경`도 없다(FR-006).
        composeRule.onNodeWithContentDescription("북촌한옥마을 다음 이동 수단 변경").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("경복궁 아래로 이동").performClick()
        composeRule.onNodeWithContentDescription("북촌한옥마을 위로 이동").performClick()
        composeRule.onNodeWithContentDescription("경복궁 삭제").performClick()
        composeRule.onNodeWithContentDescription("경복궁 체류 시간 변경").performClick()
        composeRule.onNodeWithContentDescription("경복궁 다음 이동 수단 변경").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("move:0>1", "move:1>0", "remove:0", "stay:0", "transport:0"), calls)
        }
    }

    @Test
    fun 처리된_항목은_상태를_색_아이콘_문구로_보이고_체류_시간만_바꿀_수_있다() {
        var stays = 0
        setScreen(
            state(
                draft = listOf(
                    draft("경복궁", transportToNext = TransportMode.WALK, status = ItemStatus.COMPLETED),
                    draft("북촌한옥마을", transportToNext = TransportMode.CAR, status = ItemStatus.SKIPPED),
                    draft("창덕궁"),
                ),
            ),
            onEditStay = { stays++ },
        )

        composeRule.onNodeWithText("완료").assertIsDisplayed()
        composeRule.onNodeWithText("건너뜀").assertIsDisplayed()
        composeRule.onNodeWithText("도보").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("경복궁 순서 변경 손잡이").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("경복궁 위로 이동").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("경복궁 아래로 이동").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("경복궁 삭제").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("경복궁 다음 이동 수단 변경").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("북촌한옥마을 삭제").assertDoesNotExist()
        // 처리된 항목 바로 아래의 예정 항목은 위로 옮길 수 없다(FR-017).
        composeRule.onNodeWithContentDescription("창덕궁 위로 이동").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("창덕궁 삭제").assertIsEnabled()

        composeRule.onNodeWithContentDescription("경복궁 체류 시간 변경").performClick()
        composeRule.runOnIdle { assertEquals(1, stays) }
    }

    @Test
    fun 체류_시간_대화상자는_30분_단위로_30에서_360까지만_조절하고_빠른_선택과_적용을_제공한다() {
        var applied: Int? = null
        var dismissed = 0
        setScreen(
            state(draft = listOf(draft("경복궁", stayMinutes = 150)), dialog = EditDialog.StayTime(0)),
            onApplyStay = { applied = it },
            onDismissDialog = { dismissed++ },
        )

        composeRule.onNodeWithText("경복궁 체류 시간").assertIsDisplayed()
        composeRule.onNodeWithText("150").assertIsDisplayed()
        composeRule.onNodeWithText("120분").assertIsNotSelected()

        repeat(5) { composeRule.onNodeWithContentDescription("체류 시간 30분 줄이기").performClick() }
        composeRule.onNodeWithText("30").assertIsDisplayed()
        composeRule.onNodeWithText("0").assertDoesNotExist()

        composeRule.onNodeWithText("120분").performClick()
        composeRule.onNodeWithText("120").assertIsDisplayed()
        composeRule.onNodeWithText("120분").assertIsSelected()
        composeRule.onNodeWithText("90분").assertIsNotSelected()

        repeat(9) { composeRule.onNodeWithContentDescription("체류 시간 30분 늘리기").performClick() }
        composeRule.onNodeWithText("360").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("체류 시간 30분 늘리기").performClick()
        composeRule.onNodeWithText("360").assertIsDisplayed()
        composeRule.onNodeWithText("390").assertDoesNotExist()

        composeRule.onNodeWithText("적용").performClick()
        composeRule.runOnIdle { assertEquals(360, applied) }
        composeRule.onNodeWithText("취소").performClick()
        composeRule.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test
    fun 이동_수단_시트는_다음_장소를_안내하고_체류_시간_조절_없이_선택을_적용한다() {
        var applied: TransportMode? = null
        setScreen(
            state(
                draft = listOf(draft("경복궁", transportToNext = TransportMode.WALK), draft("북촌한옥마을")),
                dialog = EditDialog.Transport(0),
            ),
            onApplyTransport = { applied = it },
        )

        composeRule.onNodeWithText("이동 수단 변경").assertIsDisplayed()
        composeRule.onNodeWithText("북촌한옥마을까지 어떻게 이동하시겠어요?").assertIsDisplayed()
        composeRule.onNodeWithText("체류 시간").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("체류 시간 30분 늘리기").assertDoesNotExist()
        composeRule.onNodeWithText("대중교통").assertIsNotSelected()
        composeRule.onNodeWithText("자동차").performClick()
        composeRule.onNodeWithText("자동차").assertIsSelected()
        composeRule.onNodeWithText("적용").performClick()
        composeRule.runOnIdle { assertEquals(TransportMode.CAR, applied) }
    }

    @Test
    fun 손잡이를_끌면_버튼_이동과_같은_순서가_된다() {
        var items by mutableStateOf(listOf(draft("경복궁", transportToNext = TransportMode.WALK), draft("북촌한옥마을", transportToNext = TransportMode.CAR), draft("창덕궁")))
        composeRule.setContent {
            GilpickTheme {
                ItineraryEditScreen(
                    state = state(draft = items),
                    onClose = {}, onSelectDate = {}, onAddPlace = {}, onSave = {}, onRetry = {}, onReauthenticate = {},
                    onDismissDialog = {}, onConfirmDiscard = {}, onNoticeShown = {}, onEditStay = {}, onApplyStay = {},
                    onChangeTransport = {}, onApplyTransport = {}, onRemove = {},
                    onMove = { from, to -> items = items.toMutableList().apply { add(to, removeAt(from)) } },
                )
            }
        }
        val first = composeRule.onNodeWithContentDescription("경복궁 순서 변경 손잡이").getBoundsInRoot()
        val second = composeRule.onNodeWithContentDescription("북촌한옥마을 순서 변경 손잡이").getBoundsInRoot()
        val rowHeightPx = with(composeRule.density) { (second.top - first.top).toPx() }

        // 두 행 높이만큼 아래로 끌면 두 칸 내려간다. 이웃 행 높이의 절반을 넘을 때마다 한 칸씩 옮긴다.
        composeRule.onNodeWithContentDescription("경복궁 순서 변경 손잡이").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, rowHeightPx * 2 / 10)) }
            up()
        }
        composeRule.runOnIdle { assertEquals(listOf("북촌한옥마을", "창덕궁", "경복궁"), items.map { it.place.name }) }

        // 위로 한 행만큼 끌면 한 칸 올라간다.
        composeRule.onNodeWithContentDescription("경복궁 순서 변경 손잡이").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, -rowHeightPx / 10)) }
            up()
        }
        composeRule.runOnIdle { assertEquals(listOf("북촌한옥마을", "경복궁", "창덕궁"), items.map { it.place.name }) }
    }

    @Test
    fun 편집_조작의_터치_영역은_48dp_이상이다() {
        setScreen(state(draft = listOf(draft("경복궁", transportToNext = TransportMode.WALK), draft("북촌한옥마을")), dialog = EditDialog.StayTime(0)))

        listOf("경복궁 순서 변경 손잡이", "경복궁 위로 이동", "경복궁 아래로 이동", "경복궁 삭제").forEach {
            composeRule.onNodeWithContentDescription(it).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        composeRule.onNodeWithContentDescription("경복궁 체류 시간 변경").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("경복궁 다음 이동 수단 변경").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("체류 시간 30분 줄이기").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("체류 시간 30분 늘리기").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithText("60분").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("적용").assertHeightIsAtLeast(48.dp)
    }

    private fun setScreen(
        state: ItineraryEditUiState,
        onClose: () -> Unit = {},
        onSelectDate: (LocalDate) -> Unit = {},
        onAddPlace: () -> Unit = {},
        onSave: () -> Unit = {},
        onRetry: () -> Unit = {},
        onReauthenticate: () -> Unit = {},
        onDismissDialog: () -> Unit = {},
        onConfirmDiscard: () -> Unit = {},
        onNoticeShown: () -> Unit = {},
        onEditStay: (Int) -> Unit = {},
        onApplyStay: (Int) -> Unit = {},
        onChangeTransport: (Int) -> Unit = {},
        onApplyTransport: (TransportMode) -> Unit = {},
        onRemove: (Int) -> Unit = {},
        onMove: (Int, Int) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            GilpickTheme {
                ItineraryEditScreen(
                    state = state,
                    onClose = onClose,
                    onSelectDate = onSelectDate,
                    onAddPlace = onAddPlace,
                    onSave = onSave,
                    onRetry = onRetry,
                    onReauthenticate = onReauthenticate,
                    onDismissDialog = onDismissDialog,
                    onConfirmDiscard = onConfirmDiscard,
                    onNoticeShown = onNoticeShown,
                    onEditStay = onEditStay,
                    onApplyStay = onApplyStay,
                    onChangeTransport = onChangeTransport,
                    onApplyTransport = onApplyTransport,
                    onRemove = onRemove,
                    onMove = onMove,
                )
            }
        }
    }

    private fun state(
        phase: ItineraryEditPhase = ItineraryEditPhase.Content,
        draft: List<DraftItem> = emptyList(),
        saving: Boolean = false,
        saveError: ItineraryError? = null,
        dialog: EditDialog? = null,
        notice: EditNotice? = null,
    ) = ItineraryEditUiState(
        tripId = "trip-1",
        days = listOf(DayTab(LocalDate.of(2026, 9, 8), 1), DayTab(LocalDate.of(2026, 9, 9), 2)),
        selectedDate = LocalDate.of(2026, 9, 8),
        draft = draft,
        dirty = draft.isNotEmpty(),
        phase = phase,
        saving = saving,
        saveError = saveError,
        dialog = dialog,
        notice = notice,
    )

    private fun draft(
        name: String,
        stayMinutes: Int = 90,
        transportToNext: TransportMode? = null,
        status: ItemStatus = ItemStatus.PLANNED,
    ) = DraftItem(
        itemId = null,
        placeId = "tourapi:$name",
        place = PlaceSnapshotDto(
            name = name,
            category = PlaceCategory.HISTORY_CULTURE,
            tourApiCategory = null,
            address = null,
            latitude = 37.58,
            longitude = 126.98,
            imageUrl = null,
        ),
        stayMinutes = stayMinutes,
        staySource = StaySource.RECOMMENDED,
        transportToNext = transportToNext,
        status = status,
    )
}
