// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.network.netty

import io.github.flowerblackg.janus.logging.Logger
import io.github.flowerblackg.janus.network.JanusSocket
import io.github.flowerblackg.janus.network.protocol.protocolDebugger
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandler
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.ssl.SslContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
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

    // These states are NOT thread-safe and must ONLY be accessed from channel.eventLoop()
    protected var readContinuation: Continuation<Int>? = null
    protected var readBuffer: ByteBuffer? = null
    protected var stashBuffer: ByteBuf? = null

    override val remoteAddress: SocketAddress?
        get() = channel?.remoteAddress()

    init {
        channel?.let { setupChannel(it) }
    }

    /**
     * For client sockets (which called connect()), this is its own event loop.
     * For server sockets, event loop is provided by parent, so socket don't need to manage it itself.
     */
    protected var workGroup: EventLoopGroup? = null

    override fun connect(remoteAddr: SocketAddress) {
        check(channel == null) { "Already connected to somewhere." }

        workGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())

        runCatching {
            val b = Bootstrap()
            b.group(workGroup)
                .channel(NioSocketChannel::class.java)
                .handler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        channel = ch
                        setupChannel(ch)
                    }
                })

            val future = b.connect(remoteAddr).sync()
            if (future.isSuccess)
                this.channel = future.channel() as SocketChannel
            else
                throw future.cause()
        }.onFailure {
            workGroup?.shutdownGracefully()
            workGroup = null
            channel = null
            throw it
        }
    }

    /**
     * Executes the block strictly on the Channel's EventLoop.
     * If strictly unnecessary (already on loop), runs immediately.
     */
    private inline fun runOnLoop(crossinline block: () -> Unit) {
        val loop = channel?.eventLoop() ?: return
        if (loop.inEventLoop()) {
            block()
        } else {
            loop.execute { block() }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    override suspend fun readSome(buffer: ByteBuffer, timeout: Duration): Int = suspendCancellableCoroutine { cont ->
        if (isClosed.load()) {
            cont.resume(-1)
            return@suspendCancellableCoroutine
        }

        val ch = channel
        if (ch == null) {
            cont.resumeWithException(IllegalStateException("Not connected"))
            return@suspendCancellableCoroutine
        }

        runOnLoop {
            if (readContinuation != null) {
                cont.resumeWithException(IllegalStateException("Read already in progress"))
                return@runOnLoop
            }

            // 1. Check stash first (safe to access here)
            val stash = stashBuffer
            if (stash != null && stash.isReadable) {
                val bytesToRead = minOf(buffer.remaining(), stash.readableBytes())
                val oldLimit = buffer.limit()
                buffer.limit(buffer.position() + bytesToRead)
                stash.readBytes(buffer) // Copy to NIO buffer
                buffer.limit(oldLimit)

                if (!stash.isReadable) {
                    stash.release()
                    stashBuffer = null
                }
                cont.resume(bytesToRead)
                return@runOnLoop
            }

            // 2. If no stash, register continuation
            readContinuation = cont
            readBuffer = buffer

            // Trigger Netty read
            ch.read()
        }

        // Cleanup on cancellation
        cont.invokeOnCancellation {
            runOnLoop {
                readContinuation = null
                readBuffer = null
            }
        }
    }

    override suspend fun writeSome(buffer: ByteBuffer): Int = suspendCancellableCoroutine { cont ->
        val ch = channel ?: run {
            cont.resumeWithException(IllegalStateException("Not connected"))
            return@suspendCancellableCoroutine
        }

        val size = buffer.remaining()
        val nettyBuf = Unpooled.wrappedBuffer(buffer)

        protocolDebugger.dump(buffer, "SEND")

        // writeAndFlush is thread-safe in Netty; it handles the thread dispatch internally.
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
        if (isClosed.compareAndSet(expectedValue = false, newValue = true)) {
            // Must release stash on the loop to avoid leaks or race conditions
            runOnLoop {
                stashBuffer?.release()
                stashBuffer = null
                readContinuation?.resume(-1)
                readContinuation = null
                readBuffer = null
            }
            channel?.close()
            workGroup?.shutdownGracefully(0, 60, TimeUnit.SECONDS)
            workGroup = null
        }
    }

    // --- ChannelHandler Methods (Always run on EventLoop) ---

    override fun channelInactive(ctx: ChannelHandlerContext?) {
        // Already on EventLoop
        readContinuation?.resume(-1) // EOF
        readContinuation = null
        readBuffer = null
        close()
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg !is ByteBuf) {
            ctx.fireChannelRead(msg)
            return
        }

        val cont = readContinuation
        val buf = readBuffer

        if (cont != null && buf != null) {
            val bytesToRead = minOf(buf.remaining(), msg.readableBytes())

            val slice = msg.readRetainedSlice(bytesToRead)
            val oldLimit = buf.limit()
            buf.limit(buf.position() + bytesToRead)
            slice.readBytes(buf)
            buf.limit(oldLimit)
            slice.release()

            if (msg.isReadable) {
                if (stashBuffer == null) {
                    stashBuffer = Unpooled.buffer()
                }
                stashBuffer!!.writeBytes(msg)
            }
            msg.release()

            readContinuation = null
            readBuffer = null

            cont.resume(bytesToRead)
        } else {
            if (stashBuffer == null) {
                stashBuffer = Unpooled.buffer()
            }
            stashBuffer!!.writeBytes(msg)
            msg.release()
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        // Already on EventLoop
        readContinuation?.resumeWithException(cause)
        readContinuation = null
        readBuffer = null
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