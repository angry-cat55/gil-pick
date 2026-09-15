package com.gilpick.trip

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gilpick.BuildConfig
import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthResult
import com.gilpick.auth.AuthService
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.SessionRevocationWorker
import com.gilpick.auth.createAuthRetrofit
import com.gilpick.notification.FcmTokenClearWorker
import com.gilpick.notification.FcmTokenSyncWorker
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException

/** 여행명 입력이 어긋난 방식. */
enum class TripNameError {
    /** 앞뒤 공백을 제외하면 2자 미만이다. */
    TOO_SHORT,

    /** 30자를 넘는다. */
    TOO_LONG,
}

/** 여행 기간 입력이 어긋난 방식. */
enum class TripPeriodError {
    /** 시작일과 종료일을 아직 고르지 않았다. */
    NOT_SELECTED,

    /** 종료일이 시작일보다 빠르다. */
    END_BEFORE_START,

    /** 시작일 포함 7일을 넘는다. */
    TOO_LONG,
}

/**
 * 폼 검증 결과.
 *
 * 두 오류를 함께 담는다. 하나씩만 알리면 사용자가 고칠 때마다 다시 막히는 경험이
 * 반복된다.
 */
data class TripFormValidation(
    val nameError: TripNameError? = null,
    val periodError: TripPeriodError? = null,
) {
    /** 서버로 보내도 되는 입력인지 여부. */
    val isValid: Boolean get() = nameError == null && periodError == null
}

/**
 * 여행 생성·수정 폼의 입력 규칙.
 *
 * 서버가 최종 판정하지만 화면도 같은 규칙을 적용해 불필요한 왕복을 줄인다. 규칙이
 * 갈라지지 않도록 근거를 함께 남긴다.
 */
object TripFormValidator {

    /** 여행명 길이 범위. `spec.md` FR-001·FR-001b. */
    private const val NAME_MIN = 2
    private const val NAME_MAX = 30

    /** 시작일과 종료일의 최대 차이. 시작일을 포함해 7일이므로 6이다. `spec.md` FR-001. */
    private const val MAX_PERIOD_DAYS = 6L

    /**
     * 여행명과 기간을 검증한다.
     *
     * @param name 사용자가 입력한 원문. 길이는 앞뒤 공백을 제거한 뒤 센다.
     * @param startDate 고른 시작일. 아직 고르지 않았으면 `null`이다.
     * @param endDate 고른 종료일. 아직 고르지 않았으면 `null`이다.
     * @return 이름·기간 각각의 오류를 담은 결과. 문제가 없으면 두 값 모두 `null`이다.
     */
    fun validate(
        name: String,
        startDate: LocalDate?,
        endDate: LocalDate?,
    ): TripFormValidation = TripFormValidation(
        nameError = validateName(name),
        periodError = validatePeriod(startDate, endDate),
    )

    private fun validateName(name: String): TripNameError? {
        val length = name.trim().length
        return when {
            length < NAME_MIN -> TripNameError.TOO_SHORT
            length > NAME_MAX -> TripNameError.TOO_LONG
            else -> null
        }
    }

    private fun validatePeriod(startDate: LocalDate?, endDate: LocalDate?): TripPeriodError? {
        if (startDate == null || endDate == null) return TripPeriodError.NOT_SELECTED
        val days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate)
        return when {
            days < 0 -> TripPeriodError.END_BEFORE_START
            days > MAX_PERIOD_DAYS -> TripPeriodError.TOO_LONG
            else -> null
        }
    }
}

/**
 * 생성 요청이 실패한 이유.
 *
 * 화면이 원인과 다음 행동을 함께 안내할 수 있도록 좁힌다. 서버 code를 화면까지
 * 그대로 노출하지 않는다.
 */
enum class TripFormSubmitError {
    /** 통신 실패. 같은 요청을 그대로 다시 보낼 수 있다. */
    NETWORK,

    /** 서버가 이름 또는 기간을 거절했다. 입력을 고쳐야 한다. */
    INVALID_INPUT,

    /**
     * `409 VERSION_CONFLICT`. 조회한 뒤 다른 요청이 여행을 바꿨다.
     *
     * 입력을 고쳐서 풀리는 문제가 아니다. 최신 정보를 다시 조회한 뒤 재시도해야 한다
     * (`spec.md` FR-011a, US4 Acceptance 8).
     */
    VERSION_CONFLICT,

    /**
     * `409 TRIP_LOCKED`. 완료된 여행의 기간을 수정하려 했다.
     *
     * 이름은 상태와 무관하게 수정할 수 있다(FR-010, FR-010a, US4 Acceptance 7).
     */
    TRIP_LOCKED,

    /**
     * `409 CONFIRMATION_REQUIRED`. 기간 축소로 삭제될 일정이 있어 확인이 필요하다.
     *
     * 정상 흐름에서는 이 값이 화면에 뜨지 않는다. 서버가 삭제될 장소 수를 함께 주므로
     * [TripFormUiState.deleteConfirmation]으로 확인 대화상자를 띄우기 때문이다(FR-013).
     * 개수 없이 code만 온 계약 위반 응답에서만 안내 문구로 남는다.
     */
    CONFIRMATION_REQUIRED,

    /**
     * `409 TRIP_PERIOD_CONFLICT`. 같은 사용자의 다른 여행과 기간이 하루라도 겹친다(FR-002a).
     *
     * 입력은 그대로 두고 다른 기간을 고르도록 안내한다. 겹친 여행 이름은 [TripFormUiState.conflictTripName]에 있다.
     */
    PERIOD_CONFLICT,

    /** `413 IMAGE_TOO_LARGE`. 대표 이미지가 5MB를 넘는다(FR-019). 여행 이름·기간은 이미 저장됐을 수 있다. */
    IMAGE_TOO_LARGE,

    /** `415 UNSUPPORTED_IMAGE_TYPE`. jpeg·png·webp가 아니다(FR-019). */
    IMAGE_UNSUPPORTED_TYPE,

    /** 그 밖의 실패. 잠시 후 다시 시도한다. */
    UNEXPECTED,
}

/**
 * 폼이 생성 중인지 수정 중인지.
 *
 * 두 흐름은 검증 규칙이 같고 화면도 공용이지만 제출 경로, 오류 종류, 기간 입력 잠금이
 * 다르다. 어느 쪽인지를 이 한 값으로만 판단해 화면과 view model에 분기가 흩어지지
 * 않게 한다(`tasks.md` T037).
 */
sealed interface FormMode {

    /** 새 여행을 만든다. */
    data object Create : FormMode

    /**
     * 이미 있는 여행을 고친다.
     *
     * @property tripId 수정 대상.
     * @property version 조회 시점의 버전. 수정 요청에 그대로 실어 보낸다(FR-011a).
     * @property status 조회 시점의 상태. 완료 상태면 기간 입력을 잠근다(FR-010a).
     */
    data class Edit(
        val tripId: String,
        val version: Int,
        val status: TripStatus,
    ) : FormMode
}

/**
 * 사용자가 고른 대표 이미지(#499). 저장할 때 업로드한다.
 *
 * 배열 내용 비교가 필요 없어 data class로 두지 않는다. 같은 선택인지는 참조로 충분하다.
 *
 * @property bytes 이미지 원본. 고를 때 5MB 이하임을 확인했다.
 * @property mimeType `image/jpeg`·`image/png`·`image/webp` 중 하나.
 */
class PickedTripImage(val bytes: ByteArray, val mimeType: String)

/** 고른 이미지를 쓸 수 없는 이유(FR-019). 고르는 즉시 안내하고 선택은 바꾸지 않는다. */
enum class TripImageError {
    /** 5MB를 넘는다. */
    TOO_LARGE,

    /** jpeg·png·webp가 아니다. */
    UNSUPPORTED_TYPE,

    /** 파일을 읽지 못했다. */
    UNREADABLE,
}

/** Photo Picker 결과를 읽어 확인한 값. [readTripImage]가 만든다. */
sealed interface TripImagePick {
    /** 올릴 수 있는 이미지. */
    data class Picked(val image: PickedTripImage) : TripImagePick

    /** 제한에 걸린 이미지. */
    data class Rejected(val error: TripImageError) : TripImagePick
}

/**
 * 달력에서 고를 수 없는 다른 여행의 기간(#501). 내 여행 목록에서 읽는다.
 *
 * @property tripId 수정 중인 자기 여행을 빼기 위한 식별자.
 */
data class OccupiedPeriod(val tripId: String, val startDate: LocalDate, val endDate: LocalDate)

/**
 * 여행 생성 폼 상태.
 *
 * @property showErrors 검증 오류를 화면에 표시할지 여부. 입력 도중에 빨간 글씨를 띄우지
 *   않고 제출을 한 번 시도한 뒤부터 보여준다.
 * @property savedTripId 생성 또는 수정에 성공한 여행 ID. 화면 이동 뒤
 *   [TripFormViewModel.consumeSaved]로 비운다.
 * @property mode 생성 중인지 수정 중인지. 제출 경로와 기간 입력 잠금이 여기서 갈린다.
 * @property deleteConfirmation 기간 축소로 삭제될 장소 수. `null`이면 확인 대화상자를
 *   띄우지 않는다. 서버가 `409 CONFIRMATION_REQUIRED`로 알려 준 값이며(FR-013),
 *   사용자가 동의해야만 같은 요청을 `confirmDeleteOutOfRangeItems=true`로 다시 보낸다.
 * @property originalStartDate 수정 모드에서 조회한 원래 시작일. 저장 전 기간 축소 표시(Figma `EditTripScreen`)에만 쓴다.
 * @property originalEndDate 수정 모드에서 조회한 원래 종료일.
 * @property deletion 수정 화면 `여행 삭제` 요청 단계(Figma `EditTripScreen`, #443). 상세의 삭제와 같은 단계를 쓴다.
 * @property occupiedPeriods 내 여행들의 기간. 수정 중인 자기 여행도 들어 있으며 [occupiedDates]가 뺀다.
 * @property conflictTripName `409 TRIP_PERIOD_CONFLICT`에서 서버가 알려 준 겹친 여행 이름. 없으면 이름 없이 안내한다.
 * @property imageUrl 서버에 저장된 대표 이미지 표시(수정 모드). 값은 "이미지 있음"으로만 쓴다.
 * @property currentImage [imageUrl]의 원본 bytes. 받기 전이거나 실패하면 `null`이고 대체 배경이 보인다.
 * @property pickedImage 새로 고른 이미지. 저장할 때 업로드한다.
 * @property removeImage 저장된 이미지를 지우기로 했는지(`기본으로`). 저장할 때 삭제한다.
 * @property imageError 방금 고른 이미지를 쓸 수 없는 이유.
 */
data class TripFormUiState(
    val name: String = "",
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val validation: TripFormValidation = TripFormValidation(),
    val showErrors: Boolean = false,
    val submitting: Boolean = false,
    val submitError: TripFormSubmitError? = null,
    val savedTripId: String? = null,
    val mode: FormMode = FormMode.Create,
    val loading: Boolean = false,
    val deleteConfirmation: Int? = null,
    val originalStartDate: LocalDate? = null,
    val originalEndDate: LocalDate? = null,
    val deletion: TripDeletePhase = TripDeletePhase.Idle,
    val occupiedPeriods: List<OccupiedPeriod> = emptyList(),
    val conflictTripName: String? = null,
    val imageUrl: String? = null,
    val currentImage: ByteArray? = null,
    val pickedImage: PickedTripImage? = null,
    val removeImage: Boolean = false,
    val imageError: TripImageError? = null,
) {
    /** 커버에 그릴 이미지. 새로 고른 것이 우선이고, 지우기로 했으면 없다. */
    val coverImage: ByteArray?
        get() = pickedImage?.bytes ?: currentImage.takeUnless { removeImage }

    /** 기본 이미지가 아닌지. `기본으로` 버튼과 `커스텀 이미지` 라벨이 이 값을 따른다. */
    val hasCustomImage: Boolean
        get() = pickedImage != null || (imageUrl != null && !removeImage)

    /**
     * 달력에서 비활성으로 둘 날짜(#501, FR-002a). 수정 중인 자기 여행의 기간은 다시 고를 수 있어야 하므로 뺀다.
     */
    val occupiedDates: Set<LocalDate>
        get() {
            val own = (mode as? FormMode.Edit)?.tripId
            return occupiedPeriods
                .filter { it.tripId != own }
                .flatMapTo(HashSet()) { period ->
                    generateSequence(period.startDate) { day -> day.plusDays(1).takeIf { it <= period.endDate } }.toList()
                }
        }

    /**
     * 기간 입력을 잠글지 여부.
     *
     * 완료된 여행은 이름만 수정할 수 있다(`spec.md` FR-010a). 서버가 최종 판정하지만
     * 화면도 같은 규칙으로 먼저 막아 사용자가 고칠 수 없는 값을 건드리지 않게 한다.
     */
    val periodLocked: Boolean
        get() = mode is FormMode.Edit && mode.status == TripStatus.COMPLETED

    /** 수정 모드에서 시작일을 원래보다 늦췄는지. 줄어든 기간 밖 일정이 삭제될 수 있다(FR-012). */
    val startShrunk: Boolean
        get() = startDate != null && originalStartDate != null && startDate > originalStartDate

    /** 수정 모드에서 종료일을 원래보다 앞당겼는지. */
    val endShrunk: Boolean
        get() = endDate != null && originalEndDate != null && endDate < originalEndDate
}

/**
 * 여행 생성 화면의 상태 보유자.
 *
 * 같은 입력에 대한 재시도는 같은 `Idempotency-Key`로 보낸다. 통신 실패 후 사용자가
 * 다시 눌렀을 때 서버에 여행이 두 건 생기지 않게 하기 위해서다. 입력이 바뀌면 다른
 * 생성이므로 키를 새로 만든다.
 *
 * @property repository 여행 데이터 접근 지점.
 */
class TripFormViewModel(private val repository: TripRepository) : ViewModel() {

    private val _state = MutableStateFlow(TripFormUiState())

    /** 화면이 관찰하는 현재 폼 상태. */
    val state: StateFlow<TripFormUiState> = _state.asStateFlow()

    /**
     * 진행 중인 생성 시도의 멱등 키.
     *
     * 입력이 바뀌거나 생성에 성공하면 비운다. 그래야 다음 제출이 새 여행으로 처리된다.
     */
    private var idempotencyKey: String? = null

    /**
     * 이미지 업로드만 실패한 새 여행(#499). 여행은 이미 만들어졌으므로 다시 저장하면 새로 만들지 않고 이 여행을 고친 뒤
     * 이미지를 다시 올린다. 이름·기간을 바꿔도 여행이 두 건 생기지 않게 한다.
     */
    private var createdTrip: TripDto? = null

    /** 마지막으로 읽은 내 여행 기간. 수정 조회가 폼 상태를 새로 만들어도 유지한다. */
    private var occupiedPeriods: List<OccupiedPeriod> = emptyList()

    init {
        viewModelScope.launch { loadOccupiedPeriods() }
    }

    /**
     * 달력 비활성 표시에 쓸 내 여행 기간을 모든 page에서 읽는다(#501).
     *
     * 실패하면 조용히 넘어간다. 비활성 표시는 보조 수단이고, 겹치는 기간은 저장할 때 서버가
     * `409 TRIP_PERIOD_CONFLICT`로 막으므로 폼 사용을 막을 이유가 없다.
     */
    private suspend fun loadOccupiedPeriods() {
        val periods = mutableListOf<OccupiedPeriod>()
        var cursor: String? = null
        do {
            // 응답 형식이 어긋나면 repository가 변환 예외를 그대로 던진다. 보조 조회라 폼을 멈추지 않고 넘어간다.
            val result = try {
                repository.listTrips(cursor = cursor, limit = OCCUPIED_PAGE_SIZE)
            } catch (e: SerializationException) {
                return
            }
            val page = when (result) {
                is AuthResult.Success -> result.value
                is AuthResult.Failure -> return
            }
            page.trips.mapTo(periods) {
                OccupiedPeriod(it.tripId, LocalDate.parse(it.startDate), LocalDate.parse(it.endDate))
            }
            cursor = page.nextCursor
        } while (page.hasNext && cursor != null)

        occupiedPeriods = periods
        _state.update { it.copy(occupiedPeriods = periods) }
    }

    /** 여행명 입력을 반영한다. */
    fun onNameChange(value: String) {
        idempotencyKey = null
        _state.update {
            it.copy(name = value, submitError = null, conflictTripName = null, deleteConfirmation = null).revalidated()
        }
    }

    /** 고른 여행 기간을 반영한다. 둘 중 하나만 고른 상태도 그대로 보존한다. */
    fun onPeriodChange(startDate: LocalDate?, endDate: LocalDate?) {
        idempotencyKey = null
        _state.update {
            it.copy(
                startDate = startDate,
                endDate = endDate,
                submitError = null,
                conflictTripName = null,
                deleteConfirmation = null,
            ).revalidated()
        }
    }

    /**
     * 수정할 여행을 조회해 폼에 채운다.
     *
     * 상세 화면이 이미 가진 값을 route로 넘기지 않고 `tripId`로 다시 조회한다. 상세를
     * 열어 둔 사이에 다른 기기가 여행을 바꿨을 수 있고, 그때 낡은 `version`으로
     * 저장하면 `409 VERSION_CONFLICT`가 난다. 수정 직전 값이 가장 정확하다.
     *
     * @param tripId 수정할 여행 식별자. navigation route가 넘긴다.
     */
    fun loadForEdit(tripId: String) {
        if (_state.value.loading) return

        _state.value = TripFormUiState(loading = true, occupiedPeriods = occupiedPeriods)
        viewModelScope.launch {
            when (val result = repository.getTrip(tripId)) {
                is AuthResult.Success -> startEditing(result.value)
                is AuthResult.Failure -> _state.update {
                    it.copy(loading = false, submitError = result.error.toSubmitError())
                }
            }
        }
    }

    /**
     * 수정할 여행을 폼에 채운다.
     *
     * 조회 시점의 `version`과 `status`를 [FormMode.Edit]에 담는다. 서버가 낙관적
     * 동시성 제어에 쓰고(FR-011a), 완료 상태면 화면이 기간 입력을 잠근다(FR-010a).
     *
     * @param trip 수정 대상. 상세 화면이 이미 받아 둔 값을 그대로 넘긴다.
     */
    fun startEditing(trip: TripDto) {
        _state.value = TripFormUiState(
            name = trip.name,
            startDate = LocalDate.parse(trip.startDate),
            endDate = LocalDate.parse(trip.endDate),
            originalStartDate = LocalDate.parse(trip.startDate),
            originalEndDate = LocalDate.parse(trip.endDate),
            mode = FormMode.Edit(
                tripId = trip.tripId,
                version = trip.version,
                status = trip.status,
            ),
            occupiedPeriods = occupiedPeriods,
            imageUrl = trip.imageUrl,
        )
        if (trip.imageUrl != null) loadCurrentImage(trip.tripId, trip.imageUrl)
    }

    /**
     * 저장된 대표 이미지 원본을 받아 커버에 채운다(#499).
     *
     * 실패하면 대체 배경을 그대로 둔다. 미리보기일 뿐이라 폼 사용을 막지 않는다. 받는 사이 사용자가 다른 이미지를 고르거나
     * 지웠으면 [TripFormUiState.coverImage]가 그 선택을 우선하므로 덮어써도 보이지 않는다.
     */
    private fun loadCurrentImage(tripId: String, imageUrl: String) {
        viewModelScope.launch {
            val result = repository.getTripImage(tripId) as? AuthResult.Success ?: return@launch
            _state.update { if (it.imageUrl == imageUrl) it.copy(currentImage = result.value) else it }
        }
    }

    /**
     * Photo Picker에서 고른 결과를 반영한다(#499).
     *
     * 제한에 걸리면 원인만 알리고 이전 선택은 그대로 둔다.
     */
    fun onImagePicked(pick: TripImagePick) {
        _state.update {
            when (pick) {
                is TripImagePick.Picked -> it.copy(pickedImage = pick.image, removeImage = false, imageError = null, submitError = null)
                is TripImagePick.Rejected -> it.copy(imageError = pick.error)
            }
        }
    }

    /** `기본으로`. 새로 고른 이미지를 버리고, 저장된 이미지가 있으면 저장할 때 지운다. */
    fun onRemoveImage() {
        _state.update {
            it.copy(pickedImage = null, removeImage = it.imageUrl != null, imageError = null, submitError = null)
        }
    }

    /**
     * 폼 내용을 서버에 보낸다.
     *
     * 생성이면 만들고 수정이면 고친다. 어느 쪽인지는 [TripFormUiState.mode]만 보고
     * 정한다. 검증에 실패하면 서버로 보내지 않고 오류만 표시한다. 이미 전송 중이면
     * 중복 요청을 만들지 않는다.
     */
    fun submit() {
        val current = _state.value
        if (current.submitting) return

        val validation = TripFormValidator.validate(current.name, current.startDate, current.endDate)
        if (!validation.isValid) {
            _state.update { it.copy(validation = validation, showErrors = true) }
            return
        }

        val start = current.startDate ?: return
        val end = current.endDate ?: return

        _state.update {
            it.copy(
                submitting = true,
                showErrors = true,
                submitError = null,
                conflictTripName = null,
                deleteConfirmation = null,
            )
        }
        viewModelScope.launch { send(current, start, end, confirmDeleteOutOfRangeItems = false) }
    }

    /**
     * 기간 축소로 삭제될 일정에 동의하고 같은 수정을 다시 보낸다.
     *
     * 확인 대화상자의 `저장하기`에서만 호출한다. 사용자가 이미 고른 이름·기간을 그대로
     * 쓰고 `confirmDeleteOutOfRangeItems`만 `true`로 바꾼다(FR-013). 새로 검증하지
     * 않는다. 방금 서버까지 다녀온 값이라 그 사이 입력이 바뀌지 않았다.
     */
    fun confirmDeleteOutOfRangeItems() {
        val current = _state.value
        if (current.submitting || current.deleteConfirmation == null) return

        val start = current.startDate ?: return
        val end = current.endDate ?: return

        _state.update { it.copy(submitting = true, deleteConfirmation = null, submitError = null) }
        viewModelScope.launch { send(current, start, end, confirmDeleteOutOfRangeItems = true) }
    }

    /**
     * 삭제 확인을 취소한다.
     *
     * 저장하지 않고 대화상자만 닫는다. 폼에 고른 기간은 그대로 남으므로 사용자가 다시
     * 줄이거나 되돌릴 수 있다. 서버의 여행은 손대지 않았다(US4 Acceptance 4).
     */
    fun cancelDeleteConfirmation() {
        _state.update { it.copy(deleteConfirmation = null) }
    }

    /**
     * 생성 또는 수정 요청을 보내고 결과를 상태에 반영한다.
     *
     * @param confirmDeleteOutOfRangeItems 기간 축소로 삭제될 일정에 사용자가 동의했는지.
     *   수정에만 쓰이며 생성에는 해당하지 않는다.
     */
    private suspend fun send(
        current: TripFormUiState,
        start: LocalDate,
        end: LocalDate,
        confirmDeleteOutOfRangeItems: Boolean,
    ) {
        val created = createdTrip
        val result = when (val mode = current.mode) {
            // 이미지 업로드만 실패했던 새 여행은 다시 만들지 않고 고친다(#499).
            // 기간은 바뀐 때만 보낸다. 지난 날짜로 만든 여행은 이미 완료 상태라 같은 기간도 TRIP_LOCKED로 거절된다(#569).
            is FormMode.Create if created != null -> {
                val periodChanged = start.toString() != created.startDate || end.toString() != created.endDate
                repository.updateTrip(
                    tripId = created.tripId,
                    version = created.version,
                    name = current.name,
                    startDate = start.takeIf { periodChanged },
                    endDate = end.takeIf { periodChanged },
                    confirmDeleteOutOfRangeItems = false,
                )
            }

            is FormMode.Create -> {
                // 같은 입력의 재시도는 같은 키로 보낸다. 통신 실패 후 다시 눌렀을 때
                // 여행이 두 건 생기지 않게 한다.
                val key = idempotencyKey
                    ?: UUID.randomUUID().toString().also { idempotencyKey = it }
                repository.createTrip(current.name, start, end, key)
            }

            is FormMode.Edit -> repository.updateTrip(
                tripId = mode.tripId,
                version = mode.version,
                name = current.name,
                // 완료된 여행은 기간을 보내지 않는다. 값이 그대로여도 서버가
                // TRIP_LOCKED로 거절한다(FR-010a).
                startDate = start.takeUnless { current.periodLocked },
                endDate = end.takeUnless { current.periodLocked },
                confirmDeleteOutOfRangeItems = confirmDeleteOutOfRangeItems,
            )
        }

        if (result is AuthResult.Failure) {
            _state.update { it.afterFailure(result.error) }
        } else {
            val saved = (result as AuthResult.Success).value
            // 이름·기간 저장 뒤에 이미지를 올리거나 지운다. 둘 다 서버가 version을 올린다.
            when (val image = saveImage(current, saved)) {
                is AuthResult.Success -> {
                    idempotencyKey = null
                    createdTrip = null
                    _state.update { it.copy(submitting = false, savedTripId = image.value.tripId) }
                }

                is AuthResult.Failure -> {
                    // 여행은 저장됐다. 폼에 남아 원인을 알리고, 다시 저장하면 방금 받은 version으로 이어서 보낸다.
                    if (current.mode is FormMode.Create) createdTrip = saved
                    _state.update { state ->
                        val mode = state.mode
                        state.copy(
                            submitting = false,
                            submitError = image.error.toSubmitError(),
                            mode = if (mode is FormMode.Edit) mode.copy(version = saved.version) else mode,
                        )
                    }
                }
            }
        }
        // 목록을 읽은 뒤 다른 기기에서 여행이 생겼을 수 있다. 겹친 여행 기간이 달력에 보이도록 다시 읽는다.
        if (_state.value.submitError == TripFormSubmitError.PERIOD_CONFLICT) loadOccupiedPeriods()
    }

    /**
     * 고른 이미지를 올리거나 `기본으로`를 반영한다(#499). 바꿀 것이 없으면 저장한 여행을 그대로 돌려준다.
     *
     * 지우려는 이미지가 이미 없으면(`404 TRIP_IMAGE_NOT_FOUND`) 원하는 상태이므로 성공으로 본다.
     */
    private suspend fun saveImage(current: TripFormUiState, saved: TripDto): AuthResult<TripDto> {
        val picked = current.pickedImage
        return when {
            picked != null -> repository.uploadTripImage(saved.tripId, picked.bytes, picked.mimeType)
            current.removeImage && saved.imageUrl != null -> {
                val deleted = repository.deleteTripImage(saved.tripId)
                val missing = (deleted as? AuthResult.Failure)?.error.let { it is AuthError.Server && it.code == TripErrorCodes.TRIP_IMAGE_NOT_FOUND }
                if (missing) AuthResult.Success(saved.copy(imageUrl = null), 200) else deleted
            }

            else -> AuthResult.Success(saved, 200)
        }
    }

    /**
     * 실패를 상태에 반영한다.
     *
     * `409 CONFIRMATION_REQUIRED`는 실패 안내가 아니라 **되물음**이다. 삭제될 장소 수를
     * 받았으면 오류 문구 대신 확인 대화상자를 띄운다. 개수가 없으면 무엇에 동의하는지
     * 말할 수 없으므로 대화상자를 열지 않고 기존 안내 문구로 돌아간다.
     */
    private fun TripFormUiState.afterFailure(error: AuthError): TripFormUiState {
        val server = error as? AuthError.Server
        val deletedItemCount =
            if (server?.code == TripErrorCodes.CONFIRMATION_REQUIRED) {
                server.details?.deletedItemCount
            } else {
                null
            }

        return if (deletedItemCount != null) {
            copy(submitting = false, deleteConfirmation = deletedItemCount)
        } else {
            copy(
                submitting = false,
                submitError = error.toSubmitError(),
                conflictTripName = server?.details?.name.takeIf { server?.code == TripErrorCodes.TRIP_PERIOD_CONFLICT },
            )
        }
    }

    /**
     * 수정 중인 여행을 삭제한다(Figma `EditTripScreen` `여행 삭제`, #443). 확인 대화상자의 `삭제하기`에서만 호출한다.
     *
     * 상세 화면의 [TripDetailViewModel.delete]와 같은 규칙이다. 응답을 기다리는 사이 두 번 눌려 같은 삭제가
     * 두 번 나가지 않게 막는다. 수정 모드가 아니면 아무 것도 하지 않는다.
     */
    fun delete() {
        val mode = _state.value.mode as? FormMode.Edit ?: return
        if (_state.value.deletion is TripDeletePhase.Deleting) return

        _state.update { it.copy(deletion = TripDeletePhase.Deleting) }
        viewModelScope.launch {
            val deletion = when (val result = repository.deleteTrip(mode.tripId)) {
                is AuthResult.Success -> TripDeletePhase.Deleted
                is AuthResult.Failure -> TripDeletePhase.Failed(result.error.toDeleteError())
            }
            _state.update { it.copy(deletion = deletion) }
        }
    }

    /** 삭제 완료 신호를 소비한다. 목록으로 돌아간 뒤 화면이 호출한다. */
    fun consumeDeleted() {
        _state.update { it.copy(deletion = TripDeletePhase.Idle) }
    }

    /** 삭제 실패 안내를 지운다. 사용자가 대화상자를 닫으면 화면이 호출한다. */
    fun clearDeleteError() {
        if (_state.value.deletion is TripDeletePhase.Failed) {
            _state.update { it.copy(deletion = TripDeletePhase.Idle) }
        }
    }

    /** 화면을 이동한 뒤 저장 결과를 비운다. 되돌아왔을 때 다시 이동하지 않게 한다. */
    fun consumeSaved() {
        _state.update { it.copy(savedTripId = null) }
    }

    /** 이미 오류를 표시 중일 때만 검증을 다시 돌려 고치는 즉시 반영되게 한다. */
    private fun TripFormUiState.revalidated(): TripFormUiState =
        if (!showErrors) this
        else copy(validation = TripFormValidator.validate(name, startDate, endDate))

    companion object {

        /** 내 여행 기간 조회 page 크기. 계약 최대값(100)이라 보통 한 번에 끝난다. */
        private const val OCCUPIED_PAGE_SIZE = 100

        /**
         * 화면이 사용할 의존성을 조립한다.
         *
         * DI 도구를 두지 않는 F001 방식을 그대로 따른다.
         */
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    val auth = AuthRepository(
                        store = AuthSessionStore.create(appContext),
                        api = createAuthRetrofit(BuildConfig.API_BASE_URL)
                            .create(AuthService::class.java),
                        appLinkHandler = AuthAppLinkHandler(BuildConfig.APP_LINK_HOST),
                        scheduleRevocation = SessionRevocationWorker.scheduler(appContext),
                        syncPushToken = { FcmTokenSyncWorker.enqueue(appContext) },
                        clearPushToken = { FcmTokenClearWorker.enqueue(appContext) },
                    )
                    TripFormViewModel(
                        TripRepository(
                            api = createTripRetrofit(BuildConfig.API_BASE_URL)
                                .create(TripService::class.java),
                            auth = auth,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * 서버·통신 실패를 화면이 안내할 수 있는 원인으로 좁힌다.
 *
 * HTTP 상태 코드가 아니라 계약이 정한 error code로 판정한다. 수정 endpoint는 `409`
 * 하나에 버전 충돌·기간 잠금·삭제 확인 세 가지를 담으므로 상태 코드로는 갈라지지
 * 않는다.
 */
internal fun AuthError.toSubmitError(): TripFormSubmitError = when (this) {
    is AuthError.Offline -> TripFormSubmitError.NETWORK
    is AuthError.Server -> when (code) {
        TripErrorCodes.VERSION_CONFLICT -> TripFormSubmitError.VERSION_CONFLICT
        TripErrorCodes.TRIP_LOCKED -> TripFormSubmitError.TRIP_LOCKED
        TripErrorCodes.CONFIRMATION_REQUIRED -> TripFormSubmitError.CONFIRMATION_REQUIRED
        TripErrorCodes.TRIP_PERIOD_CONFLICT -> TripFormSubmitError.PERIOD_CONFLICT
        TripErrorCodes.IMAGE_TOO_LARGE -> TripFormSubmitError.IMAGE_TOO_LARGE
        TripErrorCodes.UNSUPPORTED_IMAGE_TYPE -> TripFormSubmitError.IMAGE_UNSUPPORTED_TYPE
        in INPUT_ERROR_CODES -> TripFormSubmitError.INVALID_INPUT
        else -> TripFormSubmitError.UNEXPECTED
    }

    is AuthError.Malformed, is AuthError.Callback -> TripFormSubmitError.UNEXPECTED
}

/** 사용자가 입력을 고쳐야 하는 서버 오류 code. */
private val INPUT_ERROR_CODES = setOf(
    TripErrorCodes.VALIDATION_ERROR,
    TripErrorCodes.INVALID_TRIP_PERIOD,
)
