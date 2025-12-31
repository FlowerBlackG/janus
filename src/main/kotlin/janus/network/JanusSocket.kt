// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.network

import io.github.flowerblackg.janus.logging.Logger
import io.github.flowerblackg.janus.network.protocol.protocolDebugger
import java.io.EOFException
import java.net.SocketAddress
import java.nio.ByteBuffer
import kotlin.time.Duration

abstract class JanusSocket : AutoCloseable {
    abstract val remoteAddress: SocketAddress

    abstract fun connect(remoteAddr: SocketAddress)

    /**
     * Reads into an existing buffer.
     * Returns the number of bytes read, or -1 if EOF.
     *
     * You have to flip buffer yourself.
     */
    abstract suspend fun readSome(buffer: ByteBuffer, timeout: Duration = Duration.INFINITE): Int

    /**
     *
     * You have to flip buffer yourself.
     */
    suspend fun read(buffer: ByteBuffer, timeout: Duration = Duration.INFINITE) {
        while (buffer.hasRemaining()) {
            val bytesRead = readSome(buffer, timeout)
            if (bytesRead == -1)
                throw EOFException("Connection closed unexpectedly.")
        }
        protocolDebugger.dump(buffer, "RECV")
    }

    /**
     * Allocates a buffer of the specified capacity, reads into it, and returns the buffer ready for reading.
     * (Buffer is flipped before returning).
     */
    suspend fun read(capacity: Int, timeout: Duration = Duration.INFINITE): ByteBuffer {
        val buffer = ByteBuffer.allocate(capacity)
        val bytesRead = read(buffer, timeout)

        buffer.flip() // Prepare for the user to read FROM the buffer
        return buffer
    }

    /**
     * Writes the buffer to the socket.
     */
    abstract suspend fun writeSome(buffer: ByteBuffer): Int

    suspend fun write(buffer: ByteBuffer) {
        var bytesRemaining = buffer.remaining().toLong()
        while (bytesRemaining > 0) {
            val written = writeSome(buffer)
            if (written <= 0) {
                Logger.error("Something went wrong when writing to network socket. Written: $written")
                throw Exception("Failed to write to socket")
            }

            bytesRemaining -= written
        }
    }
}
