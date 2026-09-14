package com.gilpick.ui.component

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import com.gilpick.R
import com.gilpick.ui.theme.GilpickTheme
import com.gilpick.ui.theme.LocalGilpickColors
import com.gilpick.ui.theme.LocalGilpickRadius
import com.gilpick.ui.theme.LocalGilpickSpacing
import java.io.File
import java.time.Instant
import org.junit.Rule
import org.junit.Test

/**
 * #434: 빈 상태 4단계와 오류 화면(원인 카드·배너 유무), 360dp·글자 2.0배 screenshot 기록.
 *
 * 검증이 아니라 기록이다. PNG를 기기 저장소에 남기고 `adb pull`로 꺼내 Figma `ErrorScreen`·각 빈 상태와 대조한다.
 * 저장 위치: `/sdcard/Android/data/com.gilpick/files/screenshots/`.
 */
class StateMessageScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 빈_상태_목록_안() = capture("state_empty_inline", height = 320.dp) {
        EmptyState(
            icon = R.drawable.ic_lucide_search_x,
            title = "'경복궁' 검색 결과가 없어요",
            body = "띄어쓰기나 철자를 확인하거나\n카테고리로 찾아보세요",
            size = EmptyStateSize.Inline,
            modifier = Modifier.fillMaxSize(),
        )
    }

    @Test
    fun 빈_상태_화면_전체() = capture("state_empty_screen", height = 560.dp) {
        EmptyState(
            icon = R.drawable.ic_map,
            title = "아직 만든 여행이 없어요",
            body = "여행을 만들면 날짜별 일정과\n이동 경로를 한 번에 정리할 수 있어요",
            titleStyle = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxSize(),
            action = { GradientButton(label = "첫 여행 만들기", onClick = {}) },
        )
    }

    @Test
    fun 빈_상태_화면_전체_성공() = capture("state_empty_screen_success", height = 560.dp) {
        EmptyState(
            icon = R.drawable.ic_lucide_shield_check,
            title = "모든 일정이 예정대로예요",
            body = "10분마다 다시 확인하고,\n변수가 생기면 바로 알려드릴게요.",
            tone = EmptyStateTone.Success,
            modifier = Modifier.fillMaxSize(),
        )
    }

    @Test
    fun 빈_상태_헤더_없는_안내() = capture("state_empty_status", height = 560.dp) {
        EmptyState(
            icon = R.drawable.ic_lucide_map_pin,
            title = "위치 권한이 필요해요",
            body = "도착과 출발을 자동으로 감지하려면\n위치 권한이 필요해요",
            size = EmptyStateSize.Status,
            tone = EmptyStateTone.Primary,
            modifier = Modifier.fillMaxSize(),
            action = { GradientButton(label = "권한 허용하기", onClick = {}) },
        )
    }

    @Test
    fun 빈_상태_카드_안() = capture("state_empty_card", height = 240.dp) {
        val spacing = LocalGilpickSpacing.current
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(LocalGilpickRadius.current.xl))
                .padding(spacing.space5),
        ) {
            EmptyState(
                icon = R.drawable.ic_lucide_check,
                title = "오늘 일정을 모두 마쳤어요",
                body = "4곳 방문 · 마지막 도착 오후 6:30",
                size = EmptyStateSize.Card,
                tone = EmptyStateTone.Success,
                modifier = Modifier.fillMaxWidth().padding(vertical = spacing.space2),
            )
        }
    }

    @Test
    fun 빈_상태_어두운_배경() = capture("state_empty_dark", height = 560.dp, dark = true) {
        EmptyState(
            icon = R.drawable.ic_map,
            title = "이 날짜에는 장소가 없어요",
            body = "장소를 추가하면 경로를 계산해요",
            onDark = true,
            modifier = Modifier.fillMaxSize(),
            action = { GradientButton(label = "장소 추가", onClick = {}) },
        )
    }

    @Test
    fun 오류_원인_카드_배너_있음() = capture("state_error_full", height = 760.dp) {
        ErrorState(
            description = "경로를 업데이트하지 못했어요.\n기존 일정과 도착 시각은 그대로 유지됩니다.",
            primaryLabel = "다시 시도하기",
            onPrimary = {},
            cause = ErrorCause(occurredAt = Instant.parse("2026-09-14T05:32:00Z"), lastAction = "경로 재계산"),
            hint = "인터넷 연결을 확인한 후 재시도해주세요",
            secondaryLabel = "여행 진행으로 돌아가기",
            modifier = Modifier.fillMaxSize(),
        )
    }

    @Test
    fun 오류_원인_카드_없음_배너_있음() = capture("state_error_hint_only", height = 640.dp) {
        ErrorState(
            description = "알림을 불러오지 못했어요.\n읽은 상태는 그대로 유지됩니다.",
            primaryLabel = "다시 시도하기",
            onPrimary = {},
            hint = "인터넷 연결을 확인한 후 재시도해주세요",
            secondaryLabel = "내 여행으로 돌아가기",
            modifier = Modifier.fillMaxSize(),
        )
    }

    @Test
    fun 오류_원인_카드_있음_배너_없음() = capture("state_error_cause_only", height = 640.dp) {
        ErrorState(
            description = "후보를 불러오지 못했어요.\n기존 일정은 그대로예요.",
            primaryLabel = "다시 시도하기",
            onPrimary = {},
            cause = ErrorCause(lastAction = "대체 장소 추천"),
            modifier = Modifier.fillMaxSize(),
        )
    }

    @Test
    fun 오류_최소() = capture("state_error_minimal", height = 560.dp) {
        ErrorState(
            description = "세션이 만료됐어요.\n다시 로그인하면 이어서 볼 수 있어요.",
            primaryLabel = "다시 로그인",
            onPrimary = {},
            modifier = Modifier.fillMaxSize(),
        )
    }

    @Test
    fun 오류_360dp_최대_글자배율() = capture("state_error_full_360dp_fontscale2", height = 1100.dp, narrow = true) {
        ErrorState(
            description = "경로를 업데이트하지 못했어요.\n기존 일정과 도착 시각은 그대로 유지됩니다.",
            primaryLabel = "다시 시도하기",
            onPrimary = {},
            cause = ErrorCause(occurredAt = Instant.parse("2026-09-14T05:32:00Z"), lastAction = "경로 재계산"),
            hint = "인터넷 연결을 확인한 후 재시도해주세요",
            secondaryLabel = "여행 진행으로 돌아가기",
            modifier = Modifier.fillMaxSize(),
        )
    }

    @Test
    fun 빈_상태_360dp_최대_글자배율() = capture("state_empty_screen_360dp_fontscale2", height = 760.dp, narrow = true) {
        EmptyState(
            icon = R.drawable.ic_map,
            title = "아직 만든 여행이 없어요",
            body = "여행을 만들면 날짜별 일정과\n이동 경로를 한 번에 정리할 수 있어요",
            modifier = Modifier.fillMaxSize(),
            action = { GradientButton(label = "첫 여행 만들기", onClick = {}) },
        )
    }

    /** 높이를 고정해 화면 전체 상태를 그린다. [narrow]면 360dp + 글자 2.0배, [dark]면 일자 경로 배경. */
    private fun capture(name: String, height: androidx.compose.ui.unit.Dp, narrow: Boolean = false, dark: Boolean = false, content: @Composable () -> Unit) {
        composeRule.setContent {
            GilpickTheme {
                val density = LocalDensity.current
                val background = if (dark) LocalGilpickColors.current.darkMap else MaterialTheme.colorScheme.background
                Box(
                    modifier = Modifier
                        .then(if (narrow) Modifier.width(360.dp) else Modifier.fillMaxWidth())
                        .height(height)
                        .background(background),
                ) {
                    if (narrow) {
                        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) { content() }
                    } else {
                        content()
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
