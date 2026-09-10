package com.gilpick.replacement

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.auth.SuccessEnvelope
import com.gilpick.route.readyRoute
import com.gilpick.ui.theme.GilpickTheme
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T011: 미리보기 화면 세 상태와 비교 표현 검증(quickstart FE 2~3, spec UI-001·UI-002·UI-004·UI-008).
 *
 * 상태는 ViewModel 없이 [PreviewUiState]를 직접 넣는다. 응답 DTO는 계약 JSON fixture를 그대로
 * 역직렬화해 계약과 화면이 어긋나면 여기서 드러난다. 지도는 SDK 인증이 필요해 자리 표시로 바꿔 낀다.
 *
 * **`empty` 상태 test가 없는 이유**: 이 화면에는 `empty`가 없다. 후보가 없는 상황은 F009 대체 장소
 * 화면이 처리하고, 이 화면은 이미 고른 후보 하나로만 열려 "보여 줄 것이 없는" 상태가 생기지
 * 않는다(UI-004, data-model 4.1).
 */
@RunWith(AndroidJUnit4::class)
class RoutePreviewScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loading은_1초_전에는_표시하지_않고_이후_대기_표시를_띄운다() {
        composeRule.mainClock.autoAdvance = false
        setScreen(PreviewUiState.Loading)

        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithContentDescription("비교를 만드는 중").assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(600)
        composeRule.onNodeWithContentDescription("비교를 만드는 중").assertIsDisplayed()
    }

    @Test
    fun 지도_범례는_색이_아니라_문구로도_기존과_변경을_구분한다() {
        // UI-001. 색각 이상이나 흑백 화면에서도 어느 쪽이 기존인지 알 수 있어야 한다.
        setScreen(content())

        composeRule.onNodeWithText("기존").assertIsDisplayed()
        composeRule.onNodeWithText("변경").assertIsDisplayed()
    }

    @Test
    fun content는_바뀌는_장소와_감지_이유와_비교_네_항목을_보여준다() {
        setScreen(content())

        composeRule.onNodeWithTag(TAG_CHANGE_SUMMARY).assertIsDisplayed()
        composeRule.onNodeWithText("경복궁 → 창덕궁").assertIsDisplayed()
        composeRule.onNodeWithText("오후 2시 이후 강한 비 + 매우 높은 혼잡").assertIsDisplayed()

        // FR-002의 네 항목이 이전 값과 이후 값을 함께 보인다.
        composeRule.onNodeWithText("이동 시간").assertIsDisplayed()
        composeRule.onNodeWithText("이동 거리").assertIsDisplayed()
        composeRule.onNodeWithText("도착 예정").assertIsDisplayed()
        composeRule.onNodeWithText("마감 시간").assertIsDisplayed()
    }

    @Test
    fun 나아짐과_나빠짐을_색_단독이_아니라_기호와_문구로도_구분한다() {
        // UI-002. 이동 시간 30분 → 25분은 나아짐, 이동 거리 5km → 4.2km도 나아짐이다.
        setScreen(content())

        // 접근성 문구가 항목명·이전 값·이후 값과 판정을 함께 읽힌다.
        composeRule
            .onNodeWithContentDescription("이동 시간 기존 30분에서 변경 후 25분 나아짐")
            .assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("이동 거리 기존 5.0km에서 변경 후 4.2km 나아짐")
            .assertIsDisplayed()
    }

    @Test
    fun 확인하지_못한_비교_항목은_정보_없음으로_두고_값을_지어내지_않는다() {
        // FR-002. closesAt이 null인 미리보기다.
        setScreen(content(json = previewWithoutClosingTimeJson()))

        composeRule
            .onNodeWithContentDescription("마감 시간 기존 정보 없음에서 변경 후 정보 없음")
            .assertIsDisplayed()
    }

    @Test
    fun error는_원인과_기존_일정_유지_문구와_두_행동을_보여준다() {
        // UI-004·FR-007. 경로를 계산하지 못해도 일정은 그대로다.
        var retries = 0
        var others = 0
        setScreen(
            PreviewUiState.Error(ReplacementError.RouteUnavailable(retryable = true)),
            onRetry = { retries++ },
            onOtherCandidates = { others++ },
        )

        composeRule.onNodeWithText("비교를 만들 수 없어요").assertIsDisplayed()
        composeRule.onNodeWithText("경로를 계산하지 못했어요", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("기존 일정은 그대로예요", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_RETRY).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithTag(TAG_OTHER_CANDIDATES).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, others)
        }
    }

    @Test
    fun 세션이_만료되면_재인증_행동을_대신_보인다() {
        var reauth = 0
        setScreen(PreviewUiState.Error(ReplacementError.SessionExpired), onReauthenticate = { reauth++ })

        composeRule.onNodeWithTag(TAG_RETRY).assertDoesNotExist()
        composeRule.onNodeWithText("다시 로그인").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, reauth) }
    }

    @Test
    fun 승인과_다른_후보_보기는_48dp_이상이고_각각_호출된다() {
        // UI-008. 두 행동 모두 터치 영역이 48dp 이상이다.
        var approves = 0
        var others = 0
        setScreen(content(), onApprove = { approves++ }, onOtherCandidates = { others++ })

        composeRule.onNodeWithTag(TAG_APPROVE).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithTag(TAG_OTHER_CANDIDATES).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle {
            assertEquals(1, approves)
            assertEquals(1, others)
        }
    }

    @Test
    fun 뒤로_가기는_48dp_이상이고_호출된다() {
        var backs = 0
        setScreen(content(), onBack = { backs++ })

        composeRule.onNodeWithTag(TAG_BACK).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, backs) }
    }

    private fun content(json: String = routePreviewJson()) = PreviewUiState.Content(
        preview = previewJson.decodeFromString<SuccessEnvelope<RoutePreviewDto>>(json).data,
        originalRoute = readyRoute(),
    )

    private fun setScreen(
        state: PreviewUiState,
        onBack: () -> Unit = {},
        onRetry: () -> Unit = {},
        onApprove: () -> Unit = {},
        onOtherCandidates: () -> Unit = {},
        onReauthenticate: () -> Unit = {},
    ) {
        composeRule.setContent {
            GilpickTheme {
                RoutePreviewScreen(
                    state = state,
                    onBack = onBack,
                    onRetry = onRetry,
                    onApprove = onApprove,
                    onOtherCandidates = onOtherCandidates,
                    onReauthenticate = onReauthenticate,
                    // 지도는 Naver SDK 인증 key가 필요해 계측 환경에서 그릴 수 없다.
                    map = { _, modifier -> Box(modifier.fillMaxSize().testTag(TAG_MAP_SLOT)) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    private companion object {
        val previewJson = Json { ignoreUnknownKeys = true }
    }
}
