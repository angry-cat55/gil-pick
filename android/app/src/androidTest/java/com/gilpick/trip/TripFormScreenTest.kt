package com.gilpick.trip

import androidx.compose.ui.test.onNodeWithTag
import com.gilpick.ui.component.TAG_HEADER_BACK
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.R
import com.gilpick.ui.theme.GilpickTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 여행 생성·수정 화면의 입력·오류·전송 중 표현 검증(T014·T037, #443 Figma 정렬).
 *
 * `docs/design/ui-guidelines.md` 7절 입력창·날짜 선택·버튼 비활성, 9절 상태 표현, 10절 접근성 최저선 가운데 화면
 * 코드가 책임지는 부분을 확인한다.
 */
@RunWith(AndroidJUnit4::class)
class TripFormScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun string(id: Int) = context.getString(id)

    @Test
    fun 초기_화면은_헤더와_이름과_달력과_제출_버튼을_보여준다() {
        setContent(TripFormUiState())

        composeRule.onNodeWithText(string(R.string.trip_form_create_title)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.trip_form_back)).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_HEADER_BACK).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_form_name_label)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_form_calendar_label)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_form_submit)).assertIsDisplayed()
    }

    @Test
    fun 아무것도_입력하지_않은_칸은_오류로_칠하지_않고_버튼_위에_이유를_둔다() {
        setContent(TripFormUiState())

        // 입력창 아래 오류는 없고, 비활성 버튼 위에 이유 한 문장만 있다(7절 D2).
        composeRule.onNodeWithText(string(R.string.trip_form_error_name_short)).assertIsDisplayed()
        composeRule.onNode(hasText(string(R.string.trip_form_submit)) and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun 이름을_쓰기_시작하면_바로_오류를_보여준다() {
        setContent(TripFormUiState(name = "A"))

        composeRule.onNodeWithText(string(R.string.trip_form_error_name_short)).assertIsDisplayed()
    }

    @Test
    fun 입력이_유효하면_제출_버튼이_활성이고_누르면_요청을_올린다() {
        var submitted = 0
        setContent(
            TripFormUiState(name = "서울 여행", startDate = LocalDate.of(2026, 9, 1), endDate = LocalDate.of(2026, 9, 3)),
            onSubmit = { submitted++ },
        )

        composeRule.onNode(hasText(string(R.string.trip_form_submit)) and hasClickAction())
            .assertIsEnabled()
            .assertTouchHeightIsEqualTo(54.dp)
            .performClick()

        assertEquals(1, submitted)
    }

    @Test
    fun 전송_중에는_진행_문구를_보이고_클릭을_막는다() {
        var submitted = 0
        setContent(
            TripFormUiState(name = "서울 여행", startDate = LocalDate.of(2026, 9, 1), endDate = LocalDate.of(2026, 9, 3), submitting = true),
            onSubmit = { submitted++ },
        )

        composeRule.onNode(hasText(string(R.string.trip_form_submitting)) and hasClickAction())
            .assertIsNotEnabled()
            .performClick()
        assertEquals(0, submitted)
    }

    @Test
    fun 전송_실패는_원인과_다음_행동을_함께_알린다() {
        setContent(TripFormUiState(submitError = TripFormSubmitError.NETWORK))

        // #499 커버 카드가 위에 생겨 오류 문구가 첫 화면 아래에 있을 수 있다.
        composeRule.onNodeWithText(string(R.string.trip_form_error_network)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun 이름_입력은_화면_밖으로_전달된다() {
        var typed: String? = null
        setContent(TripFormUiState(), onNameChange = { typed = it })

        composeRule.onNode(hasSetTextAction()).performTextInput("제주")

        assertEquals("제주", typed)
    }

    // --- 인라인 달력(7절 날짜 선택) ---

    @Test
    fun 달력에서_시작일과_종료일을_차례로_고른다() {
        val period = setCalendarContent(start = LocalDate.of(2026, 9, 1), end = LocalDate.of(2026, 9, 1))

        // 종료일까지 고른 상태에서 누르면 새 시작일부터 다시 고른다.
        composeRule.onNodeWithContentDescription("9월 3일").performClick()
        assertEquals(LocalDate.of(2026, 9, 3) to null, period())

        composeRule.onNodeWithContentDescription("9월 5일").performClick()
        assertEquals(LocalDate.of(2026, 9, 3) to LocalDate.of(2026, 9, 5), period())
    }

    @Test
    fun 시작일보다_앞_날짜를_누르면_그_날짜가_새_시작일이다() {
        val period = setCalendarContent(start = LocalDate.of(2026, 9, 5), end = null)

        composeRule.onNodeWithContentDescription("9월 2일").performClick()

        assertEquals(LocalDate.of(2026, 9, 2) to null, period())
    }

    @Test
    fun 칠일을_넘기면_원인을_보이고_제출할_수_없다() {
        val period = setCalendarContent(start = LocalDate.of(2026, 9, 1), end = null)

        composeRule.onNodeWithContentDescription("9월 10일").performClick()

        assertEquals(LocalDate.of(2026, 9, 1) to LocalDate.of(2026, 9, 10), period())
        composeRule.onNodeWithText(string(R.string.trip_form_error_period_long)).performScrollTo().assertIsDisplayed()
        composeRule.onNode(hasText(string(R.string.trip_form_submit)) and hasClickAction()).assertIsNotEnabled()
    }

    /** #501: 다른 여행이 차지한 날짜는 누를 수 없고, 사이에 끼는 종료일은 새 시작일이 된다. */
    @Test
    fun 다른_여행_날짜는_누를_수_없고_건너뛰는_종료일은_새_시작일이다() {
        val occupied = listOf(OccupiedPeriod("t9", LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 4)))
        val period = setCalendarContent(start = LocalDate.of(2026, 9, 2), end = null, occupied = occupied)

        composeRule.onNodeWithContentDescription("9월 4일").assertIsNotEnabled()

        composeRule.onNodeWithContentDescription("9월 6일").performClick()
        assertEquals(LocalDate.of(2026, 9, 6) to null, period())
    }

    /** #501: 수정 중인 자기 여행 기간은 다시 고를 수 있다. */
    @Test
    fun 수정_중인_자기_여행_기간은_비활성이_아니다() {
        setContent(editState(TripStatus.UPCOMING).copy(occupiedPeriods = listOf(OccupiedPeriod("t1", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3)))))

        composeRule.onNodeWithText("2026. 9. 3").performClick()
        composeRule.onNodeWithContentDescription("9월 2일").assertIsEnabled()
    }

    /** #501: `409 TRIP_PERIOD_CONFLICT`는 겹친 여행 이름과 다음 행동을 함께 안내한다. */
    @Test
    fun 기간_충돌은_겹친_여행_이름과_함께_안내한다() {
        setContent(TripFormUiState(submitError = TripFormSubmitError.PERIOD_CONFLICT, conflictTripName = "제주 여행"))

        composeRule.onNodeWithText(context.getString(R.string.trip_form_error_period_conflict, "제주 여행")).performScrollTo().assertIsDisplayed()
    }

    // --- #499 대표 이미지 ---

    @Test
    fun 이미지가_없으면_기본_이미지와_48dp_사진_업로드_버튼만_보인다() {
        setContent(TripFormUiState())

        composeRule.onNodeWithText(string(R.string.trip_form_cover_default)).assertIsDisplayed()
        composeRule.onNode(hasText(string(R.string.trip_form_cover_upload)) and hasClickAction()).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText(string(R.string.trip_form_cover_reset)).assertDoesNotExist()
    }

    @Test
    fun 고른_사진이_있으면_사진_변경과_기본으로를_보이고_기본으로를_알린다() {
        var removed = 0
        composeRule.setContent {
            GilpickTheme {
                TripFormScreen(
                    state = TripFormUiState(pickedImage = PickedTripImage(byteArrayOf(1), "image/png")),
                    onNameChange = {},
                    onPeriodChange = { _, _ -> },
                    onSubmit = {},
                    onRemoveImage = { removed++ },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.trip_form_cover_change)).assertIsDisplayed()
        composeRule.onNode(hasText(string(R.string.trip_form_cover_reset)) and hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()

        assertEquals(1, removed)
    }

    /** #555: 수정 화면의 그 밖의 실패는 "만들 수 없습니다"가 아니라 "저장할 수 없습니다"다. */
    @Test
    fun 수정_화면의_알_수_없는_실패는_저장할_수_없다고_안내한다() {
        setContent(editState(TripStatus.UPCOMING).copy(submitError = TripFormSubmitError.UNEXPECTED))

        composeRule.onNodeWithText(string(R.string.trip_form_error_unexpected_edit)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_form_error_unexpected)).assertDoesNotExist()
    }

    /** #555: 사진만 실패하면 여행 정보는 저장됐다는 사실과 함께 알린다. */
    @Test
    fun 사진만_실패하면_여행은_저장됐다고_안내한다() {
        setContent(TripFormUiState(submitError = TripFormSubmitError.IMAGE_UPLOAD_FAILED))

        composeRule.onNodeWithText(string(R.string.trip_form_error_image_upload_failed)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun 쓸_수_없는_사진은_원인을_안내한다() {
        setContent(TripFormUiState(imageError = TripImageError.TOO_LARGE))

        composeRule.onNodeWithText(string(R.string.trip_form_image_too_large)).assertIsDisplayed()
    }

    @Test
    fun 달력_날짜_칸은_48dp_터치_영역이다() {
        setCalendarContent(start = LocalDate.of(2026, 9, 1), end = null)

        composeRule.onNodeWithContentDescription("9월 15일").assertTouchHeightIsEqualTo(48.dp)
    }

    // --- 수정 모드 ---

    @Test
    fun 수정_모드는_뒤로_가기_제목_버튼_문구와_여행_삭제를_보여준다() {
        setContent(editState(TripStatus.UPCOMING))

        composeRule.onNodeWithContentDescription(string(R.string.trip_form_back)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_form_edit_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_form_edit_submit)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_detail_delete)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_form_create_title)).assertDoesNotExist()
    }

    @Test
    fun 완료된_여행은_기간_입력을_누를_수_없고_이유를_보인다() {
        // FR-010a. 색과 흐린 스타일만으로는 이유를 알 수 없으므로 문구도 함께 본다.
        setContent(editState(TripStatus.COMPLETED))

        composeRule.onNodeWithText("2026. 9. 1").assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.trip_form_period_locked)).assertIsDisplayed()
    }

    @Test
    fun 완료된_여행도_이름은_수정할_수_있다() {
        // FR-010: 이름은 상태와 무관하게 수정할 수 있다.
        setContent(editState(TripStatus.COMPLETED))

        composeRule.onNode(hasSetTextAction()).assertIsEnabled()
        composeRule.onNode(hasText(string(R.string.trip_form_edit_submit)) and hasClickAction()).assertIsEnabled()
    }

    @Test
    fun 예정_여행은_기간_칸을_누르면_달력이_펼쳐진다() {
        setContent(editState(TripStatus.UPCOMING))

        composeRule.onNodeWithText("2026. 9. 3").assertIsEnabled().performClick()

        composeRule.onNodeWithContentDescription("9월 2일").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_form_period_locked)).assertDoesNotExist()
    }

    @Test
    fun 종료일을_원래보다_앞당기면_축소_경고를_보인다() {
        setContent(editState(TripStatus.UPCOMING).copy(endDate = LocalDate.of(2026, 9, 2)))

        composeRule.onNodeWithText(string(R.string.trip_form_shrink_warning_end)).assertIsDisplayed()
    }

    @Test
    fun 여행_삭제를_누르면_확인_대화상자를_연다() {
        var deleted = 0
        setContent(editState(TripStatus.UPCOMING), onDelete = { deleted++ })

        composeRule.onNodeWithText(string(R.string.trip_detail_delete)).performClick()
        composeRule.onNodeWithText(string(R.string.trip_delete_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_delete_confirm)).performClick()

        assertEquals(1, deleted)
    }

    @Test
    fun 버전_충돌은_재조회_안내로_보여준다() {
        // US4 Acceptance 8. "오류가 발생했습니다"로 끝내지 않는다.
        setContent(editState(TripStatus.UPCOMING).copy(submitError = TripFormSubmitError.VERSION_CONFLICT))

        composeRule.onNodeWithText(string(R.string.trip_form_error_version_conflict)).assertIsDisplayed()
    }

    @Test
    fun 완료_여행_기간_잠금_오류는_이유를_설명한다() {
        // US4 Acceptance 7.
        setContent(editState(TripStatus.COMPLETED).copy(submitError = TripFormSubmitError.TRIP_LOCKED))

        composeRule.onNodeWithText(string(R.string.trip_form_error_trip_locked)).assertIsDisplayed()
    }

    /** 수정 모드 상태 하나. 이름과 기간은 이미 채워져 있고 원래 기간도 같다. */
    private fun editState(status: TripStatus) = TripFormUiState(
        name = "서울 여행",
        startDate = LocalDate.of(2026, 9, 1),
        endDate = LocalDate.of(2026, 9, 3),
        originalStartDate = LocalDate.of(2026, 9, 1),
        originalEndDate = LocalDate.of(2026, 9, 3),
        mode = FormMode.Edit(tripId = "t1", version = 3, status = status),
    )

    private fun setContent(
        state: TripFormUiState,
        onNameChange: (String) -> Unit = {},
        onSubmit: () -> Unit = {},
        onDelete: () -> Unit = {},
    ) {
        composeRule.setContent {
            GilpickTheme {
                TripFormScreen(
                    state = state,
                    onNameChange = onNameChange,
                    onPeriodChange = { _, _ -> },
                    onSubmit = onSubmit,
                    onDelete = onDelete,
                )
            }
        }
    }

    /** 달력 선택이 상태에 반영되는 화면. 마지막으로 전달된 기간을 돌려준다. */
    private fun setCalendarContent(
        start: LocalDate?,
        end: LocalDate?,
        occupied: List<OccupiedPeriod> = emptyList(),
    ): () -> Pair<LocalDate?, LocalDate?> {
        var last: Pair<LocalDate?, LocalDate?> = start to end
        composeRule.setContent {
            var state by remember {
                mutableStateOf(TripFormUiState(name = "서울 여행", startDate = start, endDate = end, occupiedPeriods = occupied))
            }
            GilpickTheme {
                TripFormScreen(
                    state = state,
                    onNameChange = {},
                    onPeriodChange = { s, e ->
                        last = s to e
                        state = state.copy(startDate = s, endDate = e)
                    },
                    onSubmit = {},
                )
            }
        }
        return { last }
    }
}
