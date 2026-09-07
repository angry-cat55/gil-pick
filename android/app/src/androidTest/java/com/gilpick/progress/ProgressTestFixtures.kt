package com.gilpick.progress

import com.gilpick.auth.ResponseMeta
import com.gilpick.auth.SuccessEnvelope
import com.gilpick.itinerary.DayItineraryDto
import com.gilpick.itinerary.ItemStatus
import com.gilpick.itinerary.ItineraryItemDto
import com.gilpick.itinerary.ItineraryOverviewDto
import com.gilpick.itinerary.ItineraryPlaceDto
import com.gilpick.itinerary.RouteStatus
import com.gilpick.itinerary.StaySource
import com.gilpick.itinerary.TransportMode
import com.gilpick.place.PlaceCategory
import com.gilpick.route.ITEM_A
import com.gilpick.route.ITEM_B
import com.gilpick.route.ITEM_C
import com.gilpick.route.ROUTE_TRIP_ID
import com.gilpick.route.readyRoute
import java.time.Instant
import kotlinx.serialization.json.Json

/**
 * progress androidTest가 공유하는 fixture. 9/7~9/9 사흘 여행의 둘째 날(9/8)이 오늘이고 장소는 경복궁 →
 * 북촌한옥마을 → 인사동거리 세 곳이다. 시각은 UTC로 두고 화면이 KST(`오후 2:20`)로 보인다.
 */

internal const val PROGRESS_TRIP_ID = ROUTE_TRIP_ID
internal const val PROGRESS_DATE = "2026-09-08"
internal const val PROGRESS_REQUEST_ID = "11111111-2222-4333-8444-555555555555"

/** 오후 2:08 KST. 북촌한옥마을 ETA(오후 2:20)까지 12분 남았다(Figma `· 12분 남았어요`). */
internal val NOW_BEFORE_ETA: Instant = Instant.parse("2026-09-08T05:08:00Z")

/** 오후 2:25 KST. ETA를 5분 지났다(Figma `· 5분 지났어요`). */
internal val NOW_AFTER_ETA: Instant = Instant.parse("2026-09-08T05:25:00Z")

internal fun itineraryItem(itemId: String, sequence: Int, name: String, transportToNext: TransportMode?) = ItineraryItemDto(
    itemId = itemId,
    place = ItineraryPlaceDto(placeId = "tourapi:$sequence", name = name, category = PlaceCategory.HISTORY_CULTURE, address = null, imageUrl = null),
    sequence = sequence,
    plannedStayMinutes = 90,
    staySource = StaySource.RECOMMENDED,
    transportModeToNext = transportToNext,
    status = ItemStatus.PLANNED,
)

/** 오늘(9/8, 2일차)의 개요. 경로는 F005 READY 경로다. [route]를 `null`로 주면 경로가 아직 없는 날짜다. */
internal fun todayItinerary(route: com.gilpick.route.RouteDto? = readyRoute(scheduleVersion = 3)) = DayItineraryDto(
    date = PROGRESS_DATE,
    dayNumber = 2,
    version = 3,
    routeStatus = if (route != null) RouteStatus.READY else RouteStatus.NOT_CALCULATED,
    items = listOf(
        itineraryItem(ITEM_A, 1, "경복궁", TransportMode.TRANSIT),
        itineraryItem(ITEM_B, 2, "북촌한옥마을", TransportMode.WALK),
        itineraryItem(ITEM_C, 3, "인사동거리", null),
    ),
    route = route,
)

/** 사흘 여행의 개요. 어제·내일은 장소가 없다. */
internal fun overviewDays(today: DayItineraryDto = todayItinerary()) = listOf(
    DayItineraryDto(date = "2026-09-07", dayNumber = 1, version = 0, routeStatus = RouteStatus.NOT_CALCULATED, items = emptyList()),
    today,
    DayItineraryDto(date = "2026-09-09", dayNumber = 3, version = 0, routeStatus = RouteStatus.NOT_CALCULATED, items = emptyList()),
)

private fun item(
    itemId: String,
    sequence: Int,
    status: ItemStatus,
    eta: String? = null,
    arrivedAt: String? = null,
    completedAt: String? = null,
    inbound: InboundTravelDto? = null,
) = ProgressItemDto(
    itemId = itemId, sequence = sequence, status = status,
    estimatedArrivalAt = eta, estimatedDepartureAt = null, actualArrivedAt = arrivedAt, completedAt = completedAt,
    inboundTravel = inbound,
)

private val fromA = InboundTravelDto(fromItemId = ITEM_A, transportMode = TransportMode.TRANSIT, durationSeconds = 1200, distanceMeters = 3400, source = TravelSource.PLANNED_ROUTE)
private val fromStart = InboundTravelDto(fromItemId = null, transportMode = TransportMode.WALK, durationSeconds = 600, distanceMeters = 800, source = TravelSource.COMPUTED)

private fun progress(
    dayStatus: DayStatus,
    currentItemId: String?,
    nextItemId: String?,
    items: List<ProgressItemDto>,
    completedAt: String? = null,
) = ProgressData(
    tripId = PROGRESS_TRIP_ID,
    date = PROGRESS_DATE,
    dayStatus = dayStatus,
    progressVersion = 2,
    scheduleVersion = 3,
    actualStartedAt = "2026-09-08T04:00:00Z",
    completedAt = completedAt,
    startLocation = StartLocationDto(37.57, 126.97),
    currentItemId = currentItemId,
    nextItemId = nextItemId,
    items = items,
)

/** 이동 중: 경복궁 완료(오후 2:00), 북촌한옥마을 이동 중(ETA 오후 2:20), 인사동거리 예정(ETA 없음). */
internal fun movingProgress() = progress(
    dayStatus = DayStatus.IN_PROGRESS, currentItemId = null, nextItemId = ITEM_B,
    items = listOf(
        item(ITEM_A, 1, ItemStatus.COMPLETED, eta = "2026-09-08T04:10:00Z", arrivedAt = "2026-09-08T04:12:00Z", completedAt = "2026-09-08T05:00:00Z", inbound = fromStart),
        item(ITEM_B, 2, ItemStatus.EN_ROUTE, eta = "2026-09-08T05:20:00Z", inbound = fromA),
        item(ITEM_C, 3, ItemStatus.PLANNED),
    ),
)

/** 이동 중인데 다음 장소의 ETA와 이동 정보가 없다(FR-005 `정보 없음`). */
internal fun movingWithoutEtaProgress() = movingProgress().let { data ->
    data.copy(items = data.items.map { if (it.itemId == ITEM_B) it.copy(estimatedArrivalAt = null, inboundTravel = null) else it })
}

/** 도착: 북촌한옥마을에 오후 2:18 도착, 인사동거리가 다음(ETA 오후 4:00). */
internal fun arrivedProgress() = progress(
    dayStatus = DayStatus.IN_PROGRESS, currentItemId = ITEM_B, nextItemId = ITEM_C,
    items = listOf(
        item(ITEM_A, 1, ItemStatus.COMPLETED, eta = "2026-09-08T04:10:00Z", arrivedAt = "2026-09-08T04:12:00Z", completedAt = "2026-09-08T05:00:00Z", inbound = fromStart),
        item(ITEM_B, 2, ItemStatus.ARRIVED, eta = "2026-09-08T05:20:00Z", arrivedAt = "2026-09-08T05:18:00Z", inbound = fromA),
        item(ITEM_C, 3, ItemStatus.PLANNED, eta = "2026-09-08T07:00:00Z"),
    ),
)

/** 당일 완료: 경복궁 완료, 북촌한옥마을 건너뜀, 인사동거리 도착(오후 3:30)으로 남음. */
internal fun allDoneProgress() = progress(
    dayStatus = DayStatus.COMPLETED, currentItemId = ITEM_C, nextItemId = null, completedAt = "2026-09-08T06:30:00Z",
    items = listOf(
        item(ITEM_A, 1, ItemStatus.COMPLETED, eta = "2026-09-08T04:10:00Z", arrivedAt = "2026-09-08T04:12:00Z", completedAt = "2026-09-08T05:00:00Z", inbound = fromStart),
        item(ITEM_B, 2, ItemStatus.SKIPPED, eta = "2026-09-08T05:20:00Z", inbound = fromA),
        item(ITEM_C, 3, ItemStatus.ARRIVED, eta = "2026-09-08T06:20:00Z", arrivedAt = "2026-09-08T06:30:00Z"),
    ),
)

/** 시작 전: 모두 예정, ETA 없음. */
internal fun notStartedProgress() = progress(
    dayStatus = DayStatus.NOT_STARTED, currentItemId = null, nextItemId = ITEM_A,
    items = listOf(item(ITEM_A, 1, ItemStatus.PLANNED), item(ITEM_B, 2, ItemStatus.PLANNED), item(ITEM_C, 3, ItemStatus.PLANNED)),
).copy(progressVersion = 0, actualStartedAt = null, startLocation = null)

internal fun content(
    progress: ProgressData = movingProgress(),
    now: Instant = NOW_BEFORE_ETA,
    days: List<DayItineraryDto> = overviewDays(),
) = ProgressUiState.Content(days = days, progress = progress, now = now)

private val envelopeJson = Json { encodeDefaults = true }

/** 계약 형식의 성공 envelope JSON. MockWebServer 응답에 쓴다. */
internal inline fun <reified T> envelopeJson(data: T): String =
    envelopeJson.encodeToString(SuccessEnvelope(success = true, data = data, meta = ResponseMeta(requestId = PROGRESS_REQUEST_ID)))

internal fun overviewJson(days: List<DayItineraryDto> = overviewDays()) = envelopeJson(ItineraryOverviewDto(tripId = PROGRESS_TRIP_ID, days = days))

internal fun progressJson(progress: ProgressData = movingProgress()) = envelopeJson(progress)
