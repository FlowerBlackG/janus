// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janice.network

import java.net.SocketAddress

abstract class JaniceServerSocket : AutoCloseable {
    abstract val localAddress: SocketAddress?

    /**
     *
     * @return self.
     */
    abstract fun bind(localAddr: SocketAddress): JaniceServerSocket
    abstract suspend fun accept(): JaniceSocket
}
