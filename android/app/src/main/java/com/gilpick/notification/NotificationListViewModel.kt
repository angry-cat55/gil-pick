package com.gilpick.notification

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gilpick.auth.AuthResult
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 알림 목록 화면의 상태 보유자(T031).
 *
 * NOTI-001 한 페이지를 받아 KST 날짜 구간([toGroups])으로 나눠 [NotificationUiState.Content]로 만든다.
 * 화면이 다시 보일 때마다([load]) 같은 조회를 반복하되 내용이 있으면 대기 표시로 돌아가지 않고 조용히
 * 갱신한다(plan: 재조회 중 기존 목록 유지). 행 탭([open])은 NOTI-002를 보낸 뒤 [open]으로 목적지를
 * 알리고, `모두 읽음`([markAllRead])은 NOTI-003이 성공하면 로컬 목록도 모두 읽음으로 바꾼다.
 * 어떤 요청도 감지·전환·일정 상태를 바꾸지 않는다(FR-025).
 *
 * @param tripName 진행 알림의 목적지 헤더에 쓸 여행명 조회. 알림 payload에 없어 이동 직전에 받는다.
 *   실패하면 빈 문자열이고 진행 화면이 `정보 없음`·오류를 알아서 안내한다.
 * @param now 날짜 구간 기준 시각. test가 고정한다.
 */
class NotificationListViewModel(
    private val repository: NotificationRepository,
    private val tripName: suspend (tripId: String) -> String = { "" },
    private val now: () -> Instant = Instant::now,
) : ViewModel() {

    private val _state = MutableStateFlow<NotificationUiState>(NotificationUiState.Loading)

    /** 화면이 관찰하는 현재 상태. */
    val state: StateFlow<NotificationUiState> = _state.asStateFlow()

    private val _open = MutableStateFlow<NotificationTarget?>(null)

    /** 탭한 알림의 목적지. 화면이 이동한 뒤 [consumeOpen]으로 비운다. */
    val open: StateFlow<NotificationTarget?> = _open.asStateFlow()

    /** 진행 중인 조회. 재진입·`다시 시도` 연타로 겹치는 조회를 막는다. */
    private var job: Job? = null

    init {
        load()
    }

    /**
     * 목록을 조회한다. 화면 진입·재개와 `다시 시도하기`가 부른다.
     *
     * 내용이 이미 있으면 [NotificationUiState.Content.refreshing]만 켠 채 두고, 갱신 실패는 보던 목록을
     * 지우지 않는다.
     */
    fun load() {
        if (job?.isActive == true) return
        _state.update { current ->
            if (current is NotificationUiState.Content) current.copy(refreshing = true) else NotificationUiState.Loading
        }
        job = viewModelScope.launch {
            val next = fetch()
            _state.update { current ->
                if (current is NotificationUiState.Content && next is NotificationUiState.Error) current.copy(refreshing = false) else next
            }
        }
    }

    /**
     * 행 탭(FR-018): 안 읽음이면 NOTI-002를 보내고, 결과와 무관하게 목적지로 이동한다.
     *
     * 읽음 표시는 즉시 로컬에 반영한다. 요청이 실패해도 다음 조회가 서버 값으로 되돌리므로 이동을 막지
     * 않는다. 진행 알림은 이동 직전에 여행명을 받아 [NotificationTarget.Progress.tripName]을 채운다.
     */
    fun open(item: NotifItemUi) {
        if (_open.value != null) return
        if (item.unread) updateItems { if (it.id == item.id) it.copy(unread = false) else it }
        viewModelScope.launch {
            if (item.unread) repository.markRead(item.id)
            _open.value = when (val target = item.target) {
                is NotificationTarget.Alternative -> target
                is NotificationTarget.Progress -> target.copy(tripName = tripName(target.tripId))
            }
        }
    }

    /** 화면이 [open]의 목적지로 이동을 마쳤다. */
    fun consumeOpen() {
        _open.value = null
    }

    /** 헤더 `모두 읽음`(FR-014): 안 읽은 알림이 있을 때만 NOTI-003을 보내고, 성공하면 로컬 목록도 읽음으로 바꾼다. */
    fun markAllRead() {
        val content = _state.value as? NotificationUiState.Content ?: return
        if (content.groups.none { group -> group.items.any { it.unread } }) return
        viewModelScope.launch {
            if (repository.markAllRead() is AuthResult.Success) updateItems { it.copy(unread = false) }
        }
    }

    private fun updateItems(transform: (NotifItemUi) -> NotifItemUi) {
        _state.update { current ->
            val content = current as? NotificationUiState.Content ?: return@update current
            content.copy(groups = content.groups.map { group -> group.copy(items = group.items.map(transform)) })
        }
    }

    private suspend fun fetch(): NotificationUiState = when (val result = repository.listNotifications()) {
        is AuthResult.Success -> {
            val groups = result.value.items.toGroups(now())
            if (groups.isEmpty()) NotificationUiState.Empty else NotificationUiState.Content(groups)
        }
        is AuthResult.Failure -> {
            val error = result.error.toNotificationError()
            NotificationUiState.Error(error, error.retryable)
        }
    }

    companion object {
        /** 화면이 사용할 의존성을 조립한다. DI 도구를 두지 않는 F001 방식이다. */
        fun factory(
            repository: NotificationRepository,
            tripName: suspend (tripId: String) -> String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { NotificationListViewModel(repository = repository, tripName = tripName) }
        }
    }
}
