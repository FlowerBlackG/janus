// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.coroutine

import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher

object GlobalCoroutineScopes {
    private val nCpus = Runtime.getRuntime().availableProcessors()
    private val nThreads = maxOf(4, nCpus * 2)
    val nettyEventLoopGroup = MultiThreadIoEventLoopGroup(nThreads, NioIoHandler.newFactory())

    val nettyCoroutineDispatcher = nettyEventLoopGroup.asCoroutineDispatcher()

    val IO: CoroutineScope = CoroutineScope(nettyCoroutineDispatcher)
    val Default: CoroutineScope = CoroutineScope(nettyCoroutineDispatcher)
}
