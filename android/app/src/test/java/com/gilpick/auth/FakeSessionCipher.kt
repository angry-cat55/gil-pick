package com.gilpick.auth

/**
 * JVM unit test용 [SessionCipher].
 *
 * AndroidKeyStore를 쓸 수 없으므로 가역 변환으로 대체한다. 평문이 그대로 저장되지
 * 않는지 검사할 수 있도록 원문과 다른 byte를 만든다.
 *
 * @property invalidated `true`면 실제 key 무효화처럼 복호화가 실패한다.
 */
class FakeSessionCipher(var invalidated: Boolean = false) : SessionCipher {

    override fun encrypt(plaintext: String): EncryptedValue {
        val bytes = plaintext.toByteArray(Charsets.UTF_8)
        return EncryptedValue(
            iv = byteArrayOf(MASK),
            ciphertext = ByteArray(bytes.size) { (bytes[it].toInt() xor MASK.toInt()).toByte() },
        )
    }

    override fun decrypt(value: EncryptedValue): String {
        if (invalidated) throw SessionCipherUnavailableException()
        val bytes = value.ciphertext
        return String(
            ByteArray(bytes.size) { (bytes[it].toInt() xor MASK.toInt()).toByte() },
            Charsets.UTF_8,
        )
    }

    private companion object {
        const val MASK: Byte = 0x5A
    }
}
