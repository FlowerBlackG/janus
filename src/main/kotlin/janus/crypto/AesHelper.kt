// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.crypto

import io.github.flowerblackg.janus.logging.Logger
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.random.Random


/**
 * AES Helper.
 *
 * Anything encoded by AesHelper should be decoded by AesHelper too.
 *
 * Instances can be reused.
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
        const val IV_SIZE = 16
    }

    protected var key: ByteArray = byteArrayOf()
        get() {
            return field
        }
        set(value) {  // Must be set during construction.
            if (field.isNotEmpty())
                throw IllegalStateException("Key can only be set once.")
            field = value
        }


    private fun initKey() {
        val keyBytesRaw = when {
            keyBytes != null -> keyBytes
            keyBase64 != null -> Base64.decode(keyBase64)
            keyString != null -> keyString.toByteArray()
            else -> throw IllegalArgumentException("At least one type of key should be provided.")
        }

        if (keyBytesRaw.isEmpty())
            throw IllegalArgumentException("Key is empty.")

        if (keyBytesRaw.size == 16 || keyBytesRaw.size == 24 || keyBytesRaw.size == 32) {
            key = keyBytesRaw
            return
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val digestBytes = digest.digest(keyBytesRaw)
        key = digestBytes
        Logger.warn("Key size is ${keyBytesRaw.size}, which is not 16, 24 or 32.")
        Logger.warn("SHA-256 will be used on your key. This compromises security.")
    }

    init {
        initKey()
    }


    fun encrypt(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)

        val iv = Random.nextBytes(IV_SIZE)
        val ivSpec = IvParameterSpec(iv)

        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, KEY_ALGORITHM), ivSpec)
        return iv + cipher.doFinal(bytes)
    }

    fun encrypt(plainText: String): String {
        val encryptedBytes = this.encrypt(plainText.toByteArray())
        return Base64.encode(encryptedBytes)
    }

    fun decrypt(bytes: ByteArray): ByteArray {
        if (bytes.size < IV_SIZE) {
            throw IllegalArgumentException("bytes is too short. missing IV.")
        }

        val iv = bytes.sliceArray(0 until IV_SIZE)
        val ivSpec = IvParameterSpec(iv)

        val encryptedContent = bytes.sliceArray(IV_SIZE until bytes.size)

        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, KEY_ALGORITHM), ivSpec)
        return cipher.doFinal(encryptedContent)
    }

    fun decrypt(encryptedBase64: String): String {
        val encryptedBytes = Base64.decode(encryptedBase64)
        val plainText = this.decrypt(encryptedBytes)
        return String(plainText)
    }
}
