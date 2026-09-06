package com.gilpick.itinerary

import com.gilpick.auth.ResponseMeta
import com.gilpick.auth.SuccessEnvelope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * 응답을 test가 직접 정하는 [ItineraryService].
 *
 * 실제 HTTP 왕복은 `ItineraryApiTest`·`ItineraryRepositoryTest`가 MockWebServer로 검증하므로,
 * 여기서는 ViewModel 상태 전이에 필요한 요청 기록과 응답만 다룬다. F003 `FakePlaceService`와
 * 같은 구조다.
 */
class FakeItineraryService : ItineraryService {

    /** 지금까지 도착한 저장 요청. 호출 순서대로 쌓인다. */
    val saveCalls = mutableListOf<SaveCall>()

    /** 지금까지 도착한 날짜별 조회의 날짜. */
    val dayCalls = mutableListOf<String>()

    /** 개요 조회 응답. 기본값은 날짜 3개(9/8~9/10)가 모두 빈 여행이다. */
    var onOverview: suspend () -> Response<SuccessEnvelope<ItineraryOverviewDto>> =
        { ok(overview()) }

    /** 날짜별 조회 응답. 기본값은 계약에 없는 호출을 막는 실패다. */
    var onGetDay: suspend (String) -> Response<SuccessEnvelope<DayItineraryDto>> =
        { error("이 test는 날짜별 조회 endpoint를 호출하지 않는다") }

    /** 저장 응답. 기본값은 계약에 없는 호출을 막는 실패다. */
    var onSave: suspend (SaveCall) -> Response<SuccessEnvelope<DayItineraryDto>> =
        { error("이 test는 저장 endpoint를 호출하지 않는다") }

    override suspend fun getOverview(bearer: String, tripId: String) = onOverview()

    override suspend fun getDayItinerary(bearer: String, tripId: String, date: String): Response<SuccessEnvelope<DayItineraryDto>> {
        dayCalls += date
        return onGetDay(date)
    }

    override suspend fun saveDayItinerary(
        bearer: String,
        idempotencyKey: String,
        tripId: String,
        date: String,
        body: SaveDayItineraryRequest,
    ): Response<SuccessEnvelope<DayItineraryDto>> {
        val call = SaveCall(idempotencyKey, date, body)
        saveCalls += call
        return onSave(call)
    }

    /** 저장 요청 한 건. */
    data class SaveCall(val idempotencyKey: String, val date: String, val body: SaveDayItineraryRequest)
}

/** 성공 envelope. */
fun <T> ok(value: T, status: Int = 200): Response<SuccessEnvelope<T>> =
    Response.success(status, SuccessEnvelope(success = true, data = value, meta = ResponseMeta(ITINERARY_REQUEST_ID)))

/** 오류 envelope. `details`는 계약의 `error.details` JSON 조각이다. */
fun <T> itineraryError(status: Int, code: String, details: String = "{}"): Response<SuccessEnvelope<T>> =
    Response.error(status, errorJson(code, details = details).toResponseBody("application/json".toMediaType()))

/** 9/8~9/10 사흘 여행의 개요. [days]를 주지 않으면 모두 version 0·빈 items다. */
fun overview(vararg days: DayItineraryDto): ItineraryOverviewDto = ItineraryOverviewDto(
    tripId = TRIP_ID,
    days = if (days.isEmpty()) (0..2).map { day("2026-09-%02d".format(8 + it), dayNumber = it + 1) } else days.toList(),
)

/** 한 날짜의 저장본. */
fun day(
    date: String,
    dayNumber: Int = 1,
    version: Int = 0,
    items: List<ItineraryItemDto> = emptyList(),
): DayItineraryDto = DayItineraryDto(
    date = date,
    dayNumber = dayNumber,
    version = version,
    routeStatus = RouteStatus.NOT_CALCULATED,
    items = items,
)

/** 저장된 항목 하나. */
fun savedItem(
    itemId: String,
    sequence: Int,
    name: String = "장소 $itemId",
    stayMinutes: Int = 90,
    transportToNext: TransportMode? = null,
    status: ItemStatus = ItemStatus.PLANNED,
): ItineraryItemDto = ItineraryItemDto(
    itemId = itemId,
    place = ItineraryPlaceDto(
        placeId = "tourapi:$itemId",
        name = name,
        category = com.gilpick.place.PlaceCategory.HISTORY_CULTURE,
        address = null,
        imageUrl = null,
    ),
    sequence = sequence,
    plannedStayMinutes = stayMinutes,
    staySource = StaySource.RECOMMENDED,
    transportModeToNext = transportToNext,
    status = status,
)

/** 저장 요청을 그대로 저장본으로 바꾼 응답. 새 항목에는 `new-{sequence}` ID를 준다. */
fun savedFrom(call: FakeItineraryService.SaveCall, version: Int): DayItineraryDto = day(
    date = call.date,
    version = version,
    items = call.body.items.map { item ->
        ItineraryItemDto(
            itemId = item.itemId ?: "new-${item.sequence}",
            place = ItineraryPlaceDto(
                placeId = item.placeId,
                name = item.place?.name ?: "장소 ${item.itemId}",
                category = item.place?.category ?: com.gilpick.place.PlaceCategory.HISTORY_CULTURE,
                address = item.place?.address,
                imageUrl = item.place?.imageUrl,
            ),
            sequence = item.sequence,
            plannedStayMinutes = item.plannedStayMinutes,
            staySource = item.staySource,
            transportModeToNext = item.transportModeToNext,
            status = ItemStatus.PLANNED,
        )
    },
)
