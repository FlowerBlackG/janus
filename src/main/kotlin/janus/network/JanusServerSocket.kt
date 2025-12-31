// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.network

import java.net.SocketAddress

abstract class JanusServerSocket : AutoCloseable {
    abstract val localAddress: SocketAddress

    /**
     *
     * @return self.
     */
    abstract fun bind(localAddr: SocketAddress): JanusServerSocket
    abstract suspend fun accept(): JanusSocket
}
