package com.gilpick.settings

import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeAuthService
import com.gilpick.auth.FakeSessionCipher
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T009(ViewModel)·T014: 조회·저장 상태 전이와 빠른 연속 선택 검증.
 *
 * `spec.md` FR-003·FR-006, UI-003·UI-004와 data-model 3 `PreferencePhase`가 대상이다. HTTP 왕복은
 * [SettingsRepositoryTest]가 보므로 여기서는 [FakeSettingsService]로 응답만 정한다. 1초 대기 표시
 * 지연은 composable(T015)이 맡는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val service = FakeSettingsService()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- 조회 ---

    @Test
    fun `응답 전에는 loading이고 조회에 성공하면 저장된 값을 보인다`() = runTest {
        service.onGet = { preferenceOk(enabled = false) }
        val viewModel = newViewModel()

        assertEquals(PreferencePhase.Loading, viewModel.state.value.preference)
        advanceUntilIdle()

        assertEquals(PreferencePhase.Content(value = false, isSaving = false), viewModel.state.value.preference)
    }

    @Test
    fun `조회에 실패하면 아는 값이 없어 lastConfirmedValue가 null인 error다`() = runTest {
        service.onGet = { preferenceFailure(SettingsErrorCodes.INVALID_REQUEST, httpStatus = 500) }
        val viewModel = newViewModel()
        advanceUntilIdle()

        val phase = viewModel.state.value.preference as PreferencePhase.Error
        assertNull(phase.lastConfirmedValue)
        assertEquals(SettingsError.Unexpected, phase.error)
    }

    @Test
    fun `조회 재시도는 다시 조회한다`() = runTest {
        service.onGet = { preferenceFailure(SettingsErrorCodes.INVALID_REQUEST, httpStatus = 500) }
        val viewModel = newViewModel()
        advanceUntilIdle()

        service.onGet = { preferenceOk(enabled = true) }
        viewModel.load()
        advanceUntilIdle()

        assertEquals(2, service.getCalls)
        assertEquals(PreferencePhase.Content(value = true, isSaving = false), viewModel.state.value.preference)
    }

    // --- 저장 ---

    @Test
    fun `선택하면 고른 값을 바로 보이고 저장 중임을 알린다`() = runTest {
        // UI-004. 토글이 반응하되 처리 중임이 보여야 한다.
        val gate = CompletableDeferred<Unit>()
        service.onUpdate = { gate.await(); preferenceOk(it) }
        val viewModel = loaded(initial = true)

        viewModel.setPlaceChangeSuggestionEnabled(false)
        runCurrent()

        assertEquals(PreferencePhase.Content(value = false, isSaving = true), viewModel.state.value.preference)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(PreferencePhase.Content(value = false, isSaving = false), viewModel.state.value.preference)
    }

    @Test
    fun `저장은 원하는 절대값을 보낸다`() = runTest {
        val viewModel = loaded(initial = true)

        viewModel.setPlaceChangeSuggestionEnabled(false)
        advanceUntilIdle()

        assertEquals(listOf(false), service.updateCalls)
    }

    @Test
    fun `서버가 다른 값을 저장했으면 다투지 않고 응답값을 정본으로 확정한다`() = runTest {
        // FR-003. 다른 기기가 나중에 바꿨을 수 있다. 보낸 값과 달라도 다시 보내지 않는다 —
        // 그러면 두 기기가 값을 두고 끝없이 다투게 된다.
        service.onUpdate = { preferenceOk(enabled = true) }
        val viewModel = loaded(initial = true)

        viewModel.setPlaceChangeSuggestionEnabled(false)
        advanceUntilIdle()

        assertEquals(listOf(false), service.updateCalls)
        assertEquals(PreferencePhase.Content(value = true, isSaving = false), viewModel.state.value.preference)
    }

    // --- 빠른 연속 선택 ---

    @Test
    fun `저장 중 다시 고르면 요청을 새로 만들지 않고 희망값만 갱신한다`() = runTest {
        // 한 번에 요청 하나만 보낸다(#399 완료 조건).
        val gate = CompletableDeferred<Unit>()
        service.onUpdate = { gate.await(); preferenceOk(it) }
        val viewModel = loaded(initial = true)

        viewModel.setPlaceChangeSuggestionEnabled(false)
        runCurrent()
        viewModel.setPlaceChangeSuggestionEnabled(true)
        viewModel.setPlaceChangeSuggestionEnabled(false)
        runCurrent()

        assertEquals(listOf(false), service.updateCalls)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `빠르게 연속으로 골라도 마지막 희망값이 반영된다`() = runTest {
        // SC-004. 이전 응답이 최신 선택을 덮어쓰면 안 된다.
        val gate = CompletableDeferred<Unit>()
        service.onUpdate = { enabled -> if (service.updateCalls.size == 1) gate.await(); preferenceOk(enabled) }
        val viewModel = loaded(initial = true)

        viewModel.setPlaceChangeSuggestionEnabled(false)
        runCurrent()
        viewModel.setPlaceChangeSuggestionEnabled(true)
        runCurrent()

        gate.complete(Unit)
        advanceUntilIdle()

        // 첫 요청은 false, 이어진 요청은 마지막 희망값 true다.
        assertEquals(listOf(false, true), service.updateCalls)
        assertEquals(PreferencePhase.Content(value = true, isSaving = false), viewModel.state.value.preference)
    }

    @Test
    fun `저장 중 다시 골라 원래 값으로 돌아오면 추가 요청을 보내지 않는다`() = runTest {
        val gate = CompletableDeferred<Unit>()
        service.onUpdate = { gate.await(); preferenceOk(it) }
        val viewModel = loaded(initial = true)

        viewModel.setPlaceChangeSuggestionEnabled(false)
        runCurrent()
        viewModel.setPlaceChangeSuggestionEnabled(false)
        runCurrent()

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(false), service.updateCalls)
        assertEquals(PreferencePhase.Content(value = false, isSaving = false), viewModel.state.value.preference)
    }

    // --- 실패와 재시도 ---

    @Test
    fun `저장에 실패하면 마지막으로 저장에 성공한 값으로 되돌린다`() = runTest {
        // FR-006. 저장되지 않은 값을 성공처럼 남기지 않는다.
        service.onUpdate = { preferenceFailure(SettingsErrorCodes.INVALID_REQUEST, httpStatus = 500) }
        val viewModel = loaded(initial = true)

        viewModel.setPlaceChangeSuggestionEnabled(false)
        advanceUntilIdle()

        val phase = viewModel.state.value.preference as PreferencePhase.Error
        assertEquals(true, phase.lastConfirmedValue)
        assertEquals(SettingsError.Unexpected, phase.error)
    }

    @Test
    fun `저장 실패 뒤 재시도는 마지막 희망값을 보낸다`() = runTest {
        service.onUpdate = { preferenceFailure(SettingsErrorCodes.INVALID_REQUEST, httpStatus = 500) }
        val viewModel = loaded(initial = true)
        viewModel.setPlaceChangeSuggestionEnabled(false)
        advanceUntilIdle()

        service.onUpdate = { preferenceOk(it) }
        viewModel.retrySave()
        advanceUntilIdle()

        assertEquals(listOf(false, false), service.updateCalls)
        assertEquals(PreferencePhase.Content(value = false, isSaving = false), viewModel.state.value.preference)
    }

    @Test
    fun `저장 중에는 조회하지 않는다`() = runTest {
        // 조회 응답이 방금 보낸 변경을 덮어쓰면 고른 값이 되돌아간 것처럼 보인다.
        val gate = CompletableDeferred<Unit>()
        service.onUpdate = { gate.await(); preferenceOk(it) }
        val viewModel = loaded(initial = true)
        val before = service.getCalls

        viewModel.setPlaceChangeSuggestionEnabled(false)
        runCurrent()
        viewModel.load()
        runCurrent()

        assertEquals(before, service.getCalls)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    /** 첫 조회까지 끝난 ViewModel. `init`이 부르는 조회가 끝난 뒤를 돌려준다. */
    private suspend fun loaded(initial: Boolean): SettingsViewModel {
        service.onGet = { preferenceOk(initial) }
        val viewModel = newViewModel()
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    private suspend fun newViewModel() =
        SettingsViewModel(repository = SettingsRepository(api = service, auth = auth()))

    /** 로그인된 session을 가진 인증 계층. 다른 feature의 ViewModel test와 같다. */
    private suspend fun auth(): AuthRepository {
        val store = AuthSessionStore(
            AuthSessionStore.createDataStore(
                File(tempFolder.newFolder(), AuthSessionStore.FILE_NAME),
                scope = CoroutineScope(dispatcher + SupervisorJob()),
            ),
            FakeSessionCipher(),
        )
        val auth = AuthRepository(
            store = store,
            api = FakeAuthService,
            appLinkHandler = AuthAppLinkHandler("app.gilpick.example"),
            scope = CoroutineScope(dispatcher + SupervisorJob()),
        )
        auth.onSignedIn(
            sessionId = "session-1",
            userId = "user-1",
            nickname = null,
            profileImageUrl = null,
            accessToken = "access-token-1",
            refreshToken = "session-1.refresh-token",
            accessExpiresAtEpochSeconds = 3_600,
            refreshExpiresAtEpochSeconds = 2_592_000,
        )
        return auth
    }
}
