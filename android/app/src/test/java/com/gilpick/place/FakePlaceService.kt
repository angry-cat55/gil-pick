package com.gilpick.place

import com.gilpick.auth.ResponseMeta
import com.gilpick.auth.SuccessEnvelope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * 응답을 test가 직접 정하는 [PlaceService].
 *
 * 실제 HTTP 왕복은 `PlaceApiTest`가 MockWebServer로 검증하므로, 여기서는 화면 상태
 * 전이에 필요한 요청 기록과 응답만 다룬다. F002 `FakeTripService`와 같은 구조다.
 */
class FakePlaceService : PlaceService {

    /** 지금까지 도착한 검색 요청. 호출 순서대로 쌓인다. */
    val searchCalls = mutableListOf<SearchCall>()

    /** 검색 요청을 받아 응답을 만든다. 기본값은 계약에 없는 호출을 막는 실패다. */
    var onSearch: suspend (SearchCall) -> Response<PlaceListEnvelope> =
        { error("이 test는 검색 endpoint를 호출하지 않는다") }

    override suspend fun searchPlaces(
        bearer: String,
        query: String?,
        category: PlaceCategory?,
        areaCode: String?,
        cursor: String?,
        limit: Int?,
    ): Response<PlaceListEnvelope> {
        val call = SearchCall(query, category, cursor)
        searchCalls += call
        return onSearch(call)
    }

    /** 검색 요청의 조건. */
    data class SearchCall(val query: String?, val category: PlaceCategory?, val cursor: String?)

    /** 지금까지 도착한 상세 요청의 `placeId`. 호출 순서대로 쌓인다. */
    val getCalls = mutableListOf<String>()

    /** `placeId`를 받아 상세 응답을 만든다. 기본값은 계약에 없는 호출을 막는 실패다. */
    var onGet: suspend (String) -> Response<SuccessEnvelope<PlaceDto>> =
        { error("이 test는 상세 endpoint를 호출하지 않는다") }

    override suspend fun getPlace(
        bearer: String,
        placeId: String,
    ): Response<SuccessEnvelope<PlaceDto>> {
        getCalls += placeId
        return onGet(placeId)
    }
}

/** test용 장소 하나를 만든다. 기본값은 nullable field가 모두 `null`인 TourAPI 장소다. */
fun place(
    id: String,
    name: String = "장소 $id",
    category: PlaceCategory = PlaceCategory.HISTORY_CULTURE,
    address: String? = null,
    imageUrl: String? = null,
    rating: Double? = null,
    userRatingCount: Int? = null,
    businessStatus: String? = null,
    regularOpeningHours: List<String>? = null,
    currentOpeningHours: List<String>? = null,
    googleAttributions: List<String>? = null,
    description: String? = null,
    phone: String? = null,
    operatingGuide: String? = null,
): PlaceDto = PlaceDto(
    placeId = id,
    source = if (id.startsWith("google:")) PlaceSource.GOOGLE_PLACES else PlaceSource.TOUR_API,
    sourcePlaceId = id.substringAfter(':'),
    name = name,
    category = category,
    tourApiCategory = if (id.startsWith("google:")) null else TourApiCategoryDto(large = "A02"),
    address = address,
    latitude = null,
    longitude = null,
    imageUrl = imageUrl,
    recommendedStayMinutes = 90,
    rating = rating,
    userRatingCount = userRatingCount,
    businessStatus = businessStatus,
    regularOpeningHours = regularOpeningHours,
    currentOpeningHours = currentOpeningHours,
    googleAttributions = googleAttributions,
    description = description,
    phone = phone,
    operatingGuide = operatingGuide,
)

/** 검색 성공 응답 한 페이지를 만든다. */
fun placePage(
    places: List<PlaceDto>,
    nextCursor: String? = null,
): Response<PlaceListEnvelope> = Response.success(
    PlaceListEnvelope(
        success = true,
        data = PlaceListData(items = places),
        meta = PlaceListMeta(
            requestId = PLACE_REQUEST_ID,
            pagination = PlacePagination(nextCursor = nextCursor, hasNext = nextCursor != null),
        ),
    ),
)

/** 장소 상세 성공 응답을 만든다. */
fun placeDetail(place: PlaceDto): Response<SuccessEnvelope<PlaceDto>> = Response.success(
    SuccessEnvelope(success = true, data = place, meta = ResponseMeta(requestId = PLACE_REQUEST_ID)),
)

/**
 * 계약이 정한 error envelope로 실패 응답을 만든다.
 *
 * repository가 code와 `retryable`로 원인을 판정하므로 세 값을 함께 준다.
 */
fun <T> placeError(
    httpStatus: Int,
    code: String,
    retryable: Boolean = false,
): Response<T> = Response.error(
    httpStatus,
    """{"success":false,"error":{"code":"$code","message":"진단용 설명","retryable":$retryable},"meta":{"requestId":"$PLACE_REQUEST_ID"}}"""
        .toResponseBody("application/json".toMediaType()),
)

internal const val PLACE_REQUEST_ID = "11111111-2222-4333-8444-555555555555"
