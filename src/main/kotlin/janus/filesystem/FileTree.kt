// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.filesystem

import io.github.flowerblackg.janus.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.Path
import kotlin.io.path.name


enum class FileType {
    FILE,
    DIRECTORY,
    SYMLINK,
    OTHER
}

data class FileTree(
    var type: FileType = FileType.OTHER,
    var name: String = "",
    var children: MutableList<FileTree> = ArrayList(),
    var parent: FileTree? = null,
    var path: Path = Path("")
) {
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
}


suspend fun Path.globFiles(): FileTree? = withContext(Dispatchers.IO) {
    globFiles(this@globFiles)
}


suspend fun globFiles(path: Path, parent: FileTree? = null): FileTree? = withContext(Dispatchers.IO) {
    val attrs = try {
        Files.readAttributes(path, BasicFileAttributes::class.java)
    } catch (e: Exception) {
        Logger.error("Failed to read attributes of $path: ${e.message}")
        return@withContext null
    }

    val node = FileTree(
        type = when {
            attrs.isDirectory -> FileType.DIRECTORY
            attrs.isSymbolicLink -> FileType.SYMLINK
            attrs.isRegularFile -> FileType.FILE
            else -> FileType.OTHER
        },
        path = path,
        name = path.name,
        parent = parent
    )

    if (node.type == FileType.DIRECTORY) {
        val childPaths = ArrayList<Path>()
        try {
            Files.newDirectoryStream(path).use { stream ->
                for (child in stream) {
                    childPaths.add(child)
                }
            }
        } catch (e: Exception) {
            Logger.error("Failed to list children of $path: ${e.message}")
        }

        val children = if (childPaths.isEmpty()) {
            emptyList()
        } else if (childPaths.size < 16) {
            childPaths.map {
                globFiles(it, node)
            }
        } else {
            childPaths.map {
                async { globFiles(it, node) }
            }.awaitAll()
        }

        node.children = children.filterNotNull().toMutableList()
    }

    return@withContext node
}

