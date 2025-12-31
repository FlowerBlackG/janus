// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.network.netty

import io.github.flowerblackg.janus.network.JanusSocket
import io.github.flowerblackg.janus.network.protocol.protocolDebugger
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandler
import io.netty.channel.ChannelInitializer
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.ssl.SslContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.SocketAddress
import java.nio.ByteBuffer
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

class NettySocket(
    protected var channel: SocketChannel? = null,
    protected val sslContext: SslContext? = null
) : JanusSocket(), ChannelInboundHandler {

    // Mostly implemented by Google Gemini 3.0 Pro.


    @OptIn(ExperimentalAtomicApi::class)
    protected val isClosed = AtomicBoolean(false)

    protected var readContinuation: Continuation<Int>? = null
    protected var readBuffer: ByteBuffer? = null
    protected var stashBuffer: ByteBuf? = null

    override val remoteAddress: SocketAddress?
        get() = channel?.remoteAddress()

    init {
        channel?.let { setupChannel(it) }
    }

    override fun connect(remoteAddr: SocketAddress) {
        check(channel == null) { "Already connected to somewhere." }

        val group = MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())
        val b = Bootstrap()
        b.group(group)
            .channel(NioSocketChannel::class.java)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    channel = ch
                    setupChannel(ch)
                }
            })

        val future = b.connect(remoteAddr).sync()
        if (!future.isSuccess)
            throw future.cause()
    }

    @OptIn(ExperimentalAtomicApi::class)
    override suspend fun readSome(buffer: ByteBuffer, timeout: Duration): Int {
        if (isClosed.load())
            return -1

        // 1. Check stash first
        val stash = stashBuffer
        if (stash != null && stash.isReadable) {
            val bytesToRead = minOf(buffer.remaining(), stash.readableBytes())
            stash.readBytes(buffer) // Copy to NIO buffer

            if (!stash.isReadable) {
                stash.release()
                stashBuffer = null
            }
            return bytesToRead
        }

        // 2. If no stash, request from Netty
        return suspendCancellableCoroutine { cont ->
            synchronized(this) {
                if (readContinuation != null) {
                    cont.resumeWithException(IllegalStateException("Read already in progress"))
                    return@suspendCancellableCoroutine
                }
                readContinuation = cont
                readBuffer = buffer
            }

            // Trigger Netty to read data from OS
            channel?.read()

            // Handle timeout cancellation if needed
            cont.invokeOnCancellation {
                synchronized(this) {
                    readContinuation = null
                    readBuffer = null
                }
            }
        }
    }

    override suspend fun writeSome(buffer: ByteBuffer): Int = suspendCancellableCoroutine { cont ->
        val ch = channel ?: run {
            cont.resumeWithException(IllegalStateException("Not connected"))
            return@suspendCancellableCoroutine
        }

        val size = buffer.remaining()
        // Copy NIO buffer to Netty ByteBuf
        val nettyBuf = Unpooled.wrappedBuffer(buffer)

        protocolDebugger.dump(buffer, "SEND")

        ch.writeAndFlush(nettyBuf).addListener { future ->
            if (future.isSuccess) {
                cont.resume(size)
            } else {
                cont.resumeWithException(future.cause())
            }
        }
    }


    protected fun setupChannel(ch: SocketChannel) {
        ch.config().isAutoRead = false
        val p = ch.pipeline()
        if (sslContext != null)
            p.addFirst("ssl", sslContext.newHandler(ch.alloc()))
        p.addLast("janusHandler", this)
    }

    @OptIn(ExperimentalAtomicApi::class)
    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            stashBuffer?.release()
            stashBuffer = null
            channel?.close()
            synchronized(this) {
                readContinuation?.resume(-1)
                readContinuation = null
                readBuffer = null
            }
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext?) {
        synchronized(this) {
            readContinuation?.resume(-1) // EOF
            readContinuation = null
            readBuffer = null
        }
        close()
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg !is ByteBuf) {
            ctx.fireChannelRead(msg)
            return
        }

        synchronized(this) {
            val cont = readContinuation
            val buf = readBuffer

            if (cont != null && buf != null) {
                val bytesToRead = minOf(buf.remaining(), msg.readableBytes())

                // Copy data directly to the user's ByteBuffer
                val slice = msg.readRetainedSlice(bytesToRead)
                slice.readBytes(buf) // Writes into NIO buffer
                slice.release()

                // If there are leftovers, stash them
                if (msg.isReadable) {
                    if (stashBuffer == null) {
                        stashBuffer = Unpooled.buffer()
                    }
                    stashBuffer!!.writeBytes(msg)
                }
                msg.release()

                // Clear state
                readContinuation = null
                readBuffer = null

                // Resume coroutine
                cont.resume(bytesToRead)
            } else {
                // Received data but no one is reading?
                // Because we set AutoRead=false, this shouldn't happen often. We stash it.
                if (stashBuffer == null) {
                    stashBuffer = Unpooled.buffer()
                }
                stashBuffer!!.writeBytes(msg)
                msg.release()
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        synchronized(this) {
            readContinuation?.resumeWithException(cause)
            readContinuation = null
            readBuffer = null
        }
        close()
    }

    override fun handlerAdded(ctx: ChannelHandlerContext?) {}
    override fun handlerRemoved(ctx: ChannelHandlerContext?) {}
    override fun channelRegistered(ctx: ChannelHandlerContext?) {}
    override fun channelUnregistered(ctx: ChannelHandlerContext?) {}
    override fun channelActive(ctx: ChannelHandlerContext?) {}
    override fun channelReadComplete(ctx: ChannelHandlerContext?) {}
    override fun userEventTriggered(ctx: ChannelHandlerContext?, evt: Any?) {}
    override fun channelWritabilityChanged(ctx: ChannelHandlerContext?) {}
}
