// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.network.netty

import io.github.flowerblackg.janus.coroutine.GlobalCoroutineScopes
import io.github.flowerblackg.janus.network.JanusServerSocket
import io.github.flowerblackg.janus.network.JanusSocket
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.Channel as NettyChannel
import io.netty.handler.ssl.SslContext
import kotlinx.coroutines.channels.Channel as KChannel
import java.net.SocketAddress

class NettyServerSocket(
    protected val sslContext: SslContext? = null
) : JanusServerSocket() {

    protected var serverChannel: NettyChannel? = null

    protected val acceptedQueue = KChannel<JanusSocket>(KChannel.UNLIMITED)

    override val localAddress: SocketAddress?
        get() = serverChannel?.localAddress()

    override fun bind(localAddr: SocketAddress): JanusServerSocket {
        val b = ServerBootstrap()
        b.group(GlobalCoroutineScopes.nettyEventLoopGroup, GlobalCoroutineScopes.nettyEventLoopGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    val sock = NettySocket(ch, sslContext)
                    if (acceptedQueue.trySend(sock).isFailure)
                        ch.close()
                }
            })
            .childOption(ChannelOption.SO_KEEPALIVE, true)

        val f = b.bind(localAddr).sync()
        serverChannel = f.channel()
        return this
    }

    override suspend fun accept(): JanusSocket {
        return acceptedQueue.receive()
    }

    override fun close() {
        acceptedQueue.close()
        serverChannel?.close()
    }
}
