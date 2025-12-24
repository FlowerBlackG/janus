// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.network.protocol

import io.github.flowerblackg.janus.logging.Logger
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.declaredMemberProperties


object protocolDebugger {
    val enabled = false
    val noHex = true

    fun dump(byteBuffer: ByteBuffer, prompt: String = "Network Dump") {
        if (!enabled)
            return

        val bytes = byteBuffer.duplicate()

        if (bytes.position() > 0)
            bytes.flip()

        val line = CharBuffer.allocate(16)
        var pos = 0

        Logger.debug("-".repeat(48))
        val header = bytes.tryDecodeHeader()
        var fullPrompt = prompt
        header?.let {
            fullPrompt += " - $it"
        }
        Logger.debug(fullPrompt)

        if (noHex)
            return

        fun printLine() {
            line.flip()
            var msg = ""
            msg += "0x${pos.toHexString()} "

            for (i in 0 until 16) {
                if (line.hasRemaining())
                    msg += line.get().code.toHexString().takeLast(2) + " "
                else
                    msg += "   "
                if (i == 7) {
                    if (line.hasRemaining())
                        msg += "- "
                    else
                        msg += "  "
                }
            }

            msg += " "
            line.flip()
            for (i in 0 until 16) {
                if (line.hasRemaining()) {
                    val ch = line.get()
                    if (ch.code in 0x20..0x7E)
                        msg += ch
                    else
                        msg += "."
                } else
                    msg += " "
            }

            line.flip()

            pos += 16
            Logger.debug(msg)
        }

        while (bytes.hasRemaining()) {
            line.put(bytes.get().toInt().toChar())
            if (!line.hasRemaining())
                printLine()
        }

        if (line.position() > 0)
            printLine()
    }
}


private fun ByteBuffer.tryDecodeHeader(): String? {
    val b = this.duplicate()
    if (b.remaining() < JanusMessage.HEADER_LENGTH)
        return null
    val magicBytes = ByteArray(JanusMessage.MAGIC_STRING.length)
    b.get(magicBytes)
    val magic = String(magicBytes, StandardCharsets.US_ASCII)
    if (magic != JanusMessage.MAGIC_STRING)
        return null

    val type = b.getInt()
    val bodyLength = b.getLong()

    var typeStr = "Unknown"

    for (kClass in JanusMessage::class.sealedSubclasses) {
        val companion = kClass.companionObject
        val companionInstance = kClass.companionObjectInstance

        if (companion == null || companionInstance == null)
            continue

        val typeCodeProp = companion.declaredMemberProperties.find { it.name == JanusMessage::typeCode.name }
        typeCodeProp ?: continue

        val typeCode = typeCodeProp.getter.call(companionInstance) as? Int ?: continue

        if (typeCode == type) {
            typeStr = kClass.simpleName ?: "Unknown"
            break
        }
    }

    return "$typeStr ($bodyLength : 0x${bodyLength.toHexString().takeLast(8)})"
}

