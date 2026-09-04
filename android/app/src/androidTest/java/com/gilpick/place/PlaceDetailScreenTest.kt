package com.gilpick.place

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.ui.theme.GilpickTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T020·T024: 장소 상세 화면의 표시 단계와 누락 정보 표현 검증.
 *
 * `spec.md` US2 Acceptance Scenario 1~4와 FR-007(누락은 지어내지 않는다),
 * UI-012(provider 배지 없음, attribution 있음), 가이드라인 10절(48dp)이 대상이다.
 */
@RunWith(AndroidJUnit4::class)
class PlaceDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loading은_1초_전에는_표시하지_않고_이후_대기_표시를_띄운다() {
        composeRule.mainClock.autoAdvance = false
        setScreen(PlaceDetailUiState(PlaceDetailPhase.Loading))

        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithContentDescription("장소 정보를 불러오는 중").assertIsNotDisplayed()

        composeRule.mainClock.advanceTimeBy(700)
        composeRule.onNodeWithContentDescription("장소 정보를 불러오는 중").assertIsDisplayed()
    }

    @Test
    fun content는_hero에_이름과_주소를_정보_행에_주소와_운영_안내를_보여준다() {
        setScreen(
            content(
                testPlace(
                    "tourapi:1",
                    name = "경복궁",
                    address = "서울특별시 종로구 사직로 161",
                    operatingGuide = "매주 화요일 휴무",
                    imageUrl = "https://example.test/a.jpg",
                ),
            ),
        )

        // hero 이름과 지도 핀 라벨에 한 번씩 나온다.
        composeRule.onAllNodes(hasText("경복궁")).onFirst().assertIsDisplayed()
        // hero와 주소 행에 한 번씩.
        composeRule.onAllNodes(hasText("서울특별시 종로구 사직로 161")).assertCountEquals(2)
        composeRule.onNodeWithText("매주 화요일 휴무").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("경복궁 대표 사진").assertIsDisplayed()
        composeRule.onNodeWithText("일정에 추가").assertIsDisplayed()
    }

    @Test
    fun 누락된_정보는_지어내지_않고_정보_없음으로_구분한다() {
        setScreen(content(testPlace("tourapi:1", name = "정보 적은 장소", address = null, imageUrl = null)))

        // hero 주소 1 + stats(평점·운영시간·입장료) 3 + 행(주소·운영시간·혼잡도·날씨) 4.
        composeRule.onAllNodes(hasText("정보 없음")).assertCountEquals(8)
        composeRule.onNodeWithContentDescription("대표 사진 없음").assertIsDisplayed()
    }

    @Test
    fun google_평점과_영업정보가_있으면_attribution과_함께_표시한다() {
        setScreen(
            content(
                testPlace(
                    "tourapi:1",
                    name = "카페",
                    rating = 4.6,
                    userRatingCount = 12450,
                    businessStatus = PlaceBusinessStatus.OPERATIONAL,
                    regularOpeningHours = listOf("월요일: 오전 10:00~오후 8:00"),
                    googleAttributions = listOf("Google"),
                ),
            ),
        )

        composeRule.onNodeWithText("4.6").assertIsDisplayed()
        composeRule.onNodeWithText("운영 중").assertIsDisplayed()
        composeRule.onNodeWithText("월요일: 오전 10:00~오후 8:00").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("평점·영업정보 제공: Google").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodes(hasText("TOUR_API")).assertCountEquals(0)
        composeRule.onAllNodes(hasText("GOOGLE_PLACES")).assertCountEquals(0)
    }

    @Test
    fun google_데이터가_없으면_attribution이_없다() {
        setScreen(content(testPlace("tourapi:1", name = "경복궁")))

        composeRule.onAllNodes(hasText("평점·영업정보 제공: Google")).assertCountEquals(0)
    }

    @Test
    fun google_전용_장소는_허용된_field만_표시한다() {
        setScreen(
            content(
                testPlace("google:abc", name = "구글 카페", address = "서울 중구", rating = 4.3, userRatingCount = 12, phone = "02-0000-0000"),
            ),
        )

        composeRule.onAllNodes(hasText("구글 카페")).onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("4.3").assertIsDisplayed()
        // stats 운영시간·입장료 2 + 행 운영시간·혼잡도·날씨 3.
        composeRule.onAllNodes(hasText("정보 없음")).assertCountEquals(5)
    }

    @Test
    fun 일정에_추가는_이동_수단과_체류_시간_시트를_거쳐_선택값을_전달한다() {
        val requests = mutableListOf<AddToScheduleRequest>()
        setScreen(content(testPlace("tourapi:1", name = "경복궁")), onAddToSchedule = { requests += it })

        composeRule.onNodeWithText("일정에 추가").performClick()
        composeRule.onNodeWithText("이동 수단 선택").assertIsDisplayed()
        // 기본값: 대중교통, 추천 체류시간 90분.
        composeRule.onNodeWithText("90분").assertIsDisplayed()

        composeRule.onNodeWithText("도보").performClick()
        composeRule.onNodeWithContentDescription("체류 시간 30분 늘리기").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithText("120분").assertIsDisplayed()
        composeRule.onNodeWithTag(ADD_TO_SCHEDULE_CONFIRM_TAG).performClick()

        assertEquals(listOf(AddToScheduleRequest(PlaceTransport.WALK, 120)), requests)
    }

    @Test
    fun 시트에서_취소하면_아무것도_전달하지_않는다() {
        var adds = 0
        setScreen(content(testPlace("tourapi:1", name = "경복궁")), onAddToSchedule = { adds++ })

        composeRule.onNodeWithText("일정에 추가").performClick()
        composeRule.onNodeWithText("취소").performClick()

        assertEquals(0, adds)
        composeRule.onAllNodes(hasText("이동 수단 선택")).assertCountEquals(0)
    }

    @Test
    fun not_found는_검색으로_돌아가는_행동을_제공한다() {
        var backs = 0
        setScreen(PlaceDetailUiState(PlaceDetailPhase.NotFound), onBack = { backs++ })

        composeRule.onNodeWithText("장소를 찾을 수 없어요").assertIsDisplayed()
        composeRule.onNodeWithText("검색 결과로 돌아가기").performClick()

        assertEquals(1, backs)
    }

    @Test
    fun 재시도_가능한_실패는_원인과_재시도를_보여준다() {
        var retries = 0
        setScreen(
            PlaceDetailUiState(PlaceDetailPhase.Failed(PlaceError(PlaceErrorKind.TIMEOUT, retryable = true))),
            onRetry = { retries++ },
        )

        composeRule.onNodeWithText("장소 정보 제공이 지연되고 있어요. 잠시 후 다시 시도해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithText("다시 시도").performClick()

        assertEquals(1, retries)
    }

    @Test
    fun 재시도할_수_없는_실패는_검색으로_돌아가는_행동을_준다() {
        var backs = 0
        setScreen(
            PlaceDetailUiState(PlaceDetailPhase.Failed(PlaceError(PlaceErrorKind.RATE_LIMITED, retryable = false))),
            onBack = { backs++ },
        )

        composeRule.onNodeWithText("검색 결과로 돌아가기").performClick()

        assertEquals(1, backs)
        composeRule.onAllNodes(hasText("다시 시도")).assertCountEquals(0)
    }

    @Test
    fun 인증_만료는_다시_로그인을_제공한다() {
        var reauths = 0
        setScreen(
            PlaceDetailUiState(PlaceDetailPhase.Failed(PlaceError(PlaceErrorKind.SESSION_EXPIRED, retryable = false))),
            onReauthenticate = { reauths++ },
        )

        composeRule.onNodeWithText("로그인 상태가 만료되었어요. 다시 로그인해 주세요.").assertIsDisplayed()
        composeRule.onAllNodes(hasText("다시 시도")).assertCountEquals(0)
        composeRule.onNodeWithText("다시 로그인").performClick()

        assertEquals(1, reauths)
    }

    @Test
    fun 뒤로_가기_버튼은_48dp_이상이고_이전_화면으로_돌아간다() {
        var backs = 0
        setScreen(content(testPlace("tourapi:1", name = "경복궁")), onBack = { backs++ })

        composeRule.onNodeWithContentDescription("뒤로 가기").assertHeightIsAtLeast(48.dp).performClick()

        assertEquals(1, backs)
    }

    private fun content(place: PlaceDto) = PlaceDetailUiState(PlaceDetailPhase.Content(place))

    private fun setScreen(
        state: PlaceDetailUiState,
        onBack: () -> Unit = {},
        onRetry: () -> Unit = {},
        onReauthenticate: () -> Unit = {},
        onAddToSchedule: (AddToScheduleRequest) -> Unit = {},
    ) {
        composeRule.setContent {
            GilpickTheme {
                PlaceDetailScreen(state = state, onBack = onBack, onRetry = onRetry, onReauthenticate = onReauthenticate, onAddToSchedule = onAddToSchedule)
            }
        }
    }
}

/** test용 장소 하나를 만든다. 기본값은 nullable field가 모두 `null`인 TourAPI 장소다. */
internal fun testPlace(
    id: String,
    name: String = "장소 $id",
    category: PlaceCategory = PlaceCategory.HISTORY_CULTURE,
    address: String? = null,
    imageUrl: String? = null,
    rating: Double? = null,
    userRatingCount: Int? = null,
    businessStatus: String? = null,
    regularOpeningHours: List<String>? = null,
    currentOpeningHours: List<String>? = null,
    googleAttributions: List<String>? = null,
    description: String? = null,
    phone: String? = null,
    operatingGuide: String? = null,
): PlaceDto = PlaceDto(
    placeId = id,
    source = if (id.startsWith("google:")) PlaceSource.GOOGLE_PLACES else PlaceSource.TOUR_API,
    sourcePlaceId = id.substringAfter(':'),
    name = name,
    category = category,
    tourApiCategory = if (id.startsWith("google:")) null else TourApiCategoryDto(large = "A02"),
    address = address,
    latitude = null,
    longitude = null,
    imageUrl = imageUrl,
    recommendedStayMinutes = 90,
    rating = rating,
    userRatingCount = userRatingCount,
    businessStatus = businessStatus,
    regularOpeningHours = regularOpeningHours,
    currentOpeningHours = currentOpeningHours,
    googleAttributions = googleAttributions,
    description = description,
    phone = phone,
    operatingGuide = operatingGuide,
)
