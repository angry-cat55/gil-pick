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
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
     * F002 시점에는 일정(`trip_days`/`itinerary_items`)이 없어 서버가 삭제 개수를 항상
     * 0으로 보므로 실제로 발동하지 않는다. 화면 표현은 F004에서 만들고 여기서는 원인만
     * 구분해 둔다(FR-012).
     */
    CONFIRMATION_REQUIRED,

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
 * 여행 생성 폼 상태.
 *
 * @property showErrors 검증 오류를 화면에 표시할지 여부. 입력 도중에 빨간 글씨를 띄우지
 *   않고 제출을 한 번 시도한 뒤부터 보여준다.
 * @property savedTripId 생성 또는 수정에 성공한 여행 ID. 화면 이동 뒤
 *   [TripFormViewModel.consumeSaved]로 비운다.
 * @property mode 생성 중인지 수정 중인지. 제출 경로와 기간 입력 잠금이 여기서 갈린다.
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
) {
    /**
     * 기간 입력을 잠글지 여부.
     *
     * 완료된 여행은 이름만 수정할 수 있다(`spec.md` FR-010a). 서버가 최종 판정하지만
     * 화면도 같은 규칙으로 먼저 막아 사용자가 고칠 수 없는 값을 건드리지 않게 한다.
     */
    val periodLocked: Boolean
        get() = mode is FormMode.Edit && mode.status == TripStatus.COMPLETED
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

    /** 여행명 입력을 반영한다. */
    fun onNameChange(value: String) {
        idempotencyKey = null
        _state.update { it.copy(name = value, submitError = null).revalidated() }
    }

    /** 고른 여행 기간을 반영한다. 둘 중 하나만 고른 상태도 그대로 보존한다. */
    fun onPeriodChange(startDate: LocalDate?, endDate: LocalDate?) {
        idempotencyKey = null
        _state.update {
            it.copy(startDate = startDate, endDate = endDate, submitError = null).revalidated()
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

        _state.value = TripFormUiState(loading = true)
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
            mode = FormMode.Edit(
                tripId = trip.tripId,
                version = trip.version,
                status = trip.status,
            ),
        )
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

        _state.update { it.copy(submitting = true, showErrors = true, submitError = null) }
        viewModelScope.launch {
            val mode = current.mode
            val result = when (mode) {
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
                )
            }

            _state.update { state ->
                when (result) {
                    is AuthResult.Success -> {
                        idempotencyKey = null
                        state.copy(submitting = false, savedTripId = result.value.tripId)
                    }

                    is AuthResult.Failure ->
                        state.copy(submitting = false, submitError = result.error.toSubmitError())
                }
            }
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
