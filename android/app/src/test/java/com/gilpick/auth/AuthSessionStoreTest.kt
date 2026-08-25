package com.gilpick.auth

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** T009: `auth_session.pb` 저장소의 deviceId, 원자적 Token 저장, 평문 미저장, key 무효화 검증. */
class AuthSessionStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var file: File
    private lateinit var scope: CoroutineScope
    private val cipher = FakeSessionCipher()

    /** process 재시작을 모사할 수 있도록 store마다 취소 가능한 scope를 준다. */
    private fun newStore(): AuthSessionStore {
        file = File(tempFolder.root, AuthSessionStore.FILE_NAME)
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        return AuthSessionStore(AuthSessionStore.createDataStore(file, scope), cipher)
    }

    @Test
    fun `deviceId는 최초 생성 후 재사용된다`() = runTest {
        val store = newStore()

        val first = store.deviceId()
        val second = store.deviceId()

        assertEquals(first, second)
        assertTrue("deviceId는 UUID 형식이어야 한다", UUID_REGEX.matches(first))
    }

    @Test
    fun `앱 데이터 삭제 후에는 새 deviceId가 생성된다`() = runTest {
        val store = newStore()
        val first = store.deviceId()

        // 앱 데이터 삭제는 process 종료를 동반하므로 기존 store를 취소한 뒤 다시 연다.
        scope.cancel()
        file.delete()
        val afterWipe = newStore().deviceId()

        assertNotEquals(first, afterWipe)
    }

    @Test
    fun `Token pair가 함께 저장되고 그대로 복원된다`() = runTest {
        val store = newStore()

        store.saveSession(
            sessionId = "session-1",
            userId = "user-1",
            nickname = null,
            profileImageUrl = null,
            accessToken = ACCESS_TOKEN,
            refreshToken = REFRESH_TOKEN,
            accessExpiresAtEpochSeconds = 3_600,
            refreshExpiresAtEpochSeconds = 2_592_000,
        )
        val restored = store.loadSession()

        assertEquals(ACCESS_TOKEN, restored?.accessToken)
        assertEquals(REFRESH_TOKEN, restored?.refreshToken)
        assertEquals("session-1", restored?.sessionId)
    }

    @Test
    fun `profile이 없는 사용자도 저장하고 복원할 수 있다`() = runTest {
        val store = newStore()

        store.saveSession(
            sessionId = "session-1",
            userId = "user-1",
            nickname = null,
            profileImageUrl = null,
            accessToken = ACCESS_TOKEN,
            refreshToken = REFRESH_TOKEN,
            accessExpiresAtEpochSeconds = 3_600,
            refreshExpiresAtEpochSeconds = 2_592_000,
        )

        val restored = store.loadSession()
        assertNull(restored?.nickname)
        assertNull(restored?.profileImageUrl)
    }

    @Test
    fun `저장 파일에 Token 평문이 남지 않는다`() = runTest {
        val store = newStore()

        store.saveSession(
            sessionId = "session-1",
            userId = "user-1",
            nickname = "길픽",
            profileImageUrl = null,
            accessToken = ACCESS_TOKEN,
            refreshToken = REFRESH_TOKEN,
            accessExpiresAtEpochSeconds = 3_600,
            refreshExpiresAtEpochSeconds = 2_592_000,
        )

        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("Access Token 원문이 파일에 있으면 안 된다", raw.contains(ACCESS_TOKEN))
        assertFalse("Refresh Token 원문이 파일에 있으면 안 된다", raw.contains(REFRESH_TOKEN))
    }

    @Test
    fun `key 무효화 시 session을 제거하고 로그아웃 상태가 된다`() = runTest {
        val store = newStore()
        store.saveSession(
            sessionId = "session-1",
            userId = "user-1",
            nickname = null,
            profileImageUrl = null,
            accessToken = ACCESS_TOKEN,
            refreshToken = REFRESH_TOKEN,
            accessExpiresAtEpochSeconds = 3_600,
            refreshExpiresAtEpochSeconds = 2_592_000,
        )
        val deviceId = store.deviceId()

        cipher.invalidated = true
        assertNull("복호화할 수 없는 session은 반환하지 않는다", store.loadSession())

        cipher.invalidated = false
        assertNull("읽을 수 없는 session은 제거되어야 한다", store.loadSession())
        assertEquals("deviceId는 보존한다", deviceId, store.deviceId())
    }

    @Test
    fun `logout 요청마다 서로 다른 operation ID로 격리된다`() = runTest {
        val store = newStore()
        val deviceId = store.deviceId()

        val firstId = store.enqueueRevocation("refresh-a", deviceId)
        val secondId = store.enqueueRevocation("refresh-b", deviceId)

        assertNotEquals(firstId, secondId)
        assertEquals("refresh-a", store.loadRevocation(firstId)?.refreshToken)
        assertEquals("refresh-b", store.loadRevocation(secondId)?.refreshToken)

        store.removeRevocation(firstId)
        assertNull(store.loadRevocation(firstId))
        assertEquals("다른 요청은 남아 있어야 한다", "refresh-b", store.loadRevocation(secondId)?.refreshToken)
    }

    @Test
    fun `복호화할 수 없는 pending revocation은 제거된다`() = runTest {
        val store = newStore()
        val operationId = store.enqueueRevocation("refresh-a", store.deviceId())

        cipher.invalidated = true
        assertNull(store.loadRevocation(operationId))

        cipher.invalidated = false
        assertNull("읽을 수 없는 항목은 제거되어야 한다", store.loadRevocation(operationId))
    }

    private companion object {
        const val ACCESS_TOKEN = "access-token-plaintext-value"
        const val REFRESH_TOKEN = "session-1.refresh-token-plaintext-value"
        val UUID_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
