// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.network.protocol

import io.github.flowerblackg.janus.logging.Logger
import java.nio.ByteBuffer
import java.nio.CharBuffer


object protocolDebugger {
    val enabled = false

    fun dump(byteBuffer: ByteBuffer, prompt: String = "Network Dump") {
        if (!enabled)
            return

        val bytes = byteBuffer.duplicate()

        if (bytes.position() > 0)
            bytes.flip()

        val line = CharBuffer.allocate(16)
        var pos = 0

        Logger.debug("-".repeat(48))
        Logger.debug(prompt)

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
