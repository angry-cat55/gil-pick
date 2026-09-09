package com.gilpick.progress

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.ui.theme.GilpickTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T031: 자동 감지가 꺼졌을 때의 축소 동작 검증(quickstart FE 5, UI-005·FR-024·FR-025).
 *
 * 핵심은 **자동 감지가 부가 기능**이라는 것이다(constitution I). 권한이 없어도 F006 수동 진행은
 * 하나도 줄지 않아야 하고, 안내는 원인과 켜는 방법을 함께 주되 무시할 수 있어야 한다.
 *
 * 권한 자체는 기기 상태라 test에서 바꿀 수 없으므로, 권한이 없다고 판정된 결과 상태
 * ([ProgressUiState.Content.detectionOff])를 직접 넣는다. 권한 판정에서 이 상태로 이어지는 부분은
 * `ProgressViewModelTest`가 다룬다.
 */
@RunWith(AndroidJUnit4::class)
class DetectionPermissionTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- UI-005 안내 ---

    @Test
    fun 안내는_꺼진_원인과_켜는_방법을_함께_보여준다() {
        setScreen(permissionMissing())

        composeRule.onNodeWithText("위치 권한이 없어 자동 감지가 꺼져 있어요").assertIsDisplayed()
        // 켜는 방법과 "켜지 않아도 된다"를 함께 적는다. 색만으로 알리지 않는다(가이드라인 10절).
        composeRule.onNodeWithText("항상 허용으로 바꾸면 도착·출발을 자동으로 확인해 드려요. 지금처럼 직접 처리하셔도 됩니다.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("권한 허용").assertIsDisplayed()
    }

    @Test
    fun 권한_허용을_누르면_켜는_흐름으로_넘긴다() {
        var enabled = 0
        setScreen(permissionMissing(), onEnableDetection = { enabled++ })

        composeRule.onNodeWithText("권한 허용").performClick()

        assertEquals(1, enabled)
    }

    @Test
    fun 안내는_닫을_수_있다() {
        // 안내를 따르지 않아도 진행을 계속할 수 있어야 한다(FR-025).
        var dismissed = 0
        setScreen(permissionMissing(), onDismissDetectionNotice = { dismissed++ })

        composeRule.onNodeWithText("닫기").performClick()

        assertEquals(1, dismissed)
    }

    @Test
    fun 닫은_뒤에는_안내가_사라진다() {
        setScreen(permissionMissing().copy(detectionNoticeDismissed = true))

        composeRule.onNodeWithTag(TAG_DETECTION_OFF).assertDoesNotExist()
    }

    @Test
    fun 자동_감지가_켜져_있으면_안내가_없다() {
        setScreen(content())

        composeRule.onNodeWithTag(TAG_DETECTION_OFF).assertDoesNotExist()
    }

    @Test
    fun 오늘이_아닌_날짜에는_안내가_없다() {
        // 지난 날짜에는 감지 자체가 없으므로 꺼졌다는 안내도 뜻이 없다.
        setScreen(permissionMissing().copy(viewingDate = LocalDate.parse("2026-09-07")))

        composeRule.onNodeWithTag(TAG_DETECTION_OFF).assertDoesNotExist()
    }

    // --- FR-024 축소 동작 ---

    @Test
    fun 권한이_없어도_수동_진행_행동이_모두_동작한다() {
        var arrived = 0
        var skipped = 0
        setScreen(permissionMissing(), onArrive = { arrived++ }, onSkip = { skipped++ })

        composeRule.onNodeWithText("도착했어요").assertIsEnabled().performClick()
        composeRule.onNodeWithText("건너뛰기").assertIsEnabled().performClick()

        assertEquals(1, arrived)
        assertEquals(1, skipped)
    }

    @Test
    fun 권한이_없어도_상태_수정_시트를_열_수_있다() {
        setScreen(permissionMissing())

        composeRule.onNodeWithContentDescription("2번째 장소 북촌한옥마을, 이동 중, 오후 2:20 도착 예정")
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithTag(TAG_STATUS_SHEET).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("북촌한옥마을 상태 수정").assertIsDisplayed()
    }

    @Test
    fun 권한을_회수해도_이미_확정된_상태는_그대로다() {
        // 진행 도중 권한을 회수하면 자동 감지만 멈춘다. 이미 확정된 방문 기록은 건드리지 않는다.
        setScreen(permissionMissing())

        composeRule.onNodeWithTag(TAG_DETECTION_OFF).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("1번째 장소 경복궁, 완료, 오후 2:00 방문 완료")
            .performScrollTo()
            .assertIsDisplayed()
    }

    // --- UI-008 접근성 ---

    @Test
    fun 안내의_두_행동은_터치_영역이_48dp_이상이다() {
        setScreen(permissionMissing())

        composeRule.onNodeWithText("권한 허용").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("닫기").assertHeightIsAtLeast(48.dp)
    }

    private fun permissionMissing() = content().copy(detectionOff = DetectionOffReason.PermissionMissing)

    private fun setScreen(
        state: ProgressUiState,
        onArrive: () -> Unit = {},
        onSkip: () -> Unit = {},
        onEnableDetection: () -> Unit = {},
        onDismissDetectionNotice: () -> Unit = {},
    ) {
        composeRule.setContent {
            GilpickTheme {
                ActiveTravelScreen(
                    state = state,
                    tripName = "서울 여행",
                    onRetry = {},
                    onAddPlace = {},
                    onOpenRoute = { _, _ -> },
                    onReauthenticate = {},
                    onArrive = onArrive,
                    onSkip = onSkip,
                    onEnableDetection = onEnableDetection,
                    onDismissDetectionNotice = onDismissDetectionNotice,
                    map = { _, _, modifier -> FakeMap(modifier) },
                )
            }
        }
    }

    @Composable
    private fun FakeMap(modifier: Modifier) {
        Box(modifier = modifier.fillMaxSize().testTag(TAG_FAKE_MAP))
    }

    private companion object {
        const val TAG_FAKE_MAP = "detection_permission_fake_map"
    }
}
