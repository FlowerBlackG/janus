// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.network

import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AsyncServerSocketWrapper(val socketChannel: AsynchronousServerSocketChannel) : AutoCloseable {

    suspend fun accept(): AsynchronousSocketChannel = suspendCancellableCoroutine { continuation ->
        this.socketChannel.accept(null, object : CompletionHandler<AsynchronousSocketChannel, Unit> {
            override fun completed(result: AsynchronousSocketChannel, attachment: Unit?) {
                if (continuation.isCancelled) {
                    runCatching { socketChannel.close() }
                    return
                }
                continuation.resume(result)
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
