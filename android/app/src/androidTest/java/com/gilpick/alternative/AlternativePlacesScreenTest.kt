package com.gilpick.alternative

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.auth.SuccessEnvelope
import com.gilpick.ui.theme.GilpickTheme
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T027: 대체 장소 화면 네 상태와 후보 표현, 거절 잠금 검증(quickstart AND 1 전부, spec UI-002·UI-003·UI-005·UI-006).
 *
 * 상태는 ViewModel 없이 [AlternativeUiState]를 직접 넣는다. 응답 DTO는 계약 JSON fixture를 그대로 역직렬화해
 * 계약과 화면이 어긋나면 여기서 드러난다. 지도는 SDK 인증이 필요해 자리 표시로 바꿔 끼운다.
 */
@RunWith(AndroidJUnit4::class)
class AlternativePlacesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loading은_1초_전에는_표시하지_않고_이후_대기_표시를_띄운다() {
        composeRule.mainClock.autoAdvance = false
        setScreen(AlternativeUiState.Loading)

        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithContentDescription("대체 장소를 불러오는 중").assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(600)
        composeRule.onNodeWithContentDescription("대체 장소를 불러오는 중").assertIsDisplayed()
    }

    @Test
    fun content는_감지_요약과_변수_칩과_후보_수와_직접_검색과_후보_행과_거절을_보여준다() {
        var searches = 0
        var keeps = 0
        setScreen(content(), onSearch = { searches++ }, onKeep = { keeps++ })

        composeRule.onNodeWithText("경복궁 · 방문 어려움 감지").assertIsDisplayed()
        composeRule.onNodeWithText("오후 2시 이후 강한 비 + 매우 높은 혼잡").assertIsDisplayed()
        // 위험으로 판정된 변수만 칩이다. 운영시간은 확인 불가라 칩이 없다.
        composeRule.onNodeWithText("🌧 강수 예보").assertIsDisplayed()
        composeRule.onNodeWithText("👥 매우 혼잡").assertIsDisplayed()
        composeRule.onNodeWithText("⏰ 마감 임박").assertDoesNotExist()
        composeRule.onNodeWithText("추천 후보 2곳").assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_SEARCH).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithTag(TAG_KEEP).performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle {
            assertEquals(1, searches)
            assertEquals(1, keeps)
        }
    }

    @Test
    fun 후보_행은_순위와_이름과_카테고리와_거리와_평점과_운영_상태와_근거를_보이고_1위만_TOP이다() {
        val selected = mutableListOf<AlternativeCandidateDto>()
        setScreen(content(), onSelect = { selected += it })

        candidate(1).performScrollTo()
        inCandidate(1, "창덕궁").assertIsDisplayed()
        inCandidate(1, "TOP").assertIsDisplayed()
        inCandidate(1, "문화·역사 · 820m · ★4.5").assertIsDisplayed()
        inCandidate(1, "18:00 마감").assertIsDisplayed()
        inCandidate(1, "실내 관람 가능 · 혼잡하지 않아요 · 현재보다 가까워요").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("1위 창덕궁, 문화·역사, 820m, 18:00 마감").assertIsDisplayed()

        candidate(2).performScrollTo()
        inCandidate(2, "TOP").assertDoesNotExist()
        // 평점이 없으면 `★` 항목 자체가 없고, 운영시간을 모르면 문구로 알린다(UI-003).
        inCandidate(2, "카페 · 1.5km").assertIsDisplayed()
        inCandidate(2, "운영시간 확인 불가").assertIsDisplayed()

        inCandidate(1, "경로 비교").assertHeightIsAtLeast(48.dp).performClick()
        inCandidate(2, "비교").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(listOf(1, 2), selected.map { it.rank }) }
    }

    @Test
    fun 곧_마감_후보는_마감_시각과_함께_문구로_알린다() {
        val candidates = alternatives()
        val closingSoon = candidates.items[0].copy(operatingStatus = OperatingStatus.CLOSING_SOON)
        setScreen(content(candidates = candidates.copy(items = listOf(closingSoon) + candidates.items.drop(1))))

        inCandidate(1, "곧 마감 18:00").assertIsDisplayed()
    }

    @Test
    fun 재조회_중에는_기존_후보_목록이_그대로_보이고_대기_표시가_없다() {
        setScreen(content().copy(refreshing = true))

        composeRule.onNodeWithText("추천 후보 2곳").assertIsDisplayed()
        candidate(1).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("대체 장소를 불러오는 중").assertDoesNotExist()
    }

    @Test
    fun 거절_요청_중에는_기존_일정_그대로_진행이_비활성이다() {
        var keeps = 0
        setScreen(content().copy(dismissPending = true), onKeep = { keeps++ })

        composeRule.onNodeWithTag(TAG_KEEP).performScrollTo().assertIsNotEnabled().performClick()
        composeRule.onNodeWithText("보내는 중").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, keeps) }
    }

    @Test
    fun 거절_실패는_화면을_유지한_채_원인과_다시_시도를_보인다() {
        var retries = 0
        setScreen(content().copy(dismissError = AlternativeError.Network), onRetryKeep = { retries++ })
        composeRule.onNodeWithTag(TAG_KEEP_ERROR).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("연결을 확인한 뒤 다시 시도해 주세요. 기존 일정은 그대로예요.").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_KEEP).assertIsEnabled()
        composeRule.onNode(hasText("다시 시도") and hasAnyAncestor(hasTestTag(TAG_KEEP_ERROR))).performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun 후보가_없으면_2km_안내와_기존_일정_그대로_진행과_직접_검색해서_고르기를_보여준다() {
        var searches = 0
        var keeps = 0
        setScreen(content(candidates = alternatives(alternativesEmptyJson())), onSearch = { searches++ }, onKeep = { keeps++ })

        composeRule.onNodeWithText("2km 안에 추천할 장소가 없어요").assertIsDisplayed()
        composeRule.onNodeWithText("추천 후보 0곳").assertDoesNotExist()
        composeRule.onNodeWithTag(TAG_KEEP).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithTag(TAG_SEARCH).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithText("직접 검색해서 고르기").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(1, keeps)
            assertEquals(1, searches)
        }
    }

    @Test
    fun 추천_실패는_원인과_다시_시도하기와_돌아가기를_보이고_기존_일정은_그대로다() {
        var retries = 0
        var backs = 0
        setScreen(AlternativeUiState.Error(AlternativeError.ProviderFailed(retryable = true), retryable = true), onRetry = { retries++ }, onBack = { backs++ })

        composeRule.onNodeWithText("대체 장소를 추천할 수 없어요").assertIsDisplayed()
        composeRule.onNodeWithText("지금은 주변 장소를 가져올 수 없어요. 기존 일정은 그대로예요.").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_RETRY).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithText("돌아가기").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, backs)
        }
    }

    @Test
    fun 다시_보내도_같은_실패는_다시_시도하기가_없다() {
        setScreen(AlternativeUiState.Error(AlternativeError.NotFound, retryable = false))

        composeRule.onNodeWithText("이미 삭제되었거나 없는 감지예요.").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_RETRY).assertDoesNotExist()
        composeRule.onNodeWithText("돌아가기").assertIsDisplayed()
    }

    @Test
    fun 처리된_감지는_상태_문구와_진행_화면으로를_보여준다() {
        var backs = 0
        setScreen(AlternativeUiState.Closed(DetectionStatus.DISMISSED), onBack = { backs++ })

        composeRule.onNodeWithText("이미 처리된 감지예요").assertIsDisplayed()
        composeRule.onNodeWithText("기존 일정 그대로 진행하기로 처리된 감지예요.").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_TO_PROGRESS).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun 지도_위_뒤로_버튼은_48dp_터치_영역으로_돌아간다() {
        var backs = 0
        setScreen(content(), onBack = { backs++ })

        composeRule.onNodeWithTag(TAG_BACK).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, backs) }
    }

    private fun candidate(rank: Int) = composeRule.onNodeWithTag(TAG_CANDIDATE_PREFIX + rank)

    private fun inCandidate(rank: Int, text: String) =
        composeRule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(TAG_CANDIDATE_PREFIX + rank)))

    private fun setScreen(
        state: AlternativeUiState,
        onBack: () -> Unit = {},
        onRetry: () -> Unit = {},
        onSelect: (AlternativeCandidateDto) -> Unit = {},
        onSearch: () -> Unit = {},
        onKeep: () -> Unit = {},
        onRetryKeep: () -> Unit = onKeep,
    ) {
        composeRule.setContent {
            GilpickTheme {
                AlternativePlacesScreen(
                    state = state,
                    onBack = onBack,
                    onRetry = onRetry,
                    onSelect = onSelect,
                    onSearch = onSearch,
                    onKeep = onKeep,
                    onRetryKeep = onRetryKeep,
                    onReauthenticate = {},
                    map = { _, _, modifier -> Box(modifier = modifier.fillMaxSize().testTag(TAG_FAKE_MAP)) },
                )
            }
        }
    }

    private companion object {
        const val TAG_FAKE_MAP = "alternative_fake_map"
    }
}

private val fixtureJson = Json { ignoreUnknownKeys = true }

/** 계약 JSON fixture의 `data`를 DTO로 옮긴다. */
internal inline fun <reified T> data(body: String): T = fixtureJson.decodeFromString<SuccessEnvelope<T>>(body).data

internal fun detection(): DetectionDetailDto = data(detectionDetailJson())

internal fun alternatives(body: String = alternativesJson()): AlternativeListDto = data(body)

internal fun content(
    detection: DetectionDetailDto = detection(),
    candidates: AlternativeListDto = alternatives(),
) = AlternativeUiState.Content(detection = detection, candidates = candidates)
