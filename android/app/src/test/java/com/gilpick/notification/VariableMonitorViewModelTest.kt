package com.gilpick.notification

import com.gilpick.alternative.ALT_REQUEST_ID
import com.gilpick.alternative.AlternativeError
import com.gilpick.alternative.AlternativeErrorCodes
import com.gilpick.alternative.AlternativeRepository
import com.gilpick.alternative.DetectionStatus
import com.gilpick.alternative.FakeAlternativeService
import com.gilpick.alternative.TRIP_ID
import com.gilpick.alternative.detectionDetailJson
import com.gilpick.alternative.fail
import com.gilpick.alternative.list
import com.gilpick.alternative.ok
import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeAuthService
import com.gilpick.auth.FakeSessionCipher
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T039: 감지 목록 ViewModel 상태 전이 검증(quickstart AND 5, UI-008).
 *
 * F009 [FakeAlternativeService]로 DETECT-001·DETECT-002 응답만 정한다. 정렬은 로컬 상태이고, 상세 조회 실패는
 * 목록을 막지 않고 그 카드의 판정만 비운다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VariableMonitorViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val service = FakeAlternativeService()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        service.onListDetections = { list(monitorDetectionsJson()) }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `ACTIVE 감지를 한 페이지로 받고 항목마다 상세 판정을 붙인다`() = runTest {
        var detailCalls = 0
        service.onGetDetection = { detailCalls++; ok(detectionDetailJson()) }

        val viewModel = newViewModel()
        assertEquals(VariableMonitorUiState.Loading, viewModel.state.value)
        advanceUntilIdle()

        val content = viewModel.state.value as VariableMonitorUiState.Content
        assertEquals(listOf(DetectionStatus.ACTIVE to 50), service.listCalls)
        assertEquals(3, detailCalls)
        assertEquals(3, content.items.size)
        assertTrue(content.items.all { it.variables != null })
        assertFalse(content.items.first().variables!!.operatingHours.available)
    }

    @Test
    fun `시간순은 방문 예정 시각이 이른 순이고 위험순은 점수가 높은 순이다`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()

        val byTime = viewModel.state.value as VariableMonitorUiState.Content
        assertEquals(DetectionSort.TIME, byTime.sort)
        assertEquals(listOf("창덕궁 후원", "경복궁", "남산서울타워"), byTime.sorted.map { it.item.placeName })

        viewModel.toggleSort()

        val byRisk = viewModel.state.value as VariableMonitorUiState.Content
        assertEquals(DetectionSort.RISK, byRisk.sort)
        assertEquals(listOf("경복궁", "남산서울타워", "창덕궁 후원"), byRisk.sorted.map { it.item.placeName })

        viewModel.toggleSort()
        assertEquals(DetectionSort.TIME, (viewModel.state.value as VariableMonitorUiState.Content).sort)
    }

    @Test
    fun `ACTIVE 감지가 없으면 empty다`() = runTest {
        service.onListDetections = { list(emptyDetectionsJson()) }

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(VariableMonitorUiState.Empty, viewModel.state.value)
    }

    @Test
    fun `목록 조회 실패는 F009 오류로 옮기고 재시도 가능 여부를 담는다`() = runTest {
        service.onListDetections = { throw IOException("offline") }

        val viewModel = newViewModel()
        advanceUntilIdle()
        assertEquals(VariableMonitorUiState.Error(AlternativeError.Network, retryable = true), viewModel.state.value)

        service.onListDetections = { fail(403, AlternativeErrorCodes.TRIP_FORBIDDEN) }
        viewModel.load()
        advanceUntilIdle()
        assertEquals(VariableMonitorUiState.Error(AlternativeError.Forbidden, retryable = false), viewModel.state.value)
    }

    @Test
    fun `상세 조회가 실패한 항목은 판정 없이 목록에 남는다`() = runTest {
        service.onGetDetection = { fail(404, AlternativeErrorCodes.DETECTION_NOT_FOUND) }

        val viewModel = newViewModel()
        advanceUntilIdle()

        val content = viewModel.state.value as VariableMonitorUiState.Content
        assertEquals(3, content.items.size)
        assertTrue(content.items.all { it.variables == null })
    }

    @Test
    fun `재조회는 정렬을 유지하고 실패해도 보던 목록을 지우지 않는다`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.toggleSort()
        val before = viewModel.state.value as VariableMonitorUiState.Content

        service.onListDetections = { throw IOException("offline") }
        viewModel.load()
        assertTrue((viewModel.state.value as VariableMonitorUiState.Content).refreshing)
        advanceUntilIdle()
        assertEquals(before, viewModel.state.value)

        service.onListDetections = { list(monitorDetectionsJson()) }
        viewModel.load()
        advanceUntilIdle()
        val after = viewModel.state.value as VariableMonitorUiState.Content
        assertEquals(DetectionSort.RISK, after.sort)
        assertFalse(after.refreshing)
        assertNull(after.items.firstOrNull { it.variables == null })
    }

    private suspend fun newViewModel(): VariableMonitorViewModel =
        VariableMonitorViewModel(tripId = TRIP_ID, repository = AlternativeRepository(api = service, auth = auth()))

    /** 로그인된 session을 가진 인증 계층. F009 `AlternativeViewModelTest`와 같다. */
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
            accessToken = "access-token",
            refreshToken = "session-1.refresh-token",
            accessExpiresAtEpochSeconds = 3_600,
            refreshExpiresAtEpochSeconds = 2_592_000,
        )
        return auth
    }
}

/** DETECT-001 `status=ACTIVE` 3건(Figma 예시). 시간순 = 창덕궁 후원(11:30)·경복궁(14:00)·남산서울타워(18:30), 위험순 = 78·70·40. */
internal fun monitorDetectionsJson() = """
    {"success": true,
     "data": {"items": [
       {"detectionId": "aaaaaaaa-1111-4111-8111-111111111111",
        "itemId": "bbbbbbbb-1111-4111-8111-111111111111",
        "placeName": "경복궁",
        "primaryType": "CONGESTION",
        "status": "ACTIVE",
        "totalRiskScore": 78,
        "eta": "2026-09-10T14:00:00+09:00",
        "reason": "오늘 오후 방문이 어려울 수 있어요",
        "createdAt": "2026-09-10T14:52:00+09:00",
        "read": false},
       {"detectionId": "aaaaaaaa-2222-4222-8222-222222222222",
        "itemId": "bbbbbbbb-2222-4222-8222-222222222222",
        "placeName": "창덕궁 후원",
        "primaryType": "OPERATING_HOURS",
        "status": "ACTIVE",
        "totalRiskScore": 40,
        "eta": "2026-09-10T11:30:00+09:00",
        "reason": "오늘 오전 방문이 어려울 수 있어요",
        "createdAt": "2026-09-10T14:39:00+09:00",
        "read": false},
       {"detectionId": "aaaaaaaa-3333-4333-8333-333333333333",
        "itemId": "bbbbbbbb-3333-4333-8333-333333333333",
        "placeName": "남산서울타워",
        "primaryType": "WEATHER",
        "status": "ACTIVE",
        "totalRiskScore": 70,
        "eta": "2026-09-10T18:30:00+09:00",
        "reason": "오늘 저녁 방문이 어려울 수 있어요",
        "createdAt": "2026-09-10T14:26:00+09:00",
        "read": true}
     ]},
     "meta": {"requestId": "$ALT_REQUEST_ID", "pagination": {"nextCursor": null, "hasNext": false}}}
""".trimIndent()

/** DETECT-001 `status=ACTIVE` 0건. */
internal fun emptyDetectionsJson() = """
    {"success": true,
     "data": {"items": []},
     "meta": {"requestId": "$ALT_REQUEST_ID", "pagination": {"nextCursor": null, "hasNext": false}}}
""".trimIndent()
