package com.gilpick.trip

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.R
import com.gilpick.ui.theme.GilpickTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #151: 목록 그룹 헤더 표시 검증.
 *
 * 점의 색과 크기는 test로 보지 않는다. 그쪽은 screenshot 픽셀 대조로 확인하고 결과를
 * PR에 남긴다. 여기서는 **어떤 헤더가 몇 개 그려지는가**만 본다.
 *
 * 개수 배지는 검증 대상이 아니다. Figma 헤더에 없어서 그리지 않는다(가이드라인 12절).
 */
@RunWith(AndroidJUnit4::class)
class TripListGroupHeaderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    // --- 1. 헤더 문구 ---

    @Test
    fun 세_상태가_모두_있으면_헤더_셋을_보여준다() {
        setScreen(
            listOf(
                trip("t1", TripStatus.IN_PROGRESS),
                trip("t2", TripStatus.UPCOMING),
                trip("t3", TripStatus.COMPLETED),
            ),
        )

        composeRule.onNodeWithText(string(R.string.trips_group_in_progress)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trips_group_upcoming)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trips_group_completed)).assertIsDisplayed()
    }

    @Test
    fun 헤더_문구는_Figma의_section_라벨과_같다() {
        setScreen(listOf(trip("t1", TripStatus.IN_PROGRESS)))

        // 문구가 바뀌면 Figma와 어긋난다. 값 자체를 고정한다.
        assertEquals("진행 중", string(R.string.trips_group_in_progress))
        assertEquals("다가오는 여행", string(R.string.trips_group_upcoming))
        assertEquals("지난 여행", string(R.string.trips_group_completed))
    }

    // --- 2. 빈 그룹 ---

    @Test
    fun 여행이_없는_그룹의_헤더는_그리지_않는다() {
        setScreen(listOf(trip("t2", TripStatus.UPCOMING)))

        composeRule.onNodeWithText(string(R.string.trips_group_upcoming)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.trips_group_in_progress)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.trips_group_completed)).assertDoesNotExist()
    }

    // --- 3. 추가 페이지 ---

    @Test
    fun 추가_페이지를_이어_받아도_같은_헤더가_두_번_그려지지_않는다() {
        // limit=100을 넘겨 loadMore가 도는 경우다. 같은 그룹의 여행이 다음 페이지에도
        // 있으면 구획이 갈라져 헤더가 두 번 나올 수 있다. groupTrips가 목록 전체를 다시
        // 나누므로 그런 일이 없어야 한다.
        var trips by mutableStateOf(
            listOf(trip("t1", TripStatus.UPCOMING), trip("t2", TripStatus.UPCOMING)),
        )
        setScreen { trips }

        assertHeaderCount(R.string.trips_group_upcoming, 1)

        // 다음 페이지가 도착해 같은 그룹의 여행이 붙는다.
        trips = trips + listOf(trip("t3", TripStatus.UPCOMING), trip("t4", TripStatus.UPCOMING))
        composeRule.waitForIdle()

        assertHeaderCount(R.string.trips_group_upcoming, 1)
    }

    @Test
    fun 추가_페이지에서_새_그룹이_나오면_그때_헤더가_생긴다() {
        var trips by mutableStateOf(listOf(trip("t1", TripStatus.UPCOMING)))
        setScreen { trips }

        composeRule.onNodeWithText(string(R.string.trips_group_completed)).assertDoesNotExist()

        trips = trips + listOf(trip("t2", TripStatus.COMPLETED))
        composeRule.waitForIdle()

        assertHeaderCount(R.string.trips_group_completed, 1)
        assertHeaderCount(R.string.trips_group_upcoming, 1)
    }

    /** 해당 헤더 문구를 가진 노드가 정확히 [expected]개인지 확인한다. */
    private fun assertHeaderCount(labelRes: Int, expected: Int) {
        assertEquals(
            expected,
            composeRule.onAllNodesWithText(string(labelRes)).fetchSemanticsNodes().size,
        )
    }

    private fun trip(id: String, status: TripStatus) = TripDto(
        tripId = id,
        name = "여행 $id",
        startDate = "2026-09-01",
        endDate = "2026-09-03",
        status = status,
        dayCount = 3,
        version = 1,
    )

    private fun setScreen(trips: List<TripDto>) = setScreen { trips }

    /**
     * 목록 화면을 띄운다.
     *
     * 여행 목록을 람다로 받아 test가 도중에 바꿀 수 있게 한다. 추가 페이지가 붙는 상황을
     * 실제 recomposition으로 재현하기 위해서다.
     */
    private fun setScreen(trips: () -> List<TripDto>) {
        composeRule.setContent {
            val current = trips()
            val state = remember(current) {
                TripListUiState(trips = current, phase = TripListPhase.Content)
            }

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
        composeRule.waitForIdle()
    }

    private fun string(id: Int) = context.getString(id)
}
