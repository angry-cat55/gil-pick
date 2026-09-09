package com.gilpick.alternative

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gilpick.R
import com.gilpick.trip.KST
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 계약 값을 대체 장소 화면 문구로 옮기는 규칙. F006 `ProgressLabels`와 같은 역할이다.
 *
 * 색으로만 구분하지 않고(가이드라인 10절) 문구가 뜻을 전달한다. 거리는 F005
 * [com.gilpick.route.distanceLabel]을, 도착 예정 시각은 F006 [com.gilpick.progress.timeLabel]을 그대로 쓴다.
 */

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(KST)

/** ISO-8601 시각을 Figma 마감 표기(`18:00`)로 바꾼다. 서버 시각의 offset과 무관하게 KST다. */
fun clockLabel(iso: String): String = CLOCK_FORMAT.format(OffsetDateTime.parse(iso).toInstant())

/**
 * 도착 예정 시각 기준 운영 상태 문구(UI-003). `18:00 마감`·`곧 마감 18:00`·`운영시간 확인 불가`.
 *
 * [OperatingStatus.CLOSED]는 후보에 없고 직접 검색에서만 `방문 불가`다. 마감 시각을 모르는
 * `OPEN`은 문구를 두지 않는다(`null`).
 */
@Composable
fun operatingLabel(status: OperatingStatus, closesAt: String?): String? = when (status) {
    OperatingStatus.UNKNOWN -> stringResource(R.string.alternative_hours_unknown)
    OperatingStatus.CLOSED -> stringResource(R.string.alternative_not_visitable)
    OperatingStatus.CLOSING_SOON -> closesAt?.let { stringResource(R.string.alternative_closing_soon, clockLabel(it)) }
        ?: stringResource(R.string.alternative_closing_soon_no_time)
    OperatingStatus.OPEN -> closesAt?.let { stringResource(R.string.alternative_closes_at, clockLabel(it)) }
}

/** 추천 근거 code 하나의 문구. 계약 밖 code는 `null`이라 표시하지 않는다(UI-003). */
val String.reasonRes: Int?
    @StringRes get() = when (this) {
        "INDOOR" -> R.string.alternative_reason_indoor
        "NOT_CROWDED" -> R.string.alternative_reason_not_crowded
        "NO_RAIN_RISK" -> R.string.alternative_reason_no_rain_risk
        "CLOSER" -> R.string.alternative_reason_closer
        "OPEN_AT_ETA" -> R.string.alternative_reason_open_at_eta
        else -> null
    }

/** 근거 code 배열을 ` · `로 이은 한 줄. 아는 code가 없으면 `null`이다. */
@Composable
fun reasonsLabel(reasons: List<String>): String? {
    val labels = reasons.mapNotNull { it.reasonRes }.map { stringResource(it) }
    return labels.takeIf { it.isNotEmpty() }?.joinToString(stringResource(R.string.alternative_separator))
}

/**
 * 감지 상세의 위험 변수 칩 문구(UI-002). F008 판정 중 위험으로 판정된 변수만 Figma 이모지·문구로 0~3개.
 */
@Composable
fun VariableVerdictsDto.riskChips(): List<String> = buildList {
    if (weather.atRisk == true) add(stringResource(R.string.alternative_chip_rain))
    if (congestion.crowded == true) add(stringResource(R.string.alternative_chip_crowded))
    if (operatingHours.closingSoon == true) add(stringResource(R.string.alternative_chip_closing))
}

/** 조회·거절 실패 원인별 안내 문구. 원인을 뭉뚱그리지 않는다(가이드라인 9절). */
val AlternativeError.messageRes: Int
    @StringRes get() = when (this) {
        AlternativeError.Network -> R.string.alternative_error_network
        is AlternativeError.NotActive -> R.string.alternative_closed_body
        AlternativeError.NotFound -> R.string.alternative_error_not_found
        AlternativeError.Forbidden -> R.string.alternative_error_forbidden
        is AlternativeError.ProviderFailed -> R.string.alternative_error_provider
        AlternativeError.SessionExpired -> R.string.alternative_error_session
        AlternativeError.Unexpected -> R.string.alternative_error_unexpected
    }

/** 처리된 감지 안내에 붙는 상태 문구. */
val DetectionStatus?.closedRes: Int
    @StringRes get() = when (this) {
        DetectionStatus.DISMISSED -> R.string.alternative_closed_dismissed
        DetectionStatus.RESOLVED -> R.string.alternative_closed_resolved
        DetectionStatus.INVALIDATED -> R.string.alternative_closed_invalidated
        DetectionStatus.ACTIVE, null -> R.string.alternative_closed_body
    }
