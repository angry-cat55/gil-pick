package com.gilpick.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gilpick.ui.theme.GilpickTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T023: 계정 정보 mapping·누락 대체 표시와 앱 버전 표시 검증(spec US4, FR-009).
 *
 * 이 test가 지키는 것은 **값이 없을 때 무엇을 보이는가**다. 닉네임과 프로필 이미지는 카카오
 * 동의 항목이라 실제로 비어 올 수 있는데(F001 `SessionEnvelope`), 그때 그럴듯한 이름을
 * 지어내면 사용자는 자기 계정이 아닌 것을 본다. 그래서 누락은 `정보 없음`으로만 표시한다
 * (ui-guidelines 12절).
 *
 * 프로필 이미지의 실제 network 로드는 확인하지 않는다. 그것은 Coil과 기기 몫이고, 이 test는
 * **URL이 없어도 계정 영역이 같은 자리를 지키는지**만 본다.
 *
 * 카카오 연동 표시는 session 존재로만 판단한다. MVP provider가 카카오 하나뿐이라 session에
 * provider field를 더하지 않기로 했다(plan Account Display).
 */
@RunWith(AndroidJUnit4::class)
class SettingsAccountInfoTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 닉네임이_있으면_session_값을_그대로_보인다() {
        // FR-009. 세션이 준 표시 이름을 가공하지 않는다.
        setAccount(nickname = "김길픽", profileImageUrl = "https://img.example/p.png")

        composeRule.onNodeWithText("김길픽").assertIsDisplayed()
    }

    @Test
    fun 닉네임이_없으면_지어내지_않고_정보_없음을_보인다() {
        // US4 시나리오 2. 카카오 닉네임 미동의 계정이다.
        setAccount(nickname = null, profileImageUrl = "https://img.example/p.png")

        composeRule.onNodeWithText("정보 없음").assertIsDisplayed()
    }

    @Test
    fun 프로필_이미지가_없어도_계정_영역과_닉네임은_남는다() {
        // US4 시나리오 2. 이미지가 없다고 계정 영역이 사라지거나 설정을 못 쓰게 되면 안 된다.
        setAccount(nickname = "김길픽", profileImageUrl = null)

        composeRule.onNodeWithTag(TAG_ACCOUNT_SECTION).assertIsDisplayed()
        composeRule.onNodeWithText("김길픽").assertIsDisplayed()
    }

    @Test
    fun 닉네임과_프로필이_모두_없어도_계정_영역은_그대로다() {
        // 두 값이 함께 비는 조합. 대체 표시만 남고 화면은 유지된다.
        setAccount(nickname = null, profileImageUrl = null)

        composeRule.onNodeWithTag(TAG_ACCOUNT_SECTION).assertIsDisplayed()
        composeRule.onNodeWithText("정보 없음").assertIsDisplayed()
    }

    @Test
    fun 인증_session이_있으면_카카오_연동으로_표시한다() {
        // plan Account Display. provider field 없이 session 존재만으로 판단한다.
        setAccount(nickname = "김길픽", profileImageUrl = null, isKakaoConnected = true)

        composeRule.onNodeWithText("카카오 연동").assertIsDisplayed()
    }

    @Test
    fun 앱_정보에_설치된_버전을_보인다() {
        // US4 시나리오 3. 문의·문제 해결 때 사용자가 설치본을 식별할 수 있어야 한다.
        composeRule.setContent {
            GilpickTheme {
                PolicyDocumentSection(
                    versionName = "1.2.3",
                    openError = null,
                    onOpen = {},
                    onRetry = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithTag(TAG_APP_VERSION).assertIsDisplayed()
        composeRule.onNodeWithText("버전").assertIsDisplayed()
        composeRule.onNodeWithText("1.2.3").assertIsDisplayed()
    }

    @Test
    fun 버전은_정책_문서_항목과_같은_앱_정보에_있다() {
        // Figma `SettingsScreen` 앱 정보: 버전 → 개인정보처리방침 → 이용약관 한 묶음이다.
        composeRule.setContent {
            GilpickTheme {
                PolicyDocumentSection(
                    versionName = "1.2.3",
                    openError = null,
                    onOpen = {},
                    onRetry = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithTag(TAG_APP_VERSION).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_PRIVACY_POLICY).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_TERMS_OF_SERVICE).assertIsDisplayed()
    }

    /** 계정 영역만 떼어 그린다. 값 조합별 표시를 보는 것이 이 test의 대상이다. */
    private fun setAccount(
        nickname: String?,
        profileImageUrl: String?,
        isKakaoConnected: Boolean = true,
    ) {
        composeRule.setContent {
            GilpickTheme {
                AccountSection(
                    nickname = nickname,
                    profileImageUrl = profileImageUrl,
                    isKakaoConnected = isKakaoConnected,
                )
            }
        }
    }
}
