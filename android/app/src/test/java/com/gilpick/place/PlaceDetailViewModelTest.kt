package com.gilpick.place

import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthErrorCodes
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthService
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeAuthService
import com.gilpick.auth.FakeSessionCipher
import com.gilpick.auth.ProgrammableAuthService
import com.gilpick.auth.errorResponse
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T020·T023: 장소 상세 화면 상태 전이와 오류 분류 검증.
 *
 * `spec.md` US2 Acceptance Scenario 1~4와 US3의 원인별 복구가 대상이다. `empty`는 상세
 * 조회에 성립하지 않는다(근거는 [PlaceDetailPhase]). 뒤로가기 상태 보존은 navigation의
 * destination-scoped ViewModel이 담당하므로 `PlaceNavigationTest`가 다룬다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceDetailViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val service = FakePlaceService()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- loading ---

    @Test
    fun `첫 조회 전에는 loading이다`() = runTest {
        assertEquals(PlaceDetailPhase.Loading, newViewModel().state.value.phase)
    }

    // --- content: US2 Acceptance Scenario 1·2 ---

    @Test
    fun `장소를 받으면 content가 되고 nullable field는 그대로 넘긴다`() = runTest {
        service.onGet = {
            placeDetail(place(PLACE_ID, name = "경복궁", description = "조선의 법궁", phone = null, operatingGuide = null))
        }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        val place = (viewModel.state.value.phase as PlaceDetailPhase.Content).place
        assertEquals("경복궁", place.name)
        assertEquals("조선의 법궁", place.description)
        assertNull(place.phone)
        assertNull(place.operatingGuide)
        assertEquals(listOf(PLACE_ID), service.getCalls)
    }

    // --- US2 Acceptance Scenario 4: google 전용 장소 ---

    @Test
    fun `google 전용 장소도 같은 흐름으로 조회한다`() = runTest {
        service.onGet = { placeDetail(place("google:abc", rating = 4.3, userRatingCount = 12)) }
        val viewModel = PlaceDetailViewModel(repository(), placeId = "google:abc")

        viewModel.load()
        advanceUntilIdle()

        val place = (viewModel.state.value.phase as PlaceDetailPhase.Content).place
        assertEquals(PlaceSource.GOOGLE_PLACES, place.source)
        assertEquals(4.3, place.rating!!, 0.0)
        assertEquals(listOf("google:abc"), service.getCalls)
    }

    @Test
    fun `이미 받은 내용이 있으면 load는 다시 조회하지 않는다`() = runTest {
        service.onGet = { placeDetail(place(PLACE_ID)) }
        val viewModel = newViewModel()
        viewModel.load()
        advanceUntilIdle()

        viewModel.load()
        advanceUntilIdle()

        assertEquals(1, service.getCalls.size)
    }

    // --- US2 Acceptance Scenario 3: not found ---

    @Test
    fun `없는 장소는 not found가 된다`() = runTest {
        service.onGet = { placeError(404, PlaceErrorCodes.PLACE_NOT_FOUND) }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        assertEquals(PlaceDetailPhase.NotFound, viewModel.state.value.phase)
    }

    // --- US3: 원인별 오류 ---

    @Test
    fun `통신 실패는 재시도 가능한 network 오류가 된다`() = runTest {
        service.onGet = { throw IOException("연결 실패") }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        assertEquals(
            PlaceDetailPhase.Failed(PlaceError(PlaceErrorKind.NETWORK, retryable = true)),
            viewModel.state.value.phase,
        )
    }

    @Test
    fun `provider timeout·장애는 server의 retryable을 보존한다`() = runTest {
        val viewModel = newViewModel()

        service.onGet = { placeError(504, PlaceErrorCodes.TOUR_API_TIMEOUT, retryable = true) }
        viewModel.load()
        advanceUntilIdle()
        val timeout = viewModel.state.value.phase

        service.onGet = { placeError(502, PlaceErrorCodes.TOUR_API_FAILED, retryable = false) }
        viewModel.retry()
        advanceUntilIdle()
        val failed = viewModel.state.value.phase

        assertEquals(PlaceDetailPhase.Failed(PlaceError(PlaceErrorKind.TIMEOUT, retryable = true)), timeout)
        assertEquals(PlaceDetailPhase.Failed(PlaceError(PlaceErrorKind.PROVIDER_FAILED, retryable = false)), failed)
    }

    @Test
    fun `google 상세 실패는 provider별 code와 retryable을 보존한다`() = runTest {
        service.onGet = { placeError(429, PlaceErrorCodes.GOOGLE_PLACES_RATE_LIMITED) }
        val viewModel = PlaceDetailViewModel(repository(), placeId = "google:abc")

        viewModel.load()
        advanceUntilIdle()

        assertEquals(
            PlaceDetailPhase.Failed(PlaceError(PlaceErrorKind.RATE_LIMITED, retryable = false)),
            viewModel.state.value.phase,
        )
    }

    @Test
    fun `인증 만료는 session expired가 된다`() = runTest {
        // 401을 받으면 F001이 갱신을 한 번 시도한다. 갱신도 거절되어야 자격 무효가 확정된다.
        val authService = ProgrammableAuthService().apply {
            onRefresh = { errorResponse(401, AuthErrorCodes.INVALID_REFRESH_TOKEN) }
        }
        service.onGet = { placeError(401, PlaceErrorCodes.INVALID_ACCESS_TOKEN) }
        val viewModel = PlaceDetailViewModel(repository(authService), placeId = PLACE_ID)

        viewModel.load()
        advanceUntilIdle()

        val failed = viewModel.state.value.phase as PlaceDetailPhase.Failed
        assertEquals(PlaceErrorKind.SESSION_EXPIRED, failed.error.kind)
    }

    @Test
    fun `계약과 다른 응답과 알 수 없는 오류는 unexpected로 좁힌다`() {
        assertEquals(
            PlaceError(PlaceErrorKind.UNEXPECTED, retryable = true),
            AuthError.Malformed(IllegalStateException("body 없음")).toPlaceError(),
        )
        assertEquals(PlaceErrorKind.UNEXPECTED, AuthError.Server("INTERNAL_ERROR", true, 500).toPlaceError().kind)
        assertEquals(PlaceErrorKind.INVALID_REQUEST, AuthError.Server(PlaceErrorCodes.INVALID_REQUEST, false, 400).toPlaceError().kind)
    }

    // --- 재시도: 9절 "error는 재시도 버튼을 둔다" ---

    @Test
    fun `재시도하면 loading을 거쳐 다시 조회한다`() = runTest {
        service.onGet = { throw IOException("연결 실패") }
        val viewModel = newViewModel()
        viewModel.load()
        advanceUntilIdle()

        service.onGet = { placeDetail(place(PLACE_ID)) }
        viewModel.retry()
        assertEquals(PlaceDetailPhase.Loading, viewModel.state.value.phase)
        advanceUntilIdle()

        assertEquals(PLACE_ID, (viewModel.state.value.phase as PlaceDetailPhase.Content).place.placeId)
        assertEquals(2, service.getCalls.size)
    }

    private suspend fun newViewModel(): PlaceDetailViewModel =
        PlaceDetailViewModel(repository(), placeId = PLACE_ID)

    /** 로그인된 session을 가진 repository를 만든다. */
    private suspend fun repository(authService: AuthService = FakeAuthService): PlaceRepository {
        val store = AuthSessionStore(
            // DataStore 기본 scope는 Dispatchers.IO다. 그대로 두면 저장소 작업이 test
            // scheduler 밖에서 돌아 advanceUntilIdle()이 기다려 주지 않는다.
            AuthSessionStore.createDataStore(
                File(tempFolder.root, AuthSessionStore.FILE_NAME),
                scope = CoroutineScope(dispatcher + SupervisorJob()),
            ),
            FakeSessionCipher(),
        )
        val auth = AuthRepository(
            store = store,
            api = authService,
            appLinkHandler = AuthAppLinkHandler("app.gilpick.example"),
            // 갱신은 repository 자체 scope에서 돈다. test scheduler에 묶어야 advanceUntilIdle()이 기다린다.
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
        return PlaceRepository(api = service, auth = auth)
    }

    private companion object {
        const val PLACE_ID = "tourapi:126508"
    }
}
