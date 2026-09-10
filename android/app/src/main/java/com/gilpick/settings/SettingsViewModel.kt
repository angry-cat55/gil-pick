package com.gilpick.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gilpick.auth.AuthResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 설정 화면의 상태 보유자(T014).
 *
 * 토글은 사람이 누르는 속도로 여러 번 바뀔 수 있는데 요청은 **한 번에 하나만** 보낸다. 그래서
 * 저장 중에 들어온 선택은 요청을 더 만들지 않고 [desired]에만 적는다. 앞 요청이 끝났을 때
 * 희망값이 **보낸 값과 달라져 있으면**(= 도는 사이 사용자가 다시 골랐으면) 한 번 더 보낸다.
 *
 * ```
 * 끔  → PATCH(false) 진행 중 ┐
 *   켬  → desired=true       │  (요청을 새로 만들지 않는다)
 *     끔 → desired=false     │
 *                            ▼
 *              응답 도착: desired(false) == 보낸 값(false) → 끝
 * ```
 *
 * 이렇게 하면 빠르게 눌러도 **마지막 희망값**이 반드시 반영되고, 늦게 도착한 앞 응답이 최신
 * 선택을 덮어쓰지 않는다(SC-004).
 *
 * 응답이 보낸 값과 달라도 다시 보내지 않는다. 그 차이는 다른 기기의 나중 변경이고 서버 값이
 * 정본이다(FR-003). 다시 보내면 두 기기가 값을 두고 끝없이 다툰다.
 *
 * 저장에 실패하면 화면 값을 **마지막으로 저장에 성공한 값**으로 되돌린다(FR-006). 저장되지 않은
 * 값을 성공처럼 남겨 두지 않는 것이 이 화면의 핵심 규칙이다.
 */
class SettingsViewModel(
    private val repository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())

    /** 화면이 관찰하는 현재 상태. */
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** 마지막으로 저장에 성공한 값. 변경 실패 시 여기로 되돌린다. 아직 모르면 `null`이다. */
    private var lastConfirmed: Boolean? = null

    /** 사용자가 마지막으로 고른 값. 요청이 도는 사이 이 값이 바뀌면 한 번 더 보낸다. */
    private var desired: Boolean? = null

    /** 진행 중인 조회. 재진입·`다시 시도` 연타로 조회가 겹치지 않게 한다. */
    private var loadJob: Job? = null

    /** 진행 중인 저장. 이것이 살아 있으면 새 요청을 만들지 않는다. */
    private var saveJob: Job? = null

    init {
        load()
    }

    /**
     * 저장된 설정을 조회한다. 화면 진입과 조회 실패의 `다시 시도`가 부른다.
     *
     * 저장이 진행 중이면 조회하지 않는다. 조회 응답이 방금 보낸 변경을 덮어써 사용자가 고른
     * 값이 되돌아간 것처럼 보이기 때문이다.
     */
    fun load() {
        if (loadJob?.isActive == true || saveJob?.isActive == true) return
        _state.update { it.copy(preference = PreferencePhase.Loading) }
        loadJob = viewModelScope.launch {
            when (val result = repository.getPreferences()) {
                is AuthResult.Success -> confirm(result.value.placeChangeSuggestionNotificationEnabled)
                is AuthResult.Failure -> fail(result.error.toSettingsError())
            }
        }
    }

    /**
     * 알림 설정을 바꾼다(UI-004).
     *
     * @param enabled 사용자가 방금 고른 값. 저장 중이면 요청을 새로 만들지 않고 희망값만 갱신한다.
     */
    fun setPlaceChangeSuggestionEnabled(enabled: Boolean) {
        desired = enabled
        // 화면은 방금 고른 값을 바로 보인다. 저장에 실패하면 마지막 성공값으로 되돌린다.
        _state.update { it.copy(preference = PreferencePhase.Content(value = enabled, isSaving = true)) }
        if (saveJob?.isActive == true) return
        save()
    }

    /**
     * 실패한 변경을 다시 시도한다(UI-003).
     *
     * 실패한 그 값이 아니라 **마지막 희망값**을 보낸다. 실패한 사이 사용자가 마음을 바꿨을 수 있다.
     */
    fun retrySave() {
        val target = desired ?: return
        if (saveJob?.isActive == true) return
        _state.update { it.copy(preference = PreferencePhase.Content(value = target, isSaving = true)) }
        save()
    }

    private fun save() {
        saveJob = viewModelScope.launch {
            // 요청이 도는 사이 사용자가 값을 바꿨으면 그 값으로 한 번 더 보낸다. 바꾸지 않았으면
            // 서버가 저장한 값이 정본이므로 그대로 확정하고 멈춘다 — 응답이 보낸 값과 다르다고
            // 다시 보내면 다른 기기와 값을 두고 끝없이 다투게 된다.
            while (true) {
                val target = desired ?: return@launch
                when (val result = repository.updatePreferences(target)) {
                    is AuthResult.Success -> {
                        val saved = result.value.placeChangeSuggestionNotificationEnabled
                        lastConfirmed = saved
                        val latest = desired
                        if (latest == null || latest == target) {
                            desired = saved
                            _state.update { it.copy(preference = PreferencePhase.Content(value = saved, isSaving = false)) }
                            return@launch
                        }
                        // 저장 중에 사용자가 다시 골랐다. 그 값을 보이면서 다음 요청을 잇는다.
                        _state.update { it.copy(preference = PreferencePhase.Content(value = latest, isSaving = true)) }
                    }

                    is AuthResult.Failure -> {
                        fail(result.error.toSettingsError())
                        return@launch
                    }
                }
            }
        }
    }

    private fun confirm(value: Boolean) {
        lastConfirmed = value
        desired = value
        _state.update { it.copy(preference = PreferencePhase.Content(value = value, isSaving = false)) }
    }

    private fun fail(error: SettingsError) {
        _state.update {
            it.copy(preference = PreferencePhase.Error(lastConfirmedValue = lastConfirmed, error = error))
        }
    }

    companion object {
        /** 화면이 사용할 의존성을 조립한다. DI 도구를 두지 않는 F001 방식이다. */
        fun factory(repository: SettingsRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(repository = repository) }
        }
    }
}
