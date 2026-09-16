package com.gilpick.trip

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.ui.component.TAG_TRIP_CARD_IMAGE
import com.gilpick.ui.theme.GilpickTheme
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #153: 상태 필터 칩의 선택 상태가 semantics로 노출되는지 검증.
 *
 * 칩의 선택 여부를 색으로만 전달하면 가이드라인 10절의 "색 단독 의미 전달 금지"에
 * 걸린다. `FilterChip`이 `isSelected` semantics를 붙이므로 색을 못 보는 사용자도
 * 선택 상태를 알 수 있다. 이 test는 그 semantics가 실제로 전이되는지만 본다.
 *
 * 색 값 자체는 test로 검증하지 않는다(#153의 테스트 방법). 렌더된 색은 screenshot과
 * 픽셀 대조로 확인하며 결과를 PR에 남긴다.
 */
@RunWith(AndroidJUnit4::class)
class TripListScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- 1. 초기 상태 ---

    @Test
    fun 필터를_고르지_않으면_전체만_선택된다() {
        setScreen()

        composeRule.onNodeWithText("전체").assertIsSelected()
        composeRule.onNodeWithText("예정").assertIsNotSelected()
        composeRule.onNodeWithText("여행 중").assertIsNotSelected()
        composeRule.onNodeWithText("완료").assertIsNotSelected()
    }

    // --- 2. 선택 전이 ---

    @Test
    fun 상태_칩을_누르면_선택이_그_칩으로_옮겨간다() {
        setScreen()

        composeRule.onNodeWithText("예정").performClick()

        composeRule.onNodeWithText("예정").assertIsSelected()
        composeRule.onNodeWithText("전체").assertIsNotSelected()
        composeRule.onNodeWithText("여행 중").assertIsNotSelected()
        composeRule.onNodeWithText("완료").assertIsNotSelected()
    }

    // --- 3. 해제 전이 ---

    @Test
    fun 선택된_칩을_다시_누르면_해제되어_전체로_돌아간다() {
        setScreen()

        composeRule.onNodeWithText("여행 중").performClick()
        composeRule.onNodeWithText("여행 중").assertIsSelected()

        composeRule.onNodeWithText("여행 중").performClick()

        composeRule.onNodeWithText("여행 중").assertIsNotSelected()
        composeRule.onNodeWithText("전체").assertIsSelected()
    }

    // --- 4. 대표 이미지(#617) ---

    @Test
    fun 대표_이미지를_받은_카드에만_이미지가_그려진다() {
        val today = LocalDate.now(KST)
        val trips = listOf(
            tripOf("t1", today.minusDays(1), TripStatus.IN_PROGRESS),
            tripOf("t2", today.plusDays(7), TripStatus.UPCOMING),
            tripOf("t3", today.minusYears(1), TripStatus.COMPLETED),
            tripOf("t4", today.plusDays(30), TripStatus.UPCOMING),
        )
        // 진행 중·예정·완료 세 종류만 이미지를 받아 뒀다. `t4`는 대체 배경만 보여야 한다.
        val covers = trips.take(3).associate { coverKey(it) to png() }

        setContent(TripListUiState(phase = TripListPhase.Content, trips = trips, covers = covers))

        // 카드 전체가 하나의 클릭 대상이라 자식 semantics가 카드로 합쳐진다. 이미지 노드는 합치지 않은 tree에서 센다.
        composeRule.onAllNodesWithTag(TAG_TRIP_CARD_IMAGE, useUnmergedTree = true).assertCountEquals(3)
    }

    @Test
    fun 대표_이미지가_없으면_이미지를_그리지_않는다() {
        val trips = listOf(tripOf("t1", LocalDate.now(KST).plusDays(3), TripStatus.UPCOMING))

        setContent(TripListUiState(phase = TripListPhase.Content, trips = trips))

        composeRule.onAllNodesWithTag(TAG_TRIP_CARD_IMAGE, useUnmergedTree = true).assertCountEquals(0)
    }

    private fun tripOf(id: String, start: LocalDate, status: TripStatus) = TripDto(
        tripId = id,
        name = "서울 여행 $id",
        startDate = start.toString(),
        endDate = start.plusDays(2).toString(),
        status = status,
        dayCount = 3,
        version = 1,
        imageUrl = "http://api.example/trips/$id/image/content",
    )

    /** 카드에 넘길 실제 PNG bytes. Coil이 디코딩할 수 있는 최소 이미지다. */
    private fun png(): ByteArray = ByteArrayOutputStream().use { out ->
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.RED) }
            .compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }

    private fun setContent(state: TripListUiState) {
        composeRule.setContent {
            GilpickTheme {
                TripListScreen(
                    state = state,
                    onQueryChange = {},
                    onStatusFilterChange = {},
                    onRetry = {},
                    onLoadMore = {},
                    onCreateTrip = {},
                    onTripClick = {},
                )
            }
        }
    }

    /**
     * 실제 production composable을 상태 보유자 없이 띄운다.
     *
     * 필터 선택은 `TripListViewModel`이 아니라 화면이 올려 준 값으로 결정되므로,
     * 이 test는 화면 상태를 직접 들고 있으면서 상태 필터 전이만 확인한다.
     */
    private fun setScreen() {
        composeRule.setContent {
            var state by remember { mutableStateOf(TripListUiState(phase = TripListPhase.Empty)) }

            GilpickTheme {
                TripListScreen(
                    state = state,
                    onQueryChange = { state = state.copy(query = it) },
                    onStatusFilterChange = { state = state.copy(statusFilter = it) },
                    onRetry = {},
                    onLoadMore = {},
                    onCreateTrip = {},
                    onTripClick = {},
                )
            }
        }
    }
}
