package com.gilpick.place

import com.gilpick.auth.AuthAppLinkHandler
import com.gilpick.auth.AuthRepository
import com.gilpick.auth.AuthResult
import com.gilpick.auth.AuthSessionStore
import com.gilpick.auth.FakeAuthService
import com.gilpick.auth.FakeSessionCipher
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T012·T016: 검색 repository의 요청 전달과 응답·실패 매핑 검증.
 *
 * HTTP 왕복은 `PlaceApiTest`가 MockWebServer로 확인하므로 여기서는 repository가 조건을 어떻게
 * 넘기고 envelope를 [PlaceSearchPage]로, 실패를 [PlaceError]로 옮기는지만 본다.
 */
class PlaceRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val service = FakePlaceService()

    @Test
    fun `조건과 cursor를 그대로 넘기고 page를 읽는다`() = runTest {
        service.onSearch = { placePage(listOf(place("tourapi:1"), place("google:a")), nextCursor = "next") }

        val result = repository().searchPlaces("경복궁", PlaceCategory.CAFE, cursor = "prev")

        val page = (result as AuthResult.Success).value
        assertEquals(listOf("tourapi:1", "google:a"), page.places.map { it.placeId })
        assertEquals("next", page.nextCursor)
        assertTrue(page.hasNext)
        assertEquals(FakePlaceService.SearchCall("경복궁", PlaceCategory.CAFE, "prev"), service.searchCalls.single())
    }

    @Test
    fun `빈 키워드는 생략하고 마지막 페이지는 hasNext가 false다`() = runTest {
        service.onSearch = { placePage(emptyList()) }

        val result = repository().searchPlaces("   ", PlaceCategory.NATURE)

        val page = (result as AuthResult.Success).value
        assertTrue(page.places.isEmpty())
        assertNull(page.nextCursor)
        assertFalse(page.hasNext)
        assertNull(service.searchCalls.single().query)
    }

    @Test
    fun `계약 error code를 원인과 retryable로 좁힌다`() = runTest {
        service.onSearch = { placeError(429, PlaceErrorCodes.TOUR_API_RATE_LIMITED, retryable = false) }

        val result = repository().searchPlaces("경복궁", null)

        val error = (result as AuthResult.Failure).error.toPlaceError()
        assertEquals(PlaceError(PlaceErrorKind.RATE_LIMITED, retryable = false), error)
    }

    @Test
    fun `통신 실패는 재시도 가능한 network 오류다`() = runTest {
        service.onSearch = { throw IOException("연결 실패") }

        val result = repository().searchPlaces("경복궁", null)

        val error = (result as AuthResult.Failure).error.toPlaceError()
        assertEquals(PlaceError(PlaceErrorKind.NETWORK, retryable = true), error)
    }

    /** 로그인된 session을 가진 repository를 만든다. */
    private suspend fun repository(): PlaceRepository {
        val store = AuthSessionStore(
            AuthSessionStore.createDataStore(File(tempFolder.root, AuthSessionStore.FILE_NAME)),
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
        return PlaceRepository(api = service, auth = auth)
    }
}
