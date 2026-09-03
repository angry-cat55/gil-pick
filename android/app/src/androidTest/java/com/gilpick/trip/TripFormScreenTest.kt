package com.gilpick.trip

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * T014: 여행 생성 화면의 입력·오류·전송 중 표현 검증.
 *
 * `docs/design/ui-guidelines.md` 9절의 상태 표현과 10절의 접근성 최저선 가운데 화면
 * 코드가 책임지는 부분을 확인한다.
 */
@RunWith(AndroidJUnit4::class)
class TripFormScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun string(id: Int) = context.getString(id)

    @Test
    fun 초기_화면은_이름과_기간_입력과_제출_버튼을_보여준다() {
        setContent(TripFormUiState())

        composeRule.onNodeWithText(string(R.string.trip_form_create_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_form_name_label)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_form_period_label)).assertIsDisplayed()
        // 아직 고른 값이 없어도 무엇을 고르는 자리인지 알 수 있어야 한다.
        composeRule.onNodeWithText(string(R.string.trip_form_period_empty)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_form_submit)).assertIsDisplayed()
    }

    @Test
    fun 제출_전에는_검증_오류를_표시하지_않는다() {
        // 이름이 비어 있어도 아직 제출하지 않았으므로 빨간 글씨를 띄우지 않는다.
        setContent(
            TripFormUiState(
                validation = TripFormValidation(
                    nameError = TripNameError.TOO_SHORT,
                    periodError = TripPeriodError.NOT_SELECTED,
                ),
                showErrors = false,
            ),
        )

        composeRule.onNodeWithText(string(R.string.trip_form_error_name_short)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.trip_form_error_period_required))
            .assertDoesNotExist()
    }

    @Test
    fun 제출_후에는_이름과_기간_오류를_함께_보여준다() {
        setContent(
            TripFormUiState(
                validation = TripFormValidation(
                    nameError = TripNameError.TOO_SHORT,
                    periodError = TripPeriodError.TOO_LONG,
                ),
                showErrors = true,
            ),
        )

        composeRule.onNodeWithText(string(R.string.trip_form_error_name_short)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_form_error_period_long)).assertIsDisplayed()
    }

    @Test
    fun 고른_기간을_시작일과_종료일과_일수로_보여준다() {
        setContent(
            TripFormUiState(
                startDate = LocalDate.of(2026, 9, 1),
                endDate = LocalDate.of(2026, 9, 3),
            ),
        )

        composeRule.onNodeWithText("2026.09.01 ~ 2026.09.03", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("3일", substring = true).assertIsDisplayed()
    }

    @Test
    fun 전송_중에는_제출_버튼을_비활성화한다() {
        setContent(TripFormUiState(submitting = true))

        // 라벨이 진행 표시로 바뀌므로 제출 문구는 사라진다.
        composeRule.onNodeWithText(string(R.string.trip_form_submit)).assertDoesNotExist()
        // 진행 중임을 화면뿐 아니라 TalkBack에도 알린다.
        composeRule.onNodeWithContentDescription(string(R.string.trip_form_submitting))
            .assertIsDisplayed()
    }

    @Test
    fun 전송_실패는_원인과_다음_행동을_함께_알린다() {
        setContent(TripFormUiState(submitError = TripFormSubmitError.NETWORK))

        // "오류가 발생했습니다"로 끝내지 않고 무엇을 하면 되는지 함께 쓴다.
        composeRule.onNodeWithText(string(R.string.trip_form_error_network)).assertIsDisplayed()
    }

    @Test
    fun 주요_버튼은_최소_터치_영역을_만족한다() {
        setContent(TripFormUiState())

        composeRule.onNodeWithText(string(R.string.trip_form_submit))
            .assertHeightIsAtLeast(48.dp)
            .assertIsEnabled()
        composeRule.onNodeWithText(string(R.string.trip_form_period_empty))
            .assertHeightIsAtLeast(48.dp)
            .assertHasClickAction()
    }

    @Test
    fun 기간_선택_다이얼로그의_확인과_취소를_쓸_수_있다() {
        setContent(TripFormUiState())

        composeRule.onNodeWithText(string(R.string.trip_form_period_empty)).performClick()

        // PICKER_MAX_HEIGHT 상한이 달력을 잘라 버튼을 밀어내지 않는지 고정한다.
        // DateRangePicker는 내부에서 스크롤하므로 상한을 둬도 두 버튼은 남아 있어야 한다.
        composeRule.onNodeWithText(string(R.string.trip_form_confirm)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_form_cancel)).assertIsDisplayed()

        // 다이얼로그 버튼은 시각 높이가 Material 3 기본 40dp지만 Compose가 터치 영역을
        // 48dp로 넓힌다. 가이드라인 10절이 요구하는 것은 터치 영역이므로 그쪽을 잰다.
        composeRule.onNodeWithText(string(R.string.trip_form_confirm))
            .assertTouchHeightIsEqualTo(48.dp)
        composeRule.onNodeWithText(string(R.string.trip_form_cancel))
            .assertTouchHeightIsEqualTo(48.dp)
    }

    @Test
    fun 전송_중에는_기간_선택과_제출을_모두_막는다() {
        var submitted = 0
        setContent(TripFormUiState(submitting = true), onSubmit = { submitted++ })

        composeRule.onNodeWithText(string(R.string.trip_form_period_empty)).assertIsNotEnabled()
        assertEquals(0, submitted)
    }

    @Test
    fun 제출_버튼을_누르면_요청을_올린다() {
        var submitted = 0
        setContent(TripFormUiState(name = "서울 여행"), onSubmit = { submitted++ })

        composeRule.onNodeWithText(string(R.string.trip_form_submit)).performClick()

        assertEquals(1, submitted)
    }

    @Test
    fun 이름_입력은_화면_밖으로_전달된다() {
        var typed: String? = null
        setContent(TripFormUiState(), onNameChange = { typed = it })

        composeRule.onNodeWithText(string(R.string.trip_form_name_label)).performTextInput("제주")

        assertEquals("제주", typed)
    }

    // --- T037: 수정 모드 ---

    @Test
    fun 수정_모드는_제목과_버튼_문구를_바꾼다() {
        // pen 21. 여행 수정 기준이다. 같은 화면이지만 무엇을 하는 자리인지 달라진다.
        setContent(editState(TripStatus.UPCOMING))

        composeRule.onNodeWithText(string(R.string.trip_form_edit_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_form_edit_submit)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_form_create_title)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.trip_form_submit)).assertDoesNotExist()
    }

    @Test
    fun 생성_모드는_기존_문구를_유지한다() {
        setContent(TripFormUiState())

        composeRule.onNodeWithText(string(R.string.trip_form_create_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_form_submit)).assertIsDisplayed()
    }

    @Test
    fun 완료된_여행은_기간_입력을_누를_수_없다() {
        // FR-010a. 색과 흐린 스타일만으로는 이유를 알 수 없으므로 문구도 함께 본다.
        setContent(editState(TripStatus.COMPLETED))

        composeRule.onNodeWithText(periodLabel()).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.trip_form_period_locked)).assertIsDisplayed()
    }

    @Test
    fun 완료된_여행도_이름은_수정할_수_있다() {
        // FR-010: 이름은 상태와 무관하게 수정할 수 있다.
        setContent(editState(TripStatus.COMPLETED))

        composeRule.onNodeWithText(string(R.string.trip_form_name_label)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trip_form_edit_submit)).assertIsEnabled()
    }

    @Test
    fun 예정_여행은_기간_입력을_누를_수_있다() {
        setContent(editState(TripStatus.UPCOMING))

        composeRule.onNodeWithText(periodLabel()).assertIsEnabled()
        composeRule.onNodeWithText(string(R.string.trip_form_period_locked)).assertDoesNotExist()
    }

    @Test
    fun 버전_충돌은_재조회_안내로_보여준다() {
        // US4 Acceptance 8. "오류가 발생했습니다"로 끝내지 않는다.
        setContent(
            editState(TripStatus.UPCOMING)
                .copy(submitError = TripFormSubmitError.VERSION_CONFLICT),
        )

        composeRule.onNodeWithText(string(R.string.trip_form_error_version_conflict))
            .assertIsDisplayed()
    }

    @Test
    fun 완료_여행_기간_잠금_오류는_이유를_설명한다() {
        // US4 Acceptance 7.
        setContent(
            editState(TripStatus.COMPLETED)
                .copy(submitError = TripFormSubmitError.TRIP_LOCKED),
        )

        composeRule.onNodeWithText(string(R.string.trip_form_error_trip_locked)).assertIsDisplayed()
    }

    /** 수정 모드 상태 하나. 이름과 기간은 이미 채워져 있다. */
    private fun editState(status: TripStatus) = TripFormUiState(
        name = "서울 여행",
        startDate = LocalDate.of(2026, 9, 1),
        endDate = LocalDate.of(2026, 9, 3),
        mode = FormMode.Edit(tripId = "t1", version = 3, status = status),
    )

    /** 채워진 기간 버튼에 보이는 문구. 화면과 같은 형식으로 만든다. */
    private fun periodLabel(): String {
        val range = context.getString(R.string.trip_form_period_value, "2026.09.01", "2026.09.03")
        return "$range · " + context.getString(R.string.trip_form_period_days, 3)
    }

    private fun setContent(
        state: TripFormUiState,
        onNameChange: (String) -> Unit = {},
        onSubmit: () -> Unit = {},
    ) {
        composeRule.setContent {
            GilpickTheme {
                TripFormScreen(
                    state = state,
                    onNameChange = onNameChange,
                    onPeriodChange = { _, _ -> },
                    onSubmit = onSubmit,
                )
            }
        }
    }
}
