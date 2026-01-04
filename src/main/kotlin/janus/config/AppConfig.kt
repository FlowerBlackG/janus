// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.config

import io.github.flowerblackg.janus.crypto.AesHelper
import io.github.flowerblackg.janus.logging.Logger
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import kotlin.io.encoding.Base64

// --- Data Classes ---

/**
 * Loaded from user written json file.
 */
@Serializable
data class AppConfig(
    val mode: ConnectionMode,
    val port: Int? = null,
    val host: String? = null,
    val filter: FilterConfig? = null,
    val secret: SecretConfig? = null,
    val ssl: SslConfig? = null,
    val workspaces: List<WorkspaceConfig> = emptyList()
) {
    companion object {
        @ExperimentalSerializationApi
        private var jsonSerializer: Json? = null
            get() {
                if (field != null)
                    return field

                field = Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    explicitNulls = false
                    coerceInputValues = true
                    allowComments = true
                    allowTrailingComma = true
                }

                return field
            }


        fun parse(jsonString: String): AppConfig {
            try {
                return jsonSerializer!!.decodeFromString<AppConfig>(jsonString)
            } catch (e: Exception) {
                throw IllegalArgumentException("Failed to parse AppConfig: ${e.message}")
            }
        }

        fun parse(jsonObject: JSONObject): AppConfig = parse(jsonObject.toString())
    }


    @Serializable
    data class FilterConfig(
        val override: Boolean? = null,
        val ignore: List<String> = emptyList(),
        val protect: List<String> = emptyList(),
    )

    @Serializable
    data class WorkspaceConfig(
        val name: String,
        val role: ConnectionMode,
        val path: String,
        val secret: SecretConfig? = null,
        val filter: FilterConfig? = null,
        val host: String? = null,
        val port: Int? = null,
        val ssl: SslConfig? = null
    )

    @Serializable
    data class SecretConfig(
        val type: SecretType,
        val value: String
    ) {
        /**
         * @return null if something went wrong.
         */
        fun toCryptoConfig(): Config.CryptoConfig? {
            val cryptoConfig = Config.CryptoConfig(aes = null)
            if (value.isBlank()) {
                return cryptoConfig
            }

            var keyBytes: ByteArray? = null

            if (type == SecretType.STRING) {
                keyBytes = value.toByteArray()
            } else if (type == SecretType.BASE64) {
                keyBytes = runCatching { Base64.decode(value) }.exceptionOrNull()?.let { e ->
                    Logger.error("Failed to decode Base64 string: ${e.message}", trace = e)
                    return null
                }
            } else if (type == SecretType.FILE_BASE64 || type == SecretType.FILE_STRING) {
                val fileContent = File(value).readText().trim()
                if (fileContent.isBlank()) {
                    Logger.error("Failed to read file: $value")
                    return null
                }

                keyBytes = if (type == SecretType.FILE_BASE64) {
                    runCatching { Base64.decode(fileContent) }.exceptionOrNull()?.let { e ->
                        Logger.error("Failed to decode file: $value to Base64: ${e.message}", trace = e)
                        return null
                    }
                } else { // type is FILE_STRING
                    fileContent.toByteArray()
                }
            }


            if (keyBytes?.isNotEmpty() == true) {
                cryptoConfig.aes = AesHelper(keyBytes = keyBytes)
                return cryptoConfig
            }

            Logger.error("Unknown secret type: $type")
            return null
        }
    }


    @Serializable
    data class SslConfig(
        val key: String,
        val cert: String
    )


// --- Enums with Custom Serializers ---

    /**
     * Serializer that converts input to lowercase before matching the Enum.
     * This allows "server", "Server", "SERVER" -> ConnectionMode.SERVER
     */
    open class CaseInsensitiveEnumSerializer<T : Enum<T>>(
        private val enumClass: Class<T>,
        private val values: Array<T>
    ) : KSerializer<T> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor(enumClass.simpleName, PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: T) {
            // Serialize as lowercase by default for consistency
            encoder.encodeString(value.name.lowercase())
        }

        override fun deserialize(decoder: Decoder): T {
            val input = decoder.decodeString()
            return values.firstOrNull { it.name.equals(input, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown value '$input' for enum ${enumClass.simpleName}. Expected one of: ${values.map { it.name.lowercase() }}")
        }
    }

    // Secret Type
    @Serializable(with = SecretTypeSerializer::class)
    enum class SecretType {
        STRING, BASE64, FILE_STRING, FILE_BASE64;

        // Helper to handle "file-string" (JSON) vs "FILE_STRING" (Enum) mismatch if needed,
        // though usually standardizing on underscores or hyphens is better.
        // Below assumes user inputs "file-string" and we map it manually if simple case-insensitivity isn't enough.
        // However, given your spec, "file-string" -> FILE_STRING requires replacing '-' with '_' or mapped lookup.

        companion object {
            fun fromString(value: String): SecretType {
                val normalized = value.trim().replace("-", "_")
                return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
                    ?: throw IllegalArgumentException("Unknown SecretType: $value")
            }
        }
    }

    // Special serializer for SecretType to handle the hyphens (file-string -> FILE_STRING)
    object SecretTypeSerializer : KSerializer<SecretType> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SecretType", PrimitiveKind.STRING)
        override fun serialize(encoder: Encoder, value: SecretType) {
            // output back as hyphenated lowercase: FILE_STRING -> "file-string"
            encoder.encodeString(value.name.lowercase().replace("_", "-"))
        }

        override fun deserialize(decoder: Decoder): SecretType {
            return SecretType.fromString(decoder.decodeString())
        }
    }
}
