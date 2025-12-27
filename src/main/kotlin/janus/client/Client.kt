// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.client

import io.github.flowerblackg.janus.config.Config
import io.github.flowerblackg.janus.coroutine.GlobalCoroutineScopes
import io.github.flowerblackg.janus.filesystem.FileType
import io.github.flowerblackg.janus.filesystem.MemoryMappedFile
import io.github.flowerblackg.janus.filesystem.SyncPlan
import io.github.flowerblackg.janus.filesystem.getPermissionMask
import io.github.flowerblackg.janus.filesystem.globFilesRelative
import io.github.flowerblackg.janus.logging.Logger
import io.github.flowerblackg.janus.network.protocol.JanusProtocolConnection
import io.github.flowerblackg.janus.network.protocol.JanusProtocolConnection.Role
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.FileChannel
import kotlin.time.measureTime


private const val SMALL_FILE_SIZE_THRESHOLD = 256 * 1024
private const val SMALL_FILES_HOLDER_ARCHIVE_SIZE_THRESHOLD = 128 * 1024 * 1024
private const val SMALL_FILES_HOLDER_N_FILES_THRESHOLD = 1024  // NEVER SET THIS TO 0 OR LOWER.


private data class SmallFilesHolder(
    val ws: Config.WorkspaceConfig,
    val files: MutableList<SyncPlan> = mutableListOf(),
    var archiveSize: Long = 0L
) {
    /**
     * @param file You should ensure it is file and needs UPLOAD.
     */
    fun add(file: SyncPlan) {
        files += file
        archiveSize += file.fileSize
        archiveSize += file.path.toString().replace('\\', '/').toByteArray().size
        archiveSize += Long.SIZE_BYTES + Long.SIZE_BYTES
    }

    operator fun plusAssign(file: SyncPlan) = add(file)

    fun toByteBuffer(clearSelf: Boolean = true): ByteBuffer {

        val buf = ByteBuffer.allocate(archiveSize.toInt())

        for (file in files) {
            val realPath = ws.path.resolve(file.path)
            val pathBytes = file.path.toString().replace('\\', '/').toByteArray()
            buf.putInt(pathBytes.size)
                .putInt(realPath.getPermissionMask())
                .putLong(file.fileSize)
                .put(pathBytes)

            MemoryMappedFile.openAndMap(path = realPath, mode = FileChannel.MapMode.READ_ONLY).use { mmf ->
                mmf.read(buffer = buf, size = file.fileSize.toInt())
            }
        }

        if (clearSelf) {
            files.clear()
            archiveSize = 0L
        }

        return buf.flip()
    }

    fun isNearlyFull(): Boolean {
        val archiveLargeEnough = archiveSize >= SMALL_FILES_HOLDER_ARCHIVE_SIZE_THRESHOLD
        val nFilesEnough = files.size >= SMALL_FILES_HOLDER_N_FILES_THRESHOLD
        return archiveLargeEnough || nFilesEnough
    }
}


private fun sumFilesSize(plan: SyncPlan): Long {
    if (plan.fileType == FileType.DIRECTORY) {
        return plan.children.map { sumFilesSize(it) }.sum()
    }
    else if (plan.fileType == FileType.FILE) {
        return plan.fileSize
    }
    return 0L
}


private fun sumFilesSize(plans: Collection<SyncPlan>): Long {
    return plans.map { sumFilesSize(it) }.sum()
}


private suspend fun uploadArchive(ws: Config.WorkspaceConfig, conn: JanusProtocolConnection, byteBuf: ByteBuffer) {
    conn.uploadArchive(ws, byteBuf)
}


private suspend fun uploadFiles(conn: JanusProtocolConnection, workspace: Config.WorkspaceConfig, plan: SyncPlan) {
    var pending = Pair(mutableListOf(plan), mutableListOf<SyncPlan>())

    var smallFilesHolder = SmallFilesHolder(workspace)
    val archiveJobs = ArrayList<Deferred<ByteBuffer>>()
    val archiveJobsTmpHolder = ArrayList<Deferred<ByteBuffer>>()


    while (pending.first.isNotEmpty()) {
        for (plan in pending.first) {
            pending.second += plan.children

            for (job in archiveJobs)  {
                if (job.isActive) {
                    archiveJobsTmpHolder += job
                    continue
                }

                uploadArchive(workspace, conn, job.await())
            }
            archiveJobs.clear()
            archiveJobs.addAll(archiveJobsTmpHolder)
            archiveJobsTmpHolder.clear()

            if (smallFilesHolder.isNearlyFull()) {
                val oldHolder = smallFilesHolder
                archiveJobs += GlobalCoroutineScopes.IO.async { oldHolder.toByteBuffer() }
                smallFilesHolder = SmallFilesHolder(workspace)
            }


            if (plan.fileType != FileType.FILE || plan.action != SyncPlan.SyncAction.UPLOAD)
                continue

            if (plan.fileSize <= SMALL_FILE_SIZE_THRESHOLD) {
                smallFilesHolder += plan
                continue
            }

            conn.uploadFile(filePath = plan.path, workspace = workspace)
        }

        pending.first.clear()
        pending = Pair(pending.second, pending.first)
    }

    if (smallFilesHolder.files.isNotEmpty())
        archiveJobs += GlobalCoroutineScopes.IO.async { smallFilesHolder.toByteBuffer() }
    for (byteBuff in archiveJobs.awaitAll())
        uploadArchive(workspace, conn, byteBuff)

    // Wait for all archives.
    while (true) {
        val confirmResult = conn.confirmArchives(noBlock = false)
        if (confirmResult == null) {
            Logger.success("All archives extracted on remote.")
            break
        }

        Logger.success("Confirmed ${confirmResult.first.size} archives.")
        confirmResult.first.forEach { Logger.success("> Archive Sequence ID: $it") }
        if (confirmResult.second.isNotEmpty()) {
            Logger.error("And ${confirmResult.second} archives failed.")
            confirmResult.second.forEach { Logger.error("> Archive Sequence ID: $it") }
        }
    }
}


suspend fun runClient(config: Config): Int {
    Logger.info("Running in client mode")

    val timeBeginMillis = System.currentTimeMillis()

    val workspace = config.workspaces.values.first()
    val channel = AsynchronousSocketChannel.open()
    channel.connect(InetSocketAddress(workspace.host!!, workspace.port!!)).get()

    val allFilesSizePromise: Deferred<Long>

    JanusProtocolConnection(channel).use { conn ->
        conn.hello(Role.CLIENT)
        conn.auth(workspace = workspace)
        val localTimeMillis = System.currentTimeMillis()
        val serverTimeMillis = conn.getSystemTimeMillis()
        val networkRoundtripMillis = System.currentTimeMillis() - localTimeMillis
        val serverFileTreePromise = GlobalCoroutineScopes.IO.async { conn.fetchFileTree(workspace.path) }
        val localFileTreePromise = GlobalCoroutineScopes.IO.async {
            workspace.path.globFilesRelative(workspace.filter.ignore)
        }

        val svrToLocalTimeDiffMillis = serverTimeMillis - localTimeMillis - networkRoundtripMillis / 2

        Logger.info("Time difference between remote and local: ${svrToLocalTimeDiffMillis} ms")

        val localFileTree = localFileTreePromise.await()
        Logger.success("Local file tree prepared.")
        val serverFileTree = serverFileTreePromise.await()
        Logger.success("Server file tree fetched.")

        val syncPlans: List<SyncPlan>
        val buildSyncPlanCost = measureTime {
            syncPlans = SyncPlan.build(
                local = localFileTree,
                remote = serverFileTree,
                remoteLocalTimeDiffMillis = svrToLocalTimeDiffMillis
            ).toList()
        }

        Logger.info("Sync plan built in ${buildSyncPlanCost.inWholeMilliseconds}ms.")

        allFilesSizePromise = GlobalCoroutineScopes.Default.async { sumFilesSize(syncPlans) }

        conn.commitSyncPlan(syncPlans)
        Logger.info("Plan committed. Ready to send files.")
        syncPlans.forEach { uploadFiles(conn = conn, workspace = workspace, plan = it) }

        conn.bye()
        Logger.success("Sync complete.")
    }

    val timeEndMillis = System.currentTimeMillis()
    var timeCostMillis = timeEndMillis - timeBeginMillis
    if (timeCostMillis <= 0)
        timeCostMillis = 1
    val allFilesSize = allFilesSizePromise.await()
    val speedMBps = allFilesSize * 1000 / 1024 / 1024 / timeCostMillis
    Logger.success("Client finished in ${timeCostMillis / 1000} seconds ${timeCostMillis % 1000} ms.")
    Logger.success("> Total ${allFilesSize / 1024 / 1024} MB transferred at $speedMBps MB/s (comprehensive).")

    return 0
}
