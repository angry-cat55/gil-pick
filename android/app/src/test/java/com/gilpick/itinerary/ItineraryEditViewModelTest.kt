package com.gilpick.itinerary

import androidx.lifecycle.SavedStateHandle
import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeAuthService
import com.gilpick.auth.FakeSessionCipher
import com.gilpick.place.AddToScheduleRequest
import com.gilpick.place.PlaceDto
import com.gilpick.place.PlaceTransport
import com.gilpick.place.place
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T012·T016·T020: 일정 편집 ViewModel 상태 전이 검증.
 *
 * `spec.md` US1 Acceptance 2~6, US2 Acceptance 1~4, FR-002~009·016·017·019·021과 `research.md` 4절
 * (409 자동 재저장)이 대상이다. HTTP 왕복은 `ItineraryRepositoryTest`가 보므로 여기서는
 * [FakeItineraryService]로 응답만 정한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ItineraryEditViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val service = FakeItineraryService()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- 조회와 날짜 탭 ---

    @Test
    fun `조회하면 날짜 탭과 진입 날짜의 저장본이 초안이 된다`() = runTest {
        service.onOverview = {
            ok(overview(day("2026-09-08", 1), day("2026-09-09", 2, version = 3, items = listOf(savedItem("a", 1)))))
        }

        val viewModel = newViewModel(date = LocalDate.of(2026, 9, 9))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(ItineraryEditPhase.Content, state.phase)
        assertEquals(listOf(1, 2), state.days.map { it.dayNumber })
        assertEquals(LocalDate.of(2026, 9, 9), state.selectedDate)
        assertEquals(3, state.savedVersion)
        assertEquals(listOf("a"), state.draft.map { it.itemId })
        assertFalse(state.dirty)
    }

    @Test
    fun `진입 날짜가 여행 기간 밖이면 첫 날짜를 고른다`() = runTest {
        val viewModel = newViewModel(date = LocalDate.of(2026, 12, 25))
        advanceUntilIdle()

        assertEquals(LocalDate.of(2026, 9, 8), viewModel.state.value.selectedDate)
    }

    @Test
    fun `조회 실패는 원인을 담은 Failed가 되고 다시 시도로 복구된다`() = runTest {
        var calls = 0
        service.onOverview = {
            if (calls++ == 0) itineraryError(403, ItineraryErrorCodes.TRIP_FORBIDDEN) else ok(overview())
        }
        val viewModel = newViewModel()
        advanceUntilIdle()
        assertEquals(ItineraryEditPhase.Failed(ItineraryError.Forbidden), viewModel.state.value.phase)

        viewModel.retry()
        advanceUntilIdle()

        assertEquals(ItineraryEditPhase.Content, viewModel.state.value.phase)
    }

    @Test
    fun `변경이 없으면 날짜 탭을 바로 옮기고 변경이 있으면 버릴지 확인한다`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.selectDate(LocalDate.of(2026, 9, 9))
        assertEquals(LocalDate.of(2026, 9, 9), viewModel.state.value.selectedDate)

        viewModel.addFromSearch(placeWithLocation("tourapi:1"), request())
        viewModel.selectDate(LocalDate.of(2026, 9, 10))
        assertEquals(EditDialog.Discard(LocalDate.of(2026, 9, 10)), viewModel.state.value.dialog)
        assertEquals(LocalDate.of(2026, 9, 9), viewModel.state.value.selectedDate)

        viewModel.confirmDiscard()
        val state = viewModel.state.value
        assertEquals(LocalDate.of(2026, 9, 10), state.selectedDate)
        assertTrue(state.draft.isEmpty())
        assertFalse(state.dirty)
        assertNull(state.dialog)
    }

    // --- 검색 결과 추가: Scenario 2·3, FR-002·003·006·021 ---

    @Test
    fun `추가한 장소는 끝에 붙고 시트의 이동 수단은 직전 항목의 다음 구간이 된다`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.addFromSearch(placeWithLocation("tourapi:1"), request(transport = PlaceTransport.WALK))
        viewModel.addFromSearch(placeWithLocation("tourapi:2"), request(transport = PlaceTransport.CAR))

        val draft = viewModel.state.value.draft
        assertEquals(listOf("tourapi:1", "tourapi:2"), draft.map { it.placeId })
        // 첫 항목으로 추가된 장소의 이동 수단(WALK)은 버려지고, 두 번째가 고른 CAR가 첫 항목의 다음 구간이 된다.
        assertEquals(TransportMode.CAR, draft[0].transportToNext)
        assertNull(draft[1].transportToNext)
        assertEquals(ItemStatus.PLANNED, draft[1].status)
        assertNull(draft[1].itemId)
        assertTrue(viewModel.state.value.dirty)
    }

    @Test
    fun `좌표 없는 장소는 추가하지 않고 이유를 안내한다`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.addFromSearch(place("tourapi:1"), request())

        assertTrue(viewModel.state.value.draft.isEmpty())
        assertEquals(EditNotice.NO_COORDINATES, viewModel.state.value.notice)
        assertFalse(viewModel.state.value.dirty)
    }

    @Test
    fun `체류 시간이 추천값과 같으면 RECOMMENDED 다르면 USER_ADJUSTED다`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.addFromSearch(placeWithLocation("tourapi:1"), request(stayMinutes = 90))
        viewModel.addFromSearch(placeWithLocation("tourapi:2"), request(stayMinutes = 120))

        val draft = viewModel.state.value.draft
        assertEquals(90, draft[0].stayMinutes)
        assertEquals(StaySource.RECOMMENDED, draft[0].staySource)
        assertEquals(120, draft[1].stayMinutes)
        assertEquals(StaySource.USER_ADJUSTED, draft[1].staySource)
    }

    @Test
    fun `10곳이면 추가가 비활성화되고 그 뒤의 추가는 상한을 안내한다`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()

        repeat(10) { viewModel.addFromSearch(placeWithLocation("tourapi:$it"), request()) }
        assertTrue(viewModel.state.value.addDisabled)

        viewModel.addFromSearch(placeWithLocation("tourapi:extra"), request())

        assertEquals(10, viewModel.state.value.draft.size)
        assertEquals(EditNotice.LIMIT_REACHED, viewModel.state.value.notice)
    }

    // --- 저장: Scenario 4, FR-005·008·009·010 ---

    @Test
    fun `저장은 순서를 1부터 매기고 새 항목에만 장소 스냅샷을 실으며 성공하면 저장본이 된다`() = runTest {
        service.onOverview = {
            ok(overview(day("2026-09-08", 1, version = 2, items = listOf(savedItem("a", 1, transportToNext = TransportMode.WALK), savedItem("b", 2)))))
        }
        service.onSave = { call -> ok(savedFrom(call, version = 3)) }
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.addFromSearch(placeWithLocation("tourapi:3", name = "창덕궁"), request(transport = PlaceTransport.TRANSIT))

        viewModel.save()
        assertTrue(viewModel.state.value.saving)
        advanceUntilIdle()

        val call = service.saveCalls.single()
        assertEquals(2, call.body.version)
        assertEquals(listOf(1, 2, 3), call.body.items.map { it.sequence })
        assertEquals(listOf("a", "b", null), call.body.items.map { it.itemId })
        assertEquals(listOf(null, null, "창덕궁"), call.body.items.map { it.place?.name })
        assertEquals(listOf(TransportMode.WALK, TransportMode.TRANSIT, null), call.body.items.map { it.transportModeToNext })

        val state = viewModel.state.value
        assertFalse(state.saving)
        assertTrue(state.saved)
        assertEquals(3, state.savedVersion)
        assertFalse(state.dirty)
        assertEquals("new-3", state.draft[2].itemId)
    }

    @Test
    fun `통신 실패 뒤 다시 저장하면 같은 Idempotency-Key를 쓰고 초안이 바뀌면 새 키를 쓴다`() = runTest {
        var fail = true
        service.onSave = { call ->
            if (fail) throw java.io.IOException("끊김") else ok(savedFrom(call, version = 1), status = 201)
        }
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.addFromSearch(placeWithLocation("tourapi:1"), request())

        viewModel.save()
        advanceUntilIdle()
        assertEquals(ItineraryError.Network, viewModel.state.value.saveError)
        assertEquals(1, viewModel.state.value.draft.size)

        fail = false
        viewModel.save()
        advanceUntilIdle()
        assertEquals(service.saveCalls[0].idempotencyKey, service.saveCalls[1].idempotencyKey)
        assertTrue(viewModel.state.value.saved)

        viewModel.consumeSaved()
        viewModel.addFromSearch(placeWithLocation("tourapi:2"), request())
        viewModel.save()
        advanceUntilIdle()
        assertNotEquals(service.saveCalls[1].idempotencyKey, service.saveCalls[2].idempotencyKey)
    }

    @Test
    fun `409면 최신 version을 받아 같은 초안을 안내 없이 다시 저장한다`() = runTest {
        service.onOverview = { ok(overview(day("2026-09-08", 1, version = 1))) }
        service.onGetDay = { date -> ok(day(date, 1, version = 5, items = listOf(savedItem("other", 1)))) }
        service.onSave = { call ->
            if (call.body.version == 5) ok(savedFrom(call, version = 6))
            else itineraryError(409, ItineraryErrorCodes.VERSION_CONFLICT)
        }
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.addFromSearch(placeWithLocation("tourapi:1"), request())

        viewModel.save()
        advanceUntilIdle()

        assertEquals(listOf(1, 5), service.saveCalls.map { it.body.version })
        assertEquals(service.saveCalls[0].body.items, service.saveCalls[1].body.items)
        assertNotEquals(service.saveCalls[0].idempotencyKey, service.saveCalls[1].idempotencyKey)
        assertEquals(listOf("2026-09-08"), service.dayCalls)
        val state = viewModel.state.value
        assertTrue(state.saved)
        assertNull(state.saveError)
        assertEquals(6, state.savedVersion)
    }

    @Test
    fun `409가 세 번 이어지면 재저장을 멈추고 초안을 유지한 채 실패를 안내한다`() = runTest {
        service.onGetDay = { date -> ok(day(date, 1, version = 9)) }
        service.onSave = { itineraryError(409, ItineraryErrorCodes.VERSION_CONFLICT) }
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.addFromSearch(placeWithLocation("tourapi:1"), request())

        viewModel.save()
        advanceUntilIdle()

        assertEquals(3, service.saveCalls.size)
        val state = viewModel.state.value
        assertEquals(ItineraryError.VersionConflict, state.saveError)
        assertFalse(state.saving)
        assertFalse(state.saved)
        assertEquals(listOf("tourapi:1"), state.draft.map { it.placeId })
        assertTrue(state.dirty)
    }

    @Test
    fun `저장 규칙 위반은 초안을 유지한 채 실패를 안내한다`() = runTest {
        service.onSave = {
            itineraryError(422, ItineraryErrorCodes.INVALID_ITINERARY, details = """{"violations":[{"field":"plannedStayMinutes","itemIndex":0,"reason":"multiple of 30"}]}""")
        }
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.addFromSearch(placeWithLocation("tourapi:1"), request(stayMinutes = 45))

        viewModel.save()
        advanceUntilIdle()

        val error = viewModel.state.value.saveError as ItineraryError.InvalidItinerary
        assertEquals("plannedStayMinutes", error.violations.single().field)
        assertEquals(1, viewModel.state.value.draft.size)
    }

    // --- 닫기 확인: Scenario 5, FR-016 ---

    @Test
    fun `변경이 없으면 바로 닫고 변경이 있으면 확인 뒤에만 닫는다`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.requestClose()
        assertTrue(viewModel.state.value.exit)
        viewModel.consumeExit()

        viewModel.addFromSearch(placeWithLocation("tourapi:1"), request())
        viewModel.requestClose()
        assertFalse(viewModel.state.value.exit)
        assertEquals(EditDialog.Discard(null), viewModel.state.value.dialog)

        viewModel.dismissDialog()
        assertNull(viewModel.state.value.dialog)
        assertEquals(1, viewModel.state.value.draft.size)

        viewModel.requestClose()
        viewModel.confirmDiscard()
        assertTrue(viewModel.state.value.exit)
    }

    // --- SavedStateHandle 복원 ---

    @Test
    fun `초안과 선택 날짜는 SavedStateHandle로 복원된다`() = runTest {
        val handle = SavedStateHandle()
        val first = newViewModel(handle = handle)
        advanceUntilIdle()
        first.selectDate(LocalDate.of(2026, 9, 9))
        first.addFromSearch(placeWithLocation("tourapi:1", name = "경복궁"), request())

        // 프로세스 종료 뒤 같은 handle로 다시 만들어진 ViewModel.
        val restored = newViewModel(handle = handle)
        advanceUntilIdle()

        val state = restored.state.value
        assertEquals(LocalDate.of(2026, 9, 9), state.selectedDate)
        assertEquals(listOf("경복궁"), state.draft.map { it.place.name })
        assertTrue(state.dirty)
    }

    @Test
    fun `openSearch는 한 번만 참이고 handle을 공유하는 재생성 뒤에는 거짓이다`() = runTest {
        val handle = SavedStateHandle()
        val first = newViewModel(handle = handle, openSearch = true)

        assertTrue(first.takeOpenSearch())
        assertFalse(first.takeOpenSearch())
        assertFalse(newViewModel(handle = handle, openSearch = true).takeOpenSearch())
        assertFalse(newViewModel(openSearch = false).takeOpenSearch())
    }

    // --- US2 편집 조작(T020): FR-003·004·005·006·007·017 ---

    @Test
    fun `위아래 이동은 항목만 옮기고 구간의 이동 수단은 제자리에 둔다`() = runTest {
        val viewModel = loadedWith(savedItem("a", 1, transportToNext = TransportMode.WALK), savedItem("b", 2, transportToNext = TransportMode.CAR), savedItem("c", 3))

        viewModel.moveItem(0, 1)
        assertEquals(listOf("b", "a", "c"), viewModel.state.value.draft.map { it.itemId })
        assertEquals(listOf(TransportMode.WALK, TransportMode.CAR, null), viewModel.state.value.draft.map { it.transportToNext })
        assertTrue(viewModel.state.value.dirty)

        // 마지막으로 옮겨도 마지막 항목의 이동 수단은 계속 null이다(Scenario 4).
        viewModel.moveItem(1, 2)
        assertEquals(listOf("b", "c", "a"), viewModel.state.value.draft.map { it.itemId })
        assertEquals(listOf(1, 2, 3), viewModel.state.value.draft.toSaveItems().map { it.sequence })
        assertNull(viewModel.state.value.draft.last().transportToNext)

        // 끌기로 여러 칸을 한 번에 옮긴 결과는 버튼을 두 번 누른 결과와 같다.
        viewModel.moveItem(2, 0)
        assertEquals(listOf("a", "b", "c"), viewModel.state.value.draft.map { it.itemId })
        assertEquals(listOf(TransportMode.WALK, TransportMode.CAR, null), viewModel.state.value.draft.map { it.transportToNext })
        assertFalse(viewModel.state.value.dirty)

        // 범위 밖과 제자리는 무시한다.
        viewModel.moveItem(0, -1)
        viewModel.moveItem(2, 3)
        viewModel.moveItem(1, 1)
        assertEquals(listOf("a", "b", "c"), viewModel.state.value.draft.map { it.itemId })
    }

    @Test
    fun `삭제하면 순서가 다시 매겨지고 새 마지막 항목의 이동 수단은 null이 된다`() = runTest {
        val viewModel = loadedWith(savedItem("a", 1, transportToNext = TransportMode.WALK), savedItem("b", 2, transportToNext = TransportMode.CAR), savedItem("c", 3))

        viewModel.removeItem(2)
        val items = viewModel.state.value.draft.toSaveItems()
        assertEquals(listOf("a", "b"), items.map { it.itemId })
        assertEquals(listOf(1, 2), items.map { it.sequence })
        assertEquals(listOf(TransportMode.WALK, null), items.map { it.transportModeToNext })
        assertNull(viewModel.state.value.draft.last().transportToNext)

        viewModel.removeItem(0)
        assertEquals(listOf("b"), viewModel.state.value.draft.map { it.itemId })
        assertNull(viewModel.state.value.draft.single().transportToNext)

        viewModel.removeItem(5)
        assertEquals(1, viewModel.state.value.draft.size)
    }

    @Test
    fun `체류 시간은 30분 단위 30~360분만 적용되고 바뀌면 USER_ADJUSTED가 된다`() = runTest {
        val viewModel = loadedWith(savedItem("a", 1, stayMinutes = 90))

        viewModel.editStay(0)
        assertEquals(EditDialog.StayTime(0), viewModel.state.value.dialog)

        // 단위·범위 밖은 무시하고 대화상자를 유지한다.
        viewModel.applyStay(45)
        viewModel.applyStay(0)
        viewModel.applyStay(390)
        assertEquals(90, viewModel.state.value.draft[0].stayMinutes)
        assertEquals(StaySource.RECOMMENDED, viewModel.state.value.draft[0].staySource)
        assertEquals(EditDialog.StayTime(0), viewModel.state.value.dialog)

        // 같은 값은 출처를 바꾸지 않고 닫는다.
        viewModel.applyStay(90)
        assertEquals(StaySource.RECOMMENDED, viewModel.state.value.draft[0].staySource)
        assertNull(viewModel.state.value.dialog)
        assertFalse(viewModel.state.value.dirty)

        // 경계값 30·360은 허용하고 사용자 조절값으로 표시한다.
        viewModel.editStay(0)
        viewModel.applyStay(360)
        assertEquals(360, viewModel.state.value.draft[0].stayMinutes)
        assertEquals(StaySource.USER_ADJUSTED, viewModel.state.value.draft[0].staySource)
        viewModel.editStay(0)
        viewModel.applyStay(30)
        assertEquals(30, viewModel.state.value.draft[0].stayMinutes)
        assertTrue(viewModel.state.value.dirty)

        // 대화상자가 없으면 적용은 무시된다.
        viewModel.applyStay(120)
        assertEquals(30, viewModel.state.value.draft[0].stayMinutes)
    }

    @Test
    fun `이동 수단 시트는 마지막이 아닌 예정 항목만 열고 적용하면 그 구간만 바뀐다`() = runTest {
        val viewModel = loadedWith(savedItem("a", 1, transportToNext = TransportMode.WALK), savedItem("b", 2))

        viewModel.changeTransport(1)
        assertNull(viewModel.state.value.dialog)

        viewModel.changeTransport(0)
        assertEquals(EditDialog.Transport(0), viewModel.state.value.dialog)
        viewModel.applyTransport(TransportMode.TRANSIT)
        assertEquals(TransportMode.TRANSIT, viewModel.state.value.draft[0].transportToNext)
        assertNull(viewModel.state.value.draft[1].transportToNext)
        assertNull(viewModel.state.value.dialog)
        assertTrue(viewModel.state.value.dirty)

        viewModel.dismissDialog()
        viewModel.applyTransport(TransportMode.CAR)
        assertEquals(TransportMode.TRANSIT, viewModel.state.value.draft[0].transportToNext)
    }

    @Test
    fun `처리된 항목은 순서 이동과 삭제와 이동 수단 변경을 거부하고 체류 시간만 바꾼다`() = runTest {
        val viewModel = loadedWith(
            savedItem("done", 1, transportToNext = TransportMode.WALK, status = ItemStatus.COMPLETED),
            savedItem("skip", 2, transportToNext = TransportMode.CAR, status = ItemStatus.SKIPPED),
            savedItem("a", 3, transportToNext = TransportMode.TRANSIT),
            savedItem("b", 4),
        )
        val before = viewModel.state.value.draft

        viewModel.moveItem(1, 2)
        viewModel.moveItem(2, 1)
        // 예정 항목끼리라도 처리된 항목을 지나가면 그 순서가 바뀌므로 거부한다.
        viewModel.moveItem(3, 0)
        viewModel.removeItem(0)
        viewModel.changeTransport(0)
        assertNull(viewModel.state.value.dialog)
        assertEquals(before, viewModel.state.value.draft)
        assertFalse(viewModel.state.value.dirty)

        viewModel.moveItem(2, 3)
        assertEquals(listOf("done", "skip", "b", "a"), viewModel.state.value.draft.map { it.itemId })

        viewModel.editStay(0)
        viewModel.applyStay(120)
        assertEquals(120, viewModel.state.value.draft[0].stayMinutes)
        assertEquals(StaySource.USER_ADJUSTED, viewModel.state.value.draft[0].staySource)
        assertEquals(ItemStatus.COMPLETED, viewModel.state.value.draft[0].status)
    }

    /** 첫 날짜에 [items]가 저장된 상태로 조회를 마친 ViewModel. */
    private suspend fun TestScope.loadedWith(vararg items: ItineraryItemDto): ItineraryEditViewModel {
        service.onOverview = { ok(overview(day("2026-09-08", 1, version = 1, items = items.toList()))) }
        return newViewModel().also { advanceUntilIdle() }
    }

    private suspend fun newViewModel(
        handle: SavedStateHandle = SavedStateHandle(),
        date: LocalDate = LocalDate.of(2026, 9, 8),
        openSearch: Boolean = false,
    ): ItineraryEditViewModel = ItineraryEditViewModel(
        repository = repository(),
        savedState = handle,
        tripId = TRIP_ID,
        initialDate = date,
        openSearch = openSearch,
    )

    /** 로그인된 session을 가진 repository를 만든다. `PlaceSearchViewModelTest`와 같다. */
    private suspend fun repository(): ItineraryRepository {
        val store = AuthSessionStore(
            AuthSessionStore.createDataStore(
                // ViewModel을 두 번 만드는 복원 test가 같은 DataStore 파일을 두 번 열지 않도록 폴더를 나눈다.
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
            accessToken = "access-token",
            refreshToken = "session-1.refresh-token",
            accessExpiresAtEpochSeconds = 3_600,
            refreshExpiresAtEpochSeconds = 2_592_000,
        )
        return ItineraryRepository(api = service, auth = auth)
    }

    private fun placeWithLocation(id: String, name: String = "장소 $id"): PlaceDto =
        place(id, name = name).copy(latitude = 37.5796, longitude = 126.977)

    private fun request(transport: PlaceTransport = PlaceTransport.TRANSIT, stayMinutes: Int = 90) =
        AddToScheduleRequest(transport, stayMinutes)
}
