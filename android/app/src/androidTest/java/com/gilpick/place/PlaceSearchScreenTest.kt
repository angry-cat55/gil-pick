package com.gilpick.place

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.ui.theme.GilpickTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T013·T017·T027: 검색 화면의 표시 단계·상호작용·접근성 검증.
 *
 * `spec.md` UI-001(입력·칩·요약), UI-002(네 상태), UI-004(행 선택·`+`), UI-005(이미지 대체),
 * UI-007(48dp)이 대상이다. 상태 전이 자체는 `PlaceSearchViewModelTest`가 다루므로 여기서는
 * 상태를 직접 넣고 화면이 어떻게 보이고 무엇을 호출하는지만 본다.
 */
@RunWith(AndroidJUnit4::class)
class PlaceSearchScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 검색_전에는_안내를_보여주고_입력과_칩이_있다() {
        setScreen(PlaceSearchUiState())

        composeRule.onNodeWithText("장소 추가").assertIsDisplayed()
        composeRule.onNodeWithText("어떤 장소를 찾고 계세요?").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("장소 이름 검색").assertIsDisplayed()
        composeRule.onNodeWithText("전체").assertIsSelected()
        composeRule.onNodeWithText("문화·역사").assertIsDisplayed()
    }

    @Test
    fun 키보드_검색_동작이_검색을_실행하고_입력만으로는_실행하지_않는다() {
        var searches = 0
        var query = ""
        setScreen(PlaceSearchUiState(), onQueryChange = { query = it }, onSearch = { searches++ })

        composeRule.onNodeWithContentDescription("장소 이름 검색").performTextInput("경복궁")
        composeRule.runOnIdle { assertEquals(0, searches) }

        composeRule.onNodeWithContentDescription("장소 이름 검색").performImeAction()
        composeRule.runOnIdle {
            assertEquals("경복궁", query)
            assertEquals(1, searches)
        }
    }

    @Test
    fun 칩을_누르면_category만_바뀌고_지우기는_입력을_비운다() {
        var category: PlaceCategory? = PlaceCategory.OTHER
        var cleared = 0
        setScreen(PlaceSearchUiState(query = "경복궁"), onCategoryChange = { category = it }, onClearQuery = { cleared++ })

        composeRule.onNodeWithText("카페").performClick()
        composeRule.runOnIdle { assertEquals(PlaceCategory.CAFE, category) }
        composeRule.onNodeWithText("전체").performClick()
        composeRule.runOnIdle { assertEquals(null, category) }

        composeRule.onNodeWithContentDescription("검색어 지우기").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, cleared) }
    }

    @Test
    fun loading은_1초_전에는_표시하지_않고_이후_대기_표시를_띄운다() {
        composeRule.mainClock.autoAdvance = false
        setScreen(PlaceSearchUiState(phase = PlaceSearchPhase.Loading))

        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithContentDescription("검색 결과를 불러오는 중").assertIsNotDisplayed()

        composeRule.mainClock.advanceTimeBy(700)
        composeRule.onNodeWithContentDescription("검색 결과를 불러오는 중").assertIsDisplayed()
    }

    @Test
    fun 위치를_얻지_못하면_안내와_재시도_행동을_제공한다() {
        var retries = 0
        setScreen(
            PlaceSearchUiState(phase = PlaceSearchPhase.LocationUnavailable),
            onRetry = { retries++ },
        )

        composeRule.onNodeWithText("현재 위치를 확인할 수 없어요").assertIsDisplayed()
        composeRule.onNodeWithText("주변 서울 명소를 찾으려면\n위치 권한이 필요해요").assertIsDisplayed()
        composeRule.onNodeWithText("위치 다시 확인").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun content는_요약과_행을_보여주고_행은_상세로_플러스는_시트로_간다() {
        var opened: String? = null
        setScreen(
            content(
                testPlace("tourapi:1", name = "경복궁", rating = 4.8, businessStatus = PlaceBusinessStatus.OPERATIONAL, imageUrl = "https://example.test/a.jpg"),
                testPlace("google:x", name = "구글 카페", category = PlaceCategory.CAFE),
            ),
            onPlaceClick = { opened = it },
        )

        composeRule.onNodeWithText("검색 결과 2곳").assertIsDisplayed()
        // 칩 하나와 첫 행의 category 한 번.
        composeRule.onAllNodes(hasText("문화·역사")).assertCountEquals(2)
        composeRule.onNodeWithText("4.8").assertIsDisplayed()
        composeRule.onNodeWithText("운영 중").assertIsDisplayed()
        composeRule.onAllNodes(hasText("TOUR_API")).assertCountEquals(0)

        composeRule.onNodeWithText("구글 카페").performClick()
        composeRule.runOnIdle { assertEquals("google:x", opened) }

        composeRule.onNodeWithContentDescription("경복궁 일정에 추가")
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithText("이동 수단 선택").assertIsDisplayed()
        composeRule.onNodeWithText("경복궁까지 어떻게 이동하시겠어요?").assertIsDisplayed()
    }

    @Test
    fun 시트에서_확정하면_장소와_선택값을_넘긴다() {
        var received: Pair<PlaceDto, AddToScheduleRequest>? = null
        setScreen(content(testPlace("tourapi:1", name = "경복궁")), onAddToSchedule = { place, request -> received = place to request })

        composeRule.onNodeWithContentDescription("경복궁 일정에 추가").performClick()
        composeRule.onNodeWithText("도보").performClick()
        composeRule.onNodeWithTag(ADD_TO_SCHEDULE_CONFIRM_TAG).performClick()

        composeRule.runOnIdle {
            assertEquals("tourapi:1", received?.first?.placeId)
            assertEquals(AddToScheduleRequest(PlaceTransport.WALK, 90), received?.second)
        }
    }

    @Test
    fun 이미지와_평점_영업상태가_없어도_이름과_선택_행동은_유지된다() {
        var opened: String? = null
        setScreen(content(testPlace("tourapi:1", name = "정보 적은 장소", imageUrl = null)), onPlaceClick = { opened = it })

        composeRule.onNodeWithText("정보 적은 장소").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals("tourapi:1", opened) }
        composeRule.onAllNodes(hasText("★")).assertCountEquals(0)
    }

    @Test
    fun Google_데이터가_있으면_목록_하단에_출처를_한_번만_보여주고_스크롤해도_유지한다() {
        val places = (1..20).map { testPlace("google:$it", name = "장소 $it", rating = 4.0) } +
            testPlace("google:21", name = "출처 있는 장소", businessStatus = PlaceBusinessStatus.OPERATIONAL, googleAttributions = listOf("Google 제공", " ")) +
            testPlace("tourapi:22", name = "TourAPI 장소")
        setScreen(content(*places.toTypedArray()))

        composeRule.onNodeWithText("평점·영업정보 제공: Google 제공").assertIsDisplayed()
        composeRule.onAllNodes(hasText("평점·영업정보 제공", substring = true)).assertCountEquals(1)

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(places.size)
        composeRule.onNodeWithText("TourAPI 장소").assertIsDisplayed()
        composeRule.onNodeWithText("평점·영업정보 제공: Google 제공").assertIsDisplayed()
    }

    /** #578 F003 UI-012: TourAPI 결과가 있으면 목록 하단에 공공데이터 출처를 한 줄만 둔다. Google 출처와 함께 보인다. */
    @Test
    fun TourAPI_결과가_있으면_목록_하단에_공공데이터_출처를_한_번만_보여준다() {
        setScreen(content(testPlace("tourapi:1", name = "경복궁"), testPlace("google:2", name = "구글 카페", rating = 4.2)))

        composeRule.onNodeWithText("출처: ⓒ한국관광공사").assertIsDisplayed()
        composeRule.onAllNodes(hasText("출처: ⓒ한국관광공사")).assertCountEquals(1)
        composeRule.onNodeWithText("평점·영업정보 제공: Google").assertIsDisplayed()
    }

    /** #578: Google 결과만 있으면 공공데이터 출처를 표시하지 않는다. */
    @Test
    fun Google_결과만_있으면_공공데이터_출처가_없다() {
        setScreen(content(testPlace("google:1", name = "구글 카페", rating = 4.2)))

        composeRule.onNodeWithText("구글 카페").assertIsDisplayed()
        composeRule.onAllNodes(hasText("출처: ⓒ한국관광공사")).assertCountEquals(0)
    }

    @Test
    fun 응답_출처가_비면_기본_Google_출처를_쓴다() {
        setScreen(content(testPlace("google:1", name = "구글 카페", rating = 4.2, googleAttributions = emptyList())))

        composeRule.onNodeWithText("평점·영업정보 제공: Google").assertIsDisplayed()
    }

    @Test
    fun Google_데이터가_없으면_출처를_표시하지_않는다() {
        setScreen(content(testPlace("tourapi:1", name = "경복궁"), testPlace("tourapi:2", name = "창덕궁")))

        composeRule.onNodeWithText("경복궁").assertIsDisplayed()
        composeRule.onAllNodes(hasText("평점·영업정보 제공", substring = true)).assertCountEquals(0)
    }

    @Test
    fun 긴_장소명도_잘리지_않고_행동이_유지된다() {
        val longName = "아주 긴 이름을 가진 장소 이름을 가진 장소 이름을 가진 장소 이름을 가진 장소"
        setScreen(content(testPlace("tourapi:1", name = longName)))

        composeRule.onNodeWithText(longName).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("$longName 일정에 추가").assertIsDisplayed()
    }

    @Test
    fun empty는_검색어와_함께_안내하고_카테고리로_찾기를_제공한다() {
        var byCategory = 0
        setScreen(
            PlaceSearchUiState(query = "없는곳", committedQuery = "없는곳", phase = PlaceSearchPhase.Empty),
            onSearchByCategory = { byCategory++ },
        )

        composeRule.onNodeWithText("'없는곳' 검색 결과가 없어요").assertIsDisplayed()
        composeRule.onNodeWithText("카테고리로 찾기").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, byCategory) }
    }

    /** #519: 빈 결과 제목은 보낸 조건에 맞춰 바뀌고 빈 따옴표(`''`)가 나오지 않는다. */
    @Test
    fun empty_제목_검색어와_카테고리_함께면_검색어를_보인다() {
        setScreen(
            PlaceSearchUiState(
                query = "없는곳", category = PlaceCategory.CAFE,
                committedQuery = "없는곳", committedCategory = PlaceCategory.CAFE, phase = PlaceSearchPhase.Empty,
            ),
        )
        composeRule.onNodeWithText("'없는곳' 검색 결과가 없어요").assertIsDisplayed()
    }

    @Test
    fun empty_제목_카테고리만이면_카테고리_이름을_보인다() {
        setScreen(PlaceSearchUiState(category = PlaceCategory.CAFE, committedCategory = PlaceCategory.CAFE, phase = PlaceSearchPhase.Empty))

        composeRule.onNodeWithText("카페 검색 결과가 없어요").assertIsDisplayed()
        composeRule.onNodeWithText("''", substring = true).assertDoesNotExist()
    }

    @Test
    fun empty_제목_조건이_없으면_일반_문구다() {
        setScreen(PlaceSearchUiState(phase = PlaceSearchPhase.Empty))

        composeRule.onNodeWithText("검색 결과가 없어요").assertIsDisplayed()
        composeRule.onNodeWithText("''", substring = true).assertDoesNotExist()
    }

    @Test
    fun 짧은_키워드는_2글자_이상_입력을_안내한다() {
        setScreen(PlaceSearchUiState(query = "궁", phase = PlaceSearchPhase.Invalid(InvalidReason.TOO_SHORT)))
        composeRule.onNodeWithText("검색어를 2글자 이상 입력해 주세요").assertIsDisplayed()
    }

    @Test
    fun 조건이_없으면_조건_입력을_안내한다() {
        setScreen(PlaceSearchUiState(phase = PlaceSearchPhase.Invalid(InvalidReason.NO_CONDITION)))
        composeRule.onNodeWithText("검색어를 입력하거나 카테고리를 골라 주세요").assertIsDisplayed()
    }

    @Test
    fun error는_원인을_안내하고_재시도를_제공한다() {
        var retries = 0
        setScreen(
            PlaceSearchUiState(phase = PlaceSearchPhase.Failed(PlaceError(PlaceErrorKind.TIMEOUT, retryable = true))),
            onRetry = { retries++ },
        )

        composeRule.onNodeWithText("장소 정보 제공이 지연되고 있어요. 잠시 후 다시 시도해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithText("다시 시도").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun 재시도할_수_없는_error에는_재시도_버튼이_없다() {
        setScreen(PlaceSearchUiState(phase = PlaceSearchPhase.Failed(PlaceError(PlaceErrorKind.RATE_LIMITED, retryable = false))))

        composeRule.onAllNodes(hasText("다시 시도")).assertCountEquals(0)
    }

    @Test
    fun 인증_만료는_다시_로그인을_제공하고_재시도는_없다() {
        var reauths = 0
        setScreen(
            PlaceSearchUiState(phase = PlaceSearchPhase.Failed(PlaceError(PlaceErrorKind.SESSION_EXPIRED, retryable = false))),
            onReauthenticate = { reauths++ },
        )

        composeRule.onNodeWithText("로그인 상태가 만료되었어요. 다시 로그인해 주세요.").assertIsDisplayed()
        composeRule.onAllNodes(hasText("다시 시도")).assertCountEquals(0)
        composeRule.onNodeWithText("다시 로그인").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, reauths) }
    }

    @Test
    fun 추가_조회_실패는_기존_결과와_원인을_보여주고_다음_결과_재시도를_제공한다() {
        var retries = 0
        setScreen(
            content(testPlace("tourapi:1", name = "경복궁")).copy(loadMoreError = PlaceError(PlaceErrorKind.TIMEOUT, retryable = true)),
            onRetryLoadMore = { retries++ },
        )

        composeRule.onNodeWithText("경복궁").assertIsDisplayed()
        composeRule.onNodeWithText("다음 결과를 불러오지 못했어요").assertIsDisplayed()
        composeRule.onNodeWithText("장소 정보 제공이 지연되고 있어요. 잠시 후 다시 시도해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithText("다음 결과 다시 시도").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun 추가_조회_중_인증_만료는_기존_결과를_남기고_다시_로그인을_제공한다() {
        var reauths = 0
        setScreen(
            content(testPlace("tourapi:1", name = "경복궁")).copy(loadMoreError = PlaceError(PlaceErrorKind.SESSION_EXPIRED, retryable = false)),
            onReauthenticate = { reauths++ },
        )

        composeRule.onNodeWithText("경복궁").assertIsDisplayed()
        composeRule.onNodeWithText("로그인 상태가 만료되었어요. 다시 로그인해 주세요.").assertIsDisplayed()
        composeRule.onAllNodes(hasText("다음 결과 다시 시도")).assertCountEquals(0)
        composeRule.onNodeWithText("다시 로그인").performClick()
        composeRule.runOnIdle { assertEquals(1, reauths) }
    }

    /** #574: 칩 여섯 개가 360dp 한 줄에 가로 스크롤 없이 모두 보인다. */
    @Test
    fun 카테고리_칩_여섯_개는_360dp에서_가로_스크롤_없이_모두_보인다() {
        composeRule.setContent {
            GilpickTheme {
                Box(modifier = Modifier.width(360.dp)) {
                    PlaceSearchScreen(
                        state = PlaceSearchUiState(),
                        onBack = {},
                        onQueryChange = {},
                        onClearQuery = {},
                        onCategoryChange = {},
                        onSearch = {},
                        onRetry = {},
                        onReauthenticate = {},
                        onLoadMore = {},
                        onRetryLoadMore = {},
                        onSearchByCategory = {},
                        onPlaceClick = {},
                        onAddToSchedule = { _, _ -> },
                    )
                }
            }
        }

        CHIP_LABELS.forEach { label ->
            val chip = composeRule.onNodeWithText(label).assertIsDisplayed()
            chip.assertHeightIsAtLeast(48.dp)
            val bounds = chip.getBoundsInRoot()
            assertTrue("$label=$bounds", bounds.right <= 360.dp && bounds.left >= 0.dp)
        }
    }

    /** #574: 글자 배율을 키우면 한 줄에 다 들어가지 않는다. 가로 스크롤로 여섯 번째 칩까지 닿고 선택된다. */
    @Test
    fun 카테고리_칩은_최대_글자_배율_360dp에서도_스크롤로_모두_닿는다() {
        var picked: PlaceCategory? = null
        composeRule.setContent {
            GilpickTheme {
                Box(modifier = Modifier.width(360.dp)) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                        PlaceSearchScreen(
                            state = PlaceSearchUiState(),
                            onBack = {},
                            onQueryChange = {},
                            onClearQuery = {},
                            onCategoryChange = { picked = it },
                            onSearch = {},
                            onRetry = {},
                            onReauthenticate = {},
                            onLoadMore = {},
                            onRetryLoadMore = {},
                            onSearchByCategory = {},
                            onPlaceClick = {},
                            onAddToSchedule = { _, _ -> },
                        )
                    }
                }
            }
        }

        CHIP_LABELS.forEach { label ->
            composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        composeRule.onNodeWithText("쇼핑").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(PlaceCategory.SHOPPING, picked) }
    }

    /** #576: 검색 결과 행도 상세와 같은 규칙으로 실시간 영업 상태를 쓴다. */
    @Test
    fun 검색_결과는_openNow를_현재_영업_상태로_구분하고_폐업을_우선한다() {
        setScreen(
            content(
                testPlace("tourapi:1", name = "열린 곳", businessStatus = PlaceBusinessStatus.OPERATIONAL, openNow = true),
                testPlace("tourapi:2", name = "닫힌 곳", businessStatus = PlaceBusinessStatus.OPERATIONAL, openNow = false),
                testPlace("tourapi:3", name = "모르는 곳", businessStatus = PlaceBusinessStatus.OPERATIONAL),
                testPlace("tourapi:4", name = "폐업한 곳", businessStatus = PlaceBusinessStatus.CLOSED_PERMANENTLY, openNow = true),
                testPlace("tourapi:5", name = "정보 없는 곳"),
            ),
        )

        composeRule.onNodeWithText("영업 중").assertIsDisplayed()
        composeRule.onNodeWithText("영업 종료").assertIsDisplayed()
        // openNow를 모르면 현재 영업 여부를 지어내지 않고 `운영 중`만 남는다.
        composeRule.onAllNodes(hasText("운영 중")).assertCountEquals(1)
        composeRule.onNodeWithText("폐업").assertIsDisplayed()
        // 영업 상태를 모르는 장소에는 아무 문구도 두지 않는다.
        composeRule.onAllNodes(hasText("영업 중")).assertCountEquals(1)
    }

    private val CHIP_LABELS = listOf("전체", "자연", "문화·역사", "음식", "카페", "쇼핑")

    private fun content(vararg places: PlaceDto) = PlaceSearchUiState(
        query = "검색어",
        committedQuery = "검색어",
        results = places.toList(),
        phase = PlaceSearchPhase.Content,
    )

    private fun setScreen(
        state: PlaceSearchUiState,
        onQueryChange: (String) -> Unit = {},
        onClearQuery: () -> Unit = {},
        onCategoryChange: (PlaceCategory?) -> Unit = {},
        onSearch: () -> Unit = {},
        onRetry: () -> Unit = {},
        onReauthenticate: () -> Unit = {},
        onRetryLoadMore: () -> Unit = {},
        onSearchByCategory: () -> Unit = {},
        onPlaceClick: (String) -> Unit = {},
        onAddToSchedule: (PlaceDto, AddToScheduleRequest) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            GilpickTheme {
                PlaceSearchScreen(
                    state = state,
                    onBack = {},
                    onQueryChange = onQueryChange,
                    onClearQuery = onClearQuery,
                    onCategoryChange = onCategoryChange,
                    onSearch = onSearch,
                    onRetry = onRetry,
                    onReauthenticate = onReauthenticate,
                    onLoadMore = {},
                    onRetryLoadMore = onRetryLoadMore,
                    onSearchByCategory = onSearchByCategory,
                    onPlaceClick = onPlaceClick,
                    onAddToSchedule = onAddToSchedule,
                )
            }
        }
    }
}
