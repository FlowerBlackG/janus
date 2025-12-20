// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.filesystem

import io.github.flowerblackg.janus.config.Config
import io.github.flowerblackg.janus.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
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


suspend fun Path.globFiles(): FileTree? = withContext(Dispatchers.IO) {
    return@withContext globFilesInternal(null, this@globFiles, null, null)
}


suspend fun Path.globFilesRelative(ignoreConfig: Config.IgnoreConfig? = null): FileTree? = withContext(Dispatchers.IO) {
    val rootPath = this@globFilesRelative.toAbsolutePath()
    return@withContext globFilesInternal(rootPath, rootPath, null, ignoreConfig)
}


private suspend fun globFilesInternal(
    root: Path?,
    current: Path,
    parent: FileTree? = null,
    ignoreConfig: Config.IgnoreConfig? = null
): FileTree? = withContext(Dispatchers.IO) {

    val attrs = try {
        Files.readAttributes(current, BasicFileAttributes::class.java)
    } catch (e: Exception) {
        Logger.error("Failed to read attributes of $current: ${e.message}")
        return@withContext null
    }

    val relativePath = root?.relativize(current.toAbsolutePath()) ?: current

    // Check ignore.
    if (ignoreConfig != null && shouldIgnore(relativePath, attrs.isDirectory, ignoreConfig)) {
        return@withContext null
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
        parent = parent
    )

    if (node.type == FileType.DIRECTORY) {
        val childPaths = try {
            Files.newDirectoryStream(current).use { it.toList() }
        } catch (e: Exception) {
            Logger.error("Failed to list children of $current: ${e.message}")
            emptyList()
        }

        val children = if (childPaths.size < 16) {
            childPaths.map { globFilesInternal(root, it, node, ignoreConfig) }
        } else {
            childPaths.map { async { globFilesInternal(root, it, node, ignoreConfig) } }.awaitAll()
        }

        node.children = children.filterNotNull().toMutableList()
    }

    return@withContext node
}


/**
 * Helper to check if a relative path matches the ignore configuration.
 * Adapts basic .gitignore syntax to Java PathMatchers.
 *
 * @author Google Gemini 3.0 Pro
 */
private fun shouldIgnore(relativePath: Path, isDirectory: Boolean, config: Config.IgnoreConfig): Boolean {
    val fs = FileSystems.getDefault()

    for (line in config.lines) {
        if (line.isBlank() || line.startsWith("#"))
            continue

        var pattern = line.trim()
        var expectsDirectory = pattern.endsWith("/")

        if (expectsDirectory && !isDirectory)
            continue

        // Handle directory specific ignores (simple check)
        // If pattern ends with /, it expects a directory, but here we simply match the string prefix
        if (expectsDirectory) {
             pattern = pattern.dropLast(1)
        }

        // Convert gitignore syntax to glob
        val globPattern = when {
            // Absolute path relative to root (e.g., /build)
            pattern.startsWith("/") -> "glob:${pattern.drop(1)}"
            // Recursive match (e.g., *.class or build/)
            else -> "glob:{${pattern},**/${pattern}}"
        }


        if (fs.runCatching { getPathMatcher(globPattern).matches(relativePath) }.getOrElse { false })
            return true
    }
    return false
}

