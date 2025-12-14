// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.crypto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64


/**
 * AES Helper.
 *
 * Set key by only setting at least and at most one of the following parameters:
 *  - keyBytes
 *  - keyBase64
 *  - keyString
 */
class AesHelper constructor(
    val keyBytes: ByteArray? = null,
    val keyBase64: String? = null,
    val keyString: String? = null
) {
    companion object {
        const val CIPHER_ALGORITHM = "AES/CBC/PKCS5Padding"
        const val KEY_ALGORITHM = "AES"
    }

    protected val key: ByteArray
        get() {
            if (keyBytes != null) {
                return keyBytes
            } else if (keyBase64 != null) {
                return Base64.decode(keyBase64)
            } else if (keyString != null) {
                return keyString.toByteArray()
            } else {
                throw IllegalArgumentException("keyBytes, keyBase64 and keyString are all null")
            }
        }


    fun encrypt(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, KEY_ALGORITHM))
        return cipher.doFinal(bytes)
    }

    fun encrypt(plainText: String): String {
        val cipherBytes = this.encrypt(plainText.toByteArray())
        return Base64.encode(cipherBytes)
    }

    fun decrypt(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, KEY_ALGORITHM))
        return cipher.doFinal(bytes)
    }

    fun decrypt(cipherText: String): String {
        val cipherBytes = Base64.decode(cipherText)
        val plainText = this.decrypt(cipherBytes)
        return String(plainText)
    }
}
