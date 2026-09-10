package com.gilpick.notification

import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeSessionCipher
import com.gilpick.auth.ProgrammableAuthService
import com.gilpick.auth.SuccessEnvelope
import com.gilpick.auth.refreshOk
import java.io.File
import java.io.IOException
import java.time.Instant
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
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response

/**
 * T031: 알림 목록 ViewModel 상태 전이 검증.
 *
 * `spec.md` US4, FR-014·FR-018, UI-001·UI-004가 대상이다. HTTP 왕복은 `NotificationRepositoryTest`가 보므로
 * 여기서는 [FakeNotificationService]로 응답만 정한다. 1초 대기 표시 지연은 composable이 맡는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationListViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val service = FakeNotificationService()

    /** 갱신은 항상 성공한다. 갱신 뒤 replay도 401이면 세션 만료로 확정된다. */
    private val authApi = ProgrammableAuthService().apply { onRefresh = { refreshOk(access = "access-2", refresh = "session-2.refresh-token") } }

    /** 2026-09-10 15:00 KST. 고정값 항목(14:20·14:05)은 오늘, 하루 전 항목은 어제다. */
    private val now = Instant.parse("2026-09-10T06:00:00Z")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `조회 결과를 KST 날짜 구간으로 나누고 안 읽음을 분류한다`() = runTest {
        service.onList = { ok(listJson(suggestionItem(read = false), arrivalCheckItem(read = true), yesterdayItem())) }

        val viewModel = newViewModel()
        assertEquals(NotificationUiState.Loading, viewModel.state.value)
        advanceUntilIdle()

        val content = viewModel.state.value as NotificationUiState.Content
        assertEquals(listOf(DateBucket.TODAY, DateBucket.YESTERDAY), content.groups.map { it.bucket })
        assertEquals(listOf(true, false), content.groups[0].items.map { it.unread })
        assertEquals(listOf(NOTIFICATION_ID, NOTIFICATION_ID_2), content.groups[0].items.map { it.id })
        assertEquals(listOf("yesterday-1"), content.groups[1].items.map { it.id })
        assertFalse(content.refreshing)
    }

    @Test
    fun `받은 알림이 없으면 empty다`() = runTest {
        service.onList = { ok(listJson()) }

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(NotificationUiState.Empty, viewModel.state.value)
    }

    @Test
    fun `장소 변경 제안 탭은 읽음 처리 뒤 대체 장소 화면 목적지를 낸다`() = runTest {
        service.onList = { ok(listJson(suggestionItem(read = false))) }
        val viewModel = newViewModel()
        advanceUntilIdle()
        val item = (viewModel.state.value as NotificationUiState.Content).groups[0].items[0]

        viewModel.open(item)
        // 읽음 표시는 즉시 로컬에 반영된다.
        assertFalse((viewModel.state.value as NotificationUiState.Content).groups[0].items[0].unread)
        advanceUntilIdle()

        assertEquals(listOf(NOTIFICATION_ID), service.markReadCalls)
        assertEquals(NotificationTarget.Alternative(DETECTION_ID, TRIP_ID), viewModel.open.value)
        viewModel.consumeOpen()
        assertNull(viewModel.open.value)
    }

    @Test
    fun `진행 알림 탭은 여행명을 채운 진행 화면 목적지를 낸다`() = runTest {
        service.onList = { ok(listJson(arrivalCheckItem(read = true))) }
        val viewModel = newViewModel(tripName = { "서울 자유여행" })
        advanceUntilIdle()
        val item = (viewModel.state.value as NotificationUiState.Content).groups[0].items[0]

        viewModel.open(item)
        advanceUntilIdle()

        // 이미 읽음이라 NOTI-002는 보내지 않는다.
        assertEquals(emptyList<String>(), service.markReadCalls)
        assertEquals(NotificationTarget.Progress(TRIP_ID, "서울 자유여행"), viewModel.open.value)
    }

    @Test
    fun `읽음 처리가 실패해도 이동은 한다`() = runTest {
        service.onList = { ok(listJson(suggestionItem(read = false))) }
        service.onMarkRead = { throw IOException("offline") }
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.open((viewModel.state.value as NotificationUiState.Content).groups[0].items[0])
        advanceUntilIdle()

        assertEquals(NotificationTarget.Alternative(DETECTION_ID, TRIP_ID), viewModel.open.value)
    }

    @Test
    fun `모두 읽음은 NOTI-003 성공 뒤 로컬 목록을 모두 읽음으로 바꾼다`() = runTest {
        service.onList = { ok(listJson(suggestionItem(read = false), arrivalCheckItem(read = false), yesterdayItem())) }
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.markAllRead()
        advanceUntilIdle()

        assertEquals(1, service.markAllReadCalls)
        val content = viewModel.state.value as NotificationUiState.Content
        assertTrue(content.groups.flatMap { it.items }.none { it.unread })
    }

    @Test
    fun `모두 읽음이 실패하면 안 읽음 표시를 그대로 둔다`() = runTest {
        service.onList = { ok(listJson(suggestionItem(read = false))) }
        service.onMarkAllRead = { throw IOException("offline") }
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.markAllRead()
        advanceUntilIdle()

        assertTrue((viewModel.state.value as NotificationUiState.Content).groups[0].items[0].unread)
    }

    @Test
    fun `안 읽은 알림이 없으면 모두 읽음을 보내지 않는다`() = runTest {
        service.onList = { ok(listJson(arrivalCheckItem(read = true))) }
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.markAllRead()
        advanceUntilIdle()

        assertEquals(0, service.markAllReadCalls)
    }

    @Test
    fun `재조회 중에는 기존 목록을 유지하고 실패해도 지우지 않는다`() = runTest {
        service.onList = { ok(listJson(suggestionItem(read = false))) }
        val viewModel = newViewModel()
        advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        service.onList = { gate.await(); throw IOException("offline") }
        viewModel.load()
        runCurrent()
        val refreshing = viewModel.state.value as NotificationUiState.Content
        assertTrue(refreshing.refreshing)
        assertEquals(1, refreshing.groups.size)

        gate.complete(Unit)
        advanceUntilIdle()
        val kept = viewModel.state.value as NotificationUiState.Content
        assertFalse(kept.refreshing)
        assertEquals(1, kept.groups.size)
    }

    @Test
    fun `통신 실패는 다시 시도할 수 있는 error다`() = runTest {
        service.onList = { throw IOException("offline") }

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(NotificationUiState.Error(NotificationError.Network, retryable = true), viewModel.state.value)
    }

    @Test
    fun `갱신 뒤에도 401이면 세션 만료 error다`() = runTest {
        service.onList = { fail(401, NotificationErrorCodes.INVALID_ACCESS_TOKEN) }

        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(1, authApi.refreshCount)
        assertEquals(NotificationUiState.Error(NotificationError.SessionExpired, retryable = false), viewModel.state.value)
    }

    private fun yesterdayItem() = arrivalCheckItem(createdAt = "2026-09-09T21:00:00+09:00", read = true).copy(notificationId = "yesterday-1")

    private suspend fun newViewModel(tripName: suspend (String) -> String = { "" }): NotificationListViewModel =
        NotificationListViewModel(
            repository = NotificationRepository(api = service, auth = auth()),
            tripName = tripName,
            now = { now },
        )

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
            api = authApi,
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

/** 응답을 test가 정하는 [NotificationService]. 기기 토큰 endpoint는 이 화면이 쓰지 않는다. */
internal class FakeNotificationService : NotificationService {

    val markReadCalls = mutableListOf<String>()
    var markAllReadCalls = 0

    var onList: suspend () -> Response<NotificationListEnvelope> = { ok(listJson()) }
    var onMarkRead: suspend () -> Response<SuccessEnvelope<MarkReadResultDto>> = { ok(envelope("""{"notificationId": "$NOTIFICATION_ID", "read": true}""")) }
    var onMarkAllRead: suspend () -> Response<SuccessEnvelope<MarkAllReadResultDto>> = { ok(envelope("""{"updated": 1}""")) }

    override suspend fun listNotifications(bearer: String, cursor: String?, limit: Int?, read: Boolean?) = onList()

    override suspend fun markRead(bearer: String, notificationId: String): Response<SuccessEnvelope<MarkReadResultDto>> {
        markReadCalls += notificationId
        return onMarkRead()
    }

    override suspend fun markAllRead(bearer: String): Response<SuccessEnvelope<MarkAllReadResultDto>> {
        markAllReadCalls++
        return onMarkAllRead()
    }

    override suspend fun registerFcmToken(bearer: String, body: FcmTokenRegisterRequest): Response<SuccessEnvelope<FcmTokenRegisterResultDto>> =
        throw UnsupportedOperationException()

    override suspend fun unregisterFcmToken(bearer: String, deviceId: String): Response<Unit> = throw UnsupportedOperationException()
}

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** 항목들을 NOTI-001 응답 JSON으로 감싼다. 순서는 서버가 준 최신순 그대로다. */
internal fun listJson(vararg items: NotificationItemDto): String = """
    {"success": true,
     "data": {"items": ${json.encodeToString(kotlinx.serialization.builtins.ListSerializer(NotificationItemDto.serializer()), items.toList())}},
     "meta": {"requestId": "$REQUEST_ID", "pagination": {"nextCursor": null, "hasNext": false}}}
""".trimIndent()

internal inline fun <reified T> ok(body: String): Response<T> = Response.success(json.decodeFromString<T>(body))

internal fun <T> fail(httpStatus: Int, code: String): Response<T> =
    Response.error(httpStatus, errorJson(code).toResponseBody("application/json".toMediaType()))
