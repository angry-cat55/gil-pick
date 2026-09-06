package com.gilpick.trip

import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeAuthService
import com.gilpick.auth.FakeSessionCipher
import com.gilpick.auth.ResponseMeta
import com.gilpick.auth.SuccessEnvelope
import com.gilpick.itinerary.DayItineraryDto
import com.gilpick.itinerary.ItineraryError
import com.gilpick.itinerary.ItineraryErrorCodes
import com.gilpick.itinerary.ItineraryItemDto
import com.gilpick.itinerary.ItineraryOverviewDto
import com.gilpick.itinerary.ItineraryPlaceDto
import com.gilpick.itinerary.ItineraryRepository
import com.gilpick.itinerary.ItineraryService
import com.gilpick.itinerary.ItemStatus
import com.gilpick.itinerary.RouteStatus
import com.gilpick.itinerary.SaveDayItineraryRequest
import com.gilpick.itinerary.StaySource
import com.gilpick.itinerary.TransportMode
import com.gilpick.place.PlaceCategory
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T024: 여행 상세 화면의 상태 매핑(로딩·성공·오류) 검증.
 *
 * `spec.md` US3 Acceptance Scenario 1~3과 `docs/design/ui-guidelines.md` 9절의 화면
 * 상태가 대상이다.
 *
 * **범위**: F002 계약의 `TripDto`는 이름·기간·상태·일수만 담는다. pen
 * `08. 여행 상세 화면`의 일정(`Day`/`Step`) 영역과 `ActionBar`는 F004·F005 데이터가
 * 있어야 하므로 이 test와 T028의 범위 밖이다. 여기서는 Summary(이름·기간·상태)와 화면
 * 상태 전이만 다룬다.
 *
 * **`empty`는 적용되지 않는다**: 9절의 네 상태 중 `empty`는 "보여줄 내용이 없지만
 * 조회는 성공한 경우"다. 상세 조회는 여행 하나를 특정해 요청하므로 성공하면 반드시
 * 내용이 있고, 없으면 계약상 `404`(→ `NOT_FOUND` 오류)다. 빈 성공 응답이 존재하지
 * 않으므로 `empty` 상태를 만들지 않고 그 이유를 여기에 남긴다.
 *
 * **상태 표시**: 여행 상태(`예정`/`여행 중`/`완료`)는 화면이
 * `com.gilpick.ui.component`의 `StatusBadge`로 그린다(T028 검증 기준, 10절 "색 단독
 * 의미 전달 금지"). view model은 서버가 KST로 계산해 준 `TripDto.status`를 그대로
 * 넘기기만 하고 다시 계산하지 않는다(`spec.md` FR-006).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripDetailViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val service = FakeTripService()
    private val itineraryService = FakeItineraryService()

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
        val viewModel = newViewModel()

        assertEquals(TripDetailPhase.Loading, viewModel.state.value.phase)
    }

    // --- content: US3 Acceptance Scenario 1 ---

    @Test
    fun `여행을 받으면 content가 된다`() = runTest {
        service.onGet = { detail(trip(TRIP_ID, name = "서울 여행")) }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        val phase = viewModel.state.value.phase as TripDetailPhase.Content
        assertEquals(TRIP_ID, phase.trip.tripId)
    }

    @Test
    fun `이름과 기간과 상태를 그대로 화면에 넘긴다`() = runTest {
        // 화면이 그려야 할 값은 US3 Acceptance 1이 정한 세 가지다. 상태는 서버가 KST로
        // 계산한 값이며(FR-006) view model이 다시 계산하지 않는다.
        service.onGet = {
            detail(
                trip(
                    TRIP_ID,
                    name = "부산 출장",
                    startDate = "2026-09-02",
                    endDate = "2026-09-06",
                    status = TripStatus.IN_PROGRESS,
                ),
            )
        }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        val trip = (viewModel.state.value.phase as TripDetailPhase.Content).trip
        assertEquals("부산 출장", trip.name)
        assertEquals("2026-09-02", trip.startDate)
        assertEquals("2026-09-06", trip.endDate)
        assertEquals(TripStatus.IN_PROGRESS, trip.status)
    }

    @Test
    fun `요청한 여행의 식별자로 조회한다`() = runTest {
        service.onGet = { detail(trip(TRIP_ID)) }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        assertEquals(listOf(TRIP_ID), service.getCalls)
    }

    // --- error: US3 Acceptance Scenario 2·3 ---

    @Test
    fun `소유하지 않은 여행은 forbidden 오류가 된다`() = runTest {
        service.onGet = { errorResponse(403, TripErrorCodes.FORBIDDEN) }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        assertEquals(
            TripDetailPhase.Failed(TripDetailError.FORBIDDEN),
            viewModel.state.value.phase,
        )
    }

    @Test
    fun `삭제되었거나 없는 여행은 not found 오류가 된다`() = runTest {
        service.onGet = { errorResponse(404, TripErrorCodes.TRIP_NOT_FOUND) }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        assertEquals(
            TripDetailPhase.Failed(TripDetailError.NOT_FOUND),
            viewModel.state.value.phase,
        )
    }

    @Test
    fun `통신 실패는 network 오류가 된다`() = runTest {
        service.onGet = { throw java.io.IOException("연결 실패") }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        assertEquals(
            TripDetailPhase.Failed(TripDetailError.NETWORK),
            viewModel.state.value.phase,
        )
    }

    @Test
    fun `계약에 없는 오류는 unexpected로 좁힌다`() = runTest {
        service.onGet = { errorResponse(500, "INTERNAL_ERROR") }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        assertEquals(
            TripDetailPhase.Failed(TripDetailError.UNEXPECTED),
            viewModel.state.value.phase,
        )
    }

    // --- 재시도: 9절 "error는 재시도 버튼을 둔다" ---

    @Test
    fun `재시도하면 다시 조회한다`() = runTest {
        service.onGet = { throw java.io.IOException("연결 실패") }
        val viewModel = newViewModel()
        viewModel.load()
        advanceUntilIdle()

        service.onGet = { detail(trip(TRIP_ID)) }
        viewModel.retry()
        advanceUntilIdle()

        val phase = viewModel.state.value.phase as TripDetailPhase.Content
        assertEquals(TRIP_ID, phase.trip.tripId)
    }

    @Test
    fun `재시도하는 동안 다시 loading이 된다`() = runTest {
        // 재시도를 눌렀는데 화면이 실패 상태로 남아 있으면 눌렀는지 알 수 없다.
        service.onGet = { throw java.io.IOException("연결 실패") }
        val viewModel = newViewModel()
        viewModel.load()
        advanceUntilIdle()

        service.onGet = { detail(trip(TRIP_ID)) }
        viewModel.retry()

        assertEquals(TripDetailPhase.Loading, viewModel.state.value.phase)
    }

    // --- 일정 개요: US3 Acceptance Scenario 1·2·4 (T025) ---

    @Test
    fun `일정을 받기 전에는 일정 영역이 loading이다`() = runTest {
        val viewModel = newViewModel()

        assertEquals(ItineraryOverviewPhase.Loading, viewModel.state.value.itinerary)
    }

    @Test
    fun `여행 기간의 모든 날짜를 순서대로 그대로 넘긴다`() = runTest {
        // ITIN-003은 빈 날짜를 포함해 기간의 모든 날짜를 돌려준다. view model은 날짜를
        // 다시 만들거나 걸러내지 않는다(FR-014).
        service.onGet = { detail(trip(TRIP_ID)) }
        itineraryService.onOverview = {
            overview(
                TRIP_ID,
                listOf(
                    day("2026-09-01", dayNumber = 1, items = listOf(item("경복궁", sequence = 1))),
                    day("2026-09-02", dayNumber = 2),
                    day("2026-09-03", dayNumber = 3, items = listOf(item("서울숲", sequence = 1))),
                ),
            )
        }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        val days = (viewModel.state.value.itinerary as ItineraryOverviewPhase.Content).days
        assertEquals(listOf("2026-09-01", "2026-09-02", "2026-09-03"), days.map { it.date })
        assertEquals(listOf(1, 2, 3), days.map { it.dayNumber })
    }

    @Test
    fun `날짜별 장소 수와 순서와 체류 시간과 이동 수단을 그대로 넘긴다`() = runTest {
        service.onGet = { detail(trip(TRIP_ID)) }
        itineraryService.onOverview = {
            overview(
                TRIP_ID,
                listOf(
                    day(
                        "2026-09-01",
                        dayNumber = 1,
                        items = listOf(
                            item("경복궁", sequence = 1, plannedStayMinutes = 90, transportModeToNext = TransportMode.TRANSIT),
                            // 마지막 항목의 이동 수단은 계약상 null이다.
                            item("북촌한옥마을", sequence = 2, plannedStayMinutes = 60),
                        ),
                    ),
                ),
            )
        }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        val items = (viewModel.state.value.itinerary as ItineraryOverviewPhase.Content)
            .days
            .single()
            .items
        assertEquals(2, items.size)
        assertEquals(listOf(1, 2), items.map { it.sequence })
        assertEquals(listOf(90, 60), items.map { it.plannedStayMinutes })
        assertEquals(listOf(TransportMode.TRANSIT, null), items.map { it.transportModeToNext })
    }

    @Test
    fun `일정이 없는 날짜는 빈 항목으로 남는다`() = runTest {
        service.onGet = { detail(trip(TRIP_ID)) }
        itineraryService.onOverview = {
            overview(TRIP_ID, listOf(day("2026-09-01", dayNumber = 1)))
        }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        val emptyDay = (viewModel.state.value.itinerary as ItineraryOverviewPhase.Content)
            .days
            .single()
        assertEquals(emptyList<Any>(), emptyDay.items)
        assertEquals(0, emptyDay.version)
    }

    @Test
    fun `요청한 여행의 식별자로 일정을 조회한다`() = runTest {
        service.onGet = { detail(trip(TRIP_ID)) }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        assertEquals(listOf(TRIP_ID), itineraryService.overviewCalls)
    }

    @Test
    fun `일정 조회가 실패해도 여행 정보는 그대로 남는다`() = runTest {
        // US3 Acceptance Scenario 4: 두 조회는 서로 다른 endpoint이며 한쪽 실패가 다른
        // 쪽 화면을 지우지 않는다.
        service.onGet = { detail(trip(TRIP_ID, name = "서울 여행")) }
        itineraryService.onOverview = { throw java.io.IOException("연결 실패") }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("서울 여행", (state.phase as TripDetailPhase.Content).trip.name)
        assertEquals(
            ItineraryOverviewPhase.Failed(ItineraryError.Network),
            state.itinerary,
        )
    }

    @Test
    fun `여행 조회가 실패해도 일정은 그대로 남는다`() = runTest {
        service.onGet = { errorResponse(500, "INTERNAL_ERROR") }
        itineraryService.onOverview = {
            overview(TRIP_ID, listOf(day("2026-09-01", dayNumber = 1)))
        }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(TripDetailPhase.Failed(TripDetailError.UNEXPECTED), state.phase)
        assertEquals(1, (state.itinerary as ItineraryOverviewPhase.Content).days.size)
    }

    @Test
    fun `일정 소유권 거부는 forbidden으로 좁힌다`() = runTest {
        // 일정 계약의 소유권 거부 code는 F002의 FORBIDDEN이 아니라 TRIP_FORBIDDEN이다.
        service.onGet = { detail(trip(TRIP_ID)) }
        itineraryService.onOverview = { overviewError(403, ItineraryErrorCodes.TRIP_FORBIDDEN) }
        val viewModel = newViewModel()

        viewModel.load()
        advanceUntilIdle()

        assertEquals(
            ItineraryOverviewPhase.Failed(ItineraryError.Forbidden),
            viewModel.state.value.itinerary,
        )
    }

    @Test
    fun `일정만 다시 시도하면 일정만 다시 조회한다`() = runTest {
        service.onGet = { detail(trip(TRIP_ID)) }
        itineraryService.onOverview = { throw java.io.IOException("연결 실패") }
        val viewModel = newViewModel()
        viewModel.load()
        advanceUntilIdle()

        itineraryService.onOverview = {
            overview(TRIP_ID, listOf(day("2026-09-01", dayNumber = 1)))
        }
        viewModel.retryItinerary()
        advanceUntilIdle()

        assertEquals(1, (viewModel.state.value.itinerary as ItineraryOverviewPhase.Content).days.size)
        // 여행 조회는 다시 나가지 않는다. 실패한 것은 일정뿐이다.
        assertEquals(listOf(TRIP_ID), service.getCalls)
        assertEquals(listOf(TRIP_ID, TRIP_ID), itineraryService.overviewCalls)
    }

    @Test
    fun `일정을 다시 시도하는 동안 일정 영역이 loading이 된다`() = runTest {
        service.onGet = { detail(trip(TRIP_ID)) }
        itineraryService.onOverview = { throw java.io.IOException("연결 실패") }
        val viewModel = newViewModel()
        viewModel.load()
        advanceUntilIdle()

        viewModel.retryItinerary()

        assertEquals(ItineraryOverviewPhase.Loading, viewModel.state.value.itinerary)
    }

    @Test
    fun `여행만 다시 시도하면 일정은 다시 조회하지 않는다`() = runTest {
        service.onGet = { errorResponse(500, "INTERNAL_ERROR") }
        val viewModel = newViewModel()
        viewModel.load()
        advanceUntilIdle()

        service.onGet = { detail(trip(TRIP_ID)) }
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(listOf(TRIP_ID), itineraryService.overviewCalls)
    }

    /** 로그인된 session을 가진 repository 위에 view model을 만든다. */
    private suspend fun newViewModel(): TripDetailViewModel {
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
        return TripDetailViewModel(
            repository = TripRepository(api = service, auth = auth),
            itineraryRepository = ItineraryRepository(api = itineraryService, auth = auth),
            tripId = TRIP_ID,
        )
    }

    private companion object {
        const val TRIP_ID = "33333333-4444-4555-8666-777777777777"
    }
}

/**
 * 응답을 test가 직접 정하는 [ItineraryService].
 *
 * 실제 HTTP 왕복과 인증 갱신·replay는 `ItineraryRepositoryTest`가 MockWebServer로
 * 검증하므로, 여기서는 상세 화면 상태 전이에 필요한 개요 응답만 다룬다.
 */
private class FakeItineraryService : ItineraryService {

    /** 지금까지 도착한 개요 요청의 `tripId`. 호출 순서대로 쌓인다. */
    val overviewCalls = mutableListOf<String>()

    /** `tripId`를 받아 개요 응답을 만든다. 기본값은 날짜가 없는 성공 응답이다. */
    var onOverview: (String) -> Response<SuccessEnvelope<ItineraryOverviewDto>> =
        { overview(it, emptyList()) }

    override suspend fun getOverview(
        bearer: String,
        tripId: String,
    ): Response<SuccessEnvelope<ItineraryOverviewDto>> {
        overviewCalls += tripId
        return onOverview(tripId)
    }

    override suspend fun getDayItinerary(
        bearer: String,
        tripId: String,
        date: String,
    ): Response<SuccessEnvelope<DayItineraryDto>> =
        error("이 test는 날짜별 조회 endpoint를 호출하지 않는다")

    override suspend fun saveDayItinerary(
        bearer: String,
        idempotencyKey: String,
        tripId: String,
        date: String,
        body: SaveDayItineraryRequest,
    ): Response<SuccessEnvelope<DayItineraryDto>> =
        error("이 test는 저장 endpoint를 호출하지 않는다")
}

/** 개요 성공 응답을 만든다. */
private fun overview(
    tripId: String,
    days: List<DayItineraryDto>,
): Response<SuccessEnvelope<ItineraryOverviewDto>> = Response.success(
    SuccessEnvelope(
        success = true,
        data = ItineraryOverviewDto(tripId = tripId, days = days),
        meta = ResponseMeta(requestId = ITINERARY_REQUEST_ID),
    ),
)

/** 개요 실패 응답을 만든다. repository가 code로 원인을 판정하므로 code를 함께 준다. */
private fun overviewError(
    httpStatus: Int,
    code: String,
): Response<SuccessEnvelope<ItineraryOverviewDto>> = Response.error(
    httpStatus,
    """{"success":false,"error":{"code":"$code","message":"진단용 설명","retryable":false},"meta":{"requestId":"$ITINERARY_REQUEST_ID"}}"""
        .toResponseBody("application/json".toMediaType()),
)

/** 하루치 일정을 만든다. 저장된 적 없는 날짜는 [items]가 비고 version이 0이다. */
private fun day(
    date: String,
    dayNumber: Int,
    items: List<ItineraryItemDto> = emptyList(),
): DayItineraryDto = DayItineraryDto(
    date = date,
    dayNumber = dayNumber,
    version = if (items.isEmpty()) 0 else 1,
    routeStatus = RouteStatus.NOT_CALCULATED,
    items = items,
)

/** 일정 항목 하나를 만든다. */
private fun item(
    name: String,
    sequence: Int,
    plannedStayMinutes: Int = 90,
    transportModeToNext: TransportMode? = null,
): ItineraryItemDto = ItineraryItemDto(
    itemId = "item-$sequence",
    place = ItineraryPlaceDto(
        placeId = "place-$sequence",
        name = name,
        category = PlaceCategory.HISTORY_CULTURE,
        address = null,
        imageUrl = null,
    ),
    sequence = sequence,
    plannedStayMinutes = plannedStayMinutes,
    staySource = StaySource.RECOMMENDED,
    transportModeToNext = transportModeToNext,
    status = ItemStatus.PLANNED,
)

private const val ITINERARY_REQUEST_ID = "11111111-2222-4333-8444-555555555555"
