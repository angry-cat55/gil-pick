package com.gilpick.itinerary

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gilpick.BuildConfig
import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthResult
import com.gilpick.auth.AuthService
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.SessionRevocationWorker
import com.gilpick.auth.createAuthRetrofit
import com.gilpick.place.AddToScheduleRequest
import com.gilpick.place.PlaceDto
import com.gilpick.place.PlaceTransport
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 화면 편집 상태의 항목 하나(`data-model.md` 6절 `DraftItem`).
 *
 * 저장 요청 [SaveItemDto]와 거의 같지만 `sequence`가 없다. 순서는 목록 위치가 정하고
 * [ItineraryEditViewModel.save]가 1..N을 매긴다. 회전·프로세스 종료 뒤 복원을 위해
 * JSON으로 `SavedStateHandle`에 들어간다.
 *
 * @property itemId 저장된 항목이면 서버가 준 값, 화면에서 새로 추가했으면 `null`.
 * @property status 서버가 정한 처리 상태. 새 항목은 항상 [ItemStatus.PLANNED].
 */
@Serializable
data class DraftItem(
    val itemId: String?,
    val placeId: String,
    val place: PlaceSnapshotDto,
    val stayMinutes: Int,
    val staySource: StaySource,
    val transportToNext: TransportMode?,
    val status: ItemStatus,
) {
    /** 예정 항목만 순서·이동 수단·삭제를 바꿀 수 있다. 처리된 항목은 체류 시간만 바꾼다(FR-017). */
    val editable: Boolean get() = status == ItemStatus.PLANNED
}

/** 날짜 탭 하나. */
data class DayTab(val date: LocalDate, val dayNumber: Int)

/** 편집 화면의 조회 단계. 빈 일정은 [Content]에 `draft`가 비어 있는 상태로 표현한다. */
sealed interface ItineraryEditPhase {
    /** 조회 중. 화면은 1초를 넘길 때만 대기 표시를 띄운다(UI-007). */
    data object Loading : ItineraryEditPhase

    /** 조회 완료. */
    data object Content : ItineraryEditPhase

    /** 조회 실패. 원인과 `다시 시도`를 안내한다. */
    data class Failed(val error: ItineraryError) : ItineraryEditPhase
}

/** 화면 위에 떠 있는 대화상자. */
sealed interface EditDialog {
    /**
     * 저장하지 않은 변경을 버릴지 확인한다(UI-005).
     *
     * @property targetDate 확인 뒤 옮겨 갈 날짜. `null`이면 화면을 닫는다.
     */
    data class Discard(val targetDate: LocalDate?) : EditDialog

    /** `{장소명} 체류 시간` 대화상자(UI-003). 처리된 항목도 연다. */
    data class StayTime(val index: Int) : EditDialog

    /** `이동 수단 변경` 시트(UI-004). 마지막이 아닌 예정 항목만 연다. */
    data class Transport(val index: Int) : EditDialog
}

/** 추가가 거부된 이유. 화면이 짧게 안내하고 사용자가 닫는다. */
enum class EditNotice {
    /** 좌표 없는 장소는 일정에 넣을 수 없다(FR-002). */
    NO_COORDINATES,

    /** 하루 최대 10곳(FR-021). */
    LIMIT_REACHED,
}

/**
 * 일정 편집 화면 상태(`data-model.md` 6절 `ItineraryEditUiState`).
 *
 * @property savedVersion 마지막 조회·저장 버전. 저장 요청에 그대로 실린다.
 * @property draft 화면 편집 상태. [dirty]는 저장본과 다를 때 참이다.
 * @property saveError 마지막 저장 실패 원인. 초안은 그대로 남아 `저장`이 곧 재시도다(FR-019).
 * @property saved 저장에 성공했다. 화면이 이동한 뒤 [ItineraryEditViewModel.consumeSaved]로 비운다.
 * @property exit 변경 없이 닫거나 버리기를 확정했다. 이동 뒤 [ItineraryEditViewModel.consumeExit]로 비운다.
 */
data class ItineraryEditUiState(
    val tripId: String,
    val days: List<DayTab> = emptyList(),
    val selectedDate: LocalDate? = null,
    val savedVersion: Int = 0,
    val draft: List<DraftItem> = emptyList(),
    val dirty: Boolean = false,
    val phase: ItineraryEditPhase = ItineraryEditPhase.Loading,
    val saving: Boolean = false,
    val saveError: ItineraryError? = null,
    val dialog: EditDialog? = null,
    val notice: EditNotice? = null,
    val saved: Boolean = false,
    val exit: Boolean = false,
) {
    /** 선택한 날짜의 탭. 조회 전에는 `null`이다. */
    val selectedDay: DayTab? get() = days.firstOrNull { it.date == selectedDate }

    /** `장소 추가`를 막을지 여부. 10곳이면 비활성화하고 상한을 안내한다(FR-021). */
    val addDisabled: Boolean get() = draft.size >= MAX_ITEMS_PER_DAY
}

/**
 * 일정 편집 화면의 상태 보유자.
 *
 * 초안([ItineraryEditUiState.draft])과 저장본([savedDays])을 분리한다. 저장본은 ITIN-003
 * 개요 한 번으로 여행 기간 전체를 받아 날짜 탭과 각 날짜의 version·항목을 함께 얻는다.
 * 초안과 선택 날짜는 [savedState]에 두어 회전·프로세스 종료 뒤에도 남는다.
 *
 * @param tripId 편집할 여행.
 * @param initialDate 진입 시 선택할 날짜. 여행 기간 밖이면 첫 날짜를 쓴다.
 * @param openSearch 진입 직후 장소 검색으로 바로 갈지. [takeOpenSearch]가 한 번만 참을 돌려준다.
 */
class ItineraryEditViewModel(
    private val repository: ItineraryRepository,
    private val savedState: SavedStateHandle,
    tripId: String,
    private val initialDate: LocalDate,
    private val openSearch: Boolean = false,
) : ViewModel() {

    private val _state = MutableStateFlow(ItineraryEditUiState(tripId = tripId))

    /** 화면이 관찰하는 현재 상태. */
    val state: StateFlow<ItineraryEditUiState> = _state.asStateFlow()

    /** 날짜별 저장본. `dirty` 판정과 탭 전환의 기준이다. */
    private val savedDays = mutableMapOf<LocalDate, DayItineraryDto>()

    /**
     * 진행 중인 저장 시도의 멱등 키.
     *
     * 통신 실패 후 같은 초안을 다시 보낼 때는 같은 키를 써서 서버가 같은 항목 ID를 만들게
     * 한다. 초안이 바뀌거나 저장에 성공하면 비운다.
     */
    private var idempotencyKey: String? = null

    /** 조회가 진행 중인지. `다시 시도` 연타로 겹치는 조회를 막는다. */
    private var loading = false

    init {
        // 검색에서 돌아올 때 다시 조회하지 않도록 화면이 아니라 생성 시점에 한 번 조회한다.
        load()
    }

    /** 여행 전체 일정을 조회해 날짜 탭과 선택 날짜의 초안을 채운다. */
    private fun load() {
        if (loading) return
        loading = true

        _state.update { it.copy(phase = ItineraryEditPhase.Loading) }
        viewModelScope.launch {
            val result = repository.getOverview(_state.value.tripId)
            loading = false
            when (result) {
                is AuthResult.Success -> {
                    savedDays.clear()
                    result.value.days.forEach { savedDays[LocalDate.parse(it.date)] = it }
                    val days = savedDays.values
                        .sortedBy { it.dayNumber }
                        .map { DayTab(LocalDate.parse(it.date), it.dayNumber) }
                    val selected = restoredDate()?.takeIf { savedDays.containsKey(it) }
                        ?: initialDate.takeIf { savedDays.containsKey(it) }
                        ?: days.firstOrNull()?.date
                    val saved = selected?.let { savedDays[it] }
                    val draft = restoredDraft() ?: saved?.items?.map { it.toDraft() }.orEmpty()
                    _state.update {
                        it.copy(
                            days = days,
                            selectedDate = selected,
                            savedVersion = saved?.version ?: 0,
                            draft = draft,
                            dirty = draft != saved?.items?.map { item -> item.toDraft() }.orEmpty(),
                            phase = ItineraryEditPhase.Content,
                        )
                    }
                    persistDraft(selected, draft)
                }

                is AuthResult.Failure -> _state.update {
                    it.copy(phase = ItineraryEditPhase.Failed(result.error.toItineraryError()))
                }
            }
        }
    }

    /** 조회 실패 뒤 `다시 시도`. */
    fun retry() = load()

    /**
     * 진입 직후 장소 검색으로 바로 갈지 알려 준다.
     *
     * 검색에서 돌아올 때나 화면 재생성 뒤에는 다시 가지 않도록 한 번만 참을 돌려준다.
     */
    fun takeOpenSearch(): Boolean {
        if (!openSearch || savedState.get<Boolean>(KEY_OPEN_SEARCH_DONE) == true) return false
        savedState[KEY_OPEN_SEARCH_DONE] = true
        return true
    }

    /**
     * 날짜 탭을 고른다.
     *
     * 저장하지 않은 변경이 있으면 바로 옮기지 않고 버릴지 확인한다. 저장은 날짜 단위라
     * 다른 날짜의 초안을 함께 들고 있으면 `저장`이 무엇을 보내는지 불분명해진다.
     */
    fun selectDate(date: LocalDate) {
        val current = _state.value
        if (date == current.selectedDate || !savedDays.containsKey(date) || current.saving) return
        if (current.dirty) {
            _state.update { it.copy(dialog = EditDialog.Discard(targetDate = date)) }
        } else {
            switchTo(date)
        }
    }

    /** 닫기 버튼 또는 시스템 뒤로 가기. 변경이 없으면 바로 닫는다(UI-005). */
    fun requestClose() {
        if (_state.value.saving) return
        if (_state.value.dirty) {
            _state.update { it.copy(dialog = EditDialog.Discard(targetDate = null)) }
        } else {
            _state.update { it.copy(exit = true) }
        }
    }

    /** 대화상자의 `계속 편집`. */
    fun dismissDialog() {
        _state.update { it.copy(dialog = null) }
    }

    /** 대화상자의 `취소하고 나가기`. 변경을 버리고 닫거나 다른 날짜로 옮긴다. */
    fun confirmDiscard() {
        val dialog = _state.value.dialog as? EditDialog.Discard ?: return
        val target = dialog.targetDate
        if (target == null) {
            _state.update { it.copy(dialog = null, exit = true) }
        } else {
            switchTo(target)
        }
    }

    /** 행의 체류 시간을 눌렀다. 처리된 항목도 체류 시간은 바꿀 수 있다(FR-017). */
    fun editStay(index: Int) {
        if (index in _state.value.draft.indices) _state.update { it.copy(dialog = EditDialog.StayTime(index)) }
    }

    /**
     * 체류 시간 대화상자의 `적용`. 30분 단위·30~360분 밖의 값은 무시한다(FR-004).
     *
     * 값이 바뀌면 사용자 조절값으로 표시해 이후 추천값이 바뀌어도 유지된다(FR-003).
     */
    fun applyStay(minutes: Int) {
        val index = (_state.value.dialog as? EditDialog.StayTime)?.index ?: return
        val draft = _state.value.draft.toMutableList()
        if (minutes !in STAY_MIN..STAY_MAX || minutes % STAY_STEP != 0) return
        val item = draft[index]
        if (minutes != item.stayMinutes) {
            draft[index] = item.copy(stayMinutes = minutes, staySource = StaySource.USER_ADJUSTED)
            updateDraft(draft)
        }
        _state.update { it.copy(dialog = null) }
    }

    /** 행의 `변경`. 마지막 항목과 처리된 항목은 열지 않는다(FR-006·017). */
    fun changeTransport(index: Int) {
        val draft = _state.value.draft
        if (index < draft.lastIndex && draft[index].editable) _state.update { it.copy(dialog = EditDialog.Transport(index)) }
    }

    /** 이동 수단 시트의 `적용`. */
    fun applyTransport(mode: TransportMode) {
        val index = (_state.value.dialog as? EditDialog.Transport)?.index ?: return
        val draft = _state.value.draft.toMutableList()
        if (draft[index].transportToNext != mode) {
            draft[index] = draft[index].copy(transportToNext = mode)
            updateDraft(draft)
        }
        _state.update { it.copy(dialog = null) }
    }

    /** 행의 삭제. 남은 항목의 순서는 목록 위치가 정하고 새 마지막 항목의 이동 수단은 비운다(FR-007). */
    fun removeItem(index: Int) {
        val draft = _state.value.draft.toMutableList()
        if (index !in draft.indices || !draft[index].editable) return
        draft.removeAt(index)
        updateDraft(draft.withLastTransportCleared())
    }

    /**
     * 항목을 [from]에서 [to]로 옮긴다. 위·아래 버튼은 이웃으로, 손잡이 끌기는 여러 칸을 한 번에 옮긴다.
     *
     * 이동 수단은 항목이 아니라 구간(위치)에 붙어 있다. 항목만 옮기고 구간의 이동 수단은 제자리에
     * 두어 마지막 항목은 계속 `null`, 그 앞은 계속 값이 있게 한다(FR-006). 지나가는 항목 중
     * 처리된 항목이 있으면 그 순서가 바뀌므로 거부한다(FR-017).
     */
    fun moveItem(from: Int, to: Int) {
        val draft = _state.value.draft
        if (from == to || from !in draft.indices || to !in draft.indices) return
        val range = minOf(from, to)..maxOf(from, to)
        if (range.any { !draft[it].editable }) return
        val transports = draft.map { it.transportToNext }
        val items = draft.toMutableList().apply { add(to, removeAt(from)) }
        updateDraft(items.mapIndexed { i, item -> item.copy(transportToNext = transports[i]) })
    }

    /** 안내를 닫는다. */
    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    /**
     * F003 `일정에 추가` 시트가 확정한 장소를 초안 끝에 붙인다(FR-002, FR-006).
     *
     * 좌표 없는 장소와 10곳을 넘는 추가는 거부하고 이유를 안내한다. 시트에서 고른 이동
     * 수단은 직전 항목의 다음 구간에 넣고, 첫 항목이면 버린다. 체류 시간이 F003 추천값과
     * 같으면 추천으로, 다르면 사용자 조절값으로 표시한다(FR-003).
     */
    fun addFromSearch(place: PlaceDto, request: AddToScheduleRequest) {
        val latitude = place.latitude
        val longitude = place.longitude
        if (latitude == null || longitude == null) {
            _state.update { it.copy(notice = EditNotice.NO_COORDINATES) }
            return
        }
        val current = _state.value
        if (current.addDisabled) {
            _state.update { it.copy(notice = EditNotice.LIMIT_REACHED) }
            return
        }
        val newItem = DraftItem(
            itemId = null,
            placeId = place.placeId,
            place = PlaceSnapshotDto(
                name = place.name,
                category = place.category,
                tourApiCategory = place.tourApiCategory,
                address = place.address,
                latitude = latitude,
                longitude = longitude,
                imageUrl = place.imageUrl,
            ),
            stayMinutes = request.stayMinutes,
            staySource = if (request.stayMinutes == place.recommendedStayMinutes) StaySource.RECOMMENDED
            else StaySource.USER_ADJUSTED,
            transportToNext = null,
            status = ItemStatus.PLANNED,
        )
        val draft = current.draft.toMutableList()
        if (draft.isNotEmpty()) {
            val last = draft.lastIndex
            draft[last] = draft[last].copy(transportToNext = request.transport.toTransportMode())
        }
        draft += newItem
        updateDraft(draft)
    }

    /**
     * 선택한 날짜의 초안 전체를 저장한다(FR-008).
     *
     * 순서는 목록 위치대로 1..N을 매기고 마지막 항목의 이동 수단은 비운다. `409
     * VERSION_CONFLICT`면 최신 일정을 조회해 version만 바꾼 뒤 같은 초안을 최대 2회 더
     * 보낸다(`research.md` 4절). 그래도 실패하면 초안을 유지한 채 실패를 안내한다.
     */
    fun save() {
        val current = _state.value
        val date = current.selectedDate ?: return
        if (current.saving || current.phase !is ItineraryEditPhase.Content) return

        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            var version = current.savedVersion
            var key = idempotencyKey ?: UUID.randomUUID().toString().also { idempotencyKey = it }
            var attempt = 0
            while (true) {
                val result = repository.saveDayItinerary(
                    tripId = current.tripId,
                    date = date,
                    version = version,
                    items = current.draft.toSaveItems(),
                    idempotencyKey = key,
                )
                val error = (result as? AuthResult.Failure)?.error?.toItineraryError()
                if (result is AuthResult.Success) {
                    idempotencyKey = null
                    applySaved(result.value)
                    return@launch
                }
                if (error != ItineraryError.VersionConflict || attempt >= MAX_CONFLICT_RETRIES) {
                    _state.update { it.copy(saving = false, saveError = error) }
                    return@launch
                }
                // 최신 version을 받아 같은 초안을 다시 보낸다(마지막 저장이 이김). 새 시도이므로 키를 새로 만든다.
                val latest = repository.getDayItinerary(current.tripId, date)
                if (latest !is AuthResult.Success) {
                    _state.update {
                        it.copy(saving = false, saveError = (latest as AuthResult.Failure).error.toItineraryError())
                    }
                    return@launch
                }
                version = latest.value.version
                key = UUID.randomUUID().toString().also { idempotencyKey = it }
                attempt++
            }
        }
    }

    /** 화면을 이동한 뒤 저장 결과를 비운다. */
    fun consumeSaved() {
        _state.update { it.copy(saved = false) }
    }

    /** 화면을 닫은 뒤 닫기 신호를 비운다. */
    fun consumeExit() {
        _state.update { it.copy(exit = false) }
    }

    private fun applySaved(day: DayItineraryDto) {
        val date = LocalDate.parse(day.date)
        savedDays[date] = day
        val draft = day.items.map { it.toDraft() }
        _state.update {
            it.copy(savedVersion = day.version, draft = draft, dirty = false, saving = false, saved = true)
        }
        persistDraft(date, draft)
    }

    private fun switchTo(date: LocalDate) {
        val saved = savedDays.getValue(date)
        val draft = saved.items.map { it.toDraft() }
        idempotencyKey = null
        _state.update {
            it.copy(selectedDate = date, savedVersion = saved.version, draft = draft, dirty = false, dialog = null, saveError = null)
        }
        persistDraft(date, draft)
    }

    private fun updateDraft(draft: List<DraftItem>) {
        idempotencyKey = null
        val date = _state.value.selectedDate
        val saved = date?.let { savedDays[it] }?.items?.map { it.toDraft() }.orEmpty()
        _state.update { it.copy(draft = draft, dirty = draft != saved, saveError = null) }
        persistDraft(date, draft)
    }

    private fun persistDraft(date: LocalDate?, draft: List<DraftItem>) {
        savedState[KEY_SELECTED_DATE] = date?.toString()
        savedState[KEY_DRAFT] = draftJson.encodeToString(draft)
    }

    private fun restoredDate(): LocalDate? = savedState.get<String>(KEY_SELECTED_DATE)?.let(LocalDate::parse)

    private fun restoredDraft(): List<DraftItem>? =
        savedState.get<String>(KEY_DRAFT)?.let { draftJson.decodeFromString<List<DraftItem>>(it) }

    companion object {
        private const val KEY_DRAFT = "itinerary.draft"
        private const val KEY_SELECTED_DATE = "itinerary.selectedDate"
        private const val KEY_OPEN_SEARCH_DONE = "itinerary.openSearchDone"

        /** 체류 시간 범위와 단위(FR-004). F003 시트와 같다. */
        const val STAY_MIN = 30
        const val STAY_MAX = 360
        const val STAY_STEP = 30

        /** 버전 충돌 뒤 자동 재저장 횟수(FR-008). */
        private const val MAX_CONFLICT_RETRIES = 2

        private val draftJson = Json { ignoreUnknownKeys = true }

        /**
         * 화면이 사용할 의존성을 조립한다. DI 도구를 두지 않는 F001 방식이다.
         *
         * @param savedState navigation back stack entry의 handle. F003 결과와 초안이 같은 handle로
         *   오가도록 ViewModel 전용 handle을 따로 만들지 않는다.
         * @param repository 일정 데이터 접근 지점. navigation test가 MockWebServer를 향한 repository로
         *   바꿔 끼운다.
         */
        fun factory(
            savedState: SavedStateHandle,
            tripId: String,
            date: LocalDate,
            openSearch: Boolean,
            repository: ItineraryRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ItineraryEditViewModel(
                    repository = repository,
                    savedState = savedState,
                    tripId = tripId,
                    initialDate = date,
                    openSearch = openSearch,
                )
            }
        }

        /** 실제 서버를 향한 repository. F002 `TripFormViewModel.factory`와 같은 조립이다. */
        fun defaultRepository(context: Context): ItineraryRepository {
            val appContext = context.applicationContext
            val auth = AuthRepository(
                store = AuthSessionStore.create(appContext),
                api = createAuthRetrofit(BuildConfig.API_BASE_URL).create(AuthService::class.java),
                appLinkHandler = AuthAppLinkHandler(BuildConfig.APP_LINK_HOST),
                scheduleRevocation = SessionRevocationWorker.scheduler(appContext),
            )
            return ItineraryRepository(
                api = createItineraryRetrofit(BuildConfig.API_BASE_URL).create(ItineraryService::class.java),
                auth = auth,
            )
        }
    }
}

/** 하루 최대 장소 수(FR-021). */
const val MAX_ITEMS_PER_DAY = 10

/** 저장 응답 항목을 초안으로 옮긴다. 저장본과 초안을 같은 형으로 비교하기 위해서다. */
internal fun ItineraryItemDto.toDraft(): DraftItem = DraftItem(
    itemId = itemId,
    placeId = place.placeId,
    place = PlaceSnapshotDto(
        name = place.name,
        category = place.category,
        tourApiCategory = null,
        address = place.address,
        // 응답에는 좌표가 없다. 기존 항목은 `place`를 싣지 않으므로 값이 쓰이지 않는다.
        latitude = 0.0,
        longitude = 0.0,
        imageUrl = place.imageUrl,
    ),
    stayMinutes = plannedStayMinutes,
    staySource = staySource,
    transportToNext = transportModeToNext,
    status = status,
)

/** 마지막 항목의 이동 수단을 비운다. 삭제로 마지막이 바뀔 때 쓴다(FR-006). */
private fun List<DraftItem>.withLastTransportCleared(): List<DraftItem> =
    mapIndexed { i, item -> if (i == lastIndex && item.transportToNext != null) item.copy(transportToNext = null) else item }

/** 초안을 저장 요청 항목으로 바꾼다. `sequence`는 1..N, 마지막 항목의 이동 수단은 `null`이다(FR-005·006). */
internal fun List<DraftItem>.toSaveItems(): List<SaveItemDto> = mapIndexed { index, item ->
    SaveItemDto(
        itemId = item.itemId,
        placeId = item.placeId,
        place = item.place.takeIf { item.itemId == null },
        sequence = index + 1,
        plannedStayMinutes = item.stayMinutes,
        staySource = item.staySource,
        transportModeToNext = item.transportToNext.takeIf { index < lastIndex },
    )
}

/** F003 시트의 이동 수단을 일정 계약 값으로 옮긴다. 이름이 같아도 다른 enum이다. */
internal fun PlaceTransport.toTransportMode(): TransportMode = when (this) {
    PlaceTransport.WALK -> TransportMode.WALK
    PlaceTransport.TRANSIT -> TransportMode.TRANSIT
    PlaceTransport.CAR -> TransportMode.CAR
}
