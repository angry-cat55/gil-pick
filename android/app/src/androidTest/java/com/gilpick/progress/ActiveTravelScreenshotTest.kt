package com.gilpick.progress

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.gilpick.itinerary.ItemStatus
import com.gilpick.route.ITEM_B
import com.gilpick.ui.theme.GilpickTheme
import com.gilpick.ui.theme.LocalGilpickColors
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * T020·T039: 진행 화면의 상태별 screenshot 증빙(UI-012·SC-007).
 *
 * 검증이 아니라 기록이다. F005 `DayRouteScreenshotTest`와 같은 방식으로 각 상태를 그려 기기 저장소에
 * PNG로 남기고 `adb pull`로 꺼내 사람이 Figma `ActiveTravelScreen`과 대조한다. 지도는 SDK 인증 없이
 * 그릴 수 있도록 `darkMap` 색 자리 표시로 바꿔 끼운다.
 *
 * 저장 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/`.
 */
class ActiveTravelScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 진행_이동_중() = capture("progress_moving") { Screen(content()) }

    @Test
    fun 진행_지연() = capture("progress_overdue") { Screen(content(now = NOW_AFTER_ETA)) }

    @Test
    fun 진행_정보_없음() = capture("progress_no_eta") { Screen(content(progress = movingWithoutEtaProgress(), days = overviewDays(todayItinerary(route = null)))) }

    @Test
    fun 진행_도착() = capture("progress_arrived") { Screen(content(progress = arrivedProgress())) }

    @Test
    fun 진행_당일_완료_건너뜀_포함() = capture("progress_all_done") { Screen(content(progress = allDoneProgress())) }

    @Test
    fun 진행_시작_전() = capture("progress_not_started") { Screen(content(progress = notStartedProgress())) }

    @Test
    fun 진행_다른_날짜_지난_일정() = capture("progress_viewing_past") {
        Screen(content(days = threeDays()).copy(viewingDate = java.time.LocalDate.parse("2026-09-07")))
    }

    @Test
    fun 진행_다른_날짜_예정_일정() = capture("progress_viewing_future") {
        Screen(content(days = threeDays()).copy(viewingDate = java.time.LocalDate.parse("2026-09-09")))
    }

    @Test
    fun 상태_수정_시트_완료() = capture("progress_sheet_completed") { Sheet(content(progress = allDoneProgress()).rows[0]) }

    @Test
    fun 상태_수정_시트_건너뜀() = capture("progress_sheet_skipped") { Sheet(content(progress = allDoneProgress()).rows[1]) }

    @Test
    fun 상태_수정_시트_도착() = capture("progress_sheet_arrived") { Sheet(content(progress = allDoneProgress()).rows[2]) }

    @Test
    fun 상태_수정_시트_이동_중() = capture("progress_sheet_en_route") { Sheet(content().rows[1]) }

    @Test
    fun 상태_수정_시트_예정_최대_글자배율() = capture("progress_sheet_planned_fontscale2") { LargeFont { Sheet(content().rows[2]) } }

    /** 시트는 별도 window라 화면 capture에 찍히지 않는다. 내용만 inline으로 그린다(F004와 같은 방식). */
    @Composable
    private fun Sheet(row: ProgressRow) {
        GilpickTheme { StatusSheetContent(row = row, onAction = {}, onCancel = {}) }
    }

    @Test
    fun 진행_요청_중() = capture("progress_pending") {
        Screen(content().copy(pendingAction = ProgressAction(ITEM_B, ItemStatus.ARRIVED)))
    }

    @Test
    fun 진행_전환_실패() = capture("progress_action_error") {
        Screen(content().copy(actionError = ProgressActionFailure(ProgressAction(ITEM_B, ItemStatus.ARRIVED), ProgressError.Network)))
    }

    @Test
    fun 진행_empty() = capture("progress_empty") { Screen(ProgressUiState.Empty) }

    @Test
    fun 진행_error() = capture("progress_error") { Screen(ProgressUiState.Error(ProgressError.Network)) }

    @Test
    fun 진행_loading() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { GilpickTheme { Screen(ProgressUiState.Loading) } }
        composeRule.mainClock.advanceTimeBy(1_200)
        save("progress_loading")
    }

    @Test
    fun 진행_이동_중_360dp() = capture("progress_moving_360dp") {
        Box(modifier = Modifier.width(360.dp)) { Screen(content()) }
    }

    @Test
    fun 진행_이동_중_최대_글자배율() = capture("progress_moving_fontscale2") { LargeFont { Screen(content()) } }

    // T039: 360dp + 최대 글자 배율(2.0) 조합. 시작 전·이동 중·지연·도착·당일 완료(건너뜀 포함)·다른 날짜·loading/empty/error·전환 실패.
    @Test
    fun 진행_이동_중_360dp_최대_글자배율() = capture("progress_moving_360dp_fontscale2") { Narrow { Screen(content()) } }

    @Test
    fun 진행_지연_360dp_최대_글자배율() = capture("progress_overdue_360dp_fontscale2") { Narrow { Screen(content(now = NOW_AFTER_ETA)) } }

    @Test
    fun 진행_도착_360dp_최대_글자배율() = capture("progress_arrived_360dp_fontscale2") { Narrow { Screen(content(progress = arrivedProgress())) } }

    @Test
    fun 진행_당일_완료_360dp_최대_글자배율() = capture("progress_all_done_360dp_fontscale2") { Narrow { Screen(content(progress = allDoneProgress())) } }

    @Test
    fun 진행_시작_전_360dp_최대_글자배율() = capture("progress_not_started_360dp_fontscale2") { Narrow { Screen(content(progress = notStartedProgress())) } }

    @Test
    fun 진행_다른_날짜_지난_일정_360dp_최대_글자배율() = capture("progress_viewing_past_360dp_fontscale2") {
        Narrow { Screen(content(days = threeDays()).copy(viewingDate = java.time.LocalDate.parse("2026-09-07"))) }
    }

    @Test
    fun 진행_다른_날짜_예정_일정_360dp_최대_글자배율() = capture("progress_viewing_future_360dp_fontscale2") {
        Narrow { Screen(content(days = threeDays()).copy(viewingDate = java.time.LocalDate.parse("2026-09-09"))) }
    }

    @Test
    fun 진행_전환_실패_360dp_최대_글자배율() = capture("progress_action_error_360dp_fontscale2") {
        Narrow { Screen(content().copy(actionError = ProgressActionFailure(ProgressAction(ITEM_B, ItemStatus.ARRIVED), ProgressError.Network))) }
    }

    @Test
    fun 진행_empty_360dp_최대_글자배율() = capture("progress_empty_360dp_fontscale2") { Narrow { Screen(ProgressUiState.Empty) } }

    @Test
    fun 진행_error_360dp_최대_글자배율() = capture("progress_error_360dp_fontscale2") { Narrow { Screen(ProgressUiState.Error(ProgressError.Network)) } }

    @Test
    fun 진행_loading_360dp_최대_글자배율() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { GilpickTheme { Narrow { Screen(ProgressUiState.Loading) } } }
        composeRule.mainClock.advanceTimeBy(1_200)
        save("progress_loading_360dp_fontscale2")
    }

    @Test
    fun 상태_수정_시트_도착_360dp_최대_글자배율() = capture("progress_sheet_arrived_360dp_fontscale2") {
        Narrow { Sheet(content(progress = allDoneProgress()).rows[2]) }
    }

    // T036(F007): 도착 확인·출발 확인·자동 확정 후 되돌리기·되돌리기 만료·자동 감지 꺼짐(UI-010). 기본과 360dp+2.0 두 벌.
    // 확인 시트는 별도 window라 상태 수정 시트와 같이 내용만 inline으로 그린다.
    @Test
    fun 도착_확인_시트() = capture("detection_arrival_sheet") { ConfirmSheetInline(arrivalCandidate()) }

    @Test
    fun 도착_확인_시트_360dp_최대_글자배율() = capture("detection_arrival_sheet_360dp_fontscale2") { Narrow { ConfirmSheetInline(arrivalCandidate()) } }

    @Test
    fun 출발_확인_시트() = capture("detection_departure_sheet") { ConfirmSheetInline(departureCandidate()) }

    @Test
    fun 출발_확인_시트_360dp_최대_글자배율() = capture("detection_departure_sheet_360dp_fontscale2") { Narrow { ConfirmSheetInline(departureCandidate()) } }

    @Test
    fun 도착_확인_시트_응답_실패() = capture("detection_arrival_sheet_error") { ConfirmSheetInline(arrivalCandidate(), error = DetectionError.Network) }

    @Test
    fun 도착_확인_시트_응답_실패_360dp_최대_글자배율() = capture("detection_arrival_sheet_error_360dp_fontscale2") {
        Narrow { ConfirmSheetInline(arrivalCandidate(), error = DetectionError.Network) }
    }

    @Test
    fun 자동_확정_되돌리기() = capture("detection_undo_toast") { Screen(undoableContent(NOW_UNDOABLE)) }

    @Test
    fun 자동_확정_되돌리기_360dp_최대_글자배율() = capture("detection_undo_toast_360dp_fontscale2") { Narrow { Screen(undoableContent(NOW_UNDOABLE)) } }

    @Test
    fun 되돌리기_만료() = capture("detection_undo_expired") { Screen(undoableContent(NOW_UNDO_EXPIRED)) }

    @Test
    fun 되돌리기_만료_360dp_최대_글자배율() = capture("detection_undo_expired_360dp_fontscale2") { Narrow { Screen(undoableContent(NOW_UNDO_EXPIRED)) } }

    @Test
    fun 자동_감지_꺼짐() = capture("detection_off") { Screen(content().copy(detectionOff = DetectionOffReason.PermissionMissing)) }

    @Test
    fun 자동_감지_꺼짐_360dp_최대_글자배율() = capture("detection_off_360dp_fontscale2") {
        Narrow { Screen(content().copy(detectionOff = DetectionOffReason.PermissionMissing)) }
    }

    /** 북촌한옥마을 도착이 자동 확정된 직후의 화면. 토스트와 목록 행의 `자동 처리` 표시가 함께 보인다. */
    private fun undoableContent(now: java.time.Instant) =
        content(progress = arrivedProgress().copy(undoable = arrivalUndoable()), now = now)

    @Composable
    private fun ConfirmSheetInline(candidate: TransitionCandidateDto, error: DetectionError? = null) {
        GilpickTheme {
            ConfirmSheetContent(
                candidate = candidate,
                placeName = "북촌한옥마을",
                now = NOW_CANDIDATE,
                submitting = false,
                error = error,
                onDecide = {},
                onRetry = {},
            )
        }
    }

    /** 360dp 너비 + 최대 글자 배율. */
    @Composable
    private fun Narrow(content: @Composable () -> Unit) {
        Box(modifier = Modifier.width(360.dp)) { LargeFont { content() } }
    }

    @Composable
    private fun Screen(state: ProgressUiState) {
        ActiveTravelScreen(
            state = state,
            tripName = "서울 자유여행",
            onRetry = {},
            onAddPlace = {},
            onOpenRoute = { _, _ -> },
            onReauthenticate = {},
            map = { _, _, modifier -> Box(modifier = modifier.fillMaxSize().background(LocalGilpickColors.current.darkMap)) },
        )
    }

    /** 시스템 글자 확대 최대 배율(2.0)을 흉내 낸다. */
    @Composable
    private fun LargeFont(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) { content() }
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent { GilpickTheme { content() } }
        composeRule.waitForIdle()
        save(name)
    }

    private fun save(name: String) {
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
