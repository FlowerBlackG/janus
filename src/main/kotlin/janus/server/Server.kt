// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.server

import io.github.flowerblackg.janus.config.Config
import io.github.flowerblackg.janus.config.ConnectionMode
import io.github.flowerblackg.janus.coroutine.GlobalCoroutineScopes
import io.github.flowerblackg.janus.logging.Logger
import io.github.flowerblackg.janus.network.AsyncServerSocketWrapper
import io.github.flowerblackg.janus.network.protocol.JanusProtocolConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousServerSocketChannel


private fun serve(
    conn: JanusProtocolConnection,
    config: Config,
    permits: Map<Config.WorkspaceConfig, Mutex>
): Job {
    val lounge = Lounge(conn, config)

    var ws: Config.WorkspaceConfig? = null

    return GlobalCoroutineScopes.IO.launch {
        val resCode = lounge.serve { workspace ->
            val locked = permits[workspace]?.tryLock() ?: false
            if (locked)
                ws = workspace
            else
                Logger.error("Failed to lock workspace ${workspace.name}. Maybe another connection is using it.")
            locked
        }

        if (ws != null)
            permits[ws]?.unlock()

        if (resCode != 0)
            Logger.error("Lounge closed with code $resCode")
        else
            Logger.info("Lounge closed with code $resCode")
    }
}


suspend fun runServer(config: Config): Int {
    Logger.info("Running in server mode")

    val serverChannel = try {
        AsynchronousServerSocketChannel.open().bind(InetSocketAddress(config.port!!))
    } catch (e: Exception) {
        Logger.error("Failed to start server: ${e.message}")
        return 1
    }

    val serverWrapper = AsyncServerSocketWrapper(serverChannel)

    Logger.info("Server started on port ${config.port}")

    val workspacePermits = config.workspaces
        .filterKeys { it.first == ConnectionMode.SERVER }
        .values.
        associateWith { Mutex() }

    val tasks = mutableListOf<Job>()

    var running = true
    while (running) {
        val conn = JanusProtocolConnection(serverWrapper.accept())
        tasks.add(serve(conn, config, workspacePermits))
    }

    tasks.joinAll()

    serverWrapper.close()
    Logger.info("Server closed. Bye~~")
    return 0
}
