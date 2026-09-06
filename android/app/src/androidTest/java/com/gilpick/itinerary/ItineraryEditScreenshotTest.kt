package com.gilpick.itinerary

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
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
import com.gilpick.place.PlaceCategory
import com.gilpick.ui.theme.GilpickTheme
import java.io.File
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

/**
 * T017·UI-012: 편집 화면의 상태별 screenshot 증빙.
 *
 * 검증이 아니라 기록이다. F003 `PlaceSearchScreenshotTest`와 같은 방식으로 각 상태를 그려
 * 기기 저장소에 PNG로 남기고 `adb pull`로 꺼내 사람이 Figma `ScheduleEditScreen`과 대조한다.
 *
 * 취소 확인 대화상자는 별도 window라 `captureToImage`에 잡히지 않아 `ItineraryEditScreenTest`의
 * 문구·행동 검증으로 대신한다.
 *
 * 저장 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/`.
 * `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`로 실행해야 파일이 남는다.
 */
class ItineraryEditScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 편집_empty() = capture("itinerary_edit_empty") { Screen(state()) }

    @Test
    fun 편집_content() = capture("itinerary_edit_content") { Screen(contentState()) }

    @Test
    fun 편집_content_10곳_긴_이름() = capture("itinerary_edit_content_10_long") { Screen(tenState()) }

    @Test
    fun 편집_content_최대_글자배율() = capture("itinerary_edit_content_fontscale2") { LargeFont { Screen(contentState()) } }

    @Test
    fun 편집_content_360dp() = capture("itinerary_edit_content_360dp") {
        Box(modifier = Modifier.width(360.dp)) { Screen(contentState()) }
    }

    @Test
    fun 편집_content_360dp_최대_글자배율() = capture("itinerary_edit_content_360dp_fontscale2") {
        Box(modifier = Modifier.width(360.dp)) { LargeFont { Screen(tenState()) } }
    }

    @Test
    fun 편집_error() = capture("itinerary_edit_error") { Screen(state(phase = ItineraryEditPhase.Failed(ItineraryError.Network))) }

    @Test
    fun 편집_저장_중() = capture("itinerary_edit_saving") { Screen(contentState().copy(saving = true)) }

    @Test
    fun 편집_저장_실패() = capture("itinerary_edit_save_failed") { Screen(contentState().copy(saveError = ItineraryError.Network)) }

    @Test
    fun 편집_loading() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { GilpickTheme { Screen(state(phase = ItineraryEditPhase.Loading)) } }
        composeRule.mainClock.advanceTimeBy(1_200)
        save("itinerary_edit_loading")
    }

    private fun contentState() = state(
        draft = listOf(
            draft("경복궁", 90, TransportMode.WALK),
            draft("북촌한옥마을", 60, TransportMode.TRANSIT),
            draft("인사동 쌈지길", 90, TransportMode.CAR),
            draft("창덕궁", 60),
        ),
    )

    private fun tenState() = state(
        draft = (1..9).map { draft("장소 $it", 90, TransportMode.WALK) } +
            draft("아주 긴 이름을 가진 장소 이름을 가진 장소 이름을 가진 장소 이름을 가진 장소", 120),
    )

    @Composable
    private fun Screen(state: ItineraryEditUiState) {
        ItineraryEditScreen(
            state = state,
            onClose = {},
            onSelectDate = {},
            onAddPlace = {},
            onSave = {},
            onRetry = {},
            onReauthenticate = {},
            onDismissDialog = {},
            onConfirmDiscard = {},
            onNoticeShown = {},
        )
    }

    /** 시스템 글자 확대 최대 배율(2.0)을 흉내 낸다. */
    @Composable
    private fun LargeFont(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
            content()
        }
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

    private fun state(
        phase: ItineraryEditPhase = ItineraryEditPhase.Content,
        draft: List<DraftItem> = emptyList(),
    ) = ItineraryEditUiState(
        tripId = "trip-1",
        days = (0..3).map { DayTab(LocalDate.of(2026, 5, 21 + it), it + 1) },
        selectedDate = LocalDate.of(2026, 5, 22),
        draft = draft,
        dirty = draft.isNotEmpty(),
        phase = phase,
    )

    private fun draft(name: String, stayMinutes: Int, transportToNext: TransportMode? = null) = DraftItem(
        itemId = null,
        placeId = "tourapi:$name",
        place = PlaceSnapshotDto(
            name = name,
            category = PlaceCategory.HISTORY_CULTURE,
            tourApiCategory = null,
            address = null,
            latitude = 37.58,
            longitude = 126.98,
            imageUrl = null,
        ),
        stayMinutes = stayMinutes,
        staySource = StaySource.RECOMMENDED,
        transportToNext = transportToNext,
        status = ItemStatus.PLANNED,
    )
}
