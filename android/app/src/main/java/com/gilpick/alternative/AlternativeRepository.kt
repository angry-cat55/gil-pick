package com.gilpick.alternative

import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthErrorCodes
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthResult
import com.gilpick.auth.toAuthResult
import com.gilpick.place.PlaceListMeta
import java.io.IOException
import retrofit2.Response

/**
 * 목록 응답 한 페이지. DETECT-001과 ALT-002가 같은 pagination 규칙을 쓴다.
 *
 * @property nextCursor 다음 페이지 요청에 그대로 전달한다. 마지막 페이지면 `null`이다.
 */
data class AlternativePage<T>(
    val items: List<T>,
    val nextCursor: String?,
    val hasNext: Boolean,
)

/**
 * 감지·대체 장소 데이터의 유일한 접근 지점.
 *
 * Access Token 주입과 만료 시 갱신·replay는 [AuthRepository.withAuthorizedCall]이 소유한다.
 * F006 `ProgressRepository`와 같은 구조다.
 *
 * 후보 조회·검색은 일정·경로·감지 상태를 바꾸지 않고(FR-023), 결과를 어디에도 저장하지 않는다.
 * 상태를 바꾸는 유일한 요청은 [dismissDetection]이며 서버가 상태 기반으로 멱등하게 처리하므로
 * `Idempotency-Key`를 보내지 않는다.
 */
class AlternativeRepository(
    private val api: AlternativeService,
    private val auth: AuthRepository,
) {

    /**
     * 여행의 감지 목록을 조회한다(DETECT-001).
     *
     * @param status 지정하면 그 상태만 받는다. 진행 화면 배너는 [DetectionStatus.ACTIVE]로 조회한다.
     */
    suspend fun listDetections(
        tripId: String,
        status: DetectionStatus? = null,
        cursor: String? = null,
    ): AuthResult<AlternativePage<DetectionListItemDto>> = call { token ->
        api.listDetections(bearer = token, tripId = tripId, status = status, cursor = cursor)
            .toAuthResult { it.data.items.page(it.meta) }
    }

    /** 감지 상세를 조회한다(DETECT-002). */
    suspend fun getDetection(detectionId: String): AuthResult<DetectionDetailDto> = call { token ->
        api.getDetection(bearer = token, detectionId = detectionId).toAuthResult()
    }

    /**
     * 감지를 거절한다(DETECT-004, `기존 일정 그대로 진행`).
     *
     * 이미 처리된 감지는 오류가 아니라 현재 상태를 담은 성공 응답이다. 화면은
     * [DismissResultDto.status]로 실제로 거절됐는지 구분한다.
     */
    suspend fun dismissDetection(detectionId: String): AuthResult<DismissResultDto> = call { token ->
        api.dismissDetection(bearer = token, detectionId = detectionId).toAuthResult()
    }

    /** 추천 후보를 조회한다(ALT-001). 후보가 없으면 `items`가 빈 성공 응답이다. */
    suspend fun listAlternatives(detectionId: String): AuthResult<AlternativeListDto> = call { token ->
        api.listAlternatives(bearer = token, detectionId = detectionId).toAuthResult()
    }

    /**
     * 대체 장소를 직접 검색한다(ALT-002).
     *
     * 2글자 검증은 ViewModel이 요청 전에 끝내므로 받은 값을 그대로 보낸다(F003과 같다).
     */
    suspend fun searchAlternatives(
        detectionId: String,
        query: String,
        cursor: String? = null,
    ): AuthResult<AlternativePage<AlternativeSearchItemDto>> = call { token ->
        api.searchAlternatives(bearer = token, detectionId = detectionId, query = query, cursor = cursor)
            .toAuthResult { it.data.items.page(it.meta) }
    }

    /** 다섯 endpoint가 같은 인증·통신 실패 규칙을 쓰도록 한곳에 모은다. */
    private suspend fun <T> call(
        request: suspend (bearer: String) -> AuthResult<T>,
    ): AuthResult<T> = auth.withAuthorizedCall { accessToken ->
        try {
            request("Bearer $accessToken")
        } catch (e: IOException) {
            AuthResult.Failure(AuthError.Offline(e))
        }
    }

    private fun <T> List<T>.page(meta: PlaceListMeta) =
        AlternativePage(items = this, nextCursor = meta.pagination.nextCursor, hasNext = meta.pagination.hasNext)
}

/**
 * 감지·대체 장소 요청이 실패한 이유. 화면이 원인과 다음 행동을 안내하는 데 쓴다.
 *
 * 후보 없음은 여기 없다. 오류가 아니라 `items`가 빈 성공 응답이다(FR-021은 빈 목록 위장을
 * 금지하므로 TourAPI 실패는 반드시 [ProviderFailed]로 드러난다).
 */
sealed interface AlternativeError {
    /** 통신 실패. 같은 요청을 그대로 다시 보낸다. */
    data object Network : AlternativeError

    /**
     * `409 DETECTION_NOT_ACTIVE`. 이미 처리된 감지다.
     *
     * 화면은 그 사실을 알리고 진행 화면으로 돌아가는 행동을 제공한다(spec UI-006).
     *
     * @property status 서버가 알린 현재 상태. 계약에 없는 값이면 `null`.
     */
    data class NotActive(val status: DetectionStatus?) : AlternativeError

    /** `404 DETECTION_NOT_FOUND`. 없거나 삭제된 감지·여행이다. */
    data object NotFound : AlternativeError

    /** `403 TRIP_FORBIDDEN`. 다른 사용자의 여행이다. */
    data object Forbidden : AlternativeError

    /**
     * `502 TOUR_API_FAILED`·`504 TOUR_API_TIMEOUT`·`429 TOUR_API_RATE_LIMITED`. 추천 실패다.
     *
     * 화면은 `다시 시도하기`와 돌아가기를 제공하고 기존 일정은 그대로 둔다(spec UI-006).
     *
     * @property retryable 서버가 알린 재시도 가능 여부.
     */
    data class ProviderFailed(val retryable: Boolean) : AlternativeError

    /** 갱신·replay 후에도 자격이 무효로 확정됐다. F001 재인증 흐름으로 넘어간다. */
    data object SessionExpired : AlternativeError

    /** 그 밖의 실패(`400 INVALID_REQUEST`·`INVALID_CURSOR`, 5xx, 계약과 다른 응답). 잠시 후 다시 시도한다. */
    data object Unexpected : AlternativeError
}

/** 인증 계층의 실패를 대체 장소 화면이 안내할 수 있는 원인으로 좁힌다. */
fun AuthError.toAlternativeError(): AlternativeError = when (this) {
    is AuthError.Offline -> AlternativeError.Network
    is AuthError.Malformed, is AuthError.Callback -> AlternativeError.Unexpected
    is AuthError.Server -> when (code) {
        AlternativeErrorCodes.DETECTION_NOT_ACTIVE -> AlternativeError.NotActive(
            DetectionStatus.entries.firstOrNull { it.name == details?.status },
        )
        AlternativeErrorCodes.DETECTION_NOT_FOUND -> AlternativeError.NotFound
        AlternativeErrorCodes.TRIP_FORBIDDEN -> AlternativeError.Forbidden
        AlternativeErrorCodes.TOUR_API_FAILED,
        AlternativeErrorCodes.TOUR_API_TIMEOUT,
        AlternativeErrorCodes.TOUR_API_RATE_LIMITED,
        -> AlternativeError.ProviderFailed(retryable)

        // 갱신·replay 후에도 401이면 자격이 무효로 확정된 것이다. F001 계약의 refresh
        // 오류 code도 같은 뜻이다.
        AlternativeErrorCodes.INVALID_ACCESS_TOKEN,
        AuthErrorCodes.INVALID_REFRESH_TOKEN,
        AuthErrorCodes.TOKEN_EXPIRED,
        AuthErrorCodes.DEVICE_MISMATCH,
        -> AlternativeError.SessionExpired

        else -> when (httpStatus) {
            401 -> AlternativeError.SessionExpired
            502, 504 -> AlternativeError.ProviderFailed(retryable)
            else -> AlternativeError.Unexpected
        }
    }
}
