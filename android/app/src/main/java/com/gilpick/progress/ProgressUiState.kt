package com.gilpick.progress

import com.gilpick.itinerary.DayItineraryDto
import com.gilpick.itinerary.ItemStatus
import com.gilpick.itinerary.ItineraryError
import com.gilpick.itinerary.ItineraryItemDto
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
     */
    data class Content(
        val days: List<DayItineraryDto>,
        val progress: ProgressData,
        val now: Instant,
    ) : ProgressUiState {
        /** 진행 현황의 날짜(오늘, KST). */
        val today: LocalDate get() = LocalDate.parse(progress.date)

        /** 오늘 날짜의 개요. 개요에 오늘이 없으면(기간 밖) `null`이다. */
        val todayItinerary: DayItineraryDto? get() = days.firstOrNull { it.date == progress.date }

        /** 오늘 날짜의 장소 행. 개요 순서를 따르고 진행 항목이 없는 장소는 `PLANNED`로 본다. */
        val rows: List<ProgressRow>
            get() {
                val byId = progress.items.associateBy { it.itemId }
                return todayItinerary?.items.orEmpty().map { item ->
                    ProgressRow(item = item, progress = byId[item.itemId] ?: plannedProgress(item))
                }
            }

        /** `ARRIVED` 장소(현재 장소). 없으면 `null`. */
        val currentRow: ProgressRow? get() = progress.currentItemId?.let { id -> rows.firstOrNull { it.item.itemId == id } }

        /** 다음 장소(`EN_ROUTE` 또는 첫 `PLANNED`). 남은 장소가 없으면 `null`. */
        val nextRow: ProgressRow? get() = progress.nextItemId?.let { id -> rows.firstOrNull { it.item.itemId == id } }

        /** 방문을 마친 장소 수(`COMPLETED`·`ARRIVED`). 헤더의 `x/y 완료`에 쓴다. */
        val visitedCount: Int get() = progress.items.count { it.status == ItemStatus.COMPLETED || it.status == ItemStatus.ARRIVED }

        private fun plannedProgress(item: ItineraryItemDto) = ProgressItemDto(
            itemId = item.itemId, sequence = item.sequence, status = ItemStatus.PLANNED,
            estimatedArrivalAt = null, estimatedDepartureAt = null, actualArrivedAt = null, completedAt = null,
            inboundTravel = null,
        )
    }
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
