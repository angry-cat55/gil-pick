package com.gilpick.auth

import kotlinx.serialization.Serializable

/**
 * `auth_session.pb`에 저장되는 최상위 상태.
 *
 * Token 원문은 담지 않는다. [sessionEnvelope]와 [pendingRevocations]의 Token은
 * AndroidKeyStore AES-GCM ciphertext로만 존재한다.
 *
 * @property deviceId 설치 단위 UUID. 최초 실행 시 생성해 재사용한다.
 * @property sessionEnvelope 현재 로그인 session. 로그아웃 상태면 `null`이다.
 * @property pendingRevocations 서버 폐기를 아직 완료하지 못한 logout 요청 목록.
 */
@Serializable
data class AuthSessionState(
    val deviceId: String = "",
    val sessionEnvelope: SessionEnvelope? = null,
    val pendingRevocations: List<PendingRevocation> = emptyList(),
)

/**
 * 암호화된 Token pair와 평문으로 두어도 안전한 session metadata.
 *
 * @property sessionId 서버 `DeviceSession` 식별자. Token 원문이 아니므로 평문이다.
 * @property userId 로그인한 길픽 사용자 ID.
 * @property nickname 카카오가 제공한 표시 이름. 미동의 시 `null`이다.
 * @property profileImageUrl 카카오 profile image URL. 미동의 시 `null`이다.
 * @property accessToken Access Token ciphertext.
 * @property refreshToken Refresh Token ciphertext.
 * @property accessExpiresAtEpochSeconds Access Token 만료 시각.
 * @property refreshExpiresAtEpochSeconds Refresh Token 만료 시각.
 */
@Serializable
data class SessionEnvelope(
    val sessionId: String,
    val userId: String,
    val nickname: String? = null,
    val profileImageUrl: String? = null,
    val accessToken: EncryptedValue,
    val refreshToken: EncryptedValue,
    val accessExpiresAtEpochSeconds: Long,
    val refreshExpiresAtEpochSeconds: Long,
)

/**
 * 오프라인 로그아웃의 서버 폐기 재시도 단위.
 *
 * 요청마다 새 [revocationOperationId]를 부여해 같은 기기의 재로그인·복수 logout이
 * 서로의 WorkManager 작업과 충돌하지 않게 격리한다.
 *
 * @property revocationOperationId 요청별 고유 ID. WorkManager unique work 이름이 된다.
 * @property refreshToken 폐기 대상 Refresh Token ciphertext.
 * @property deviceId 폐기 요청에 사용할 기기 ID.
 */
@Serializable
data class PendingRevocation(
    val revocationOperationId: String,
    val refreshToken: EncryptedValue,
    val deviceId: String,
)

/**
 * AES-GCM ciphertext와 그 nonce.
 *
 * @property iv AES-GCM nonce. 값마다 새로 생성한다.
 * @property ciphertext 인증 tag를 포함한 암호문.
 */
@Serializable
data class EncryptedValue(
    val iv: ByteArray,
    val ciphertext: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is EncryptedValue &&
                    iv.contentEquals(other.iv) &&
                    ciphertext.contentEquals(other.ciphertext)
                )

    override fun hashCode(): Int = 31 * iv.contentHashCode() + ciphertext.contentHashCode()
}
