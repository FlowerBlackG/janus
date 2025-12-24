// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.filesystem

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoBuf
import java.nio.file.Path
import kotlin.io.path.Path

@Serializable
data class SyncPlan(
    val name: String,
    val fileType: FileType,
    @Transient
    var path: Path = Path(""),

    /**
     * If object is deserialized from somewhere, never trust this field.
     */
    @Transient
    var fileSize: Long = 0,
    var action: SyncAction,
    var children: List<SyncPlan> = mutableListOf()
) {
    @Serializable
    enum class SyncAction {
        NONE,

        /**
         * For file: means remote file is old or doesn't exist.
         * For folder: means need to create folder.
         */
        UPLOAD,
        DELETE_REMOTE,
    }

    companion object {
        fun build(
            local: FileTree?,
            remote: FileTree?,
            remoteLocalTimeDiffMillis: Long = 0
        ) = buildSyncPlan(local = local, remote = remote, remoteLocalTimeDiffMillis = remoteLocalTimeDiffMillis)

        fun from(bytes: ByteArray, baseRoot: Path = Path("")): SyncPlan {
            val tree = ProtoBuf.decodeFromByteArray(serializer(), bytes)

            tree.reconstruct(baseRoot)

            if (!tree.isSafe()) {
                throw Exception("Malicious SyncPlan detected: Contains illegal path references.")
            }

            return tree
        }

    }


    fun toList(): List<SyncPlan> {
        return listOf(this) + children.flatMap { it.toList() }
    }


    fun reconstruct(currentPath: Path) {
        this.path = currentPath
        for (child in children) {
            child.reconstruct(currentPath.resolve(child.name))
        }
    }


    fun isSafe(basePath: Path = this.path): Boolean {
        return runCatching {
            val resolvedPath = basePath.resolve(this.path).normalize().toAbsolutePath()
            val absoluteBase = basePath.toAbsolutePath().normalize()

            // Check if the resolved path still starts with the base directory
            if (!resolvedPath.startsWith(absoluteBase)) {
                return false
            }

            // Recurse through children
            children.all { it.isSafe(basePath) }
        }.getOrNull() ?: false
    }


    fun encodeToByteArray(): ByteArray {
        return ProtoBuf.encodeToByteArray(serializer(), this)
    }
}


private fun buildSyncPlan(
    local: FileTree?,
    remote: FileTree?,
    remoteLocalTimeDiffMillis: Long = 0
): List<SyncPlan> {
    if (local == null && remote == null)
        return emptyList()

    if (local == null) {
        remote!!
        val plan = makeSyncPlan(file = remote, action = SyncPlan.SyncAction.DELETE_REMOTE)
        return listOf(plan)
    }


    if (remote == null) {
        val plan = makeSyncPlan(file = local, action = SyncPlan.SyncAction.UPLOAD)

        if (local.type == FileType.DIRECTORY) {
            plan.children = local.children
                .map { buildSyncPlan(it, null, remoteLocalTimeDiffMillis) }
                .flatten()
        }

        return listOf(plan)
    }

    // now, both remote and local exists.

    // we just want to handle files and directories. maybe support symlink and others later...
    if (local.type != FileType.DIRECTORY && local.type != FileType.FILE)
        return emptyList()
    if (remote.type != FileType.DIRECTORY && remote.type != FileType.FILE)
        return emptyList()


    if (local.type != remote.type) {
        // mark remote as DELETE.

        val delPlan = makeSyncPlan(file = remote, action = SyncPlan.SyncAction.DELETE_REMOTE)
        val addPlan = makeSyncPlan(file = local, action = SyncPlan.SyncAction.UPLOAD)

        if (local.type == FileType.FILE)
            return listOf(delPlan, addPlan)

        // for directory, we need to handle children.
        addPlan.children = local.children
            .map { buildSyncPlan(it, null, remoteLocalTimeDiffMillis) }
            .flatten()

        return listOf(delPlan, addPlan)
    }

    // now, we can say that both exists and of same type.

    if (local.type == FileType.FILE && local.lastModifiedMillis + remoteLocalTimeDiffMillis < remote.lastModifiedMillis)
        return emptyList()

    // for directories...

    val plan = makeSyncPlan(file = local, action = SyncPlan.SyncAction.NONE)

    val localChildren = local.children.associateBy { it.name }
    val remoteChildren = remote.children.associateBy { it.name }
    val allNames = (localChildren.keys + remoteChildren.keys).toSet()

    val allChildren = mutableListOf<SyncPlan>()

    for (name in allNames) {
        allChildren += buildSyncPlan(localChildren[name], remoteChildren[name], remoteLocalTimeDiffMillis)
    }

    plan.children = allChildren

    return if (plan.children.isNotEmpty())
        listOf(plan)
    else
        emptyList()
}

private fun makeSyncPlan(file: FileTree, action: SyncPlan.SyncAction): SyncPlan {
    return SyncPlan(
        name = file.name,
        path = file.path,
        fileSize = file.fileSize,
        fileType = file.type,
        action = action
    )
}
