package com.gilpick.notification

import com.gilpick.alternative.AlternativeError
import com.gilpick.alternative.DetectionListItemDto
import com.gilpick.alternative.VariableVerdictsDto

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

/**
 * 감지 목록 화면의 표시 상태(data-model.md 4.2, spec UI-008).
 *
 * 데이터는 F009 `AlternativeRepository`를 읽기로만 쓴다. `Empty`는 `ACTIVE` 감지 0(Figma `hasAlerts=false`)이다.
 */
sealed interface VariableMonitorUiState {
    /** 조회 중. 화면은 1초를 넘길 때만 대기 표시를 띄운다. */
    data object Loading : VariableMonitorUiState

    /** `ACTIVE` 감지가 없다. */
    data object Empty : VariableMonitorUiState

    /**
     * 목록을 보여줄 수 없다. 오류 원인·문구는 F009 형식 그대로다.
     *
     * @property retryable `다시 시도`를 보일지.
     */
    data class Error(val error: AlternativeError, val retryable: Boolean) : VariableMonitorUiState

    /**
     * `ACTIVE` 감지 목록.
     *
     * @property items 서버가 준 순서 그대로. 화면은 [sorted]를 그린다.
     * @property sort 현재 정렬. 시간순은 방문 예정 시각(`eta`)이 이른 순, 위험순은 `totalRiskScore`가 높은 순이다.
     * @property refreshing 재조회 중. 기존 목록을 그대로 두고 조용히 갱신한다.
     */
    data class Content(
        val items: List<DetectionUi>,
        val sort: DetectionSort = DetectionSort.TIME,
        val refreshing: Boolean = false,
    ) : VariableMonitorUiState {
        /** [sort]를 적용한 목록. 같은 값끼리는 방문 예정 시각이 이른 순이다. */
        val sorted: List<DetectionUi>
            get() = when (sort) {
                DetectionSort.TIME -> items.sortedBy { it.item.eta }
                DetectionSort.RISK -> items.sortedWith(compareByDescending<DetectionUi> { it.item.totalRiskScore }.thenBy { it.item.eta })
            }
    }
}

/** 감지 목록 정렬 기준(Figma `시간순`/`위험순`). */
enum class DetectionSort { TIME, RISK }

/**
 * 감지 카드 하나.
 *
 * @property item DETECT-001 항목(장소명·요약·방문 예정 시각·감지 시각).
 * @property variables DETECT-002의 변수별 판정. 상세 조회에 실패하면 `null`이고 카드는 판정 줄을 비운다(없는 값을 지어내지 않는다).
 */
data class DetectionUi(
    val item: DetectionListItemDto,
    val variables: VariableVerdictsDto?,
)
