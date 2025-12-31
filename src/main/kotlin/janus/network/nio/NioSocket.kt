// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.network.nio

import io.github.flowerblackg.janus.network.JanusSocket
import io.github.flowerblackg.janus.network.protocol.protocolDebugger
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

class NioSocket(
    protected val socketChannel: AsynchronousSocketChannel
) : JanusSocket() {
    companion object {
        fun open(): NioSocket {
            return AsynchronousSocketChannel.open().toNioSocket()
        }
    }

    override val remoteAddress: SocketAddress
        get() = socketChannel.remoteAddress

    override fun connect(remoteAddr: SocketAddress) {
        socketChannel.connect(remoteAddr).get()
    }

    override suspend fun readSome(
        buffer: ByteBuffer,
        timeout: Duration
    ): Int = suspendCancellableCoroutine { continuation ->
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

    override suspend fun writeSome(buffer: ByteBuffer): Int = suspendCancellableCoroutine { continuation ->
        protocolDebugger.dump(buffer, "SEND")
        socketChannel.write(buffer, null, object : CompletionHandler<Int, Nothing?> {
            override fun completed(result: Int, attachment: Nothing?) = continuation.resume(result)
            override fun failed(exc: Throwable, attachment: Nothing?) = continuation.resumeWithException(exc)
        })
    }

    override fun close() {
        runCatching { socketChannel.close() }
    }
}


fun AsynchronousSocketChannel.toNioSocket() = NioSocket(this)

