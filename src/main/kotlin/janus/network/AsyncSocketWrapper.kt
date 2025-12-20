// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.network

import io.github.flowerblackg.janus.network.protocol.protocolDebugger
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

open class AsyncSocketWrapper(val socketChannel: AsynchronousSocketChannel) : AutoCloseable {

    /**
     * Reads into an existing buffer.
     * Returns the number of bytes read, or -1 if EOF.
     *
     * You have to flip buffer yourself.
     */
    suspend fun readSome(buffer: ByteBuffer, timeout: Duration = Duration.INFINITE): Int = suspendCancellableCoroutine { continuation ->
        val handler = object : CompletionHandler<Int, Nothing?> {
            override fun completed(result: Int, attachment: Nothing?) {
                continuation.resume(result)
            }
            override fun failed(exc: Throwable, attachment: Nothing?) = continuation.resumeWithException(exc)
        }

        if (timeout.isInfinite()) {
            socketChannel.read(buffer, null, handler)
        } else {
            socketChannel.read(buffer, timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS, null, handler)
        }
    }

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
    suspend fun write(buffer: ByteBuffer): Int = suspendCancellableCoroutine { continuation ->
        protocolDebugger.dump(buffer, "SEND")
        socketChannel.write(buffer, null, object : CompletionHandler<Int, Nothing?> {
            override fun completed(result: Int, attachment: Nothing?) = continuation.resume(result)
            override fun failed(exc: Throwable, attachment: Nothing?) = continuation.resumeWithException(exc)
        })
    }


    override fun close() {
        runCatching { socketChannel.close() }
    }

    fun isOpen(): Boolean = socketChannel.isOpen
}