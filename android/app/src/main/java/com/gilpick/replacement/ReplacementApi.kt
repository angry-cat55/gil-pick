package com.gilpick.replacement

import com.gilpick.auth.SuccessEnvelope
import com.gilpick.itinerary.RouteStatus
import com.gilpick.route.RouteDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * 계약에 정의된 일정 변경 error code. 값은 `contracts/replacements.openapi.yaml`을 따른다.
 *
 * `INVALID_REQUEST`·`INVALID_ACCESS_TOKEN`·`TRIP_FORBIDDEN`은 다른 feature와 같은 문자열이고
 * 나머지가 F010 전용이다. 하나의 `409`에 여러 원인이 몰려 있고 다음 행동이 서로 달라
 * HTTP 상태 코드가 아니라 이 code로 분류한다(UI-005).
 */
object ReplacementErrorCodes {
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val INVALID_ACCESS_TOKEN = "INVALID_ACCESS_TOKEN"
    const val TRIP_FORBIDDEN = "TRIP_FORBIDDEN"

    // REPL-001 미리보기 생성
    const val INVALID_CANDIDATE = "INVALID_CANDIDATE"
    const val DETECTION_NOT_FOUND = "DETECTION_NOT_FOUND"
    const val PLACE_NOT_FOUND = "PLACE_NOT_FOUND"
    const val DETECTION_NOT_ACTIVE = "DETECTION_NOT_ACTIVE"
    const val ITEM_ALREADY_VISITED = "ITEM_ALREADY_VISITED"
    const val PLACE_ALREADY_IN_SCHEDULE = "PLACE_ALREADY_IN_SCHEDULE"
    const val DAY_NOT_IN_PROGRESS = "DAY_NOT_IN_PROGRESS"
    const val ROUTE_PROVIDER_ERROR = "ROUTE_PROVIDER_ERROR"
    const val ROUTE_PROVIDER_TIMEOUT = "ROUTE_PROVIDER_TIMEOUT"

    // REPL-002 승인 · REPL-003 폐기
    const val PREVIEW_NOT_FOUND = "PREVIEW_NOT_FOUND"
    const val PREVIEW_EXPIRED = "PREVIEW_EXPIRED"
    const val PREVIEW_SUPERSEDED = "PREVIEW_SUPERSEDED"
    const val PREVIEW_REJECTED = "PREVIEW_REJECTED"
    const val ALREADY_APPROVED = "ALREADY_APPROVED"
    const val VERSION_CONFLICT = "VERSION_CONFLICT"
    const val ALTERNATIVE_UNAVAILABLE = "ALTERNATIVE_UNAVAILABLE"

    // REPL-004 되돌리기
    const val REPLACEMENT_NOT_FOUND = "REPLACEMENT_NOT_FOUND"
    const val UNDO_EXPIRED = "UNDO_EXPIRED"
    const val FOLLOW_UP_CHANGE_EXISTS = "FOLLOW_UP_CHANGE_EXISTS"
}

/**
 * `POST /detections/{detectionId}/route-previews` 요청(REPL-001).
 *
 * @property placeId 대체 장소 식별자. F003 장소 DTO의 `placeId`와 같은 형식(예 `tourapi:123`).
 * @property candidateId F009 ALT-001이 발급한 후보 식별자. 직접 검색(ALT-002)으로 고른
 *   장소는 `null`이며 두 경우의 응답 형식은 같다(FR-004).
 * @property scheduleVersion 앱이 보고 있는 그 날짜의 일정 version. 승인 시 이 값으로 충돌을 본다.
 */
@Serializable
data class CreatePreviewRequest(
    val placeId: String,
    val candidateId: String?,
    val scheduleVersion: Int,
)

/**
 * 비교 한 쌍.
 *
 * 값을 확보하지 못하면 그쪽이 `null`이고 화면은 `정보 없음`으로 표시한다(FR-002·UI-002).
 * 값을 지어내지 않는다. 계약의 `ComparisonValue`는 number·string·null을 모두 받지만 항목마다
 * 실제 타입이 정해져 있어 [PreviewComparisonDto]에서 구체 타입으로 고정한다.
 */
@Serializable
data class ComparisonValue<T>(
    val before: T?,
    val after: T?,
)

/**
 * 미리보기가 보여 주는 네 비교 항목(FR-002).
 *
 * @property totalDurationSeconds 그 날짜 전체 이동 시간(초).
 * @property totalDistanceMeters 그 날짜 전체 이동 거리(m).
 * @property estimatedArrivalAt 바뀌는 장소의 도착 예정 시각. ISO-8601 문자열.
 * @property closesAt 바뀌는 장소의 운영 마감 시각. 확인하지 못하면 `null`이다.
 */
@Serializable
data class PreviewComparisonDto(
    val totalDurationSeconds: ComparisonValue<Int>,
    val totalDistanceMeters: ComparisonValue<Int>,
    val estimatedArrivalAt: ComparisonValue<String>,
    val closesAt: ComparisonValue<String>,
)

/**
 * 바뀌기 전후 장소의 표시용 정보.
 *
 * 좌표는 계약상 선택 항목이다. 좌표가 없는 장소가 있어 `null`을 허용한다.
 */
@Serializable
data class ReplacedPlaceDto(
    val placeId: String,
    val name: String,
    val category: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/**
 * 변경 경로 미리보기(REPL-001 응답 data).
 *
 * 이 응답을 받은 시점에 일정·경로·진행 상태·감지 결과는 하나도 바뀌지 않았다(FR-001).
 *
 * @property itemId 바꿀 대상 항목. 승인 전후로 같아서 F006 진행·F007 감지 대상이 그대로 유효하다.
 * @property detectionReason 왜 바꾸는지. F008 감지 결과의 `reason`을 그대로 전달한다(UI-002).
 * @property route 변경 후 그 날짜 경로. F005 ROUTE-001 응답의 `route`와 같은 형식이라
 *   [RouteDto]를 그대로 쓴다. 지도에 변경 경로를 그리는 데 쓴다(UI-001).
 * @property expiresAt 만료 시각. 앱은 표시에만 쓰고 만료 판정은 서버가 한다(FR-005).
 */
@Serializable
data class RoutePreviewDto(
    val previewId: String,
    val detectionId: String,
    val tripId: String,
    val date: String,
    val itemId: String,
    val originalPlace: ReplacedPlaceDto,
    val alternativePlace: ReplacedPlaceDto,
    val detectionReason: String,
    val comparison: PreviewComparisonDto,
    val route: RouteDto,
    val scheduleVersion: Int,
    val expiresAt: String,
)

/**
 * 승인으로 확정된 장소 변경(REPL-002 응답 data).
 *
 * @property routeStatus 승인은 미리보기에서 계산을 마친 경로를 확정만 하므로 항상
 *   [RouteStatus.READY]다. 계산하지 못한 상태로는 미리보기 자체가 만들어지지 않는다.
 * @property undoExpiresAt 되돌릴 수 있는 시각(승인 시각 + 30초). 앱은 남은 시간 표시에만
 *   쓰고 만료 판정은 하지 않는다(UI-006).
 */
@Serializable
data class ReplacementDto(
    val replacementId: String,
    val tripId: String,
    val date: String,
    val itemId: String,
    val originalPlaceId: String,
    val newPlaceId: String,
    val newPlaceName: String,
    val originalPlaceName: String,
    val scheduleVersion: Int,
    val routeStatus: RouteStatus,
    val undoExpiresAt: String,
)

/**
 * 되돌리기 결과(REPL-004 응답 data).
 *
 * @property restored 항상 `true`. 이미 되돌린 변경에 다시 요청해도 `true`다(FR-017).
 * @property routeStatus 승인 전 경로를 다시 활성화하므로 보통 [RouteStatus.READY]다.
 *   승인 전 경로가 실패 상태였다면 그 상태가 그대로 복원된다.
 * @property detectionRestored 감지 결과가 사용자 결정 전 상태로 돌아갔는지(FR-018). 같은
 *   항목에 더 새로운 감지 결과가 이미 있으면 `false`이고, 이때는 그 새 감지 결과가 배너로 보인다.
 */
@Serializable
data class ReplacementUndoResultDto(
    val replacementId: String,
    val restored: Boolean,
    val scheduleVersion: Int,
    val routeStatus: RouteStatus,
    val detectionRestored: Boolean,
)

/**
 * 일정 변경 endpoint 전용 Json 설정.
 *
 * 서버가 field를 추가해도 앱이 깨지지 않도록 모르는 key를 무시한다. 계약이 required로 정의한
 * nullable field(`candidateId`, `comparison`의 `before`·`after`)는 `null`도 key째 주고받아야
 * 하므로 `explicitNulls`는 기본값(true)을 유지한다. F007 `DetectionApi`와 같은 이유다.
 */
private val replacementJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** 일정 변경 endpoint 전용 Retrofit 인스턴스를 만든다. */
fun createReplacementRetrofit(
    baseUrl: String,
    client: OkHttpClient = OkHttpClient(),
): Retrofit = Retrofit.Builder()
    .baseUrl(baseUrl)
    .client(client)
    .addConverterFactory(replacementJson.asConverterFactory("application/json".toMediaType()))
    .build()

/**
 * 일정 변경 endpoint 호출 계약(REPL-001~004).
 *
 * Access Token은 [com.gilpick.auth.AuthRepository.withAuthorizedCall]이 넘겨주므로 각 함수가
 * `Authorization` header를 직접 받는다. F007 [com.gilpick.progress.DetectionService]와 같은 구조다.
 *
 * `Idempotency-Key`는 상태를 새로 만드는 두 endpoint(REPL-001·REPL-002)만 받는다. 폐기와
 * 되돌리기는 결과가 대상의 상태로 정해져 같은 요청을 다시 보내도 같은 답이 나오므로 계약에
 * header가 없다.
 */
interface ReplacementService {

    /**
     * 감지 결과 하나와 대체 장소 하나로 변경 경로 미리보기를 만든다(REPL-001).
     *
     * 이 호출은 일정을 바꾸지 않는다. 같은 감지 결과에 이미 대기 중인 미리보기가 있으면
     * 그 미리보기는 더 이상 승인할 수 없게 된다(FR-006).
     *
     * `502`·`504`는 경로를 계산하지 못한 것이며 이때도 일정은 그대로다(FR-007).
     */
    @POST("detections/{detectionId}/route-previews")
    suspend fun createPreview(
        @Header("Authorization") bearer: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Path("detectionId") detectionId: String,
        @Body body: CreatePreviewRequest,
    ): Response<SuccessEnvelope<RoutePreviewDto>>

    /**
     * 미리보기의 계산 결과를 확정한다(REPL-002).
     *
     * 장소 교체·경로 확정·이력 저장·감지 결과 종료가 하나의 transaction으로 처리된다.
     * `409`의 여러 원인은 다음 행동이 서로 달라 [ReplacementError]로 나눠 분류한다(UI-005).
     */
    @POST("route-previews/{previewId}/approve")
    suspend fun approvePreview(
        @Header("Authorization") bearer: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Path("previewId") previewId: String,
    ): Response<SuccessEnvelope<ReplacementDto>>

    /**
     * 미리보기만 폐기한다(REPL-003). 기존 일정·경로·감지 결과는 그대로다.
     *
     * 사용자가 `다른 후보 보기`로 나갈 때 호출한다(UI-003). 이미 폐기했거나 밀려난
     * 미리보기에 다시 요청해도 `204`라 별도 성공 판정이 필요 없다.
     */
    @POST("route-previews/{previewId}/reject")
    suspend fun rejectPreview(
        @Header("Authorization") bearer: String,
        @Path("previewId") previewId: String,
    ): Response<Unit>

    /**
     * 승인으로 바뀐 장소와 경로를 승인 전 상태로 복원한다(REPL-004).
     *
     * 되돌릴 수 있는지는 서버가 판정한다. 앱은 남은 시간을 표시만 하고 만료를 스스로
     * 결정하지 않는다(FR-015).
     */
    @POST("replacements/{replacementId}/undo")
    suspend fun undoReplacement(
        @Header("Authorization") bearer: String,
        @Path("replacementId") replacementId: String,
    ): Response<SuccessEnvelope<ReplacementUndoResultDto>>
}
