package com.gilpick.trip

import com.gilpick.auth.ResponseMeta
import com.gilpick.auth.SuccessEnvelope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * 응답을 test가 직접 정하는 [TripService].
 *
 * 실제 HTTP 왕복은 `TripRepositoryTest`가 MockWebServer로 검증하므로, 여기서는 화면
 * 상태 전이에 필요한 요청 기록과 응답만 다룬다.
 */
class FakeTripService : TripService {

    /** 목록 endpoint에 전달된 요청 인자. 조건 변경 시 cursor 초기화를 확인한다. */
    data class ListCall(
        val query: String?,
        val status: TripStatus?,
        val cursor: String?,
        val limit: Int?,
    )

    /** 지금까지 도착한 목록 요청. 호출 순서대로 쌓인다. */
    val listCalls = mutableListOf<ListCall>()

    /** 요청 인자를 받아 목록 응답을 만든다. 기본값은 빈 목록이다. */
    var onList: (ListCall) -> Response<TripListEnvelope> = { emptyPage() }

    override suspend fun listTrips(
        bearer: String,
        query: String?,
        status: TripStatus?,
        cursor: String?,
        limit: Int?,
    ): Response<TripListEnvelope> {
        val call = ListCall(query, status, cursor, limit)
        listCalls += call
        return onList(call)
    }

    override suspend fun createTrip(
        bearer: String,
        idempotencyKey: String,
        body: CreateTripRequest,
    ): Response<SuccessEnvelope<TripDto>> = error("이 test는 생성 endpoint를 호출하지 않는다")

    /** 지금까지 도착한 상세 요청의 `tripId`. 호출 순서대로 쌓인다. */
    val getCalls = mutableListOf<String>()

    /**
     * `tripId`를 받아 상세 응답을 만든다. 기본값은 계약에 없는 호출을 막는 실패다.
     *
     * 상세를 쓰지 않는 test가 실수로 호출하면 조용히 통과하는 대신 즉시 드러나야 한다.
     */
    var onGet: (String) -> Response<SuccessEnvelope<TripDto>> =
        { error("이 test는 상세 endpoint를 호출하지 않는다") }

    override suspend fun getTrip(
        bearer: String,
        tripId: String,
    ): Response<SuccessEnvelope<TripDto>> {
        getCalls += tripId
        return onGet(tripId)
    }

    override suspend fun updateTrip(
        bearer: String,
        tripId: String,
        body: UpdateTripRequest,
    ): Response<SuccessEnvelope<TripDto>> = error("이 test는 수정 endpoint를 호출하지 않는다")

    override suspend fun deleteTrip(
        bearer: String,
        tripId: String,
    ): Response<Unit> = error("이 test는 삭제 endpoint를 호출하지 않는다")
}

/** 여행 목록 한 페이지 응답을 만든다. */
fun page(
    trips: List<TripDto>,
    nextCursor: String? = null,
    hasNext: Boolean = nextCursor != null,
): Response<TripListEnvelope> = Response.success(
    TripListEnvelope(
        success = true,
        data = TripListData(items = trips),
        meta = TripListMeta(
            requestId = "11111111-2222-4333-8444-555555555555",
            pagination = TripPagination(nextCursor = nextCursor, hasNext = hasNext),
        ),
    ),
)

/** 결과가 없는 페이지. 검색·필터로 걸러진 경우와 여행이 아예 없는 경우 모두에 쓴다. */
fun emptyPage(): Response<TripListEnvelope> = page(trips = emptyList())

/** test용 여행 하나를 만든다. */
fun trip(
    id: String,
    name: String = "서울 여행",
    startDate: String = "2026-09-01",
    endDate: String = "2026-09-03",
    status: TripStatus = TripStatus.UPCOMING,
): TripDto = TripDto(
    tripId = id,
    name = name,
    startDate = startDate,
    endDate = endDate,
    status = status,
    dayCount = 3,
    version = 1,
)

/** 여행 상세 성공 응답을 만든다. */
fun detail(trip: TripDto): Response<SuccessEnvelope<TripDto>> = Response.success(
    SuccessEnvelope(
        success = true,
        data = trip,
        meta = ResponseMeta(requestId = REQUEST_ID),
    ),
)

/**
 * 계약이 정한 error envelope로 실패 응답을 만든다.
 *
 * repository가 code로 원인을 판정하므로 상태 코드와 code를 함께 준다.
 */
fun errorResponse(
    httpStatus: Int,
    code: String,
): Response<SuccessEnvelope<TripDto>> = Response.error(
    httpStatus,
    """{"success":false,"error":{"code":"$code","message":"진단용 설명","retryable":false},"meta":{"requestId":"$REQUEST_ID"}}"""
        .toResponseBody("application/json".toMediaType()),
)

private const val REQUEST_ID = "11111111-2222-4333-8444-555555555555"
