package com.gilpick.progress

import com.gilpick.auth.ResponseMeta
import com.gilpick.auth.SuccessEnvelope
import com.gilpick.itinerary.ItemStatus
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * 응답을 test가 직접 정하는 [ProgressService].
 *
 * 실제 HTTP 왕복과 인증 갱신·replay는 `ProgressRepositoryTest`가 MockWebServer로 검증하므로,
 * ViewModel test는 여기서 조회·시작·전환 응답만 정한다.
 */
class FakeProgressService : ProgressService {

    /** 지금까지 도착한 조회 요청의 날짜. */
    val getCalls = mutableListOf<String>()

    /** 지금까지 도착한 시작 요청의 `(Idempotency-Key, body)`. */
    val startCalls = mutableListOf<Pair<String, StartProgressRequest>>()

    /** 지금까지 도착한 상태 전환 요청의 `(Idempotency-Key, itemId, body)`. */
    val updateCalls = mutableListOf<Triple<String, String, UpdateProgressStatusRequest>>()

    var onGet: suspend (date: String) -> Response<SuccessEnvelope<ProgressData>> = { progressOk(notStarted(date = it)) }
    var onUpdate: suspend (itemId: String, UpdateProgressStatusRequest) -> Response<SuccessEnvelope<ProgressData>> =
        { _, _ -> progressOk(inProgress(progressVersion = 3)) }
    var onStart: (StartProgressRequest) -> Response<SuccessEnvelope<ProgressData>> =
        { progressOk(inProgress(progressVersion = 1)) }

    override suspend fun getDayProgress(bearer: String, tripId: String, date: String): Response<SuccessEnvelope<ProgressData>> {
        getCalls += date
        return onGet(date)
    }

    override suspend fun startDayProgress(
        bearer: String,
        idempotencyKey: String,
        tripId: String,
        date: String,
        body: StartProgressRequest,
    ): Response<SuccessEnvelope<ProgressData>> {
        startCalls += idempotencyKey to body
        return onStart(body)
    }

    override suspend fun updateItemStatus(
        bearer: String,
        idempotencyKey: String,
        itemId: String,
        body: UpdateProgressStatusRequest,
    ): Response<SuccessEnvelope<ProgressData>> {
        updateCalls += Triple(idempotencyKey, itemId, body)
        return onUpdate(itemId, body)
    }
}

/** 시작 전 진행 현황 DTO. [items]가 비면 장소가 없는 날짜다. */
fun notStarted(
    date: String = PROGRESS_DATE,
    items: List<ProgressItemDto> = listOf(plannedItem(P_ITEM_A, 1), plannedItem(P_ITEM_B, 2)),
): ProgressData = ProgressData(
    tripId = PROGRESS_TRIP_ID,
    date = date,
    dayStatus = DayStatus.NOT_STARTED,
    progressVersion = 0,
    scheduleVersion = 3,
    actualStartedAt = null,
    completedAt = null,
    startLocation = null,
    currentItemId = null,
    nextItemId = items.firstOrNull()?.itemId,
    items = items,
)

fun plannedItem(itemId: String, sequence: Int) = ProgressItemDto(
    itemId = itemId, sequence = sequence, status = ItemStatus.PLANNED,
    estimatedArrivalAt = null, estimatedDepartureAt = null, actualArrivedAt = null, completedAt = null,
    inboundTravel = null,
)

fun progressOk(data: ProgressData): Response<SuccessEnvelope<ProgressData>> = Response.success(
    SuccessEnvelope(success = true, data = data, meta = ResponseMeta(requestId = PROGRESS_REQUEST_ID)),
)

fun progressError(httpStatus: Int, code: String): Response<SuccessEnvelope<ProgressData>> = Response.error(
    httpStatus,
    progressErrorJson(code).toResponseBody("application/json".toMediaType()),
)
