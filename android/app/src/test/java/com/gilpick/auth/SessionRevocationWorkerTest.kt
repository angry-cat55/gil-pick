package com.gilpick.auth

import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response

/**
 * T037: pending revocation queue의 재시도 정책과 WorkManager 입력 검증.
 *
 * WorkManager 실행 자체는 계측 test(T038)가 확인하고, 여기서는 Context 없이 검증할 수
 * 있는 결과 분류·항목 격리·입력 안전성만 본다.
 */
class SessionRevocationWorkerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val api = ProgrammableAuthService()
    private val cipher = FakeSessionCipher()
    private lateinit var store: AuthSessionStore

    @Before
    fun setUp() {
        store = AuthSessionStore(
            AuthSessionStore.createDataStore(File(tempFolder.root, AuthSessionStore.FILE_NAME)),
            cipher,
        )
    }

    // --- 성공과 동등한 결과 ---

    @Test
    fun `폐기에 성공하면 대기 항목을 제거한다`() = runTest {
        val operationId = store.enqueueRevocation(REFRESH_TOKEN, DEVICE_ID)
        api.onLogout = { Response.success(204, Unit) }

        assertEquals(RevocationOutcome.COMPLETED, revokePendingSession(store, api, operationId))
        assertNull(store.loadRevocation(operationId))
        assertEquals(REFRESH_TOKEN, api.lastLogoutRequest?.refreshToken)
        assertEquals(DEVICE_ID, api.lastLogoutRequest?.deviceId)
    }

    @Test
    fun `이미 무효한 Token은 폐기 성공과 동등하게 처리한다`() = runTest {
        val operationId = store.enqueueRevocation(REFRESH_TOKEN, DEVICE_ID)
        api.onLogout = { errorResponse(401, AuthErrorCodes.INVALID_REFRESH_TOKEN) }

        assertEquals(RevocationOutcome.COMPLETED, revokePendingSession(store, api, operationId))
        assertNull(store.loadRevocation(operationId))
    }

    @Test
    fun `이미 만료된 Token은 폐기 성공과 동등하게 처리한다`() = runTest {
        val operationId = store.enqueueRevocation(REFRESH_TOKEN, DEVICE_ID)
        api.onLogout = { errorResponse(401, AuthErrorCodes.TOKEN_EXPIRED) }

        assertEquals(RevocationOutcome.COMPLETED, revokePendingSession(store, api, operationId))
        assertNull(store.loadRevocation(operationId))
    }

    @Test
    fun `대기 항목이 없으면 아무 것도 하지 않는다`() = runTest {
        assertEquals(RevocationOutcome.COMPLETED, revokePendingSession(store, api, "없는-operation"))
        assertEquals(0, api.logoutCount)
    }

    @Test
    fun `복호화할 수 없는 항목은 제거하고 재시도하지 않는다`() = runTest {
        val operationId = store.enqueueRevocation(REFRESH_TOKEN, DEVICE_ID)
        cipher.invalidated = true

        assertEquals(RevocationOutcome.COMPLETED, revokePendingSession(store, api, operationId))
        assertEquals("서버 호출 없이 끝나야 한다", 0, api.logoutCount)
    }

    // --- 재시도 대상 ---

    @Test
    fun `통신 실패는 대기 항목을 보존한 채 재시도한다`() = runTest {
        val operationId = store.enqueueRevocation(REFRESH_TOKEN, DEVICE_ID)
        api.onLogout = { throw IOException("연결 실패") }

        assertEquals(RevocationOutcome.RETRY, revokePendingSession(store, api, operationId))
        assertNotNull(store.loadRevocation(operationId))
    }

    @Test
    fun `429는 대기 항목을 보존한 채 재시도한다`() = runTest {
        val operationId = store.enqueueRevocation(REFRESH_TOKEN, DEVICE_ID)
        api.onLogout = { errorResponse(429, "RATE_LIMITED", retryable = true) }

        assertEquals(RevocationOutcome.RETRY, revokePendingSession(store, api, operationId))
        assertNotNull(store.loadRevocation(operationId))
    }

    @Test
    fun `5xx는 대기 항목을 보존한 채 재시도한다`() = runTest {
        val operationId = store.enqueueRevocation(REFRESH_TOKEN, DEVICE_ID)
        api.onLogout = { errorResponse(500, "INTERNAL_ERROR", retryable = true) }

        assertEquals(RevocationOutcome.RETRY, revokePendingSession(store, api, operationId))
        assertNotNull(store.loadRevocation(operationId))
    }

    // --- terminal ---

    @Test
    fun `기기 불일치는 재시도하지 않고 항목을 제거한다`() = runTest {
        val operationId = store.enqueueRevocation(REFRESH_TOKEN, DEVICE_ID)
        api.onLogout = { errorResponse(403, AuthErrorCodes.DEVICE_MISMATCH) }

        assertEquals(RevocationOutcome.TERMINAL, revokePendingSession(store, api, operationId))
        assertNull(store.loadRevocation(operationId))
    }

    // --- operation ID별 격리 ---

    @Test
    fun `한 operation의 처리가 다른 operation의 대기 항목을 건드리지 않는다`() = runTest {
        val first = store.enqueueRevocation("session-a.$SECRET", DEVICE_ID)
        val second = store.enqueueRevocation("session-b.$SECRET", DEVICE_ID)
        api.onLogout = { Response.success(204, Unit) }

        assertEquals(RevocationOutcome.COMPLETED, revokePendingSession(store, api, first))

        assertNull(store.loadRevocation(first))
        assertEquals("session-b.$SECRET", store.loadRevocation(second)?.refreshToken)
        assertEquals(1, api.logoutCount)
    }

    @Test
    fun `요청마다 서로 다른 operation ID를 부여한다`() = runTest {
        val first = store.enqueueRevocation(REFRESH_TOKEN, DEVICE_ID)
        val second = store.enqueueRevocation(REFRESH_TOKEN, DEVICE_ID)

        assertFalse(first == second)
        assertFalse(
            SessionRevocationWorker.uniqueWorkName(first) ==
                SessionRevocationWorker.uniqueWorkName(second),
        )
    }

    // --- 입력 안전성 ---

    @Test
    fun `worker 입력에는 operation ID만 담고 Token은 담지 않는다`() {
        val input = SessionRevocationWorker.inputData(OPERATION_ID)

        assertEquals(setOf(SessionRevocationWorker.KEY_OPERATION_ID), input.keyValueMap.keys)
        assertEquals(OPERATION_ID, input.getString(SessionRevocationWorker.KEY_OPERATION_ID))
        assertTrue(
            "직렬화된 입력에 Token 원문이 남으면 안 된다",
            input.keyValueMap.values.none { it.toString().contains(SECRET) },
        )
    }

    private companion object {
        const val DEVICE_ID = "11111111-2222-4333-8444-555555555555"
        const val OPERATION_ID = "22222222-3333-4444-8555-666666666666"
        const val SECRET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"
        const val REFRESH_TOKEN = "session-1.$SECRET"
    }
}
