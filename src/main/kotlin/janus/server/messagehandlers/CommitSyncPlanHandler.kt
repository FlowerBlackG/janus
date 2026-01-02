// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.server.messagehandlers

import io.github.flowerblackg.janus.config.Config
import io.github.flowerblackg.janus.filesystem.FSUtils
import io.github.flowerblackg.janus.filesystem.FileType
import io.github.flowerblackg.janus.filesystem.SyncPlan
import io.github.flowerblackg.janus.logging.Logger
import io.github.flowerblackg.janus.network.protocol.JanusMessage
import io.github.flowerblackg.janus.network.protocol.JanusProtocolConnection
import java.nio.file.Files
import java.nio.file.Path


private data class PlanList(
    var deletePlans: MutableList<SyncPlan> = mutableListOf(),
    var otherPlans: MutableList<SyncPlan> = mutableListOf(),
)


/**
 * Two plans. The front one is the one you read from; the back one is the one you write to.
 *
 */
private data class DoubleBufferedPlanList(
    var plans: Pair<PlanList, PlanList> = Pair(PlanList(), PlanList())
) : Iterable<SyncPlan> {
    fun flip() {
        deletePlans.clear()
        otherPlans.clear()
        plans = Pair(plans.second, plans.first)
    }

    val deletePlans: MutableList<SyncPlan> get() = plans.first.deletePlans
    val otherPlans: MutableList<SyncPlan> get() = plans.first.otherPlans

    fun isEmpty() = deletePlans.isEmpty() && otherPlans.isEmpty()
    fun isNotEmpty() = !isEmpty()

    fun add(plan: SyncPlan) {
        if (plan.action == SyncPlan.SyncAction.DELETE_REMOTE)
            plans.second.deletePlans += plan
        else
            plans.second.otherPlans += plan
    }

    fun add(plans: Collection<SyncPlan>) {
        plans.forEach { add(it) }
    }

    operator fun plusAssign(plan: SyncPlan) = add(plan)
    operator fun plusAssign(plans: Collection<SyncPlan>) = add(plans)

    override fun iterator(): Iterator<SyncPlan> {
        return sequence {
            yieldAll(plans.first.deletePlans)
            yieldAll(plans.first.otherPlans)
        }.iterator()
    }
}


class CommitSyncPlanHandler(val workspace: Config.WorkspaceConfig) : MessageHandler<JanusMessage.CommitSyncPlan> {
    private fun ensurePathExists(fileType: FileType, path: Path) {
        val absPath = path.toAbsolutePath().normalize()
        if (!absPath.startsWith(workspace.path.toAbsolutePath().normalize())) {
            Logger.error("Malicious path: ${absPath}")
            throw Exception("Malicious path: ${absPath}")
        }

        val targetDir = if (fileType == FileType.DIRECTORY) absPath else absPath.parent ?: absPath

        var current = targetDir.root
        for (dir in targetDir) {
            current = current.resolve(dir)

            val curShouldBeDirectory = dir != targetDir.last() || fileType == FileType.DIRECTORY

            if (Files.exists(current)) {
                if (!Files.isDirectory(current) && curShouldBeDirectory) {
                    current.toFile().delete()
                    Files.createDirectory(current)
                }
            }
            else {
                if (curShouldBeDirectory)
                    Files.createDirectory(current)
            }
        }

        if (fileType == FileType.FILE && Files.exists(absPath) && Files.isDirectory(absPath))
            absPath.toFile().deleteRecursively()
    }

    private fun doPlan(plan: SyncPlan, planHolder: DoubleBufferedPlanList) {
        planHolder += plan.children

        if (plan.action == SyncPlan.SyncAction.DELETE_REMOTE) {
            val relativePath = workspace.path.relativize(plan.path)
            if (!Files.exists(plan.path))
                return

            val protectList = workspace.filter.protect
            val isDir = plan.fileType == FileType.DIRECTORY
            if (FSUtils.shouldIgnore(relativePath, isDirectory = isDir, ignoreList = protectList)) {
                // ignored due to protect rule.
                return
            }
            Logger.info("[DELETE] ${plan.path.toAbsolutePath().normalize()}")

            plan.path.toFile().deleteRecursively()
        }
        else if (plan.action == SyncPlan.SyncAction.NONE) {
            // pass
        }
        else if (plan.action == SyncPlan.SyncAction.UPLOAD) {
            ensurePathExists(plan.fileType, plan.path)
            Logger.info("[CREATE] ${plan.path.toAbsolutePath().normalize()}")
            if (plan.fileType == FileType.DIRECTORY) {
                return
            }
        }
        else {
            Logger.error("Action not found: ${plan.action}")
            throw Exception("Failed to do action: ${plan.action}")
        }
    }


    override suspend fun handle(conn: JanusProtocolConnection, msg: JanusMessage.CommitSyncPlan) {
        val syncPlans = msg.syncPlansBytes.map { SyncPlan.from(it, workspace.path) }

        val planHolder = DoubleBufferedPlanList()
        planHolder += syncPlans

        planHolder.flip()

        while (planHolder.isNotEmpty()) {
            planHolder.forEach { doPlan(it, planHolder) }
            planHolder.flip()
        }

        conn.sendResponse(code = 0)
    }
}
