package com.gilpick.trip

import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeAuthService
import com.gilpick.auth.FakeSessionCipher
import java.io.File
import java.io.IOException
import java.time.LocalDate
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 여행 폼 view model.
 *
 * - #501: 겹치는 여행 기간 비활성 표시와 `409 TRIP_PERIOD_CONFLICT` 안내(F002 FR-002a·FR-002b, T053).
 * - #499: 대표 이미지 선택·업로드·삭제와 업로드 실패 후 이어서 저장(FR-019).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripFormViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val service = FakeTripService()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `모든 page의 내 여행 기간을 비활성 날짜로 모은다`() = runTest {
        service.onList = { call ->
            if (call.cursor == null) page(listOf(trip("t1", startDate = "2026-09-01", endDate = "2026-09-02")), nextCursor = "c2")
            else page(listOf(trip("t2", startDate = "2026-09-10", endDate = "2026-09-10")))
        }
        val viewModel = newViewModel()

        advanceUntilIdle()

        assertEquals(
            setOf(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 10)),
            viewModel.state.value.occupiedDates,
        )
        assertEquals(listOf(null, "c2"), service.listCalls.map { it.cursor })
    }

    @Test
    fun `수정 중인 자기 여행 기간은 비활성에서 뺀다`() = runTest {
        service.onList = { page(listOf(trip("t1", startDate = "2026-09-01", endDate = "2026-09-02"), trip("t2", startDate = "2026-09-05", endDate = "2026-09-05"))) }
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.startEditing(trip("t1", startDate = "2026-09-01", endDate = "2026-09-02"))

        assertEquals(setOf(LocalDate.of(2026, 9, 5)), viewModel.state.value.occupiedDates)
    }

    @Test
    fun `목록 조회가 실패하면 비활성 없이 폼을 그대로 쓴다`() = runTest {
        service.onList = { throw IOException("offline") }
        val viewModel = newViewModel()

        advanceUntilIdle()

        assertTrue(viewModel.state.value.occupiedDates.isEmpty())
        assertNull(viewModel.state.value.submitError)
    }

    @Test
    fun `기간 충돌은 입력을 유지하고 겹친 여행 이름과 함께 안내하며 다시 보낼 수 있다`() = runTest {
        service.onCreate = { periodConflict(name = "제주 여행") }
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.onNameChange("서울 여행")
        viewModel.onPeriodChange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3))

        viewModel.submit()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(TripFormSubmitError.PERIOD_CONFLICT, state.submitError)
        assertEquals("제주 여행", state.conflictTripName)
        assertEquals("서울 여행", state.name)
        assertEquals(LocalDate.of(2026, 9, 1) to LocalDate.of(2026, 9, 3), state.startDate to state.endDate)
        // 겹친 여행 기간이 달력에 반영되도록 목록을 다시 읽는다.
        assertEquals(2, service.listCalls.size)

        service.onCreate = { detail(trip("t3")) }
        viewModel.onPeriodChange(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 11))
        assertNull(viewModel.state.value.conflictTripName)
        viewModel.submit()
        advanceUntilIdle()

        assertEquals("t3", viewModel.state.value.savedTripId)
    }

    @Test
    fun `이름 없는 기간 충돌도 기간 충돌로 안내한다`() = runTest {
        service.onCreate = { periodConflict(name = null) }
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.onNameChange("서울 여행")
        viewModel.onPeriodChange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3))

        viewModel.submit()
        advanceUntilIdle()

        assertEquals(TripFormSubmitError.PERIOD_CONFLICT, viewModel.state.value.submitError)
        assertNull(viewModel.state.value.conflictTripName)
    }

    // --- #499 대표 이미지 ---

    @Test
    fun `새 여행을 만든 뒤 고른 사진을 올리고 저장을 끝낸다`() = runTest {
        service.onCreate = { detail(trip("t1")) }
        service.onUpload = { detail(trip("t1", version = 2, imageUrl = IMAGE_URL)) }
        val viewModel = filledViewModel()
        viewModel.onImagePicked(TripImagePick.Picked(PickedTripImage(byteArrayOf(1, 2, 3), "image/png")))

        viewModel.submit()
        advanceUntilIdle()

        assertEquals(1, service.createCalls.size)
        assertEquals(listOf("t1"), service.uploadCalls.map { it.first })
        assertEquals("image/png", service.uploadCalls.single().second.body.contentType().toString())
        assertEquals("t1", viewModel.state.value.savedTripId)
    }

    @Test
    fun `사진 업로드만 실패하면 폼에 남고 다시 저장하면 여행을 새로 만들지 않고 이어서 올린다`() = runTest {
        service.onCreate = { detail(trip("t1", version = 1)) }
        service.onUpdate = { detail(trip("t1", version = 2)) }
        service.onUpload = { errorResponse(413, TripErrorCodes.IMAGE_TOO_LARGE) }
        val viewModel = filledViewModel()
        viewModel.onImagePicked(TripImagePick.Picked(PickedTripImage(byteArrayOf(1), "image/jpeg")))

        viewModel.submit()
        advanceUntilIdle()

        assertEquals(TripFormSubmitError.IMAGE_TOO_LARGE, viewModel.state.value.submitError)
        assertNull(viewModel.state.value.savedTripId)

        service.onUpload = { detail(trip("t1", version = 3, imageUrl = IMAGE_URL)) }
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(1, service.createCalls.size)
        // #589: 이름·기간이 그대로라 보낼 것이 없는 수정 요청은 아예 만들지 않는다. 사진만 다시 올라간다.
        assertEquals(emptyList<UpdateTripRequest>(), service.updateCalls)
        assertEquals(2, service.uploadCalls.size)
        assertEquals("t1", viewModel.state.value.savedTripId)
    }

    /** #589: 사진만 실패했고 이름·기간이 그대로면 다시 저장할 때 수정 요청 없이 사진만 올린다. */
    @Test
    fun `이름과 기간이 그대로면 다시 저장할 때 수정 요청을 보내지 않는다`() = runTest {
        service.onCreate = { detail(trip("t1", name = "서울 여행", version = 1)) }
        service.onUpdate = { error("이름·기간이 그대로면 수정 요청을 보내지 않는다") }
        service.onUpload = { errorResponse(500, "INTERNAL_ERROR") }
        val viewModel = filledViewModel()
        viewModel.onImagePicked(TripImagePick.Picked(PickedTripImage(byteArrayOf(1), "image/jpeg")))

        viewModel.submit()
        advanceUntilIdle()
        assertEquals(TripFormSubmitError.IMAGE_UPLOAD_FAILED, viewModel.state.value.submitError)

        service.onUpload = { detail(trip("t1", name = "서울 여행", version = 2, imageUrl = IMAGE_URL)) }
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(1, service.createCalls.size)
        assertEquals(emptyList<UpdateTripRequest>(), service.updateCalls)
        assertEquals(2, service.uploadCalls.size)
        assertEquals("t1", viewModel.state.value.savedTripId)
    }

    /** #555: 배포 서버에 이미지 API가 없어 404가 오면 "만들 수 없음"이 아니라 사진만 실패했다고 알린다. */
    @Test
    fun `사진 경로가 404면 여행은 저장된 채 사진 업로드 실패로 안내한다`() = runTest {
        service.onCreate = { detail(trip("t1", version = 1)) }
        service.onUpload = { errorResponse(404, "NOT_FOUND") }
        val viewModel = filledViewModel()
        viewModel.onImagePicked(TripImagePick.Picked(PickedTripImage(byteArrayOf(1), "image/jpeg")))

        viewModel.submit()
        advanceUntilIdle()

        assertEquals(TripFormSubmitError.IMAGE_UPLOAD_FAILED, viewModel.state.value.submitError)
        assertEquals(1, service.createCalls.size)
        assertNull(viewModel.state.value.savedTripId)
    }

    @Test
    fun `사진 형식 거절 415는 사진을 바꾸라는 안내를 유지한다`() = runTest {
        service.onCreate = { detail(trip("t1", version = 1)) }
        service.onUpload = { errorResponse(415, TripErrorCodes.UNSUPPORTED_IMAGE_TYPE) }
        val viewModel = filledViewModel()
        viewModel.onImagePicked(TripImagePick.Picked(PickedTripImage(byteArrayOf(1), "image/jpeg")))

        viewModel.submit()
        advanceUntilIdle()

        assertEquals(TripFormSubmitError.IMAGE_UNSUPPORTED_TYPE, viewModel.state.value.submitError)
    }

    @Test
    fun `지난 기간으로 만든 여행의 사진만 다시 저장하면 기간을 보내지 않아 잠금에 걸리지 않는다`() = runTest {
        // 서버처럼 완료된 여행에 기간이 오면 TRIP_LOCKED로 거절한다(#569).
        service.onCreate = { detail(trip("t1", status = TripStatus.COMPLETED, version = 1)) }
        service.onUpdate = { body ->
            if (body.startDate != null || body.endDate != null) errorResponse(409, TripErrorCodes.TRIP_LOCKED)
            else detail(trip("t1", status = TripStatus.COMPLETED, version = 2))
        }
        service.onUpload = { errorResponse(413, TripErrorCodes.IMAGE_TOO_LARGE) }
        val viewModel = filledViewModel()
        viewModel.onImagePicked(TripImagePick.Picked(PickedTripImage(byteArrayOf(1), "image/jpeg")))
        viewModel.submit()
        advanceUntilIdle()

        service.onUpload = { detail(trip("t1", status = TripStatus.COMPLETED, version = 3, imageUrl = IMAGE_URL)) }
        viewModel.submit()
        advanceUntilIdle()

        assertNull(viewModel.state.value.submitError)
        assertEquals("t1", viewModel.state.value.savedTripId)
        // #589부터는 이름·기간이 그대로면 수정 요청 자체를 보내지 않으므로 완료된 여행도 잠금에 걸릴 일이 없다.
        assertEquals(emptyList<UpdateTripRequest>(), service.updateCalls)
    }

    @Test
    fun `수정 중 업로드가 실패하면 다시 저장할 때 서버가 올린 version으로 보낸다`() = runTest {
        service.onUpdate = { body -> detail(trip("t1", version = body.version + 1)) }
        service.onUpload = { throw java.io.IOException("offline") }
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.startEditing(trip("t1", version = 5))
        viewModel.onImagePicked(TripImagePick.Picked(PickedTripImage(byteArrayOf(1), "image/webp")))

        viewModel.submit()
        advanceUntilIdle()
        // #555 여행은 저장됐고 사진만 실패했다. 통신 실패도 사진 실패로 구분해 알린다.
        assertEquals(TripFormSubmitError.IMAGE_UPLOAD_FAILED, viewModel.state.value.submitError)

        service.onUpload = { detail(trip("t1", version = 7, imageUrl = IMAGE_URL)) }
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(listOf(5, 6), service.updateCalls.map { it.version })
        assertEquals("t1", viewModel.state.value.savedTripId)
    }

    @Test
    fun `기본으로 되돌리면 저장할 때 이미지를 지운다`() = runTest {
        service.onImageContent = { okhttp3.ResponseBody.create(null, byteArrayOf(9)).let { retrofit2.Response.success(it) } }
        service.onUpdate = { detail(trip("t1", version = 6, imageUrl = IMAGE_URL)) }
        service.onDeleteImage = { detail(trip("t1", version = 7)) }
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.startEditing(trip("t1", version = 5, imageUrl = IMAGE_URL))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.hasCustomImage)
        assertEquals(listOf<Byte>(9), viewModel.state.value.coverImage?.toList())

        viewModel.onRemoveImage()
        assertNull(viewModel.state.value.coverImage)
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(listOf("t1"), service.deleteImageCalls)
        assertEquals("t1", viewModel.state.value.savedTripId)
    }

    @Test
    fun `제한에 걸린 사진은 원인을 알리고 이전 선택을 유지한다`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()
        val first = PickedTripImage(byteArrayOf(1), "image/png")
        viewModel.onImagePicked(TripImagePick.Picked(first))

        viewModel.onImagePicked(TripImagePick.Rejected(TripImageError.UNREADABLE))

        assertEquals(TripImageError.UNREADABLE, viewModel.state.value.imageError)
        assertTrue(viewModel.state.value.pickedImage === first)
    }

    /** 이름·기간을 채운 생성 폼. */
    private suspend fun kotlinx.coroutines.test.TestScope.filledViewModel(): TripFormViewModel {
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.onNameChange("서울 여행")
        viewModel.onPeriodChange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3))
        return viewModel
    }

    private suspend fun newViewModel(): TripFormViewModel {
        val store = AuthSessionStore(
            // DataStore 기본 scope는 Dispatchers.IO다. test scheduler 안에서 돌게 한다.
            AuthSessionStore.createDataStore(
                File(tempFolder.root, AuthSessionStore.FILE_NAME),
                scope = CoroutineScope(dispatcher + SupervisorJob()),
            ),
            FakeSessionCipher(),
        )
        val auth = AuthRepository(
            store = store,
            api = FakeAuthService,
            appLinkHandler = AuthAppLinkHandler("app.gilpick.example"),
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
        return TripFormViewModel(TripRepository(api = service, auth = auth))
    }
}

private const val IMAGE_URL = "http://api.example/api/v1/trips/t1/image/content"
