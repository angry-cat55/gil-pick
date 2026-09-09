package com.gilpick.alternative

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.ui.theme.GilpickTheme
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T029: 직접 검색 화면 검증(quickstart AND 2-2, spec US3, UI-007·UI-008).
 *
 * 상태 전이는 `AlternativeSearchViewModelTest`가 보므로 여기서는 [AlternativeSearchUiState]를 직접 넣고
 * 화면이 어떻게 보이고 무엇을 호출하는지만 본다. 결과 DTO는 계약 JSON fixture를 그대로 역직렬화한다.
 */
@RunWith(AndroidJUnit4::class)
class AlternativeSearchScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 검색_전에는_F003_빈_상태_틀로_안내하고_칩은_없다() {
        setScreen(AlternativeSearchUiState())

        composeRule.onNodeWithText("직접 검색").assertIsDisplayed()
        composeRule.onNodeWithText("어떤 장소를 찾고 계세요?").assertIsDisplayed()
        composeRule.onNodeWithText("기존 장소 대신 갈 곳의 이름을 검색해 보세요").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("장소 이름 검색").assertIsDisplayed()
        composeRule.onNodeWithText("전체").assertDoesNotExist()
        composeRule.onNodeWithTag(TAG_SEARCH_BACK).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun 키보드_검색_동작이_검색을_실행하고_입력만으로는_실행하지_않는다() {
        var searches = 0
        var query = ""
        setScreen(AlternativeSearchUiState(), onQueryChange = { query = it }, onSearch = { searches++ })

        composeRule.onNodeWithContentDescription("장소 이름 검색").performTextInput("덕수궁")
        composeRule.runOnIdle { assertEquals(0, searches) }

        composeRule.onNodeWithContentDescription("장소 이름 검색").performImeAction()
        composeRule.runOnIdle {
            assertEquals("덕수궁", query)
            assertEquals(1, searches)
        }
    }

    @Test
    fun 짧은_검색어는_2글자_이상_입력을_안내한다() {
        setScreen(AlternativeSearchUiState(query = "궁", phase = AlternativeSearchPhase.TooShort))

        composeRule.onNodeWithText("검색어를 2글자 이상 입력해 주세요").assertIsDisplayed()
    }

    @Test
    fun 결과_행은_거리와_방문_가능_여부를_보이고_방문_불가_행은_비활성이다() {
        setScreen(content())

        composeRule.onNodeWithText("검색 결과 3곳").assertIsDisplayed()
        // 방문 가능: 거리만 있고 상태 문구가 없다.
        composeRule.onNodeWithText("기존 장소에서 820m").assertIsDisplayed()
        row("tourapi:126508").assertIsEnabled().assertHeightIsAtLeast(48.dp)
        // 운영 종료: 좌표가 없어 거리 문구가 없고 `방문 불가`와 비활성이 병기된다.
        composeRule.onNodeWithText("방문 불가").assertIsDisplayed()
        row("google:ChIJ_abc-123").assertIsNotEnabled()
        // 기존 장소 자신: `이미 일정에 있음`과 비활성.
        composeRule.onNodeWithText("기존 장소에서 0m").assertIsDisplayed()
        composeRule.onNodeWithText("이미 일정에 있음").assertIsDisplayed()
        row("tourapi:126001").assertIsNotEnabled()
    }

    @Test
    fun 방문_가능한_행을_고르면_그_항목을_넘기고_방문_불가_행은_넘기지_않는다() {
        val selected = mutableListOf<AlternativeSearchItemDto>()
        setScreen(content(), onSelect = { selected += it })

        row("google:ChIJ_abc-123").performClick()
        row("tourapi:126508").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("tourapi:126508"), selected.map { it.place.placeId })
            assertEquals(820, selected.single().distanceMeters)
        }
    }

    @Test
    fun empty는_검색어와_함께_F003_빈_상태_틀로_안내한다() {
        setScreen(AlternativeSearchUiState(query = "없는곳", committedQuery = "없는곳", phase = AlternativeSearchPhase.Empty))

        composeRule.onNodeWithText("'없는곳' 검색 결과가 없어요").assertIsDisplayed()
        composeRule.onNodeWithText("띄어쓰기나 철자를 확인해 주세요").assertIsDisplayed()
        composeRule.onNodeWithText("카테고리로 찾기").assertDoesNotExist()
    }

    @Test
    fun 다음_페이지가_있으면_목록_끝에서_이어_받고_받는_동안_표시한다() {
        var loadMore = 0
        setScreen(content(hasNext = true, loadingMore = true), onLoadMore = { loadMore++ })

        composeRule.onNodeWithContentDescription("다음 결과를 불러오는 중").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, loadMore) }
    }

    @Test
    fun 추가_조회_실패는_기존_결과와_원인을_보여주고_다음_결과_재시도를_제공한다() {
        var retries = 0
        setScreen(content(hasNext = true, loadMoreError = AlternativeError.Network), onRetryLoadMore = { retries++ })

        composeRule.onNodeWithText("검색 결과 3곳").assertIsDisplayed()
        composeRule.onNodeWithText("다음 결과를 불러오지 못했어요").assertIsDisplayed()
        composeRule.onNodeWithText("다음 결과 다시 시도").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun error는_원인을_안내하고_재시도를_제공하며_처리된_감지는_재시도가_없다() {
        var retries = 0
        setScreen(AlternativeSearchUiState(phase = AlternativeSearchPhase.Failed(AlternativeError.ProviderFailed(retryable = true))), onRetry = { retries++ })

        composeRule.onNodeWithText("대체 장소를 추천할 수 없어요").assertIsDisplayed()
        composeRule.onNodeWithText("다시 시도").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun 처리된_감지의_검색_실패는_안내만_하고_재시도가_없다() {
        setScreen(AlternativeSearchUiState(phase = AlternativeSearchPhase.Failed(AlternativeError.NotActive(DetectionStatus.DISMISSED))))

        composeRule.onNodeWithText("이 감지는 이미 처리됐어요. 진행 화면에서 최신 상태를 확인해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithText("다시 시도").assertDoesNotExist()
    }

    /** 결과 행의 누를 수 있는 본문. tag는 행 전체에 있고 클릭 semantics는 본문 Row에 있다. */
    private fun row(placeId: String) = composeRule.onNode(hasClickAction() and hasAnyAncestor(hasTestTag(TAG_SEARCH_ROW_PREFIX + placeId)))

    private fun content(
        hasNext: Boolean = false,
        loadingMore: Boolean = false,
        loadMoreError: AlternativeError? = null,
    ) = AlternativeSearchUiState(
        query = "궁궐",
        committedQuery = "궁궐",
        results = searchItems(),
        phase = AlternativeSearchPhase.Content,
        hasNext = hasNext,
        loadingMore = loadingMore,
        loadMoreError = loadMoreError,
    )

    private fun setScreen(
        state: AlternativeSearchUiState,
        onQueryChange: (String) -> Unit = {},
        onSearch: () -> Unit = {},
        onRetry: () -> Unit = {},
        onLoadMore: () -> Unit = {},
        onRetryLoadMore: () -> Unit = {},
        onSelect: (AlternativeSearchItemDto) -> Unit = {},
    ) {
        composeRule.setContent {
            GilpickTheme {
                AlternativeSearchScreen(
                    state = state,
                    onBack = {},
                    onQueryChange = onQueryChange,
                    onClearQuery = {},
                    onSearch = onSearch,
                    onRetry = onRetry,
                    onReauthenticate = {},
                    onLoadMore = onLoadMore,
                    onRetryLoadMore = onRetryLoadMore,
                    onSelect = onSelect,
                )
            }
        }
    }
}

private val searchFixtureJson = Json { ignoreUnknownKeys = true }

/** ALT-002 계약 JSON fixture의 항목을 DTO로 옮긴다. */
internal fun searchItems(body: String = searchJson()): List<AlternativeSearchItemDto> =
    searchFixtureJson.decodeFromString<AlternativeSearchEnvelope>(body).data.items
