// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.coroutine

import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler

object GlobalNettyEventLoopGroups {
    private val nCpus = Runtime.getRuntime().availableProcessors()
    private val defaultNThreads = maxOf(4, nCpus * 2)

    val Default = MultiThreadIoEventLoopGroup(defaultNThreads, NioIoHandler.newFactory())
}
