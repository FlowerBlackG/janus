// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janice.server

import io.github.flowerblackg.janice.config.Config
import io.github.flowerblackg.janice.config.ConnectionMode
import io.github.flowerblackg.janice.coroutine.GlobalCoroutineScopes
import io.github.flowerblackg.janice.logging.Logger
import io.github.flowerblackg.janice.network.netty.NettyServerSocket
import io.github.flowerblackg.janice.network.protocol.JaniceProtocolConnection
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.net.InetSocketAddress


private fun serve(
    conn: JaniceProtocolConnection,
    config: Config,
    permits: Map<Config.WorkspaceConfig, Mutex>
): Job {
    val lounge = Lounge(conn, config)

    var ws: Config.WorkspaceConfig? = null

    return GlobalCoroutineScopes.IO.launch {
        lounge.onAuthorized = { workspace ->
            val locked = permits[workspace]?.tryLock() ?: false
            if (locked)
                ws = workspace
            else
                Logger.error("Failed to lock workspace ${workspace.name}. Maybe another connection is using it.")
            locked
        }

        val resCode = lounge.use {
            it.serve()
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

    val serverSock = try {
        val localAddr = InetSocketAddress(config.port!!)
        NettyServerSocket(config.ssl.serverContext).bind(localAddr)
    } catch (e: Exception) {
        Logger.error("Failed to start server: ${e.message}")
        return 1
    }

    Logger.info("Server started on port ${config.port}")

    val workspacePermits = config.workspaces.values.associateWith { Mutex() }

    val tasks = mutableListOf<Job>()

    var running = true
    while (running) {
        val conn = JaniceProtocolConnection(serverSock.accept())
        tasks.add(serve(conn, config, workspacePermits))
    }

    tasks.joinAll()
    Logger.info("Server closed. Bye~~")
    return 0
}
