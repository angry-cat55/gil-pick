package com.gilpick.place

import com.gilpick.auth.SuccessEnvelope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 계약에 정의된 장소 error code. 화면 분기에서 문자열 오타를 막는다.
 *
 * 값은 `contracts/places.openapi.yaml`을 따른다. `GOOGLE_PLACES_*`는 `google:` 상세
 * 조회에서만 노출된다. 검색과 `tourapi:` 상세는 Google 실패를 격리하므로(FR-019) 이
 * 세 code를 반환하지 않는다.
 *
 * 재시도 여부는 code로 추론하지 않고 항상 `error.retryable`을 읽는다. 같은
 * `GOOGLE_PLACES_FAILED`도 일시적 실패일 때만 `retryable=true`로 온다.
 */
object PlaceErrorCodes {
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val INVALID_CURSOR = "INVALID_CURSOR"
    const val INVALID_ACCESS_TOKEN = "INVALID_ACCESS_TOKEN"
    const val PLACE_NOT_FOUND = "PLACE_NOT_FOUND"
    const val TOUR_API_RATE_LIMITED = "TOUR_API_RATE_LIMITED"
    const val TOUR_API_FAILED = "TOUR_API_FAILED"
    const val TOUR_API_TIMEOUT = "TOUR_API_TIMEOUT"
    const val GOOGLE_PLACES_RATE_LIMITED = "GOOGLE_PLACES_RATE_LIMITED"
    const val GOOGLE_PLACES_FAILED = "GOOGLE_PLACES_FAILED"
    const val GOOGLE_PLACES_TIMEOUT = "GOOGLE_PLACES_TIMEOUT"
}

/**
 * Google이 제공한 영업 상태의 알려진 값.
 *
 * enum이 아니라 상수로 두는 이유는 이 값이 Google 원문 enum을 그대로 통과시킨 것이기
 * 때문이다. Google이 값을 추가하면 strict enum은 상세 응답 전체를 parsing 실패로
 * 만든다. 알려지지 않은 값은 표시하지 않는다.
 */
object PlaceBusinessStatus {
    const val OPERATIONAL = "OPERATIONAL"
    const val CLOSED_TEMPORARILY = "CLOSED_TEMPORARILY"
    const val CLOSED_PERMANENTLY = "CLOSED_PERMANENTLY"
}

/**
 * 길픽 내부 장소 분류.
 *
 * TourAPI 신분류 code를 server가 이 6개로 변환하며 미매핑 code는 [OTHER]다. server가
 * 닫아 둔 값이라 enum으로 받는다.
 */
@Serializable
enum class PlaceCategory {
    NATURE,
    HISTORY_CULTURE,
    FOOD,
    CAFE,
    SHOPPING,
    OTHER,
}

/** 장소 정보의 기준 provider. 화면 배지로 표시하지 않고 상세 routing 판단에만 쓴다. */
@Serializable
enum class PlaceSource {
    TOUR_API,
    GOOGLE_PLACES,
}

/** TourAPI 신분류 원본 code. TourAPI 기준 결과만 값을 가진다. */
@Serializable
data class TourApiCategoryDto(
    val large: String? = null,
    val middle: String? = null,
    val small: String? = null,
)

/**
 * 장소 표현.
 *
 * 검색 결과(`PlaceSummary`)와 상세(`PlaceDetail`)를 하나의 DTO로 받는다. 계약에서
 * 상세는 검색 결과에 세 field를 더한 것뿐이라 18개 field를 복제하는 클래스를 따로 두지
 * 않았다. F002 [com.gilpick.trip.TripDto]가 생성 응답 전용 field를 같은 방식으로
 * 다룬다.
 *
 * 계약이 `required`로 정의한 field는 기본값을 두지 않아 누락을 parsing 오류로 드러낸다.
 * nullable은 "값이 없음"이지 "field가 없음"이 아니다.
 *
 * @property placeId `tourapi:{contentId}` 또는 `google:{placeId}`. 상세 조회 경로에 그대로 쓴다.
 * @property tourApiCategory field는 필수이며 TourAPI 기준 결과가 아니면 `null`이다.
 * @property recommendedStayMinutes server가 [category]로 계산한 값. 60·90·120 중 하나다.
 * @property businessStatus [PlaceBusinessStatus]의 알려진 값 또는 `null`.
 * @property googleAttributions Google 데이터를 표시할 때 함께 렌더링해야 하는 출처 정보.
 * @property description 상세 응답에만 포함된다.
 * @property phone 상세 응답에만 포함된다. 전화 가능 여부를 단정하지 않는다.
 * @property operatingGuide 상세 응답에만 포함된다. 현재 영업 여부를 추론하지 않는다.
 */
@Serializable
data class PlaceDto(
    val placeId: String,
    val source: PlaceSource,
    val sourcePlaceId: String,
    val name: String,
    val category: PlaceCategory,
    val tourApiCategory: TourApiCategoryDto?,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val imageUrl: String?,
    val recommendedStayMinutes: Int,
    val rating: Double?,
    val userRatingCount: Int?,
    val businessStatus: String?,
    val regularOpeningHours: List<String>?,
    val currentOpeningHours: List<String>?,
    val googleAttributions: List<String>?,
    val description: String? = null,
    val phone: String? = null,
    val operatingGuide: String? = null,
)

/**
 * 검색 응답의 cursor 상태.
 *
 * @property nextCursor 다음 페이지 요청에 그대로 전달한다. 마지막 페이지면 `null`이다.
 * @property hasNext 이어질 항목이 남았는지 여부.
 */
@Serializable
data class PlacePagination(
    val nextCursor: String? = null,
    val hasNext: Boolean,
)

/**
 * 검색 전용 응답 metadata.
 *
 * 검색만 pagination을 포함하므로 상세가 쓰는 공통 `ResponseMeta` 대신 별도로 둔다.
 * F002 [com.gilpick.trip.TripListMeta]와 같은 이유다.
 */
@Serializable
data class PlaceListMeta(
    val requestId: String,
    val pagination: PlacePagination,
)

/** @property items 이 페이지의 장소. 빈 목록도 정상 응답이다. */
@Serializable
data class PlaceListData(
    val items: List<PlaceDto>,
)

/** 검색 응답 envelope. `data.items`와 `meta.pagination`을 함께 받는다. */
@Serializable
data class PlaceListEnvelope(
    val success: Boolean,
    val data: PlaceListData,
    val meta: PlaceListMeta,
)

/**
 * 장소 endpoint 전용 Json 설정.
 *
 * 서버가 나중에 field를 추가해도 앱이 깨지지 않도록 모르는 키를 무시한다. 장소
 * endpoint는 모두 `GET`이라 요청 body가 없어 encoding 설정은 두지 않는다.
 */
private val placeJson = Json {
    ignoreUnknownKeys = true
}

/** 장소 endpoint 전용 Retrofit 인스턴스를 만든다. */
fun createPlaceRetrofit(
    baseUrl: String,
    client: OkHttpClient = OkHttpClient(),
): Retrofit = Retrofit.Builder()
    .baseUrl(baseUrl)
    .client(client)
    .addConverterFactory(placeJson.asConverterFactory("application/json".toMediaType()))
    .build()

/**
 * 장소 endpoint 호출 계약.
 *
 * Access Token은 [com.gilpick.auth.AuthRepository.withAccessToken]이 넘겨주므로 각
 * 함수가 `Authorization` header를 직접 받는다. 만료 시 갱신과 replay도 그쪽이 처리한다.
 *
 * ponytail: `SuccessEnvelope`·`ErrorEnvelope`·`AuthResult`를 `com.gilpick.auth`에서
 * 그대로 가져다 쓴다. [com.gilpick.trip.TripRepository]가 남긴 메모대로 F003이 세 번째
 * 소비자라 중립 패키지로 옮길 시점이지만, F002 상세 PR(#154)이 같은 파일을 열어 두고
 * 있어 지금 옮기면 충돌만 만든다. #154 merge 후 별도 refactor Issue로 옮긴다.
 */
interface PlaceService {

    /**
     * 키워드·category·지역 조건으로 장소를 검색한다.
     *
     * [query]와 [category]는 각각 단독으로도 쓸 수 있고 함께 쓸 수도 있다. 둘 다 없으면
     * server가 `400 INVALID_REQUEST`로 거절한다.
     *
     * @param areaCode 계약이 허용한 TourAPI 지역코드(`1`~`8`, `31`~`39`)만 보낸다.
     * @param cursor 이전 응답의 `meta.pagination.nextCursor`를 그대로 전달한다.
     */
    @GET("places/search")
    suspend fun searchPlaces(
        @Header("Authorization") bearer: String,
        @Query("query") query: String? = null,
        @Query("category") category: PlaceCategory? = null,
        @Query("areaCode") areaCode: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
    ): Response<PlaceListEnvelope>

    /**
     * 장소 하나의 상세를 조회한다.
     *
     * [placeId]의 `tourapi:`·`google:` prefix가 server의 조회 provider를 결정하므로
     * 검색 결과에서 받은 값을 가공하지 않고 그대로 넘긴다.
     */
    @GET("places/{placeId}")
    suspend fun getPlace(
        @Header("Authorization") bearer: String,
        @Path("placeId") placeId: String,
    ): Response<SuccessEnvelope<PlaceDto>>
}
