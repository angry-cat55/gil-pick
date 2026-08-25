package com.gilpick.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 장기 secret을 암·복호화하는 경계.
 *
 * 실제 구현은 AndroidKeyStore의 non-exportable key를 사용하지만 JVM unit test에서는
 * KeyStore를 쓸 수 없으므로 이 interface로 교체한다.
 */
interface SessionCipher {
    /** [plaintext]를 새 nonce로 암호화한다. */
    fun encrypt(plaintext: String): EncryptedValue

    /**
     * [value]를 복호화한다.
     *
     * @throws SessionCipherUnavailableException key가 무효화되어 복호화할 수 없을 때.
     */
    fun decrypt(value: EncryptedValue): String
}

/**
 * AndroidKeyStore key 손실·무효화로 기존 암호문을 복호화할 수 없음을 알린다.
 *
 * 이 경우 읽을 수 없는 local data를 제거하고 `SignedOut`으로 전환한다.
 */
class SessionCipherUnavailableException(cause: Throwable? = null) :
    Exception("AndroidKeyStore key를 사용할 수 없습니다", cause)

/**
 * AndroidKeyStore의 non-exportable AES key와 AES-GCM으로 Token을 보호한다.
 *
 * key는 최초 사용 시 생성하고 이후 재사용한다. key material은 앱 process로 나오지 않으며
 * 암호문·nonce만 `auth_session.pb`에 저장한다.
 */
class KeystoreSessionCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SessionCipher {

    override fun encrypt(plaintext: String): EncryptedValue {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
        return EncryptedValue(
            iv = cipher.iv,
            ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)),
        )
    }

    override fun decrypt(value: EncryptedValue): String =
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                loadOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, value.iv),
            )
            String(cipher.doFinal(value.ciphertext), Charsets.UTF_8)
        } catch (e: java.security.GeneralSecurityException) {
            // key 무효화·교체 후에는 기존 암호문을 복원할 수 없다.
            throw SessionCipherUnavailableException(e)
        }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS = "gilpick.auth.session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val KEY_SIZE_BITS = 256
    }
}
