package com.gilpick.place

import androidx.annotation.StringRes
import com.gilpick.R

/**
 * 계약 값을 화면 문구로 옮기는 규칙. 상세와 검색(#142)이 같은 표를 쓴다.
 *
 * 색으로만 구분하지 않고(가이드라인 10절) 문구가 뜻을 전달한다. Google 원문 enum은
 * server가 번역하지 않으므로 여기서 현지화한다(T035 결정).
 */

/** 내부 category 표시명. */
val PlaceCategory.labelRes: Int
    @StringRes get() = when (this) {
        PlaceCategory.NATURE -> R.string.place_category_nature
        PlaceCategory.HISTORY_CULTURE -> R.string.place_category_history_culture
        PlaceCategory.FOOD -> R.string.place_category_food
        PlaceCategory.CAFE -> R.string.place_category_cafe
        PlaceCategory.SHOPPING -> R.string.place_category_shopping
        PlaceCategory.OTHER -> R.string.place_category_other
    }

/**
 * Google 영업 상태의 표시명. 알려지지 않은 값은 표시하지 않는다.
 *
 * `OPERATIONAL`은 "지금 문을 열었다"가 아니라 "운영되는 곳이다"라는 뜻이다. 현재 영업
 * 여부를 추론해 쓰지 않는다(`spec.md` FR-007).
 */
@StringRes
fun businessStatusLabelRes(status: String?): Int? = when (status) {
    PlaceBusinessStatus.OPERATIONAL -> R.string.place_business_operational
    PlaceBusinessStatus.CLOSED_TEMPORARILY -> R.string.place_business_closed_temporarily
    PlaceBusinessStatus.CLOSED_PERMANENTLY -> R.string.place_business_closed_permanently
    else -> null
}

/** Google 평점·영업정보 중 하나라도 있는지. attribution 표시 여부를 정한다. */
val PlaceDto.hasGoogleData: Boolean
    get() = rating != null || userRatingCount != null || businessStatus != null ||
        !regularOpeningHours.isNullOrEmpty() || !currentOpeningHours.isNullOrEmpty()

/** 평점을 소수 첫째 자리까지 쓴다. `4.0`도 `4`가 아니라 `4.0`이다. */
internal fun Double.toRatingText(): String = String.format(java.util.Locale.KOREA, "%.1f", this)

/** 상세 화면의 실패 원인별 안내 문구. 원인을 뭉뚱그리지 않는다(가이드라인 9절). */
val PlaceError.detailMessageRes: Int
    @StringRes get() = when (kind) {
        PlaceErrorKind.NETWORK -> R.string.place_detail_error_network
        PlaceErrorKind.TIMEOUT -> R.string.place_detail_error_timeout
        PlaceErrorKind.RATE_LIMITED -> R.string.place_detail_error_rate_limited
        PlaceErrorKind.PROVIDER_FAILED ->
            if (retryable) R.string.place_detail_error_provider
            else R.string.place_detail_error_provider_permanent
        PlaceErrorKind.INVALID_REQUEST,
        PlaceErrorKind.NOT_FOUND,
        -> R.string.place_detail_not_found_hint
        PlaceErrorKind.SESSION_EXPIRED -> R.string.place_detail_error_session
        PlaceErrorKind.UNEXPECTED -> R.string.place_detail_error_unexpected
    }
