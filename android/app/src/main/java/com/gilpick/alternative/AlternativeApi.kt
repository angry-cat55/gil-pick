package com.gilpick.alternative

import com.gilpick.auth.SuccessEnvelope
import com.gilpick.place.PlaceDto
import com.gilpick.place.PlaceListMeta
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 계약에 정의된 대체 장소·감지 error code. 값은 `contracts/alternatives.openapi.yaml`을 따른다.
 *
 * `INVALID_REQUEST`·`INVALID_ACCESS_TOKEN`·`TRIP_FORBIDDEN`은 다른 feature와 같은 문자열이고,
 * `DETECTION_*`·`TOUR_API_*`가 F008·F009 전용이다.
 */
object AlternativeErrorCodes {
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val INVALID_CURSOR = "INVALID_CURSOR"
    const val INVALID_ACCESS_TOKEN = "INVALID_ACCESS_TOKEN"
    const val TRIP_FORBIDDEN = "TRIP_FORBIDDEN"
    const val DETECTION_NOT_FOUND = "DETECTION_NOT_FOUND"
    const val DETECTION_NOT_ACTIVE = "DETECTION_NOT_ACTIVE"
    const val TOUR_API_RATE_LIMITED = "TOUR_API_RATE_LIMITED"
    const val TOUR_API_FAILED = "TOUR_API_FAILED"
    const val TOUR_API_TIMEOUT = "TOUR_API_TIMEOUT"
}

/**
 * F008 감지 결과의 상태.
 *
 * [ACTIVE]만 후보 조회·직접 검색·거절을 받는다. [DISMISSED]는 F009 거절, [RESOLVED]는 F010 승인,
 * [INVALIDATED]는 대상 장소 도착·완료·건너뜀·당일 완료로 F008이 종료한 것이다.
 */
@Serializable
enum class DetectionStatus { ACTIVE, RESOLVED, DISMISSED, INVALIDATED }

/** 종합 점수 기여가 가장 큰 변수. 배너·화면 상단 아이콘 선택에 쓴다. */
@Serializable
enum class DetectionType { CONGESTION, WEATHER, OPERATING_HOURS }

/** 변수 하나를 평가할 수 없었던 이유(F008). */
@Serializable
enum class UnavailableReason { NO_FORECAST, NOT_IN_SUPPORT_AREA, HOURS_UNKNOWN, INDOOR, TIMEOUT }

/** ETA 시점 혼잡 수준(F008). */
@Serializable
enum class CongestionLevel { RELAXED, NORMAL, SLIGHTLY_CROWDED, CROWDED }

/** 카테고리 혼잡 민감도. `HIGH`는 쇼핑·음식·카페다. */
@Serializable
enum class CongestionSensitivity { HIGH, MEDIUM }

/** 기상청 강수 형태(F008). */
@Serializable
enum class PrecipitationType { NONE, RAIN, RAIN_SNOW, SNOW, SHOWER }

/**
 * 기존 장소 도착 예정 시각 기준 운영 상태.
 *
 * [CLOSED]는 ALT-001 후보에는 나타나지 않고 ALT-002 직접 검색에서만 `방문 불가`로 표시된다.
 * [UNKNOWN]은 `운영시간 확인 불가`로 표시한다(spec UI-003).
 */
@Serializable
enum class OperatingStatus { OPEN, CLOSING_SOON, CLOSED, UNKNOWN }

/** 후보를 찾은 분류 비교 단계. 후보가 없으면 [NONE]이다. */
@Serializable
enum class CategoryMatchLevel { SMALL, MIDDLE, LARGE, NONE }

/**
 * DETECT-001 목록 항목. F008 항목에 F009가 `eta`·`reason`을 더했다.
 *
 * 진행 화면 배너는 `status=ACTIVE`로 조회한 이 목록에서 [eta]가 가장 이른 하나를 보여 준다
 * (data-model.md 3.3).
 *
 * @property eta 기존 장소 도착 예정 시각. ISO-8601.
 * @property reason 사용자 표시 요약. 화면이 지어내지 않고 그대로 쓴다(spec UI-002).
 */
@Serializable
data class DetectionListItemDto(
    val detectionId: String,
    val itemId: String,
    val placeName: String,
    val primaryType: DetectionType,
    val status: DetectionStatus,
    val totalRiskScore: Int,
    val eta: String,
    val reason: String,
    val createdAt: String,
    val read: Boolean,
)

/** DETECT-001 응답 data. */
@Serializable
data class DetectionListData(
    val items: List<DetectionListItemDto>,
)

/** DETECT-001 응답 envelope. `meta.pagination`이 붙어 [SuccessEnvelope] 대신 쓴다. */
@Serializable
data class DetectionListEnvelope(
    val success: Boolean,
    val data: DetectionListData,
    val meta: PlaceListMeta,
)

/**
 * 혼잡 변수 판정(F008). [available]이 `false`면 나머지는 `null`이다.
 *
 * @property crowded 민감도 기준 혼잡 위험 판정.
 */
@Serializable
data class CongestionVerdictDto(
    val available: Boolean,
    val unavailableReason: UnavailableReason? = null,
    val level: CongestionLevel? = null,
    val sensitivity: CongestionSensitivity? = null,
    val crowded: Boolean? = null,
)

/**
 * 날씨 변수 판정(F008). 실내 장소는 예보가 있어도 [UnavailableReason.INDOOR]로 제외된다.
 *
 * @property atRisk 강수확률 70% 이상·강수량 1.0mm/h 이상·강수성 상태 중 하나라도 해당하면 `true`.
 */
@Serializable
data class WeatherVerdictDto(
    val available: Boolean,
    val unavailableReason: UnavailableReason? = null,
    val precipitationProbability: Int? = null,
    val precipitationMmPerHour: Double? = null,
    val precipitationType: PrecipitationType? = null,
    val atRisk: Boolean? = null,
)

/**
 * 운영시간 변수 판정(F008). 확인할 수 없으면 운영 중으로 가정하고 제외된다.
 *
 * @property closesAt ETA 요일의 폐점 시각. 확인 불가면 `null`.
 * @property closingSoon ETA가 폐점 30분 전 이내인지.
 */
@Serializable
data class OperatingHoursVerdictDto(
    val available: Boolean,
    val unavailableReason: UnavailableReason? = null,
    val closesAt: String? = null,
    val closingSoon: Boolean? = null,
)

/** 감지 상세의 변수별 판정. 화면 상단 위험 변수 칩의 근거다(spec UI-002). */
@Serializable
data class VariableVerdictsDto(
    val congestion: CongestionVerdictDto,
    val weather: WeatherVerdictDto,
    val operatingHours: OperatingHoursVerdictDto,
)

/**
 * DETECT-002 감지 상세. 대체 장소 화면 상단(기존 장소명·감지 이유·변수 칩)이 그대로 쓴다.
 *
 * @property eta 후보 평가의 기준 시각이기도 하다(ALT-001 `eta`와 같다).
 * @property lastEvaluatedAt 마지막 재평가 시각.
 */
@Serializable
data class DetectionDetailDto(
    val detectionId: String,
    val tripId: String,
    val itemId: String,
    val placeName: String,
    val primaryType: DetectionType,
    val status: DetectionStatus,
    val eta: String,
    val totalRiskScore: Int,
    val reason: String,
    val variables: VariableVerdictsDto,
    val read: Boolean,
    val createdAt: String,
    val lastEvaluatedAt: String,
)

/** 점수에 실제로 쓰인 변수의 정규화 값(0~1). 제외된 변수는 key 자체가 없다. */
@Serializable
data class ScoreBreakdownDto(
    val distance: Double? = null,
    val rating: Double? = null,
    val congestion: Double? = null,
    val weather: Double? = null,
)

/**
 * ALT-001 후보 하나.
 *
 * @property candidateId 감지·장소·평가 시각을 담은 서명 토큰(15분). F010 REPL-001 요청에 그대로 넘긴다.
 * @property place F003 장소 DTO 그대로. 후보 화면과 F010이 같은 표현을 쓴다.
 * @property distanceMeters 기존 장소 좌표와의 거리(m).
 * @property adjustedRating 베이지안 보정 평점. 평점이 없으면 `null`이고 화면은 평점 항목을 생략한다.
 * @property score 정렬용 원점수. 화면은 [displayScore]를 쓴다.
 * @property closesAt 도착 예정 시각 기준 마감 시각. 없거나 확인 불가면 `null`.
 * @property reasons 추천 근거 code(`INDOOR`·`NOT_CROWDED`·`NO_RAIN_RISK`·`CLOSER`·`OPEN_AT_ETA` 등).
 *   문구 변환은 화면 몫이며 모르는 code는 표시하지 않는다.
 */
@Serializable
data class AlternativeCandidateDto(
    val rank: Int,
    val candidateId: String,
    val place: PlaceDto,
    val distanceMeters: Int,
    val adjustedRating: Double?,
    val score: Double,
    val displayScore: Int,
    val scoreBreakdown: ScoreBreakdownDto,
    val operatingStatus: OperatingStatus,
    val closesAt: String?,
    val reasons: List<String>,
)

/**
 * ALT-001 응답 data. 저장되지 않고 요청마다 계산된다.
 *
 * @property searchRadiusMeters 후보를 찾은(또는 마지막으로 시도한) 반경. 500·1000·2000.
 * @property categoryMatchLevel 후보를 찾은 분류 비교 단계. 후보가 없으면 [CategoryMatchLevel.NONE].
 * @property evaluatedAt 서버 평가 시각(= `candidateId` 발급 시각).
 * @property items 점수 내림차순 최대 10개. 비어 있으면 `2km 안에 추천할 장소가 없어요`다.
 */
@Serializable
data class AlternativeListDto(
    val detectionId: String,
    val originPlaceId: String,
    val eta: String,
    val searchRadiusMeters: Int,
    val categoryMatchLevel: CategoryMatchLevel,
    val evaluatedAt: String,
    val items: List<AlternativeCandidateDto>,
)

/**
 * ALT-002 직접 검색 결과 항목. F003 검색 항목에 거리·운영 상태·방문 가능 여부를 더한 것이다.
 *
 * @property distanceMeters 좌표가 없으면 `null`.
 * @property visitable `operatingStatus != CLOSED && !inSchedule`. 선택 버튼 활성 여부.
 * @property inSchedule 기존 장소 자신이거나 같은 날짜 일정에 이미 있다(`이미 일정에 있음`).
 */
@Serializable
data class AlternativeSearchItemDto(
    val place: PlaceDto,
    val distanceMeters: Int?,
    val operatingStatus: OperatingStatus,
    val visitable: Boolean,
    val inSchedule: Boolean,
)

/** ALT-002 응답 data. */
@Serializable
data class AlternativeSearchData(
    val items: List<AlternativeSearchItemDto>,
)

/** ALT-002 응답 envelope. F003 검색과 같은 pagination meta를 쓴다. */
@Serializable
data class AlternativeSearchEnvelope(
    val success: Boolean,
    val data: AlternativeSearchData,
    val meta: PlaceListMeta,
)

/**
 * DETECT-004 거절 결과.
 *
 * 이미 처리된 감지는 상태를 바꾸지 않고 현재 상태를 돌려주므로 [status]가 [DetectionStatus.DISMISSED]가
 * 아닐 수 있다(상태 기반 멱등).
 *
 * @property decidedAt 거절·처리 시각. [DetectionStatus.INVALIDATED]처럼 사용자 결정이 없으면 `null`.
 */
@Serializable
data class DismissResultDto(
    val detectionId: String,
    val status: DetectionStatus,
    val decidedAt: String?,
)

/**
 * 후보·직접 검색에서 고른 장소를 F010 변경 경로 미리보기에 넘기는 값(data-model.md 3.2).
 *
 * F009는 이 값을 만들기만 하고 일정을 바꾸지 않는다(FR-023).
 *
 * @property candidateId ALT-001 후보면 그 토큰, 직접 검색이면 `null`.
 * @property distanceMeters 직접 검색에서 좌표가 없으면 `null`.
 * @property displayScore 직접 검색이면 `null`.
 */
data class SelectedAlternative(
    val detectionId: String,
    val placeId: String,
    val candidateId: String?,
    val name: String,
    val distanceMeters: Int?,
    val displayScore: Int?,
)

/**
 * 대체 장소·감지 endpoint 전용 Json 설정.
 *
 * 서버가 field를 추가해도 앱이 깨지지 않도록 모르는 key를 무시한다. 요청 body가 있는
 * endpoint가 없어 encoding 설정은 두지 않는다.
 */
private val alternativeJson = Json {
    ignoreUnknownKeys = true
}

/** 대체 장소·감지 endpoint 전용 Retrofit 인스턴스를 만든다. F006 `createProgressRetrofit`과 같은 구조다. */
fun createAlternativeRetrofit(
    baseUrl: String,
    client: OkHttpClient = OkHttpClient(),
): Retrofit = Retrofit.Builder()
    .baseUrl(baseUrl)
    .client(client)
    .addConverterFactory(alternativeJson.asConverterFactory("application/json".toMediaType()))
    .build()

/**
 * 대체 장소·감지 endpoint 호출 계약.
 *
 * Access Token은 [com.gilpick.auth.AuthRepository.withAuthorizedCall]이 넘겨주므로 각 함수가
 * `Authorization` header를 직접 받는다. F006 `ProgressService`와 같은 구조다.
 */
interface AlternativeService {

    /**
     * 여행의 감지 목록을 조회한다(DETECT-001, F009 확장).
     *
     * @param status 지정하면 그 상태의 감지만 받는다. 배너는 [DetectionStatus.ACTIVE]로 조회한다.
     */
    @GET("trips/{tripId}/detections")
    suspend fun listDetections(
        @Header("Authorization") bearer: String,
        @Path("tripId") tripId: String,
        @Query("status") status: DetectionStatus? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
    ): Response<DetectionListEnvelope>

    /** 감지 상세를 조회한다(DETECT-002). */
    @GET("detections/{detectionId}")
    suspend fun getDetection(
        @Header("Authorization") bearer: String,
        @Path("detectionId") detectionId: String,
    ): Response<SuccessEnvelope<DetectionDetailDto>>

    /**
     * 감지를 거절해 기존 일정 그대로 진행한다(DETECT-004).
     *
     * 상태 기반 멱등이라 `Idempotency-Key`가 없다. 이미 처리된 감지도 `200`으로 현재 상태를 준다.
     */
    @POST("detections/{detectionId}/dismiss")
    suspend fun dismissDetection(
        @Header("Authorization") bearer: String,
        @Path("detectionId") detectionId: String,
    ): Response<SuccessEnvelope<DismissResultDto>>

    /**
     * 추천 후보를 조회한다(ALT-001).
     *
     * `409 DETECTION_NOT_ACTIVE`는 이미 처리된 감지, `502`·`504`는 TourAPI 최종 실패·시간 초과다.
     */
    @GET("detections/{detectionId}/alternatives")
    suspend fun listAlternatives(
        @Header("Authorization") bearer: String,
        @Path("detectionId") detectionId: String,
    ): Response<SuccessEnvelope<AlternativeListDto>>

    /**
     * 대체 장소를 직접 검색한다(ALT-002).
     *
     * @param query trim 후 2글자 이상. 미만이면 `400 INVALID_REQUEST`다.
     */
    @GET("detections/{detectionId}/alternatives/search")
    suspend fun searchAlternatives(
        @Header("Authorization") bearer: String,
        @Path("detectionId") detectionId: String,
        @Query("query") query: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
    ): Response<AlternativeSearchEnvelope>
}
