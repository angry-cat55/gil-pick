package com.gilpick.progress

import com.gilpick.itinerary.DayItineraryDto
import com.gilpick.itinerary.ItemStatus
import com.gilpick.itinerary.ItineraryError
import com.gilpick.itinerary.ItineraryItemDto
import com.gilpick.route.RouteMarks
import java.time.Instant
import java.time.LocalDate

/**
 * 진행 화면의 표시 상태(`plan.md` State & Interaction, spec UI-008).
 *
 * `docs/design/ui-guidelines.md` 9절의 네 상태를 모두 쓴다. [Content]는 F004 개요(모든 날짜)와
 * 오늘 날짜의 진행 현황(PROG-001)을 합친 것이다. 화면은 이 둘을 `itemId`로 이어 장소명과 진행
 * 상태를 한 행에 그린다.
 */
sealed interface ProgressUiState {
    /** 조회 중. 화면은 1초를 넘길 때만 대기 표시를 띄운다(UI-008). */
    data object Loading : ProgressUiState

    /** 오늘 날짜에 장소가 없다. `장소 추가`를 제공한다(UI-008). */
    data object Empty : ProgressUiState

    /** 진행 현황을 보여줄 수 없다. 원인과 `다시 시도`를 제공한다. */
    data class Error(val error: ProgressError) : ProgressUiState

    /**
     * 오늘 날짜의 진행 현황.
     *
     * @property days 여행 기간의 모든 날짜(F004 개요). 헤더의 날짜 진행 표시와 장소명의 출처다.
     * @property progress 오늘 날짜의 진행 현황(PROG-001).
     * @property now 남은·지난 시간 계산에 쓰는 기기 시각. ViewModel이 1분마다 갱신한다.
     * @property pendingAction 서버에 보낸 뒤 응답을 기다리는 전환. 있는 동안 진행 행동 버튼은 비활성이다(UI-008).
     * @property actionError 마지막 전환 실패. 내용은 요청 전 그대로이고 원인과 `다시 시도`를 보인다(US2 시나리오 8).
     * @property viewingDate 목록에 보이는 날짜(UI-005). `null`이면 오늘이다. 오늘이 아니면 카드·행동·시트가 없다.
     */
    data class Content(
        val days: List<DayItineraryDto>,
        val progress: ProgressData,
        val now: Instant,
        val pendingAction: ProgressAction? = null,
        val actionError: ProgressActionFailure? = null,
        val viewingDate: LocalDate? = null,
    ) : ProgressUiState {
        /** 진행 현황의 날짜(오늘, KST). */
        val today: LocalDate get() = LocalDate.parse(progress.date)

        /** 목록에 보이는 날짜. 기본은 오늘이다. */
        val viewing: LocalDate get() = viewingDate ?: today

        /** 오늘을 보고 있다. 다음 장소 카드·진행 행동·상태 수정 시트는 오늘에만 있다(UI-005). */
        val isToday: Boolean get() = viewing == today

        /** 오늘 날짜의 개요. 개요에 오늘이 없으면(기간 밖) `null`이다. */
        val todayItinerary: DayItineraryDto? get() = days.firstOrNull { it.date == progress.date }

        /** 보고 있는 날짜의 개요. 기간 밖이면 `null`이다. */
        val viewingItinerary: DayItineraryDto? get() = days.firstOrNull { it.date == viewing.toString() }

        /** 오늘 날짜의 장소 행. 개요 순서를 따르고 진행 항목이 없는 장소는 `PLANNED`로 본다. */
        val todayRows: List<ProgressRow>
            get() {
                val byId = progress.items.associateBy { it.itemId }
                return todayItinerary?.items.orEmpty().map { item ->
                    ProgressRow(item = item, progress = byId[item.itemId] ?: plannedProgress(item, item.status))
                }
            }

        /**
         * 목록에 보이는 날짜의 장소 행. 오늘이면 [todayRows]이고, 다른 날짜는 F004 개요의 저장된 상태만
         * 있어 시각 없이 상태만 보인다(US4 시나리오 1).
         */
        val rows: List<ProgressRow>
            get() = if (isToday) todayRows else viewingItinerary?.items.orEmpty().map { ProgressRow(it, plannedProgress(it, it.status)) }

        /** 보고 있는 날짜가 시작됐다. 오늘은 `dayStatus`, 다른 날짜는 저장된 상태에 `PLANNED` 아닌 것이 있는지로 본다. */
        val viewingStarted: Boolean
            get() = if (isToday) progress.dayStatus != DayStatus.NOT_STARTED else rows.any { it.progress.status != ItemStatus.PLANNED }

        /** `ARRIVED` 장소(현재 장소). 없으면 `null`. */
        val currentRow: ProgressRow? get() = progress.currentItemId?.let { id -> todayRows.firstOrNull { it.item.itemId == id } }

        /** 다음 장소(`EN_ROUTE` 또는 첫 `PLANNED`). 남은 장소가 없으면 `null`. */
        val nextRow: ProgressRow? get() = progress.nextItemId?.let { id -> todayRows.firstOrNull { it.item.itemId == id } }

        /** 방문을 마친 장소 수(`COMPLETED`·`ARRIVED`). 헤더의 `x/y 완료`에 쓴다. */
        val visitedCount: Int get() = progress.items.count { it.status == ItemStatus.COMPLETED || it.status == ItemStatus.ARRIVED }

        private fun plannedProgress(item: ItineraryItemDto, status: ItemStatus) = ProgressItemDto(
            itemId = item.itemId, sequence = item.sequence, status = status,
            estimatedArrivalAt = null, estimatedDepartureAt = null, actualArrivedAt = null, completedAt = null,
            inboundTravel = null,
        )
    }
}

/**
 * 진행 현황을 지도·경로 화면의 진행 표시로 옮긴다(UI-011). 시작 전 날짜는 계획만 보이도록 [RouteMarks.NONE]이다.
 */
fun ProgressData.toRouteMarks(): RouteMarks = if (dayStatus == DayStatus.NOT_STARTED) {
    RouteMarks.NONE
} else {
    RouteMarks(
        start = startLocation?.let { listOf(it.longitude, it.latitude) },
        statuses = items.associate { it.itemId to it.status },
    )
}

/**
 * 사용자가 요청한 상태 전환(PROG-006 한 번). 목표 상태만 담고 파생 전환은 서버가 계산한다(research 결정 3).
 *
 * @property itemId 대상 장소.
 * @property status 목표 상태. `ARRIVED`·`COMPLETED`·`SKIPPED`·`PLANNED` 중 하나다.
 */
data class ProgressAction(val itemId: String, val status: ItemStatus)

/**
 * 전환 실패. [action]을 그대로 다시 보내면 같은 `Idempotency-Key`가 나간다(FR-017).
 *
 * `VERSION_CONFLICT`·`INVALID_STATUS_TRANSITION`은 ViewModel이 최신 현황을 다시 조회하므로 `다시 시도`가 뜻이 없다([retryable]).
 */
data class ProgressActionFailure(val action: ProgressAction, val error: ProgressError) {
    val retryable: Boolean get() = error == ProgressError.Network || error == ProgressError.Unexpected
}

/**
 * 일정 목록 한 행. F004 저장 항목(장소명·체류·이동수단)과 F006 진행 항목(상태·시각)을 잇는다.
 */
data class ProgressRow(val item: ItineraryItemDto, val progress: ProgressItemDto)

/**
 * F004 개요 조회 실패를 진행 화면이 안내할 수 있는 원인으로 좁힌다.
 *
 * 개요와 진행 현황은 한 화면의 한 `error` 상태로 합쳐지므로 원인 형식을 하나로 맞춘다. 저장
 * 전용 실패(`InvalidItinerary`·`ItemLocked`)는 조회에서 나올 수 없어 그 밖의 실패로 둔다.
 */
fun ItineraryError.toProgressError(): ProgressError = when (this) {
    ItineraryError.Network -> ProgressError.Network
    ItineraryError.NotFound -> ProgressError.NotFound
    ItineraryError.Forbidden -> ProgressError.Forbidden
    ItineraryError.SessionExpired -> ProgressError.SessionExpired
    ItineraryError.VersionConflict -> ProgressError.VersionConflict
    is ItineraryError.InvalidItinerary, is ItineraryError.ItemLocked, ItineraryError.Unexpected -> ProgressError.Unexpected
}
