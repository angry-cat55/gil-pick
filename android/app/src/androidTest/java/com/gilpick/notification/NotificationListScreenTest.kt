package com.gilpick.notification

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.ui.theme.GilpickTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T033: 알림 목록 화면 네 상태·그룹·안 읽음·모두 읽음·접근성 검증(quickstart AND 1.1~1.4, UI-001·UI-002·UI-004·UI-006).
 *
 * 상태는 [NotificationUiState]를 직접 넣는다. ViewModel 전이는 `NotificationListViewModelTest`가, 이동은
 * `NotificationNavigationTest`가 본다.
 */
@RunWith(AndroidJUnit4::class)
class NotificationListScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loading은_1초_전에는_표시하지_않고_이후_대기_표시를_띄운다() {
        composeRule.mainClock.autoAdvance = false
        setScreen(NotificationUiState.Loading)

        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithContentDescription("알림을 불러오는 중").assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(600)
        composeRule.onNodeWithContentDescription("알림을 불러오는 중").assertIsDisplayed()
    }

    @Test
    fun content는_오늘_어제_그룹과_제목_본문_상대_시각을_보여준다() {
        setScreen(mixedContent())

        composeRule.onNodeWithTag(TAG_GROUP_PREFIX + "TODAY").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_GROUP_PREFIX + "YESTERDAY").assertIsDisplayed()
        composeRule.onNodeWithText("다음 장소 변경을 추천해요").assertIsDisplayed()
        composeRule.onNodeWithText("인사동거리가 매우 혼잡해요. 대체 장소를 확인해보세요.").assertIsDisplayed()
        composeRule.onNodeWithText("3분 전").assertIsDisplayed()
        composeRule.onNodeWithText("12분 전").assertIsDisplayed()
        composeRule.onNodeWithText("오후 6:04").assertIsDisplayed()
    }

    @Test
    fun 안_읽은_행에만_안_읽음_점이_있고_행은_48dp_이상이다() {
        setScreen(mixedContent())

        composeRule.onNodeWithTag(TAG_UNREAD_DOT_PREFIX + NOTIF_SUGGESTION_ID, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_UNREAD_DOT_PREFIX + NOTIF_ARRIVAL_ID, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("안 읽음", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_ROW_PREFIX + NOTIF_SUGGESTION_ID).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun 행을_누르면_그_항목으로_onOpen이_불린다() {
        val opened = mutableListOf<NotifItemUi>()
        setScreen(mixedContent(), onOpen = { opened += it })

        composeRule.onNodeWithTag(TAG_ROW_PREFIX + NOTIF_ARRIVAL_ID).performClick()

        composeRule.runOnIdle { assertEquals(listOf(NOTIF_ARRIVAL_ID), opened.map { it.id }) }
    }

    @Test
    fun 헤더_버튼은_설명_문구와_48dp_터치_영역을_갖고_모두_읽음은_안_읽음이_있을_때만_활성이다() {
        var backs = 0
        var markAll = 0
        setScreen(mixedContent(), onBack = { backs++ }, onMarkAllRead = { markAll++ })

        composeRule.onNodeWithContentDescription("뒤로 가기").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithContentDescription("모두 읽음").assertIsEnabled().assertWidthIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle {
            assertEquals(1, backs)
            assertEquals(1, markAll)
        }
    }

    @Test
    fun 모두_읽은_뒤에는_모두_읽음이_비활성이다() {
        val allRead = mixedContent().let { content ->
            content.copy(groups = content.groups.map { group -> group.copy(items = group.items.map { it.copy(unread = false) }) })
        }
        setScreen(allRead)

        composeRule.onNodeWithContentDescription("모두 읽음").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("안 읽음", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun empty는_아이콘_상자_제목_설명_돌아가기를_보여준다() {
        var backs = 0
        setScreen(NotificationUiState.Empty, onBack = { backs++ })

        composeRule.onNodeWithText("받은 알림이 없어요").assertIsDisplayed()
        composeRule.onNodeWithText("도착·출발 확인과 장소 변경 제안이 오면 여기에 모아 드려요.").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_EMPTY).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun error는_원인과_다시_시도하기와_돌아가기를_보여주고_재시도를_부른다() {
        var retries = 0
        setScreen(NotificationUiState.Error(NotificationError.Network, retryable = true), onRetry = { retries++ })

        composeRule.onNodeWithText("알림을 불러올 수 없어요").assertIsDisplayed()
        composeRule.onNodeWithText("연결을 확인한 뒤 다시 시도해 주세요.").assertIsDisplayed()
        composeRule.onNodeWithText("돌아가기").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_RETRY).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun 세션_만료_error는_다시_로그인을_보여준다() {
        var reauth = 0
        setScreen(NotificationUiState.Error(NotificationError.SessionExpired, retryable = false), onReauthenticate = { reauth++ })

        composeRule.onNodeWithTag(TAG_RETRY).assertDoesNotExist()
        composeRule.onNodeWithText("다시 로그인").performClick()
        composeRule.runOnIdle { assertEquals(1, reauth) }
    }

    @Test
    fun content는_360dp_최대_글자_배율에서도_어제_그룹까지_스크롤해_볼_수_있다() {
        setScreen(mixedContent(), narrow = true)

        composeRule.onNodeWithText("다음 장소 변경을 추천해요").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_LIST).performScrollToNode(hasTestTag(TAG_ROW_PREFIX + NOTIF_AUTO_ID))
        composeRule.onNodeWithText("도착으로 자동 처리했어요").assertIsDisplayed()
    }

    private fun setScreen(
        state: NotificationUiState,
        onBack: () -> Unit = {},
        onRetry: () -> Unit = {},
        onOpen: (NotifItemUi) -> Unit = {},
        onMarkAllRead: () -> Unit = {},
        onReauthenticate: () -> Unit = {},
        narrow: Boolean = false,
    ) {
        composeRule.setContent {
            GilpickTheme {
                val screen: @Composable () -> Unit = {
                    NotificationListScreen(
                        state = state,
                        onBack = onBack,
                        onRetry = onRetry,
                        onOpen = onOpen,
                        onMarkAllRead = onMarkAllRead,
                        onReauthenticate = onReauthenticate,
                        now = NOW,
                    )
                }
                if (narrow) Narrow(screen) else screen()
            }
        }
    }

    /** 360dp 너비 + 최대 글자 배율(2.0). */
    @Composable
    private fun Narrow(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        Box(modifier = Modifier.width(360.dp)) {
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) { content() }
        }
    }
}
