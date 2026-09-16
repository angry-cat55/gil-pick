package com.gilpick.alternative

import com.gilpick.place.PlaceCategory
import com.gilpick.auth.ResponseMeta
import com.gilpick.auth.SuccessEnvelope
import com.gilpick.place.PlaceDto
import com.gilpick.place.place
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * network 없이 대체 장소 API 응답을 정하는 [AlternativeService].
 *
 * F007 `FakeDetectionService`와 같은 방식이다. 응답은 [AlternativeFixtures.kt]의 계약 JSON을 그대로
 * 역직렬화해 DTO와 계약이 어긋나면 여기서도 드러나게 한다.
 */
class FakeAlternativeService : AlternativeService {

    /** 지금까지 도착한 거절 요청의 detectionId. */
    val dismissCalls = mutableListOf<String>()

    /** 지금까지 도착한 감지 목록 요청의 `(status, limit)`. 진행 화면 배너 조회를 확인한다. */
    val listCalls = mutableListOf<Pair<DetectionStatus?, Int?>>()

    var onListDetections: suspend () -> Response<DetectionListEnvelope> = { list(detectionListJson()) }

    var onGetDetection: suspend () -> Response<SuccessEnvelope<DetectionDetailDto>> = { ok(detectionDetailJson()) }
    var onListAlternatives: suspend () -> Response<SuccessEnvelope<AlternativeListDto>> = { ok(alternativesJson()) }
    var onDismiss: suspend () -> Response<SuccessEnvelope<DismissResultDto>> = { ok(dismissJson()) }

    /** 지금까지 도착한 직접 검색 요청의 `(query, cursor)`. */
    val searchCalls = mutableListOf<Pair<String, String?>>()

    /** 검색마다 받은 카테고리 필터(#450). */
    val searchCategories = mutableListOf<PlaceCategory?>()
    var onSearch: suspend (cursor: String?) -> Response<AlternativeSearchEnvelope> = { search(searchJson()) }

    override suspend fun listDetections(
        bearer: String,
        tripId: String,
        status: DetectionStatus?,
        cursor: String?,
        limit: Int?,
    ): Response<DetectionListEnvelope> {
        listCalls += status to limit
        return onListDetections()
    }

    override suspend fun getDetection(bearer: String, detectionId: String) = onGetDetection()

    override suspend fun dismissDetection(bearer: String, detectionId: String): Response<SuccessEnvelope<DismissResultDto>> {
        dismissCalls += detectionId
        return onDismiss()
    }

    override suspend fun listAlternatives(bearer: String, detectionId: String) = onListAlternatives()

    /** 지금까지 도착한 장소 상세 조회의 placeId(#660 기존 장소 좌표). */
    val placeCalls = mutableListOf<String>()

    /** 기본은 좌표가 없는 장소다. 좌표가 필요한 test가 바꿔 끼운다. */
    var onGetPlace: suspend (String) -> Response<SuccessEnvelope<PlaceDto>> = { placeId -> placeResponse(originPlace(placeId)) }

    override suspend fun getPlace(bearer: String, placeId: String): Response<SuccessEnvelope<PlaceDto>> {
        placeCalls += placeId
        return onGetPlace(placeId)
    }

    override suspend fun searchAlternatives(
        bearer: String,
        detectionId: String,
        query: String,
        cursor: String?,
        limit: Int?,
        category: PlaceCategory?,
    ): Response<AlternativeSearchEnvelope> {
        searchCalls += query to cursor
        searchCategories += category
        return onSearch(cursor)
    }
}

private val json = Json { ignoreUnknownKeys = true }

/** 계약 JSON을 성공 응답으로 감싼다. */
internal inline fun <reified T> ok(body: String): Response<SuccessEnvelope<T>> =
    Response.success(json.decodeFromString<SuccessEnvelope<T>>(body))

/** DETECT-001 계약 JSON을 성공 응답으로 감싼다. `meta.pagination`이 있어 [SuccessEnvelope]가 아니다. */
internal fun list(body: String): Response<DetectionListEnvelope> = Response.success(json.decodeFromString<DetectionListEnvelope>(body))

/** ALT-002 계약 JSON을 성공 응답으로 감싼다. */
internal fun search(body: String): Response<AlternativeSearchEnvelope> = Response.success(json.decodeFromString<AlternativeSearchEnvelope>(body))

/** 계약 오류 JSON을 HTTP 오류 응답으로 감싼다. */
internal fun <T> fail(httpStatus: Int, code: String, retryable: Boolean = false, details: String = "null"): Response<T> =
    Response.error(httpStatus, alternativeErrorJson(code, retryable, details).toResponseBody("application/json".toMediaType()))

/** 장소 상세 성공 응답. */
internal fun placeResponse(place: PlaceDto): Response<SuccessEnvelope<PlaceDto>> =
    Response.success(SuccessEnvelope(success = true, data = place, meta = ResponseMeta(requestId = "req-660")))

/** 기존 장소 상세. 지도에 원래 장소를 그리는 좌표만 뜻이 있다(#660). */
fun originPlace(placeId: String, latitude: Double? = null, longitude: Double? = null): PlaceDto =
    place(id = placeId, name = "기존 장소").copy(latitude = latitude, longitude = longitude)
