// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.network.nio

import io.github.flowerblackg.janus.network.JanusServerSocket
import io.github.flowerblackg.janus.network.JanusSocket
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.SocketAddress
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NioServerSocket(
    protected val socketChannel: AsynchronousServerSocketChannel
) : JanusServerSocket() {

    companion object {
        fun open(): NioServerSocket {
            return AsynchronousServerSocketChannel.open().toNioServerSocket()
        }
    }

    override val localAddress: SocketAddress
        get() = socketChannel.localAddress

    override fun bind(localAddr: SocketAddress) = apply {
        socketChannel.bind(localAddr)
    }

    override suspend fun accept(): JanusSocket = suspendCancellableCoroutine { continuation ->
        this.socketChannel.accept(null, object : CompletionHandler<AsynchronousSocketChannel, Unit> {
            override fun completed(result: AsynchronousSocketChannel, attachment: Unit?) {
                if (continuation.isCancelled) {
                    runCatching { socketChannel.close() }
                    return
                }
                continuation.resume(NioSocket(result))
            }

            override fun failed(exc: Throwable?, attachment: Unit?) {
                if (!continuation.isCancelled) {
                    continuation.resumeWithException(exc ?: RuntimeException("Unknown error"))
                }
            }
        })

        continuation.invokeOnCancellation {
            runCatching { socketChannel.close() }
        }
    }

    override fun close() {
        runCatching { socketChannel.close() }
    }

}


fun AsynchronousServerSocketChannel.toNioServerSocket() = NioServerSocket(this)
