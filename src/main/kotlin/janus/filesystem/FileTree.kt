// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.filesystem

import io.github.flowerblackg.janus.config.Config
import io.github.flowerblackg.janus.coroutine.GlobalCoroutineScopes
import io.github.flowerblackg.janus.logging.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoBuf
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.Path
import kotlin.io.path.name


@Serializable
enum class FileType {
    FILE,
    DIRECTORY,
    SYMLINK,
    OTHER
}


@ExperimentalSerializationApi
@Serializable
data class FileTree(
    var type: FileType = FileType.OTHER,
    var name: String = "",
    var fileSize: Long = 0L,
    var children: MutableList<FileTree> = ArrayList(),
    @Transient
    var parent: FileTree? = null,

    var lastModifiedMillis: Long = 0L,

    /**
     * We mark this as Transient for serialization.
     * We reconstruct the path during the 'reconstruct' phase
     * to avoid saving redundant string data for every single node.
     */
    @Transient
    var path: Path = Path("")
) {
    companion object {
        @ExperimentalSerializationApi
        fun from(bytes: ByteArray, baseRoot: Path = Path("")): FileTree? {
            val tree = ProtoBuf.decodeFromByteArray(serializer(), bytes)

            // Reconstruct paths and parents.
            tree.reconstruct(baseRoot, null)

            if (!tree.isSafe()) {
                Logger.warn("Malicious FileTree detected: Contains illegal path references.")
                return null
            }

            return tree
        }
    }


    override fun toString(): String {
        return "FileTree($name, $type, lastModified: $lastModifiedMillis, ${children.size} children)"
    }


    /**
     * Internal helper to restore Transients after decompression
     */
    private fun reconstruct(currentPath: Path, parentNode: FileTree?) {
        this.parent = parentNode
        this.path = currentPath
        for (child in children) {
            child.reconstruct(currentPath.resolve(child.name), this)
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


    fun hasDuplicatedNamesInDirectory(recursive: Boolean = false): Boolean {
        if (type != FileType.DIRECTORY)
            return false

        val names = HashSet<String>()
        for (child in children) {
            if (names.contains(child.name)) {
                return true
            }
            names.add(child.name)
        }

        if (!recursive)
            return false

        for (child in children) {
            if (child.hasDuplicatedNamesInDirectory(true)) {
                return true
            }
        }

        return false
    }


    fun encodeToByteArray(): ByteArray {
        return ProtoBuf.encodeToByteArray(serializer(), this)
    }
}


suspend fun Path.globFiles(): FileTree? {
    return globFilesInternal(null, this@globFiles, null, null)
}


suspend fun Path.globFilesRelative(ignoreList: Iterable<String>? = null): FileTree? {
    val rootPath = this@globFilesRelative.toAbsolutePath()
    return globFilesInternal(rootPath, rootPath, null, ignoreList)
}


private suspend fun globFilesInternal(
    root: Path?,
    current: Path,
    parent: FileTree? = null,
    ignoreList: Iterable<String>? = null
): FileTree? {

    val attrs = try {
        Files.readAttributes(current, BasicFileAttributes::class.java)
    } catch (e: Exception) {
        Logger.error("Failed to read attributes of $current: ${e.message}")
        return null
    }

    val relativePath = root?.relativize(current.toAbsolutePath()) ?: current

    // Check ignore.
    if (ignoreList != null && FSUtils.shouldIgnore(relativePath, attrs.isDirectory, ignoreList)) {
        return null
    }


    val node = FileTree(
        type = when {
            attrs.isDirectory -> FileType.DIRECTORY
            attrs.isSymbolicLink -> FileType.SYMLINK
            attrs.isRegularFile -> FileType.FILE
            else -> FileType.OTHER
        },
        path = relativePath,
        name = current.name,
        lastModifiedMillis = attrs.lastModifiedTime().toMillis(),
        parent = parent,
        fileSize = when {
            attrs.isRegularFile -> attrs.size()
            else -> 0L
        },
    )

    if (node.type == FileType.DIRECTORY) {
        val childPaths = try {
            Files.newDirectoryStream(current).use { it.toList() }
        } catch (e: Exception) {
            Logger.error("Failed to list children of $current: ${e.message}")
            emptyList()
        }

        val children = if (childPaths.size < 16) {
            childPaths.map { globFilesInternal(root, it, node, ignoreList) }
        } else {
            childPaths.map {
                GlobalCoroutineScopes.IO.async { globFilesInternal(root, it, node, ignoreList) }
            }.awaitAll()
        }

        node.children = children.filterNotNull().toMutableList()
    }

    return node
}
