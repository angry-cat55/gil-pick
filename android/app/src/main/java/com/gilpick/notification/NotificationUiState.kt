package com.gilpick.notification

/**
 * 알림 목록 화면의 표시 상태(data-model.md 4.1, spec UI-004).
 *
 * F009 `AlternativeUiState`와 같은 계열이되 `Empty`가 별도 값이다(받은 알림 0은 흔한 정상 상태).
 */
sealed interface NotificationUiState {
    /** 조회 중. 화면은 1초를 넘길 때만 대기 표시를 띄운다. */
    data object Loading : NotificationUiState

    /** 받은 알림이 없다. */
    data object Empty : NotificationUiState

    /**
     * 목록을 보여줄 수 없다.
     *
     * @property retryable `다시 시도`를 보일지. 세션 만료는 다시 보내도 같다.
     */
    data class Error(val error: NotificationError, val retryable: Boolean) : NotificationUiState

    /**
     * 날짜 구간별 알림 목록.
     *
     * @property groups `오늘`/`어제`/`그 이전` 순서, 각 구간은 최신순. 빈 구간은 없다.
     * @property refreshing 재조회 중. 기존 목록을 그대로 두고 조용히 갱신한다.
     */
    data class Content(
        val groups: List<NotifGroup>,
        val refreshing: Boolean = false,
    ) : NotificationUiState
}

/** KST 기준 날짜 구간(UI-001). 순서가 화면 순서다. */
enum class DateBucket { TODAY, YESTERDAY, EARLIER }

/** 한 날짜 구간의 알림. */
data class NotifGroup(
    val bucket: DateBucket,
    val items: List<NotifItemUi>,
)

/**
 * 목록 행 하나.
 *
 * @property createdAt 서버 생성 시각(ISO-8601). 상대 시각 문구는 그릴 때 [relativeTimeLabel]로 만든다.
 * @property unread 안 읽음. `surfaceTint` 배경 + 점 표식으로 표현한다(UI-002).
 * @property target 탭 목적지. 유형에서 파생한다([NotificationItemDto.target]).
 */
data class NotifItemUi(
    val id: String,
    val type: NotificationType,
    val title: String,
    val body: String,
    val createdAt: String,
    val unread: Boolean,
    val target: NotificationTarget,
)

/** 알림 탭·푸시 탭이 여는 화면(data-model.md 4.1·4.3). */
sealed interface NotificationTarget {
    /** 장소 변경 제안 → `AlternativePlacesRoute`. */
    data class Alternative(val detectionId: String, val tripId: String) : NotificationTarget

    /**
     * 도착·출발·자동 처리·재질문 → `ActiveTravelRoute`.
     *
     * @property tripName 진행 화면 헤더 여행명. 알림·푸시 payload에는 없어 빈 문자열이며,
     *   T030 진행 딥링크에서 여행 조회로 채운다.
     */
    data class Progress(val tripId: String, val tripName: String = "") : NotificationTarget
}
