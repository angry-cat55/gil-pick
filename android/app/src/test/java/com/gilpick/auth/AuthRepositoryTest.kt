package com.gilpick.auth

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** T012: 저장된 session 복원과 SignedOut·Authenticated·RefreshOffline 전이 검증. */
class AuthRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val cipher = FakeSessionCipher()
    private lateinit var store: AuthSessionStore
    private lateinit var repository: AuthRepository

    @Before
    fun setUp() {
        val file = File(tempFolder.root, AuthSessionStore.FILE_NAME)
        store = AuthSessionStore(AuthSessionStore.createDataStore(file), cipher)
        repository = newRepository()
    }

    @Test
    fun `초기 상태는 Loading이다`() {
        assertEquals(AuthUiState.Loading, repository.state.value)
    }

    @Test
    fun `저장된 session이 없으면 SignedOut으로 복원된다`() = runTest {
        assertEquals(AuthUiState.SignedOut, repository.restore())
        assertEquals(AuthUiState.SignedOut, repository.state.value)
    }

    @Test
    fun `저장된 session은 Authenticated로 복원된다`() = runTest {
        signIn(nickname = "길픽")

        val restored = newRepository().restore()

        val authenticated = restored as AuthUiState.Authenticated
        assertEquals("user-1", authenticated.userId)
        assertEquals("길픽", authenticated.nickname)
    }

    @Test
    fun `profile이 null인 사용자도 Authenticated가 된다`() = runTest {
        signIn(nickname = null)

        val authenticated = newRepository().restore() as AuthUiState.Authenticated

        assertEquals(null, authenticated.nickname)
        assertEquals(null, authenticated.profileImageUrl)
    }

    @Test
    fun `통신 장애는 session을 유지한 채 RefreshOffline이 된다`() = runTest {
        signIn(nickname = null)

        val state = repository.onRefreshOffline()

        assertEquals(AuthUiState.RefreshOffline("user-1"), state)
        assertTrue("session은 유지되어야 한다", repository.currentSession() != null)
    }

    @Test
    fun `로그아웃 상태에서는 RefreshOffline로 전이하지 않는다`() = runTest {
        repository.restore()

        assertEquals(AuthUiState.SignedOut, repository.onRefreshOffline())
    }

    @Test
    fun `로그아웃은 local session을 즉시 제거한다`() = runTest {
        signIn(nickname = null)

        assertEquals(AuthUiState.SignedOut, repository.onSignedOut())
        assertEquals(null, repository.currentSession())
    }

    @Test
    fun `key 무효화된 session은 SignedOut으로 복원된다`() = runTest {
        signIn(nickname = null)
        cipher.invalidated = true

        assertEquals(AuthUiState.SignedOut, newRepository().restore())
    }

    @Test
    fun `deviceId는 repository를 통해서도 재사용된다`() = runTest {
        assertEquals(repository.deviceId(), newRepository().deviceId())
    }

    /** 이 test는 저장·복원과 상태 전이만 보므로 network 계층은 호출되지 않는다. */
    private fun newRepository() = AuthRepository(
        store = store,
        api = FakeAuthService,
        appLinkHandler = AuthAppLinkHandler("app.gilpick.example"),
    )

    private suspend fun signIn(nickname: String?) {
        repository.onSignedIn(
            sessionId = "session-1",
            userId = "user-1",
            nickname = nickname,
            profileImageUrl = null,
            accessToken = "access-token",
            refreshToken = "session-1.refresh-token",
            accessExpiresAtEpochSeconds = 3_600,
            refreshExpiresAtEpochSeconds = 2_592_000,
        )
    }
}
