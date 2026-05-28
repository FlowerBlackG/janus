// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janice.network.nio

import io.github.flowerblackg.janice.network.JaniceServerSocket
import io.github.flowerblackg.janice.network.JaniceSocket
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.SocketAddress
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NioServerSocket(
    protected val socketChannel: AsynchronousServerSocketChannel
) : JaniceServerSocket() {

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

    override suspend fun accept(): JaniceSocket = suspendCancellableCoroutine { continuation ->
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
