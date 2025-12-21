// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.client

import io.github.flowerblackg.janus.config.Config
import io.github.flowerblackg.janus.filesystem.FileType
import io.github.flowerblackg.janus.filesystem.SyncPlan
import io.github.flowerblackg.janus.filesystem.globFilesRelative
import io.github.flowerblackg.janus.logging.Logger
import io.github.flowerblackg.janus.network.protocol.JanusProtocolConnection
import io.github.flowerblackg.janus.network.protocol.JanusProtocolConnection.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousSocketChannel
import kotlin.time.measureTime


private suspend fun uploadFiles(conn: JanusProtocolConnection, workspace: Config.WorkspaceConfig, plan: SyncPlan) = withContext(Dispatchers.IO) {
    var pending = Pair(mutableListOf(plan), mutableListOf<SyncPlan>())

    while (pending.first.isNotEmpty()) {
        for (plan in pending.first) {
            pending.second += plan.children

            if (plan.fileType != FileType.FILE || plan.action != SyncPlan.SyncAction.UPLOAD)
                continue

            conn.uploadFile(filePath = plan.path, workspace = workspace)
        }

        pending.first.clear()
        pending = Pair(pending.second, pending.first)
    }
}


suspend fun runClient(config: Config): Int = withContext(Dispatchers.IO) {
    Logger.info("Running in client mode")

    val workspace = config.workspaces.values.first()
    val channel = AsynchronousSocketChannel.open()
    channel.connect(InetSocketAddress(workspace.host!!, workspace.port!!)).get()

    JanusProtocolConnection(channel).use { conn ->
        conn.hello(Role.CLIENT)
        conn.auth(workspace = workspace)
        val localTimeMillis = System.currentTimeMillis()
        val serverTimeMillis = conn.getSystemTimeMillis()
        val networkRoundtripMillis = System.currentTimeMillis() - localTimeMillis
        val serverFileTreePromise = async { conn.fetchFileTree(workspace.path) }
        val localFileTreePromise = async { workspace.path.globFilesRelative(ignoreConfig = workspace.ignore) }

        val svrToLocalTimeDiffMillis = serverTimeMillis - localTimeMillis - networkRoundtripMillis / 2

        Logger.info("Time difference between remote and local: ${svrToLocalTimeDiffMillis} ms")

        val (serverFileTree, localFileTree) = Pair(serverFileTreePromise.await(), localFileTreePromise.await())

        val syncPlans: List<SyncPlan>
        val buildSyncPlanCost = measureTime {
            syncPlans = SyncPlan.build(
                local = localFileTree,
                remote = serverFileTree,
                remoteLocalTimeDiffMillis = svrToLocalTimeDiffMillis
            ).toList()
        }

        Logger.info("Sync plan built in ${buildSyncPlanCost.inWholeMilliseconds}ms.")
        conn.commitSyncPlan(syncPlans)
        Logger.info("Plan committed. Ready to send files.")
        syncPlans.forEach { uploadFiles(conn = conn, workspace = workspace, plan = it) }
        Logger.success("Sync complete.")
    }

    return@withContext 0
}
